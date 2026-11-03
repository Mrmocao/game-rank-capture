package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.CaptureSources;
import com.youkia.iaa.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.nio.file.*;
import java.util.*;

/** Read model for source candidates. Internal source keys are never presented as verified platform appids. */
@Service
public class MarketCaptureService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final String tenant;private final Path receipts;
    public MarketCaptureService(JdbcTemplate jdbc,ObjectMapper mapper,@Value("${PROFILE_TENANT_ID:default}")String tenant,
            @Value("${CAPTURE_SYNC_RECEIPT_DIR:./data/deliveries}")String receipts){this.jdbc=jdbc;this.mapper=mapper;this.tenant=tenant;this.receipts=Path.of(receipts);}
    public LocalDate latest(){return jdbc.queryForObject("SELECT MAX(o.business_date) FROM external_ranking_observation o WHERE o.tenant_id=? AND o.business_date<=? AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.observation_id=o.id AND r.tenant_id=o.tenant_id AND r.status IN ('SUCCEEDED','PARTIAL'))",LocalDate.class,tenant,LocalDate.now(ZoneId.of("Asia/Shanghai")));}
    private List<Map<String,Object>> observations(LocalDate start,LocalDate end){
        return jdbc.queryForList("""
            SELECT o.id,o.source_key,o.chart_key,o.board_name,o.platform,o.region,o.business_date,o.source_date,
              o.captured_at,o.available_at,o.completeness,o.actual_count,o.requested_top_n,o.contiguous_top_n,o.source_authority,o.source_url,o.raw_sha256
            FROM external_ranking_observation o WHERE o.tenant_id=? AND o.business_date BETWEEN ? AND ?
              AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.observation_id=o.id AND r.tenant_id=o.tenant_id AND r.status IN ('SUCCEEDED','PARTIAL'))
            ORDER BY o.captured_at DESC,o.available_at DESC,o.id DESC
            """,tenant,start,end);
    }
    private static String scope(Map<String,Object> o){return o.get("source_key")+":"+o.get("chart_key")+":"+o.get("region");}
    private static String text(Map<String,Object> o,String key){return Objects.toString(o.get(key),"");}
    private JsonNode read(Object value){try{return value instanceof byte[] b?mapper.readTree(b):mapper.readTree(Objects.toString(value,"{}"));}catch(Exception e){throw new IllegalStateException("来源字段解析失败",e);}}
    public Map<String,Object> catalog(LocalDate day){
        Map<String,Map<String,Object>> latest=new LinkedHashMap<>();for(var row:observations(day,day))latest.putIfAbsent(scope(row),row);
        List<Map<String,Object>> charts=new ArrayList<>();
        for(var request:CaptureSources.daily(100)){
            String key=request.sourceKey()+":"+request.chartKey()+":"+request.region();var o=latest.get(key);
            Map<String,Object> chart=new LinkedHashMap<>();chart.put("id","capture:"+key);chart.put("sourceKey",request.sourceKey());chart.put("chartKey",request.chartKey());chart.put("region",request.region().toUpperCase(Locale.ROOT));chart.put("external",true);
            chart.put("platform",request.sourceKey().equals("gravity-wechat")?"WECHAT":request.sourceKey().equals("gravity-douyin")?"DOUYIN":"IOS");
            String label=switch(request.chartKey()){case "popularity"->request.sourceKey().equals("gravity-wechat")?"人气榜":"热门榜";case "bestseller"->"畅销榜";case "most_played"->"畅玩榜";case "fresh_game"->"新游榜";case "publisher_heat"->"发行人热度榜";default->"游戏免费榜";};
            chart.put("name",label);chart.put("chart_type",switch(request.chartKey()){case "top-free"->"FREE";case "bestseller"->"BESTSELLER";case "publisher_heat"->"PROMOTION";case "fresh_game"->"NEW";default->"POPULARITY";});
            chart.put("actual_count",o==null?0:o.get("actual_count"));chart.put("requested_top_n",100);chart.put("covered_top_n",o==null?0:o.get("contiguous_top_n"));
            String state=o==null?"MISSING_SNAPSHOT":"REQUESTED_RANGE_COMPLETE".equals(o.get("completeness"))?"COMPLETE":"PARTIAL";
            chart.put("data_state",state);chart.put("completeness",state);chart.put("observationId",o==null?null:o.get("id"));chart.put("sourceUrl",o==null?null:o.get("source_url"));chart.put("capturedAt",o==null?null:o.get("captured_at"));chart.put("availableAt",o==null?null:o.get("available_at"));charts.add(chart);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("date",day);result.put("latestAvailableDate",latest());result.put("charts",charts);result.put("rankingEntries",charts.stream().mapToInt(c->((Number)c.get("actual_count")).intValue()).sum());result.put("observedCharts",latest.size());result.put("expectedCharts",charts.size());result.put("sync",sync());return result;
    }
    public Map<String,Object> candidates(LocalDate day,String platform,String q){
        var universe=universe(day);Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
        for(var row:universe)if(day.toString().equals(text(row,"businessDate"))){
            if(platform!=null&&!platform.isBlank()&&!platform.equals(row.get("platform")))continue;
            if(q!=null&&!q.isBlank()&&!text(row,"name").toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))continue;
            String key=text(row,"candidateKey");var item=unique.get(key);
            if(item==null){item=new LinkedHashMap<>(row);item.put("rankings",new ArrayList<Map<String,Object>>());unique.put(key,item);}
            ((List<Map<String,Object>>)item.get("rankings")).add(row);
        }
        var items=new ArrayList<>(unique.values());items.sort(Comparator.comparing(r->text(r,"platform")+text(r,"name")));
        return Map.of("date",day,"total",items.size(),"items",items,"notice","来源候选按平台独立保留；同来源同名同发行方仅用于待核观察匹配，不是已核实产品身份。海外缺失不阻断国内研究。");
    }
    public Map<String,Object> ranking(LocalDate day,String chartId){
        var charts=(List<Map<String,Object>>)catalog(day).get("charts");var chart=charts.stream().filter(c->chartId.equals(c.get("id"))).findFirst().orElseThrow(()->bad("未知来源榜单"));
        var items=universe(day).stream().filter(r->chartId.equals(r.get("chartId"))&&day.toString().equals(text(r,"businessDate"))).sorted(Comparator.comparingInt(r->((Number)r.get("rank")).intValue())).toList();
        return Map.of("chart",chart,"date",day,"items",items,"notice","完整仅表示来源请求Top100覆盖；热度、畅销、买量信号分别记录，来源描述中的经营数字未经独立核验。");
    }
    private List<Map<String,Object>> universe(LocalDate day){
        Map<String,Map<String,Object>> chosen=new LinkedHashMap<>();for(var o:observations(day.minusDays(6),day))chosen.putIfAbsent(scope(o)+":"+o.get("business_date"),o);
        if(chosen.isEmpty())return List.of();Map<Long,Map<String,Object>> byId=new HashMap<>();chosen.values().forEach(o->byId.put(((Number)o.get("id")).longValue(),o));
        String marks=String.join(",",Collections.nCopies(byId.size(),"?"));var entries=jdbc.queryForList("SELECT observation_id,rank_position,external_id,name,developer,evidence_locator,source_fields FROM external_ranking_entry WHERE observation_id IN ("+marks+")",byId.keySet().toArray());
        for(var e:entries){var o=byId.get(((Number)e.get("observation_id")).longValue());for(String k:List.of("source_key","platform","region"))e.put(k,o.get(k));}
        new SourceIdentityService(jdbc,mapper,tenant).enrich(entries,day,LocalDateTime.now(ZoneOffset.UTC));
        List<Map<String,Object>> rows=new ArrayList<>();Map<String,Map<String,Map<String,Object>>> history=new HashMap<>();
        for(var e:entries){var o=byId.get(((Number)e.get("observation_id")).longValue());var fields=read(e.get("source_fields"));Map<String,Object> r=new LinkedHashMap<>();
            String identity=!text(e,"external_id").isBlank()?"id:"+e.get("external_id"):e.get("name")+":"+text(e,"developer")+(text(e,"developer").isBlank()?":"+o.get("id"):"");
            r.put("candidateKey",MarketWeeklySkill.hash(o.get("source_key")+":"+o.get("region")+":"+identity));r.put("chartId","capture:"+scope(o));r.put("observationId",o.get("id"));r.put("rank",e.get("rank_position"));
            r.put("name",e.get("name"));r.put("product_name",e.get("name"));r.put("developer",e.get("developer"));r.put("externalId",e.get("external_id"));r.put("identityState",e.get("external_id")==null?"UNVERIFIED":"SOURCE_ID_OBSERVED");r.put("monetization","UNKNOWN");
            r.put("platform",o.get("platform"));r.put("region",text(o,"region").toUpperCase(Locale.ROOT));r.put("sourceKey",o.get("source_key"));r.put("boardName",o.get("board_name"));r.put("chartKey",o.get("chart_key"));r.put("sourceUrl",o.get("source_url"));r.put("businessDate",o.get("business_date").toString());
            r.put("capturedAt",o.get("captured_at"));r.put("availableAt",o.get("available_at"));r.put("rawSha256",o.get("raw_sha256"));r.put("evidenceLocator",e.get("evidence_locator"));r.put("authority",o.get("source_authority"));r.put("completeness","REQUESTED_RANGE_COMPLETE".equals(o.get("completeness"))?"COMPLETE":"PARTIAL");
            r.put("thumbnail_url",IconCache.display(fields.path("thumbnailUrl").asText("")));r.put("tags",fields.path("tags").isArray()?mapper.convertValue(fields.path("tags"),List.class):List.of());r.put("description",fields.path("descriptionRaw").asText(""));r.put("gameplay",SourceGameplay.read(fields));
            r.put("associationKey",SourceEntity.sha(e));
            for(var field:Map.of("unified_product_id","unifiedProductId","unified_product_name","unifiedProductName","association_basis","associationBasis","identity_review","identityReview","reviewed_gameplay","reviewedGameplay").entrySet())if(e.containsKey(field.getKey()))r.put(field.getValue(),e.get(field.getKey()));
            rows.add(r);history.computeIfAbsent(r.get("candidateKey")+":"+r.get("chartId"),k->new HashMap<>()).put(text(r,"businessDate"),r);
        }
        for(var row:rows)if(day.toString().equals(text(row,"businessDate"))){var series=history.get(row.get("candidateKey")+":"+row.get("chartId"));List<Map<String,Object>> points=new ArrayList<>();
            for(int i=6;i>=0;i--){String date=day.minusDays(i).toString();var original=series.get(date);Map<String,Object> p=new LinkedHashMap<>();p.put("date",date);p.put("rank",original==null?null:original.get("rank"));p.put("state",original==null?"MISSING_OR_IDENTITY_UNCONFIRMED":"OBSERVED");if(original!=null){p.put("observationId",original.get("observationId"));p.put("rawSha256",original.get("rawSha256"));p.put("evidenceLocator",original.get("evidenceLocator"));}points.add(p);}
            row.put("history",points);
        }
        return rows;
    }
    public Map<String,Object> sync(){
        Map<String,Object> out=new LinkedHashMap<>();for(String name:List.of("heartbeat","latest","source-session")){Path p=receipts.resolve(name+".json");try{if(Files.isRegularFile(p)&&Files.size(p)<32768)out.put(name,mapper.readTree(Files.readString(p)));}catch(Exception e){out.put("readError","同步记录暂不可读");}}
        return out;
    }
    public Map<String,Object> selections(){var rows=jdbc.queryForList("SELECT id,owner,status,note,created_at,source_snapshot FROM source_candidate_selection WHERE tenant_id=? ORDER BY created_at DESC,id DESC LIMIT 200",tenant);rows.forEach(r->r.put("source_snapshot",read(r.get("source_snapshot"))));return Map.of("items",rows);}
    @Transactional public Map<String,Object> select(JsonNode body,String key){
        String owner=body.path("owner").asText("").trim(),status=body.path("status").asText("SELECTED"),note=body.path("note").asText("");
        if(owner.isBlank()||owner.length()>100||note.length()>2000||!Set.of("SELECTED","TESTING","COMPLETED","ABANDONED").contains(status)||key==null||!key.matches("[a-zA-Z0-9-]{16,64}"))throw bad("负责人、状态或请求标识无效");
        long observation=body.path("observationId").asLong();int rank=body.path("rank").asInt();
        var old=reused(key,observation,rank,owner,status,note);if(old!=null)return old;
        var dates=jdbc.queryForList("SELECT business_date FROM external_ranking_observation WHERE id=? AND tenant_id=?",LocalDate.class,observation,tenant);if(dates.isEmpty())throw bad("来源观察不存在");
        var rows=universe(dates.getFirst());var row=rows.stream().filter(r->((Number)r.get("observationId")).longValue()==observation&&((Number)r.get("rank")).intValue()==rank).findFirst().orElseGet(()->exactSnapshot(observation,rank));
        try{jdbc.update("INSERT INTO source_candidate_selection(tenant_id,request_key,observation_id,rank_position,owner,status,note,source_snapshot,created_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",tenant,key,observation,rank,owner,status,note,mapper.writeValueAsString(row));}catch(org.springframework.dao.DuplicateKeyException e){var existing=reused(key,observation,rank,owner,status,note);if(existing!=null)return existing;throw e;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return Map.of("id",jdbc.queryForObject("SELECT id FROM source_candidate_selection WHERE tenant_id=? AND request_key=?",Long.class,tenant,key),"reused",false);
    }
    private Map<String,Object> reused(String key,long observation,int rank,String owner,String status,String note){
        var rows=jdbc.queryForList("SELECT id,observation_id,rank_position,owner,status,note FROM source_candidate_selection WHERE tenant_id=? AND request_key=?",tenant,key);if(rows.isEmpty())return null;var r=rows.getFirst();
        if(((Number)r.get("observation_id")).longValue()!=observation||((Number)r.get("rank_position")).intValue()!=rank||!owner.equals(r.get("owner"))||!status.equals(r.get("status"))||!note.equals(r.get("note")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同一请求标识不能用于不同研究记录");
        return Map.of("id",r.get("id"),"reused",true);
    }
    private Map<String,Object> exactSnapshot(long observation,int rank){
        var rows=jdbc.queryForList("SELECT o.bundle_json,o.available_at FROM external_ranking_observation o WHERE o.id=? AND o.tenant_id=? AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.observation_id=o.id AND r.tenant_id=o.tenant_id AND r.status IN ('SUCCEEDED','PARTIAL'))",observation,tenant);
        if(rows.isEmpty())throw bad("来源观察不可用");var b=read(rows.getFirst().get("bundle_json"));var ob=b.path("observation");JsonNode entry=null;for(var e:ob.path("entries"))if(e.path("rank").asInt()==rank){entry=e;break;}if(entry==null)throw bad("来源名次不存在");var fields=entry.path("sourceFields");
        Map<String,Object> r=new LinkedHashMap<>();r.put("name",entry.path("name").asText());r.put("developer",entry.path("developer").asText(""));r.put("platform",ob.path("platform").asText());r.put("region",b.path("request").path("region").asText().toUpperCase(Locale.ROOT));r.put("boardName",ob.path("boardName").asText());r.put("chartId","capture:"+b.path("request").path("sourceKey").asText()+":"+b.path("request").path("chartKey").asText()+":"+b.path("request").path("region").asText());r.put("observationId",observation);r.put("rank",rank);r.put("businessDate",ob.path("businessDate").asText());r.put("capturedAt",b.path("evidence").path("capturedAt").asText());r.put("availableAt",rows.getFirst().get("available_at"));r.put("sourceUrl",b.path("evidence").path("sourceUrl").asText());r.put("rawSha256",b.path("evidence").path("sha256").asText());r.put("evidenceLocator",entry.path("evidenceLocator").asText());r.put("description",fields.path("descriptionRaw").asText(""));r.put("gameplay",SourceGameplay.read(fields));r.put("monetization","UNKNOWN");r.put("identityState",entry.hasNonNull("externalId")?"SOURCE_ID_OBSERVED":"UNVERIFIED");r.put("completeness","REQUESTED_RANGE_COMPLETE".equals(b.path("coverage").path("status").asText())?"COMPLETE":"PARTIAL");r.put("history",List.of(Map.of("date",ob.path("businessDate").asText(),"rank",rank,"observationId",observation)));return r;
    }
    private static ApiException bad(String message){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"SOURCE_CANDIDATE_INVALID",message);}
}
