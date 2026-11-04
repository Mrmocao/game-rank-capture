package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import com.youkia.capture.metrics.*;
import com.youkia.iaa.service.SecretCipher;
import org.springframework.jdbc.core.RowCallbackHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Read ranking evidence; write only independent capture_metric_* records. */
public final class GameMetricService {
    @FunctionalInterface public interface Fetcher { RawMaterial fetch(String tenant,MetricTarget target) throws Exception; }
    private final CaptureWorkspaceStore store;
    private final Fetcher fetcher;
    private final Path root;
    private final SecretCipher cipher;
    private final boolean encryptionReady;
    private final StoreMetricParser parser;
    public GameMetricService(CaptureWorkspaceStore store,Fetcher fetcher,Path root,SecretCipher cipher,boolean encryptionReady) {
        this.store=store;this.fetcher=fetcher;this.root=root.toAbsolutePath().normalize();this.cipher=cipher;this.encryptionReady=encryptionReady;parser=new StoreMetricParser(store.mapper);
    }
    public Map<String,Object> config(String tenant) {
        store.jdbc.update("INSERT IGNORE INTO capture_metric_config(tenant_id,updated_at) VALUES(?,?)",tenant,store.now());
        var r=store.jdbc.queryForMap("SELECT * FROM capture_metric_config WHERE tenant_id=?",tenant);r.remove("tenant_id");
        Instant seen=CaptureWorkspaceStore.instant(r.get("worker_seen_at"));r.put("workerOnline",seen!=null&&seen.isAfter(store.clock.instant().minusSeconds(180)));return r;
    }
    public record ConfigEdit(boolean enabled,int refreshHours,String transport,int revision) {}
    public Map<String,Object> saveConfig(String tenant,ConfigEdit edit) {
        config(tenant);if(!Set.of(24,48,72).contains(edit.refreshHours())||!Set.of("web-http","firecrawl").contains(edit.transport()))throw new IllegalArgumentException("缓存仅支持24/48/72小时，工具须为普通网页或Firecrawl");
        int n=store.jdbc.update("UPDATE capture_metric_config SET enabled=?,refresh_hours=?,transport=?,revision=revision+1,planned_at=NULL,updated_at=? WHERE tenant_id=? AND revision=?",edit.enabled(),edit.refreshHours(),edit.transport(),store.now(),tenant,edit.revision());
        if(n!=1)throw conflict();return config(tenant);
    }
    private static String identity(String platform,String id,String region){return platform+":"+id+":"+region;}
    /** Streaming row projection avoids loading full descriptions for every ranked game. */
    private List<Map<String,Object>> ranked(String tenant) {
        String sql="""
            SELECT o.id observation_id,o.source_key,o.platform,o.region,o.business_date,o.captured_at,o.source_url,o.raw_sha256,
                   o.chart_key,e.rank_position,e.external_id,e.name,e.developer,e.evidence_locator,e.source_fields
            FROM external_ranking_observation o JOIN external_ranking_entry e ON e.observation_id=o.id
            WHERE o.tenant_id=? AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.tenant_id=o.tenant_id AND r.observation_id=o.id AND r.status IN ('SUCCEEDED','PARTIAL'))
              AND EXISTS(SELECT 1 FROM capture_target_config c WHERE c.tenant_id=o.tenant_id AND c.source_key=o.source_key AND c.region=o.region AND c.enabled=TRUE)
              AND NOT EXISTS(SELECT 1 FROM external_ranking_observation n WHERE n.tenant_id=o.tenant_id AND n.source_key=o.source_key AND n.region=o.region AND n.chart_key=o.chart_key
                AND (n.available_at>o.available_at OR (n.available_at=o.available_at AND n.id>o.id))
                AND EXISTS(SELECT 1 FROM external_capture_run r2 WHERE r2.tenant_id=n.tenant_id AND r2.observation_id=n.id AND r2.status IN ('SUCCEEDED','PARTIAL')))
            ORDER BY o.captured_at DESC,o.id DESC,e.rank_position LIMIT 5000
            """;
        var unique=new LinkedHashMap<String,Map<String,Object>>();
        store.jdbc.query(sql,(RowCallbackHandler) rs->{
            String externalId=rs.getString("external_id");
            String key=identity(rs.getString("platform"),externalId==null||externalId.isBlank()?"unknown:"+rs.getLong("observation_id")+":"+rs.getInt("rank_position"):externalId,rs.getString("region"));
            var row=unique.get(key);
            if(row==null) {
                row=new LinkedHashMap<>();for(String col:List.of("platform","external_id","region","name","developer","source_key","source_url","raw_sha256","evidence_locator"))row.put(col,rs.getString(col));
                row.put("observationId",rs.getLong("observation_id"));row.put("businessDate",rs.getObject("business_date"));row.put("capturedAt",rs.getTimestamp("captured_at"));row.put("rankings",new ArrayList<Map<String,Object>>());
                JsonNode fields=(JsonNode)store.read(rs.getObject("source_fields"));var existing=new LinkedHashMap<String,Object>();
                for(String field:List.of("priceRaw","installsDisplay","recentInstallsDisplay","recentPeriodDays","recentInstallsEstimated","rating"))if(fields!=null&&fields.has(field))existing.put(field,fields.get(field));
                row.put("existing",existing);row.put("identity",key);unique.put(key,row);
            }
            @SuppressWarnings("unchecked") var rankings=(List<Map<String,Object>>)row.get("rankings");rankings.add(Map.of("chart",rs.getString("chart_key"),"rank",rs.getInt("rank_position"),"observationId",rs.getLong("observation_id")));
        },tenant);
        return new ArrayList<>(unique.values());
    }
    public Map<String,Object> overview(String tenant,String platform,String region,String search,int page) {
        var cfg=config(tenant);var all=ranked(tenant);String term=search==null?"":search.toLowerCase(Locale.ROOT).strip();
        var filtered=all.stream().filter(r->platform==null||platform.isBlank()||platform.equals(r.get("platform"))).filter(r->region==null||region.isBlank()||region.equals(r.get("region"))).filter(r->term.isBlank()||(r.get("name")+" "+r.get("external_id")+" "+r.get("developer")).toLowerCase(Locale.ROOT).contains(term)).toList();
        int actualPage=Math.max(1,Math.min(page,Math.max(1,(filtered.size()+19)/20)));var rows=new ArrayList<>(filtered.subList((actualPage-1)*20,Math.min(actualPage*20,filtered.size())));
        var jobs=store.jdbc.queryForList("SELECT j.id,j.platform,j.external_id,j.region,j.source_key,j.status,j.error_code,j.attempts,j.last_started_at,j.last_finished_at,j.last_success_id,o.captured_at,o.snapshot_json FROM capture_metric_job j LEFT JOIN capture_metric_observation o ON o.id=j.last_success_id AND o.tenant_id=j.tenant_id WHERE j.tenant_id=?",tenant);
        var byIdentity=new HashMap<String,List<Map<String,Object>>>();
        for(var j:jobs) {Object snapshot=j.remove("snapshot_json");if(snapshot!=null)j.put("snapshot",store.read(snapshot));Instant captured=CaptureWorkspaceStore.instant(j.get("captured_at"));j.put("stale",captured==null||captured.isBefore(store.clock.instant().minusSeconds(((Number)cfg.get("refresh_hours")).longValue()*3600)));byIdentity.computeIfAbsent(identity((String)j.get("platform"),(String)j.get("external_id"),(String)j.get("region")),k->new ArrayList<>()).add(j);}
        for(var row:rows){row.put("jobs",byIdentity.getOrDefault(row.get("identity"),List.of()));row.put("supported",Set.of("IOS","GOOGLE_PLAY").contains(row.get("platform"))&&row.get("external_id")!=null);}
        var counts=store.jdbc.queryForList("SELECT status,COUNT(*) count FROM capture_metric_job WHERE tenant_id=? GROUP BY status",tenant);
        return Map.of("config",cfg,"rows",rows,"total",filtered.size(),"page",actualPage,"pageSize",20,"rankedGames",all.size(),"jobCounts",counts,"providers",providers(tenant));
    }
    public record Enqueue(String platform,String externalId,String region) {}
    public Map<String,Object> enqueue(String tenant,Enqueue request) {
        config(tenant);int added=0,skipped=0;var rows=ranked(tenant);
        for(var row:rows) {
            if(request!=null&&request.platform()!=null&&!request.platform().isBlank()&&!request.platform().equals(row.get("platform")))continue;
            if(request!=null&&request.externalId()!=null&&!request.externalId().isBlank()&&!request.externalId().equals(row.get("external_id")))continue;
            if(request!=null&&request.region()!=null&&!request.region().isBlank()&&!request.region().equals(row.get("region")))continue;
            if(row.get("external_id")==null)continue;
            var sources=switch((String)row.get("platform")){case "IOS"->List.of("apple-lookup","apple-detail");case "GOOGLE_PLAY"->List.of("google-detail");default->List.<String>of();};
            for(String source:sources) {
                var target=new MetricTarget((String)row.get("platform"),(String)row.get("external_id"),(String)row.get("region"),source);
                String id=hash(tenant+":"+identity(target.platform(),target.externalId(),target.region())+":"+source);
                int n=store.jdbc.update("INSERT IGNORE INTO capture_metric_job(id,tenant_id,platform,external_id,region,source_key,name,ranking_observation_id,status,due_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'PENDING',?,?)",id,tenant,target.platform(),target.externalId(),target.region(),source,row.get("name"),row.get("observationId"),store.now(),store.now());
                if(n==0)n=store.jdbc.update("UPDATE capture_metric_job SET status='PENDING',name=?,ranking_observation_id=?,error_code=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status IN ('SUCCEEDED','FAILED','CANCELLED') AND due_at<=?",row.get("name"),row.get("observationId"),store.now(),tenant,id,store.now());
                if(n>0)added++;else skipped++;
            }
        }
        return Map.of("queued",added,"cachedOrPending",skipped,"notice","按平台ID去重；成功缓存和失败退避期内不重复请求。小游戏缺少对应公开指标，不按同名匹配。");
    }
    /** Called by an independent process; at most one actual source request per tick. */
    public Map<String,Object> tick(String tenant) {
        var cfg=config(tenant);store.jdbc.update("UPDATE capture_metric_config SET worker_seen_at=? WHERE tenant_id=?",store.now(),tenant);
        Instant planned=CaptureWorkspaceStore.instant(cfg.get("planned_at"));
        if(Boolean.TRUE.equals(cfg.get("enabled"))&&(planned==null||planned.isBefore(store.clock.instant().minusSeconds(300)))) {
            int n=store.jdbc.update("UPDATE capture_metric_config SET planned_at=? WHERE tenant_id=? AND (planned_at IS NULL OR planned_at<?)",store.now(),tenant,Timestamp.from(store.clock.instant().minusSeconds(300)));
            if(n==1)enqueue(tenant,null);
        }
        store.jdbc.update("UPDATE capture_metric_job SET status='FAILED',error_code='LEASE_EXPIRED',lease_token=NULL,lease_until=NULL,due_at=?,last_finished_at=? WHERE tenant_id=? AND status='RUNNING' AND lease_until<?",store.now(),store.now(),tenant,store.now());
        cancelInactive(tenant);
        var candidates=store.jdbc.queryForList("SELECT * FROM capture_metric_job WHERE tenant_id=? AND platform='IOS' AND status='PENDING' AND due_at<=? ORDER BY due_at,source_key,id LIMIT 1",tenant,store.now());
        if(candidates.isEmpty())return Map.of("executed",false);
        var job=candidates.getFirst();String id=(String)job.get("id"),token=UUID.randomUUID().toString();
        if(store.jdbc.update("UPDATE capture_metric_job SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,last_started_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='PENDING'",token,Timestamp.from(store.clock.instant().plusSeconds(180)),store.now(),store.now(),tenant,id)!=1)return Map.of("executed",false);
        try {
            var target=new MetricTarget((String)job.get("platform"),(String)job.get("external_id"),(String)job.get("region"),(String)job.get("source_key"));
            RawMaterial raw=fetcher.fetch(tenant,target);String observation=UUID.randomUUID().toString();Path relative=Path.of("metrics",observation,"raw.bin");Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.write(file,raw.bytes(),StandardOpenOption.CREATE_NEW);
            var snapshot=parser.parse(target,raw);Files.writeString(file.resolveSibling("snapshot.json"),store.json(snapshot),StandardOpenOption.CREATE_NEW);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(store.jdbc.getDataSource())));
            tx.executeWithoutResult(status->{
                int n=store.jdbc.update("UPDATE capture_metric_job SET status='SUCCEEDED',last_success_id=?,error_code=NULL,lease_token=NULL,lease_until=NULL,last_finished_at=?,due_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",observation,store.now(),Timestamp.from(store.clock.instant().plusSeconds(((Number)cfg.get("refresh_hours")).longValue()*3600)),store.now(),tenant,id,token,store.now());
                if(n!=1)throw new CaptureException("METRIC_LEASE_LOST","指标任务租约丢失，不能提交");
                store.jdbc.update("INSERT INTO capture_metric_observation(id,tenant_id,job_id,captured_at,raw_sha256,raw_locator,snapshot_json) VALUES(?,?,?,?,?,?,?)",observation,tenant,id,Timestamp.from(raw.capturedAt()),raw.sha256(),relative.toString().replace('\\','/'),store.json(snapshot));
            });return Map.of("executed",true,"jobId",id,"status","SUCCEEDED","observationId",observation);
        }catch(Exception e) {
            String code=RankingCaptureRunner.errorCode(e);store.jdbc.update("UPDATE capture_metric_job SET status='FAILED',error_code=?,lease_token=NULL,lease_until=NULL,last_finished_at=?,due_at=?,updated_at=? WHERE tenant_id=? AND id=? AND lease_token=?",code,store.now(),Timestamp.from(store.clock.instant().plusSeconds(21600)),store.now(),tenant,id,token);
            return Map.of("executed",true,"jobId",id,"status","FAILED","errorCode",code);
        } finally {store.jdbc.update("UPDATE capture_metric_config SET worker_seen_at=? WHERE tenant_id=?",store.now(),tenant);}
    }
    private void cancelInactive(String tenant) {
        store.jdbc.update("""
            UPDATE capture_metric_job SET status='CANCELLED',error_code='RANK_SOURCE_DISABLED',updated_at=?
            WHERE tenant_id=? AND status='PENDING' AND NOT EXISTS (
              SELECT 1 FROM external_ranking_observation o JOIN capture_target_config c
              ON c.tenant_id=o.tenant_id AND c.source_key=o.source_key AND c.region=o.region
              WHERE o.id=capture_metric_job.ranking_observation_id AND o.tenant_id=capture_metric_job.tenant_id AND c.enabled=TRUE)
            """,store.now(),tenant);
    }
    public Map<String,Object> history(String tenant,String jobId) {
        var jobs=store.jdbc.queryForList("SELECT id,platform,external_id,region,source_key,status,error_code,attempts,last_started_at,last_finished_at,due_at,ranking_observation_id FROM capture_metric_job WHERE tenant_id=? AND id=?",tenant,jobId);
        if(jobs.isEmpty())throw new CaptureException("METRIC_JOB_NOT_FOUND","指标任务不存在");
        var observations=store.jdbc.queryForList("SELECT id,captured_at,raw_sha256,raw_locator,snapshot_json FROM capture_metric_observation WHERE tenant_id=? AND job_id=? ORDER BY captured_at DESC LIMIT 30",tenant,jobId);
        observations.forEach(o->o.put("snapshot_json",store.read(o.get("snapshot_json"))));return Map.of("job",jobs.getFirst(),"observations",observations);
    }
    /** Authenticated overseas executor receives only a fixed public URL and a scoped lease. */
    public Map<String,Object> claimRemote(String tenant) {
        config(tenant);store.jdbc.update("UPDATE capture_metric_config SET remote_seen_at=? WHERE tenant_id=?",store.now(),tenant);
        cancelInactive(tenant);
        var candidates=store.jdbc.queryForList("SELECT * FROM capture_metric_job WHERE tenant_id=? AND platform='GOOGLE_PLAY' AND status='PENDING' AND due_at<=? ORDER BY due_at,id LIMIT 1",tenant,store.now());
        if(candidates.isEmpty())return Map.of("available",false);
        var j=candidates.getFirst();String token=UUID.randomUUID().toString();
        if(store.jdbc.update("UPDATE capture_metric_job SET status='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1,last_started_at=? WHERE tenant_id=? AND id=? AND status='PENDING'",token,Timestamp.from(store.clock.instant().plusSeconds(180)),store.now(),tenant,j.get("id"))!=1)return Map.of("available",false);
        var target=new MetricTarget((String)j.get("platform"),(String)j.get("external_id"),(String)j.get("region"),(String)j.get("source_key"));
        return Map.of("available",true,"jobId",j.get("id"),"leaseToken",token,"url",target.url().toString(),"maxBytes",RawMaterial.MAX_BYTES);
    }
    public record RemoteResult(String jobId,String leaseToken,String capturedAt,String rawBase64,String errorCode) { @Override public String toString(){return "RemoteResult[redacted]";} }
    public Map<String,Object> acceptRemote(String tenant,RemoteResult result) {
        var rows=store.jdbc.queryForList("SELECT * FROM capture_metric_job WHERE tenant_id=? AND id=? AND platform='GOOGLE_PLAY' AND status='RUNNING' AND lease_token=? AND lease_until>=?",tenant,result.jobId(),result.leaseToken(),store.now());
        if(rows.isEmpty())throw new CaptureException("METRIC_LEASE_LOST","远程任务租约无效或过期");
        var j=rows.getFirst();
        try {
            if(result.errorCode()!=null&&!result.errorCode().isBlank())throw new CaptureException("REMOTE_FETCH_FAILED","海外来源未成功，保留旧观察");
            if(result.rawBase64()==null||result.rawBase64().length()>5_333_336)throw new CaptureException("METRIC_SIZE_LIMIT","远程材料超过4MB");
            Instant captured=Instant.parse(result.capturedAt());
            if(captured.isAfter(store.clock.instant().plusSeconds(60))||captured.isBefore(store.clock.instant().minusSeconds(600)))throw new CaptureException("METRIC_CAPTURE_TIME","远程材料采集时间越界");
            var target=new MetricTarget("GOOGLE_PLAY",(String)j.get("external_id"),(String)j.get("region"),"google-detail");
            var raw=new RawMaterial(target.url(),captured,"overseas-public-https",Base64.getDecoder().decode(result.rawBase64()));
            var snapshot=parser.parse(target,raw);String observation=UUID.randomUUID().toString();Path relative=Path.of("metrics",observation,"raw.bin");Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.write(file,raw.bytes(),StandardOpenOption.CREATE_NEW);Files.writeString(file.resolveSibling("snapshot.json"),store.json(snapshot),StandardOpenOption.CREATE_NEW);
            var cfg=config(tenant);var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(store.jdbc.getDataSource())));
            tx.executeWithoutResult(status->{
                int n=store.jdbc.update("UPDATE capture_metric_job SET status='SUCCEEDED',last_success_id=?,error_code=NULL,lease_token=NULL,lease_until=NULL,last_finished_at=?,due_at=?,updated_at=? WHERE tenant_id=? AND id=? AND status='RUNNING' AND lease_token=? AND lease_until>=?",observation,store.now(),Timestamp.from(store.clock.instant().plusSeconds(((Number)cfg.get("refresh_hours")).longValue()*3600)),store.now(),tenant,result.jobId(),result.leaseToken(),store.now());
                if(n!=1)throw new CaptureException("METRIC_LEASE_LOST","远程租约丢失");
                store.jdbc.update("INSERT INTO capture_metric_observation(id,tenant_id,job_id,captured_at,raw_sha256,raw_locator,snapshot_json) VALUES(?,?,?,?,?,?,?)",observation,tenant,result.jobId(),Timestamp.from(raw.capturedAt()),raw.sha256(),relative.toString().replace('\\','/'),store.json(snapshot));
            });return Map.of("status","SUCCEEDED","observationId",observation);
        }catch(Exception e){String code=RankingCaptureRunner.errorCode(e);store.jdbc.update("UPDATE capture_metric_job SET status='FAILED',error_code=?,lease_token=NULL,lease_until=NULL,last_finished_at=?,due_at=? WHERE tenant_id=? AND id=? AND lease_token=?",code,store.now(),Timestamp.from(store.clock.instant().plusSeconds(21600)),tenant,result.jobId(),result.leaseToken());return Map.of("status","FAILED","errorCode",code);}
    }
    public List<Map<String,Object>> providers(String tenant) {
        var rows=new ArrayList<Map<String,Object>>();
        for(String key:List.of("appfigures","diandian","sensortower")) {
            var found=store.jdbc.queryForList("SELECT account_label,licensed,revision,api_key_ciphertext,updated_at FROM capture_metric_provider WHERE tenant_id=? AND provider_key=?",tenant,key);
            var r=found.isEmpty()?new LinkedHashMap<String,Object>(Map.of("account_label","","licensed",false,"revision",0)):new LinkedHashMap<>(found.getFirst());
            r.put("keyConfigured",r.remove("api_key_ciphertext")!=null);r.put("provider",key);r.put("status","AWAITING_LICENSE_VALIDATION");r.put("enabled",false);
            r.put("url",switch(key){case "appfigures"->"https://docs.appfigures.com/api/reference/v2/estimates";case "diandian"->"https://app.diandian.com/service/api";default->"https://sensortower.com/";});rows.add(r);
        }return rows;
    }
    public record ProviderEdit(String accountLabel,String apiKey,boolean clearKey,boolean licensed,int revision) { @Override public String toString(){return "ProviderEdit[redacted]";} }
    public List<Map<String,Object>> saveProvider(String tenant,String provider,ProviderEdit edit) {
        if(!Set.of("appfigures","diandian","sensortower").contains(provider)||edit.accountLabel()==null||edit.accountLabel().length()>200)throw new IllegalArgumentException("供应商或账号备注无效");
        String key=edit.apiKey()==null?"":edit.apiKey().strip();if(key.length()>4096||key.contains("\n")||key.contains("\r"))throw new IllegalArgumentException("Key格式无效");
        if(!key.isBlank()&&!encryptionReady)throw new CaptureException("ENCRYPTION_KEY_REQUIRED","服务器未配置工具加密密钥");
        store.jdbc.update("INSERT IGNORE INTO capture_metric_provider(tenant_id,provider_key,updated_at) VALUES(?,?,?)",tenant,provider,store.now());
        String sql="UPDATE capture_metric_provider SET account_label=?,licensed=?,revision=revision+1,updated_at=?";var args=new ArrayList<Object>(List.of(edit.accountLabel().strip(),edit.licensed(),store.now()));
        if(!key.isBlank()||edit.clearKey()){sql+=",api_key_ciphertext=?";args.add(key.isBlank()?null:cipher.encrypt(key));}
        sql+=" WHERE tenant_id=? AND provider_key=? AND revision=?";args.addAll(List.of(tenant,provider,edit.revision()));if(store.jdbc.update(sql,args.toArray())!=1)throw conflict();return providers(tenant);
    }
    private static CaptureException conflict(){return new CaptureException("CONFIG_CONFLICT","配置已被其他请求更新，请刷新");}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
