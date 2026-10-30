package com.youkia.iaa.collection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.*;
import java.util.*;

/** Public Apple games feed. Array position is source rank, never an IAA classification. */
@Service
public class AppleGamesService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Path materials;
    public AppleGamesService(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${app.material-dir:./data/materials}") String materialDir) {
        this.jdbc=jdbc; this.mapper=mapper; this.materials=Path.of(materialDir).toAbsolutePath().normalize();
    }
    public record Material(String country, int top, LocalDate sourceDate, LocalDate businessDate,
                           String updated, String url, JsonNode entries, String hash, String raw) {}

    public Material validate(String country, int top, LocalDate requestedDate, String raw) {
        if (!Set.of("cn","us","gb","de","fr").contains(country) || top<1 || top>100)
            throw invalid("INVALID_SCOPE", "国家或TopN不支持");
        if(raw==null || raw.isBlank() || raw.length()>4_000_000) throw invalid("INVALID_MATERIAL", "材料为空或超过4MB");
        try {
            JsonNode feed=mapper.readTree(raw).path("feed"), entries=feed.path("entry");
            String url="https://itunes.apple.com/"+country+"/rss/topfreeapplications/limit="+top+"/genre=6014/json";
            if(!url.equals(feed.path("id").path("label").asText())) throw invalid("SOURCE_SCOPE_MISMATCH","材料国家、游戏分类或TopN与请求不符");
            OffsetDateTime updated=OffsetDateTime.parse(feed.path("updated").path("label").asText());
            LocalDate business=updated.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if(business.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))) || (requestedDate!=null && !requestedDate.equals(business)))
                throw invalid("SOURCE_DATE_MISMATCH","必须按来源更新时间所属业务日入库，不能把当前榜单补成历史榜单");
            if(!entries.isArray() || entries.isEmpty() || entries.size()>top) throw invalid("INVALID_ENTRIES","来源无有效条目或条数超出请求范围");
            Set<String> ids=new HashSet<>();
            for(JsonNode entry:entries) {
                String id=entry.path("id").path("attributes").path("im:id").asText();
                if(!id.matches("[0-9]+") || !ids.add(id) || entry.path("im:name").path("label").asText().isBlank())
                    throw invalid("INVALID_ENTRY","条目缺失ID、名称或ID重复，整份材料拒绝发布");
                if(!"6014".equals(entry.path("category").path("attributes").path("im:id").asText()))
                    throw invalid("NOT_GAME","条目分类不是来源声明的游戏分类");
            }
            return new Material(country,top,updated.toLocalDate(),business,updated.toString(),url,entries,hash(raw),raw);
        } catch(ApiException e) {throw e;} catch(Exception e) {throw invalid("INVALID_MATERIAL","不是有效的Apple游戏RSS材料");}
    }

    @Transactional
    public Map<String,Object> ingest(String country, int top, LocalDate requestedDate, String raw) {
        Material m=validate(country,top,requestedDate,raw);
        long source=source();
        long chart=chart(source,m.country());
        // Serialize revisions per chart, including concurrent repeats of the same material.
        jdbc.queryForObject("SELECT id FROM chart_definition WHERE id=? FOR UPDATE",Long.class,chart);
        List<Long> prior=jdbc.queryForList("SELECT id FROM ranking_snapshot WHERE chart_id=? AND business_date=? AND raw_sha256=?",Long.class,chart,m.businessDate(),m.hash());
        if(!prior.isEmpty()) return result(prior.getFirst(),chart,m,true);
        Path file=materials.resolve(m.hash()+".json");
        try {
            Files.createDirectories(materials);
            if(!Files.exists(file)) Files.writeString(file,m.raw(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            if(!hash(Files.readString(file,StandardCharsets.UTF_8)).equals(m.hash())) throw new IllegalStateException("Hash mismatch");
        } catch(Exception e) {throw invalid("MATERIAL_STORE_FAILED","原始材料未能保存和核验，禁止发布快照");}
        List<Long> jobs=jdbc.queryForList("SELECT id FROM collection_job WHERE source_id=?",Long.class,source);
        long job=jobs.isEmpty()?insert("INSERT INTO collection_job(source_id,name,schedule_spec,timezone,enabled,config,version) VALUES(?,?,'manual','Asia/Shanghai',FALSE,'{}',0)",source,"Apple Games public validation"):jobs.getFirst();
        long run=insert("INSERT INTO collection_run(job_id,business_date,run_mode,status,stage,idempotency_key,started_at,heartbeat_at,model_metadata) VALUES(?,?,'LIVE_PUBLIC','RUNNING','VALIDATED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?)",
                job,m.businessDate(),"apple-games:"+chart+":"+m.businessDate()+":"+m.hash(),"{\"provider\":\"none\",\"parser\":\"apple-games-v1\"}");
        int revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM ranking_snapshot WHERE chart_id=? AND business_date=?",Integer.class,chart,m.businessDate());
        long snapshot=insert("INSERT INTO ranking_snapshot(chart_id,run_id,business_date,source_date,revision,status,completeness,requested_top_n,covered_top_n,actual_count,source_url,raw_locator,raw_sha256,captured_at,published_at) VALUES(?,?,?,?,?,'PUBLISHED',?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                chart,run,m.businessDate(),m.sourceDate(),revision,m.entries().size()==m.top()?"COMPLETE":"PARTIAL",m.top(),m.entries().size(),m.entries().size(),m.url(),file.toString(),m.hash());
        int rank=0;
        for(JsonNode entry:m.entries()) {
            rank++;
            String external=entry.path("id").path("attributes").path("im:id").asText();
            String name=entry.path("im:name").path("label").asText();
            List<Long> versions=jdbc.queryForList("SELECT id FROM platform_version WHERE platform='IOS' AND external_id=?",Long.class,external);
            long version;
            if(versions.isEmpty()) {
                long product=insert("INSERT INTO product(name,pool_state,first_observed_at,created_at,updated_at,version) VALUES(?,'PENDING_IDENTIFICATION',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0)",name);
                version=insert("INSERT INTO platform_version(product_id,platform,external_id,name,developer,package_name,entry_url,first_observed_at,source_fields,created_at,updated_at) VALUES(?,'IOS',?,?,?,?,?,UTC_TIMESTAMP(3),?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    product,external,name,entry.path("im:artist").path("label").asText(),entry.path("id").path("attributes").path("im:bundleId").asText(),entry.path("id").path("label").asText(),json(Map.of("source","APPLE_GAMES_PUBLIC","category","GAME","initialImport",true)));
            } else version=versions.getFirst();
            String summary=entry.path("summary").path("label").asText();
            Map<String,Object> metrics=new LinkedHashMap<>();
            metrics.put("source","APPLE_GAMES_PUBLIC");metrics.put("sourceUpdated",m.updated());metrics.put("name",name);
            metrics.put("sourceReleaseDate",entry.path("im:releaseDate").path("label").asText());
            metrics.put("sourceCategory",entry.path("category").path("attributes").path("label").asText());
            metrics.put("metricDefinitions",Map.of("rank",Map.of("kind","STORE_FREE_RANK","estimated",false,"scope",m.country(),"date",m.businessDate().toString()),"releaseDate","来源声明的上架时间，不是系统首次发现时间","monetization","UNKNOWN；商店免费定价不构成纯IAA证据"));
            metrics.put("developer",entry.path("im:artist").path("label").asText());metrics.put("summary",summary);
            metrics.put("storeUrl",entry.path("id").path("label").asText());metrics.put("estimated",false);metrics.put("monetization","UNKNOWN");
            JsonNode images=entry.path("im:image");
            if(images.isArray()&&!images.isEmpty()) metrics.put("thumbnailUrl",images.get(images.size()-1).path("label").asText(null));
            jdbc.update("INSERT INTO ranking_entry(snapshot_id,platform_version_id,rank_position,source_metrics) VALUES(?,?,?,?)",snapshot,version,rank,json(metrics));
            jdbc.update("INSERT INTO evidence(platform_version_id,source_id,kind,source_url,source_record_id,locator,raw_sha256,excerpt,observed_at,scope,metadata,created_by,created_at) VALUES(?,?,'PUBLIC_GAME_RANKING',?,?,?,?,?,UTC_TIMESTAMP(3),?,?,'apple-games',UTC_TIMESTAMP(3))",
                version,source,m.url(),external,"feed.entry["+(rank-1)+"]",m.hash(),name+"；原榜名次 #"+rank+"；来源介绍："+summary.substring(0,Math.min(summary.length(),1200)),
                json(Map.of("snapshotId",String.valueOf(snapshot),"region",m.country().toUpperCase(Locale.ROOT),"businessDate",m.businessDate().toString())),
                json(Map.of("public",true,"noIaa",true,"sourceUpdated",m.updated(),"rawLocator",file.toString(),"transport","submitted-material","authorization","Public endpoint access verified; commercial redistribution rights unverified")));
        }
        jdbc.update("UPDATE collection_run SET status='SUCCEEDED',stage='PUBLISHED',finished_at=UTC_TIMESTAMP(3),metrics=? WHERE id=?",json(Map.of("entries",rank,"rawSha256",m.hash())),run);
        new SnapshotMediaService(jdbc,mapper).extract(snapshot);
        return result(snapshot,chart,m,false);
    }
    private Map<String,Object> result(long snapshot,long chart,Material m,boolean reused) {
        return Map.of("snapshotId",String.valueOf(snapshot),"chartId",String.valueOf(chart),"businessDate",m.businessDate(),"sourceUpdated",m.updated(),"entries",m.entries().size(),"completeness",m.entries().size()==m.top()?"COMPLETE":"PARTIAL","reused",reused,"rawSha256",m.hash(),"monetization","UNKNOWN","sourceUrl",m.url());
    }
    private long source() {
        List<Long> ids=jdbc.queryForList("SELECT id FROM data_source WHERE adapter_key='apple-games-public'",Long.class);
        return ids.isEmpty()?insert("INSERT INTO data_source(name,adapter_key,access_method,enabled,config,authorization_notes,version,created_at,updated_at) VALUES(?,'apple-games-public','PUBLIC_API',TRUE,'{}',?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
            "Apple公开游戏分类RSS","公开访问已验证；本地技术验证，商业展示及再分发授权未核实；非IAA证据"):ids.getFirst();
    }
    private long chart(long source,String country) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM chart_definition WHERE source_id=? AND region=? AND external_chart_id='apple-games-free'",Long.class,source,country.toUpperCase(Locale.ROOT));
        return ids.isEmpty()?insert("INSERT INTO chart_definition(source_id,platform,region,external_chart_id,name,kind,category_key,device_scope,requested_top_n,enabled,methodology,config) VALUES(?,'IOS',?,'apple-games-free',?,'HEAT','GAME','DEFAULT',100,TRUE,?,'{}')",
            source,country.toUpperCase(Locale.ROOT),"Apple公开游戏免费榜（RSS分类6014）","Apple RSS数组顺序；游戏分类6014，设备未细分；业务日取来源更新时间转换上海时区；不是下载量或IAA结论"):ids.getFirst();
    }
    private long insert(String sql,Object... args) {
        GeneratedKeyHolder keys=new GeneratedKeyHolder();
        jdbc.update(c->{var s=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);return s;},keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hash(String raw) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ApiException invalid(String code,String message) {return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}
}
