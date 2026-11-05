package com.youkia.iaa.collection.capture;

import com.youkia.capture.*;
import com.youkia.capture.profiles.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.security.MessageDigest;

/** Independent capture read model. Same-name app documents remain candidate evidence. */
public final class ProfileSupplementService {
    @FunctionalInterface public interface Searcher { List<RawMaterial> search(String name) throws Exception; }
    private final CaptureWorkspaceStore store;private final Path root;private final Searcher searcher;
    private java.time.Instant nextPlan=java.time.Instant.MIN;
    private java.time.Instant authRetry=java.time.Instant.MIN;
    public ProfileSupplementService(CaptureWorkspaceStore store,Path root,Searcher searcher){this.store=store;this.root=root.toAbsolutePath().normalize();this.searcher=searcher;}
    public List<Map<String,Object>> seeds(String tenant) {
        return store.jdbc.queryForList("""
            SELECT o.id observation_id,o.platform,o.chart_key,e.rank_position,e.name,e.external_id
            FROM external_ranking_observation o JOIN external_ranking_entry e ON e.observation_id=o.id
            WHERE o.tenant_id=? AND o.platform IN ('WECHAT','DOUYIN','IOS') AND o.region='cn' AND o.chart_key<>'publisher_heat'
              AND EXISTS(SELECT 1 FROM capture_target_config c WHERE c.tenant_id=o.tenant_id AND c.source_key=o.source_key AND c.region=o.region AND c.enabled=TRUE)
              AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.tenant_id=o.tenant_id AND r.observation_id=o.id AND r.status IN ('SUCCEEDED','PARTIAL'))
              AND NOT EXISTS(SELECT 1 FROM external_ranking_observation n WHERE n.tenant_id=o.tenant_id AND n.source_key=o.source_key AND n.region=o.region AND n.chart_key=o.chart_key AND (n.available_at>o.available_at OR n.available_at=o.available_at AND n.id>o.id)
                AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.tenant_id=n.tenant_id AND r.observation_id=n.id AND r.status IN ('SUCCEEDED','PARTIAL')))
            ORDER BY e.rank_position,o.id DESC LIMIT 1000
            """,tenant);
    }
    public Map<String,Object> enqueue(String tenant) {
        int n=0;var names=new LinkedHashSet<String>();for(var seed:seeds(tenant))names.add((String)seed.get("name"));
        for(String name:names) {
            String id=jobId(tenant,name);
            n+=store.jdbc.update("INSERT IGNORE INTO capture_profile_job(id,tenant_id,seed_name,status,due_at,updated_at) VALUES(?,?,?,'PENDING',?,?)",id,tenant,name,store.now(),store.now());
            n+=store.jdbc.update("UPDATE capture_profile_job SET status='PENDING',error_code=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status IN ('SUCCEEDED','NO_CANDIDATE','FAILED') AND due_at<=?",store.now(),tenant,id,store.now());
        }
        return Map.of("queued",n,"uniqueNames",names.size(),"browserConfigured",searcher!=null);
    }
    private List<Map<String,Object>> documents(String tenant) {
        var rows=store.jdbc.queryForList("""
            SELECT d.id,d.external_id,d.name,d.captured_at,d.document_json FROM capture_profile_document d
            WHERE d.tenant_id=? AND NOT EXISTS(SELECT 1 FROM capture_profile_document n WHERE n.tenant_id=d.tenant_id AND n.source_key=d.source_key AND n.external_id=d.external_id AND (n.captured_at>d.captured_at OR n.captured_at=d.captured_at AND n.id>d.id))
            ORDER BY d.captured_at DESC LIMIT 2000
            """,tenant);
        rows.forEach(r->r.put("document",store.read(r.remove("document_json"))));return rows;
    }
    public Map<String,Object> overview(String tenant) {
        var docs=documents(tenant);var unique=new LinkedHashMap<String,Map<String,Object>>();for(var s:seeds(tenant))unique.putIfAbsent(s.get("platform")+":"+s.get("name"),s);var seeds=new ArrayList<>(unique.values());var jobs=store.jdbc.queryForList("SELECT id,seed_name,status,error_code,attempts,due_at,updated_at FROM capture_profile_job WHERE tenant_id=? ORDER BY updated_at DESC LIMIT 1000",tenant);
        for(var seed:seeds)seed.put("profiles",matches(seed,docs));
        return Map.of("seeds",seeds,"jobs",jobs,"documents",docs,"browserConfigured",searcher!=null,"notice","点点账号可见的中国iOS资料。同名小游戏仅显示候选，不自动合并身份；全球速览仅保留原文，未转换为国内下载或收入。保存时间不代表历史榜单当天的值。");
    }
    public Map<String,Object> observation(String tenant,long id) {
        var rows=store.jdbc.queryForList("SELECT e.rank_position,e.name,e.external_id,o.platform,o.region FROM external_ranking_entry e JOIN external_ranking_observation o ON o.id=e.observation_id WHERE o.tenant_id=? AND o.id=? ORDER BY e.rank_position",tenant,id);
        if(rows.isEmpty())throw new CaptureException("OBSERVATION_NOT_FOUND","榜单观察不存在");
        var docs=documents(tenant);for(var row:rows)row.put("profiles",matches(row,docs));
        return Map.of("rows",rows,"mode","LATEST_SUPPLEMENT","notice","扩展资料是最新观察；小游戏名称匹配为未核验候选，不改变原榜或收入指标。");
    }
    private List<Map<String,Object>> matches(Map<String,Object> seed,List<Map<String,Object>> docs) {
        if(!Set.of("WECHAT","DOUYIN","IOS").contains(seed.get("platform")) || seed.containsKey("region")&&!"cn".equals(seed.get("region")))return List.of();
        return docs.stream().filter(d->"IOS".equals(seed.get("platform"))?Objects.equals(seed.get("external_id"),d.get("external_id")):DiandianProfileParser.candidate((String)seed.get("name"),(String)d.get("name"))).limit(3).toList();
    }
    public Map<String,Object> accept(String tenant,RawMaterial raw) throws Exception {
        var doc=new DiandianProfileParser().parse(raw);
        if(seeds(tenant).stream().noneMatch(s->"IOS".equals(s.get("platform"))?Objects.equals(s.get("external_id"),doc.externalId()):DiandianProfileParser.candidate((String)s.get("name"),doc.name())))
            throw new CaptureException("PROFILE_NOT_RANKED","资料不是现有榜单游戏的候选");
        var prior=store.jdbc.queryForList("SELECT id FROM capture_profile_document WHERE tenant_id=? AND raw_sha256=?",tenant,raw.sha256());
        if(!prior.isEmpty())return Map.of("status","CACHED","id",prior.getFirst().get("id"));
        String id=UUID.randomUUID().toString();Path rel=Path.of("profile-documents",id,"visible.json"),file=root.resolve(rel);Files.createDirectories(file.getParent());Files.write(file,raw.bytes(),StandardOpenOption.CREATE_NEW);
        Files.writeString(file.resolveSibling("document.json"),store.json(doc),StandardOpenOption.CREATE_NEW);
        store.jdbc.update("INSERT IGNORE INTO capture_profile_document(id,tenant_id,source_key,external_id,name,name_key,captured_at,raw_sha256,raw_locator,document_json) VALUES(?,?,?,?,?,?,?,?,?,?)",id,tenant,doc.source(),doc.externalId(),doc.name(),DiandianProfileParser.normalize(doc.name()),Timestamp.from(raw.capturedAt()),raw.sha256(),rel.toString().replace('\\','/'),store.json(doc));
        return Map.of("status","SUCCEEDED","id",id,"name",doc.name(),"externalId",doc.externalId(),"fields",doc.fields().size());
    }
    public Map<String,Object> tick(String tenant) {
        if(searcher==null)return Map.of("executed",false,"reason","PROFILE_BROWSER_NOT_CONFIGURED");
        if(store.clock.instant().isBefore(authRetry))return Map.of("executed",false);
        if(store.clock.instant().isAfter(nextPlan)){enqueue(tenant);nextPlan=store.clock.instant().plusSeconds(300);}
        store.jdbc.update("UPDATE capture_profile_job SET status='FAILED',error_code='LEASE_EXPIRED',lease_token=NULL,due_at=? WHERE tenant_id=? AND status='RUNNING' AND lease_until<?",store.now(),tenant,store.now());
        var jobs=store.jdbc.queryForList("SELECT id,seed_name FROM capture_profile_job WHERE tenant_id=? AND status='PENDING' AND due_at<=? ORDER BY due_at,id LIMIT 1",tenant,store.now());
        if(jobs.isEmpty())return Map.of("executed",false);
        var j=jobs.getFirst();String token=UUID.randomUUID().toString();
        if(seeds(tenant).stream().noneMatch(s->jobId(tenant,(String)s.get("name")).equals(j.get("id")))) {
            store.jdbc.update("UPDATE capture_profile_job SET status='CANCELLED',error_code='RANK_SOURCE_INACTIVE',updated_at=? WHERE tenant_id=? AND id=? AND status='PENDING'",store.now(),tenant,j.get("id"));
            return Map.of("executed",true,"status","CANCELLED");
        }
        if(store.jdbc.update("UPDATE capture_profile_job SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=? WHERE tenant_id=? AND id=? AND status='PENDING'",token,Timestamp.from(store.clock.instant().plusSeconds(300)),store.now(),tenant,j.get("id"))!=1)return Map.of("executed",false);
        String status="FAILED",error=null;int count=0;
        try{var materials=searcher.search((String)j.get("seed_name"));for(var raw:materials){accept(tenant,raw);count++;}status=count>0?"SUCCEEDED":"NO_CANDIDATE";}
        catch(Exception e){error=RankingCaptureRunner.errorCode(e);if(Set.of("SOURCE_AUTH_REQUIRED","SOURCE_VERIFICATION_REQUIRED").contains(error))authRetry=store.clock.instant().plusSeconds(3600);}
        store.jdbc.update("UPDATE capture_profile_job SET status=?,error_code=?,lease_token=NULL,lease_until=NULL,due_at=?,updated_at=? WHERE tenant_id=? AND id=? AND lease_token=?",status,error,Timestamp.from(store.clock.instant().plusSeconds(error==null?86400:21600)),store.now(),tenant,j.get("id"),token);
        return Map.of("executed",true,"status",status,"documents",count,"errorCode",error==null?"":error);
    }
    private static String jobId(String tenant,String name){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((tenant+":"+DiandianProfileParser.normalize(name)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
