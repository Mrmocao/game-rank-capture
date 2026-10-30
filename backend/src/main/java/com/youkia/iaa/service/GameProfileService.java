package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Single current game dossier. Market facts and current collaboration use distinct time scopes. */
@Service
public class GameProfileService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final ProfileFieldCatalog catalog;
    private final String tenant;
    public GameProfileService(JdbcTemplate jdbc,ObjectMapper mapper,@Value("${PROFILE_TENANT_ID:default}") String tenant){this.jdbc=jdbc;this.mapper=mapper;this.tenant=tenant;catalog=new ProfileFieldCatalog(mapper);}
    public String tenant(){return tenant;}
    public JsonNode definitions(){return catalog.document();}
    /** Scoped recommendation projection; coverage is deliberately absent from numeric inputs. */
    public void enrichRecommendation(Map<String,Object> input,long product,long entity,String market,LocalDate day,LocalDateTime availableAt){
        LocalDateTime end=cutoff(day,"Asia/Shanghai");if(availableAt.isBefore(end))end=availableAt;
        final var knownAt=end;
        var scopes=jdbc.queryForList("SELECT l.*,v.product_id FROM market_listing l JOIN platform_version v ON v.id=l.platform_version_id WHERE l.tenant_id=? AND l.platform_version_id=? AND v.product_id=? AND l.market=? AND l.available_at<=? AND l.first_observed_at<=? ORDER BY l.id",tenant,entity,product,market,knownAt,knownAt);
        // Legacy packets without an application release cannot certify current IAA.
        input.put("iaaCurrent",false);
        if(scopes.isEmpty())return;
        var scope=scopes.getFirst();long lid=num(scope,"id");
        var releases=jdbc.queryForList("SELECT * FROM release_record WHERE tenant_id=? AND listing_id=? AND released_at<=? AND available_at<=? ORDER BY released_at DESC,id DESC LIMIT 1",tenant,lid,end,end);
        Long release=releases.isEmpty()?null:num(releases.getFirst(),"id");
        var histories=observations(lid,end);var fields=new LinkedHashMap<String,Map<String,Object>>();
        if(histories.isEmpty()){input.put("profileFacts",Map.of("schemaVersion","2.3.0","listingId",lid,"market",market,"language",scope.get("language"),"status","missing","isSynthetic",false));return;}
        for(var definition:catalog.fields()){String key=definition.path("key").asText();fields.put(key,GameProfileResolver.resolve(definition,histories.getOrDefault(key,List.of()),end,release));}
        boolean synthetic=fields.values().stream().anyMatch(f->Boolean.TRUE.equals(f.get("isSynthetic"))||f.get("isSynthetic") instanceof Number n&&n.intValue()!=0);
        var qualification=GameProfileResolver.qualification(fields);
        input.put("profileFacts",Map.of("schemaVersion","2.3.0","listingId",lid,"releaseId",release==null?"unknown":release,"market",market,"language",scope.get("language"),"qualification",qualification,"fields",fields,"isSynthetic",synthetic));
        var mode=fields.get("mode");if("available".equals(mode.get("status"))&&!"inference".equals(mode.get("kind")))input.put("mode",GameProfileResolver.text(mode.get("value")));
        input.put("iaaCurrent",Boolean.TRUE.equals(qualification.get("eligible"))&&!synthetic);
        if(release!=null){
            // Old packets must explicitly name this actual release before version-bound judgements survive.
            var packet=mapper.valueToTree(input.get("packet"));boolean same=packet.path("appVersion").asText().equals(releases.getFirst().get("app_version"));
            if(!same){input.put("gameplay",null);input.put("effortLower",null);input.put("effortUpper",null);input.put("adStage",null);input.put("gapStage",null);}
        }
        boolean identity=List.of("external_id","developer").stream().allMatch(k->usableFact(fields.get(k)));
        if(identity&&!synthetic)input.put("identity",true);
        if(!synthetic){
            if(usableFact(fields.get("core_loop"))&&usableFact(fields.get("summary")))input.put("gameplay",true);
            if(usableFact(fields.get("family")))input.put("family",GameProfileResolver.text(fields.get("family").get("value")));
            var effort=fields.get("effort");if(usableFact(effort)){var range=mapper.valueToTree(effort.get("value"));input.put("effortLower",range.path("lower").asDouble());input.put("effortUpper",range.path("upper").asDouble());}
        }
        var states=new LinkedHashMap<String,Object>();if(input.get("states") instanceof Map<?,?> old)old.forEach((k,v)->states.put(String.valueOf(k),v));
        if(Set.of("conflict","stale").contains(mode.get("status"))){states.put("iaa",mode.get("status"));input.put("iaaCurrent",false);input.put("mode","unknown");}input.put("states",states);
    }
    private boolean usableFact(Map<String,Object> f){return f!=null&&"available".equals(f.get("status"))&&Boolean.TRUE.equals(f.get("reviewed"))&&!"inference".equals(f.get("kind"))&&f.get("value")!=null;}
    public void synchronizeListings(){
        jdbc.update("""
            INSERT IGNORE INTO market_listing(tenant_id,platform_version_id,market,language,source_id,first_observed_at,available_at)
            SELECT ?,v.id,c.region,CASE c.region WHEN 'CN' THEN 'zh-CN' WHEN 'DE' THEN 'de-DE' WHEN 'FR' THEN 'fr-FR' WHEN 'GB' THEN 'en-GB' ELSE 'en-US' END,
             MIN(c.source_id),MIN(s.captured_at),MIN(s.published_at)
            FROM platform_version v JOIN ranking_entry e ON e.platform_version_id=v.id JOIN ranking_snapshot s ON s.id=e.snapshot_id
            JOIN chart_definition c ON c.id=s.chart_id JOIN data_source ds ON ds.id=c.source_id
            WHERE s.status='PUBLISHED' AND ds.access_method<>'SIMULATED' GROUP BY v.id,c.region
            """,tenant);
    }
    @Transactional public Map<String,Object> products(LocalDate day,String q,String platform,String market,String qualification,String gap,Long chartId,int page,int pageSize){
        var end=cutoff(day,"Asia/Shanghai");if(page<1||pageSize<1||pageSize>100)throw new IllegalArgumentException("分页范围无效");
        if(chartId!=null){var c=jdbc.queryForList("SELECT platform,region FROM chart_definition WHERE id=?",chartId);if(c.isEmpty())throw new IllegalArgumentException("指定榜单不存在");if(platform==null||market==null||!platform.equals(c.getFirst().get("platform"))||!market.equals(c.getFirst().get("region")))throw new IllegalArgumentException("按名次排序需指定同一平台、市场与榜单");}
        boolean filtered=qualification!=null&&!qualification.isBlank()||gap!=null&&!gap.isBlank()||chartId!=null;
        var base=new ProductQueryService(jdbc,tenant).list(day.toString(),q,platform,market,null,null,null,filtered?1:page,filtered?100:pageSize);
        int total=((Number)base.get("total")).intValue();var candidates=new ArrayList<Map<String,Object>>();
        if(filtered){
            if(total>2000)throw new IllegalArgumentException("筛选范围超过2000款，请先限定平台、地区或关键词");
            for(int i=1;i<=(total+99)/100;i++){var batch=i==1?base:new ProductQueryService(jdbc,tenant).list(day.toString(),q,platform,market,null,null,null,i,100);candidates.addAll((List<Map<String,Object>>)batch.get("items"));}
        }else{candidates.addAll((List<Map<String,Object>>)base.get("items"));}
        var out=new ArrayList<Map<String,Object>>();
        for(var item:candidates){long id=Long.parseLong(item.get("id").toString());var choices=listings(id).stream().filter(l->!GameProfileResolver.time(l.get("available_at")).isAfter(end)&&!GameProfileResolver.time(l.get("first_observed_at")).isAfter(end)&&(platform==null||platform.isBlank()||platform.equals(l.get("platform")))&&(market==null||market.isBlank()||market.equals(l.get("market")))).toList();if(choices.isEmpty())continue;var scope=choices.getFirst();long lid=num(scope,"id");
            var releases=jdbc.queryForList("SELECT id FROM release_record WHERE tenant_id=? AND listing_id=? AND available_at<=? AND released_at<=? ORDER BY released_at DESC,id DESC LIMIT 1",tenant,lid,end,end);Long release=releases.isEmpty()?null:num(releases.getFirst(),"id");
            var histories=observations(lid,end);var source=sourceFields(scope,day,end);var fields=new LinkedHashMap<String,Map<String,Object>>();for(var d:catalog.fields()){String key=d.path("key").asText();var h=new ArrayList<>(histories.getOrDefault(key,List.of()));if(h.stream().noneMatch(x->!"inference".equals(x.get("kind")))&&source.containsKey(key))h.add(source.get(key));fields.put(key,GameProfileResolver.resolve(d,h,end,release));}markNotYetKnown(lid,end,fields);
            var qual=GameProfileResolver.qualification(fields);var cov=GameProfileResolver.coverage(fields);
            if(qualification!=null&&!qualification.isBlank()&&!qualification.equals(qual.get("status")))continue;
            if("missing".equals(gap)&&((Number)cov.get("missing")).intValue()==0)continue;
            if("conflict_stale".equals(gap)&&fields.values().stream().noneMatch(f->Set.of("stale","conflict").contains(f.get("status"))))continue;
            item.put("name",fields.get("name").get("value")==null?"游戏 #"+id:GameProfileResolver.text(fields.get("name").get("value")));
            item.put("referenceListingId",lid);item.put("referenceVersionId",scope.get("platform_version_id"));item.put("referencePlatform",scope.get("platform"));item.put("referenceMarket",scope.get("market"));item.put("qualification",qual);item.put("profileCoverage",cov);item.put("summary",fields.get("summary").get("value"));
            var ranks=(List<Map<String,Object>>)item.get("rankings");ranks=ranks.stream().filter(r->choices.stream().anyMatch(l->String.valueOf(l.get("platform_version_id")).equals(String.valueOf(r.get("versionId")))&&l.get("market").equals(r.get("region")))).toList();for(var r:ranks){var ls=choices.stream().filter(l->String.valueOf(l.get("platform_version_id")).equals(String.valueOf(r.get("versionId")))&&l.get("market").equals(r.get("region"))).findFirst().orElseThrow();var ss=series(ls,day,end);var point=ss.stream().filter(p->day.equals(p.get("date"))&&String.valueOf(r.get("chartId")).equals(String.valueOf(p.get("chartId")))).findFirst().orElse(Map.of("state","MISSING_SNAPSHOT"));var old=ss.stream().filter(p->day.minusDays(7).equals(p.get("date"))&&String.valueOf(r.get("chartId")).equals(String.valueOf(p.get("chartId")))).findFirst().orElse(Map.of());r.put("rank",point.get("rank"));r.put("dataState",point.get("state"));r.put("snapshotId",point.get("snapshotId"));r.put("delta7d",point.get("rank") instanceof Number n&&old.get("rank") instanceof Number o?o.intValue()-n.intValue():null);r.put("monetization","UNKNOWN");}item.put("rankings",ranks);Object rank=ranks.stream().filter(r->chartId!=null&&chartId.toString().equals(String.valueOf(r.get("chartId")))).map(r->r.get("rank")).filter(Objects::nonNull).findFirst().orElse(null);item.put("sortRank",rank);out.add(item);
        }
        if(chartId!=null)out.sort(Comparator.comparingInt(i->i.get("sortRank") instanceof Number n?n.intValue():Integer.MAX_VALUE));
        int n=filtered?out.size():total;var items=filtered?out.subList(Math.min((page-1)*pageSize,out.size()),Math.min(page*pageSize,out.size())):out;
        return Map.of("items",items,"total",n,"page",page,"pageSize",pageSize,"date",day,"referenceScope","每行资格与资料量仅针对参考平台市场版本");
    }
    public static LocalDateTime cutoff(LocalDate day,String timezone){
        ZoneId zone=ZoneId.of(timezone==null?"Asia/Shanghai":timezone);
        if(day==null||day.isAfter(LocalDate.now(zone)))throw new IllegalArgumentException("知识日期不能为空或晚于今天");
        return day.plusDays(1).atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().minusNanos(1_000_000);
    }
    @Transactional public List<Map<String,Object>> listings(long product){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=?",Integer.class,product)==0)notFound("产品不存在");
        jdbc.update("""
            INSERT IGNORE INTO market_listing(tenant_id,platform_version_id,market,language,source_id,first_observed_at,available_at)
            SELECT ?,v.id,c.region,CASE c.region WHEN 'CN' THEN 'zh-CN' WHEN 'DE' THEN 'de-DE' WHEN 'FR' THEN 'fr-FR' WHEN 'GB' THEN 'en-GB' ELSE 'en-US' END,
              MIN(c.source_id),MIN(s.captured_at),MIN(s.published_at)
            FROM platform_version v JOIN ranking_entry e ON e.platform_version_id=v.id JOIN ranking_snapshot s ON s.id=e.snapshot_id
            JOIN chart_definition c ON c.id=s.chart_id JOIN data_source ds ON ds.id=c.source_id
            WHERE v.product_id=? AND s.status='PUBLISHED' AND ds.access_method<>'SIMULATED' GROUP BY v.id,c.region
            """,tenant,product);
        return jdbc.queryForList("SELECT l.*,v.product_id,v.platform,v.external_id FROM market_listing l JOIN platform_version v ON v.id=l.platform_version_id WHERE l.tenant_id=? AND v.product_id=? ORDER BY v.platform,l.market,l.id",tenant,product);
    }
    public Map<String,Object> listing(long id){var rows=jdbc.queryForList("SELECT l.*,v.product_id,v.platform,v.external_id,v.first_observed_at entity_first_observed FROM market_listing l JOIN platform_version v ON v.id=l.platform_version_id WHERE l.tenant_id=? AND l.id=?",tenant,id);if(rows.isEmpty())notFound("发行范围不存在或无权访问");return rows.getFirst();}
    @Transactional public Map<String,Object> profile(long product,Long listingId,Long entityId,String market,LocalDate day,String timezone){
        LocalDateTime end=cutoff(day,timezone);var choices=listings(product).stream().filter(x->!GameProfileResolver.time(x.get("first_observed_at")).isAfter(end)&&!GameProfileResolver.time(x.get("available_at")).isAfter(end)).toList();
        if(choices.isEmpty())notFound("截至该日尚未发现该产品的发行范围");
        var scope=choices.stream().filter(x->listingId!=null?num(x,"id")==listingId:(entityId==null||num(x,"platform_version_id")==entityId)&&(market==null||market.equals(x.get("market")))).findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"SCOPE_NOT_FOUND","指定平台市场不属于该产品或当时尚未知"));
        long lid=num(scope,"id");
        var releases=jdbc.queryForList("SELECT * FROM release_record WHERE tenant_id=? AND listing_id=? AND released_at<=? AND available_at<=? ORDER BY released_at,id",tenant,lid,end,end);
        var release=releases.isEmpty()?null:releases.getLast();Long current=release==null?null:num(release,"id");
        var history=observations(lid,end);var fallback=sourceFields(scope,day,end);
        Map<String,Map<String,Object>> fields=new LinkedHashMap<>();
        for(var definition:catalog.fields()){
            String key=definition.path("key").asText();var h=new ArrayList<>(history.getOrDefault(key,List.of()));
            // Structured source projections are used only when no independently reviewed observation exists.
            if(h.stream().noneMatch(x->!"inference".equals(x.get("kind")))&&fallback.containsKey(key))h.add(fallback.get(key));
            fields.put(key,GameProfileResolver.resolve(definition,h,end,current));
        }
        markNotYetKnown(lid,end,fields);
        Map<String,Object> result=new LinkedHashMap<>();result.put("schemaVersion","2.3.0");result.put("productId",String.valueOf(product));result.put("listing",scope);result.put("listings",choices);result.put("asOf",day);result.put("timezone",timezone==null?"Asia/Shanghai":timezone);result.put("knowledgeCutoff",end);
        result.put("currentRelease",release);result.put("fields",fields);result.put("coverage",GameProfileResolver.coverage(fields));result.put("qualification",GameProfileResolver.qualification(fields));result.put("domains",catalog.document().path("domains"));
        result.put("series",series(scope,day,end));result.put("media",media(lid,end,current));result.put("isSynthetic",String.valueOf(scope.get("external_id")).startsWith("demo-")||fields.values().stream().anyMatch(f->Boolean.TRUE.equals(f.get("isSynthetic"))||f.get("isSynthetic") instanceof Number n&&n.intValue()!=0));
        result.put("collaboration",Map.of("scope","CURRENT","aiRuns",jdbc.queryForList("SELECT id,status,model,field_keys,result_json,created_at,finished_at FROM profile_ai_run WHERE tenant_id=? AND listing_id=? ORDER BY id DESC LIMIT 20",tenant,lid),"tasks",tasks(lid),"submissions",submissions(lid),"selections",jdbc.queryForList("SELECT id,owner_name,status,reference_version_id,reference_listing_id,selected_on,started_on,finished_on,note,version FROM selection_record WHERE tenant_id=? AND product_id=? ORDER BY id DESC",tenant,product)));
        return result;
    }
    private Map<String,List<Map<String,Object>>> observations(long listing,LocalDateTime end){
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        var rows=jdbc.queryForList("SELECT o.*,r.app_version,e.rights,e.is_synthetic,e.source_name,e.source_url FROM profile_field_observation o JOIN profile_evidence e ON e.id=o.evidence_id AND e.tenant_id=o.tenant_id LEFT JOIN release_record r ON r.id=o.release_id WHERE o.tenant_id=? AND o.listing_id=? AND o.available_at<=? AND o.observed_at<=? ORDER BY o.available_at,o.id",tenant,listing,end,end);
        for(var r:rows){var h=observation(r);result.computeIfAbsent(String.valueOf(r.get("field_key")),k->new ArrayList<>()).add(h);}
        var claims=jdbc.queryForList("SELECT c.*,e.rights,e.is_synthetic,e.source_name,e.source_url FROM profile_claim c JOIN profile_evidence e ON e.id=c.evidence_id AND e.tenant_id=c.tenant_id WHERE c.tenant_id=? AND c.listing_id=? AND c.available_at<=? AND c.observed_at<=? AND c.status='draft' ORDER BY c.available_at,c.id",tenant,listing,end,end);
        for(var r:claims){r.put("value_kind","inference");r.put("data_status","available");var h=observation(r);h.put("id","claim-"+r.get("id"));h.put("reviewed",false);h.put("modelMetadata",read(r.get("model_metadata")));result.computeIfAbsent(String.valueOf(r.get("field_key")),k->new ArrayList<>()).add(h);}
        return result;
    }
    private void markNotYetKnown(long listing,LocalDateTime cutoff,Map<String,Map<String,Object>> fields){
        // Only the query state is exposed; no later value, time, evidence or author enters replay.
        var keys=jdbc.queryForList("SELECT field_key FROM profile_field_observation WHERE tenant_id=? AND listing_id=? AND (available_at>? OR observed_at>?) UNION SELECT field_key FROM profile_claim WHERE tenant_id=? AND listing_id=? AND (available_at>? OR observed_at>?)",String.class,tenant,listing,cutoff,cutoff,tenant,listing,cutoff,cutoff);
        for(String key:keys){var field=fields.get(key);if(field!=null&&"missing".equals(field.get("status"))){field.put("status","not_yet_known");field.put("reason","截至该日尚未形成可用资料");}}
    }
    private Map<String,Object> observation(Map<String,Object> row){
        var h=new LinkedHashMap<String,Object>();for(var e:Map.of("id","id","status","data_status","kind","value_kind","observedAt","observed_at","availableAt","available_at","validUntil","valid_until","releaseId","release_id","evidenceId","evidence_id","supersedesId","supersedes_id","reason","reason").entrySet())h.put(e.getKey(),row.get(e.getValue()));
        h.put("value",row.get("typed_value")==null?null:read(row.get("typed_value")));h.put("reviewed",true);h.put("rights",row.get("rights"));h.put("reviewer",row.get("reviewer"));h.put("appVersion",row.get("app_version"));h.put("sourceName",row.get("source_name"));h.put("sourceUrl",row.get("source_url"));h.put("isSynthetic",row.get("is_synthetic"));return h;
    }
    private Map<String,Map<String,Object>> sourceFields(Map<String,Object> scope,LocalDate day,LocalDateTime end){
        long entity=num(scope,"platform_version_id");String market=String.valueOf(scope.get("market"));
        var rows=jdbc.queryForList("""
            SELECT e.*,s.business_date,s.captured_at,s.published_at,s.source_url,s.raw_sha256,s.raw_locator,s.completeness,c.id chart_id,c.name chart_name
            FROM ranking_entry e JOIN ranking_snapshot s ON s.id=e.snapshot_id JOIN chart_definition c ON c.id=s.chart_id
            JOIN data_source ds ON ds.id=c.source_id WHERE e.platform_version_id=? AND c.region=? AND ds.access_method<>'SIMULATED'
            AND s.business_date<=? AND s.captured_at<=? AND s.published_at<=? AND s.status='PUBLISHED'
            ORDER BY s.business_date DESC,s.revision DESC,c.id LIMIT 1
            """,entity,market,day,end,end);
        Map<String,Map<String,Object>> fields=new LinkedHashMap<>();
        if(rows.isEmpty())return fields;
        var r=rows.getFirst();var metrics=read(r.get("source_metrics"));
        for(var entry:Map.of("name","name","developer","developer","description","summary","store_url","storeUrl","release_at","sourceReleaseDate","category","category").entrySet())if(metrics.hasNonNull(entry.getValue())&&!metrics.path(entry.getValue()).asText().isBlank())putSource(fields,entry.getKey(),entry.getKey().equals("release_at")?mapper.valueToTree(metrics.path(entry.getValue()).asText().substring(0,10)):metrics.path(entry.getValue()),r,"observed");
        putSource(fields,"external_id",scope.get("external_id"),r,"observed");putSource(fields,"market",market,r,"observed");putSource(fields,"language",scope.get("language"),r,"observed");
        putSource(fields,"first_seen",GameProfileResolver.time(scope.get("first_observed_at")).toLocalDate().toString(),r,"derived");
        if("TITLE_ONLY".equals(metrics.path("summaryKind").asText()))fields.remove("description");
        if(!fields.containsKey("name")){var ns=metrics.path("name");if(!ns.isMissingNode()&&!ns.isNull())putSource(fields,"name",ns,r,"observed");}
        // Source ratings are display values, never substitute for sampled player opinions.
        if(r.get("rating")!=null)putSource(fields,"rating",r.get("rating"),r,"observed");
        if(r.get("rating_count")!=null)putSource(fields,"rating_count",r.get("rating_count"),r,"observed");
        var allPoints=series(scope,day,end);Object referenceChart=allPoints.getFirst().get("chartId");var points=allPoints.stream().filter(p->Objects.equals(referenceChart,p.get("chartId"))).toList();var observed=points.stream().filter(p->p.get("rank")!=null).toList();
        putSource(fields,"observed_days",observed.size(),r,"derived");
        if(!observed.isEmpty())putSource(fields,"peak",observed.stream().mapToInt(p->((Number)p.get("rank")).intValue()).min().orElseThrow(),r,"derived");
        var today=points.getLast();var currentSource=new LinkedHashMap<>(r);if(today.get("snapshotId")!=null){currentSource.put("snapshot_id",today.get("snapshotId"));currentSource.put("source_url",today.get("sourceUrl"));currentSource.put("raw_sha256",today.get("sha256"));currentSource.put("business_date",day);currentSource.put("captured_at",today.get("observedAt"));currentSource.put("published_at",today.get("availableAt"));}
        putSource(fields,"chart_status",today.get("state"),currentSource,"derived");if(today.get("rank")!=null)putSource(fields,"rank",today.get("rank"),currentSource,"derived");
        for(String key:List.of("observed_days","peak","chart_status","rank"))if(fields.containsKey(key)){var f=fields.get(key);f.put("windowFrom",day.minusDays(13));f.put("windowTo",day);f.put("referenceChartId",referenceChart);f.put("inputSnapshots",points.stream().filter(p->p.get("snapshotId")!=null).toList());}
        return fields;
    }
    private void putSource(Map<String,Map<String,Object>> fields,String key,Object value,Map<String,Object> source,String kind){
        var h=new LinkedHashMap<String,Object>();h.put("id","snapshot-"+source.get("snapshot_id"));h.put("status","available");h.put("kind",kind);h.put("value",mapper.valueToTree(value));h.put("observedAt",source.get("captured_at"));h.put("availableAt",source.get("published_at"));h.put("rights","public_excerpt");h.put("reviewed",false);
        for(String f:List.of("source_url","raw_sha256","raw_locator","chart_id","business_date"))h.put(f,source.get(f));fields.put(key,h);
    }
    public List<Map<String,Object>> series(Map<String,Object> scope,LocalDate day,LocalDateTime end){
        long v=num(scope,"platform_version_id");String market=scope.get("market").toString();
        var charts=jdbc.queryForList("SELECT DISTINCT c.id,c.name FROM chart_definition c JOIN ranking_snapshot s ON s.chart_id=c.id JOIN ranking_entry e ON e.snapshot_id=s.id JOIN data_source d ON d.id=c.source_id WHERE e.platform_version_id=? AND c.region=? AND d.access_method<>'SIMULATED' AND s.business_date<=? AND s.published_at<=? AND s.captured_at<=? ORDER BY c.id",v,market,day,end,end);
        var points=new ArrayList<Map<String,Object>>();
        var snapshots=jdbc.queryForList("""
            SELECT s.id,s.chart_id,s.business_date,s.captured_at,s.published_at,s.completeness,s.covered_top_n,s.raw_sha256,s.source_url,e.rank_position
            FROM ranking_snapshot s JOIN chart_definition c ON c.id=s.chart_id
            LEFT JOIN ranking_entry e ON e.snapshot_id=s.id AND e.platform_version_id=?
            WHERE c.platform=? AND c.region=? AND s.business_date BETWEEN ? AND ? AND s.status='PUBLISHED'
            AND s.captured_at<=? AND s.published_at<=?
            AND NOT EXISTS(SELECT 1 FROM ranking_snapshot n WHERE n.chart_id=s.chart_id AND n.business_date=s.business_date AND n.status='PUBLISHED' AND n.revision>s.revision AND n.captured_at<=? AND n.published_at<=?)
            """,v,scope.get("platform"),market,day.minusDays(13),day,end,end,end,end);
        var byDay=new HashMap<String,Map<String,Object>>();snapshots.forEach(s->byDay.put(s.get("chart_id")+":"+s.get("business_date"),s));
        // Single listing can have multiple boards; every series retains its chart identity.
        for(var c:charts)for(int i=13;i>=0;i--){LocalDate date=day.minusDays(i);var snapshot=byDay.get(c.get("id")+":"+date);
            var p=new LinkedHashMap<String,Object>();p.put("date",date);p.put("chartId",c.get("id"));p.put("chartName",c.get("name"));p.put("rank",null);p.put("state","MISSING_SNAPSHOT");
            if(snapshot!=null){var s=snapshot;p.put("rank",s.get("rank_position"));p.put("state",s.get("rank_position")!=null?"OBSERVED":"COMPLETE".equals(s.get("completeness"))?"out_of_monitored_range":"PARTIAL_COVERAGE");p.put("snapshotId",s.get("id"));p.put("completeness",s.get("completeness"));p.put("sha256",s.get("raw_sha256"));p.put("sourceUrl",s.get("source_url"));p.put("observedAt",s.get("captured_at"));p.put("availableAt",s.get("published_at"));}points.add(p);
        }
        if(points.isEmpty()){var p=new LinkedHashMap<String,Object>();p.put("date",day);p.put("rank",null);p.put("state","MISSING_SNAPSHOT");points.add(p);}return points;
    }
    public List<Map<String,Object>> history(long listing,String key,LocalDate day,String timezone){listing(listing);catalog.field(key);return observations(listing,cutoff(day,timezone)).getOrDefault(key,List.of());}
    public Map<String,Object> evidence(long id,LocalDate day,String timezone){
        var rows=jdbc.queryForList("SELECT * FROM profile_evidence WHERE id=? AND tenant_id=? AND observed_at<=? AND available_at<=?",id,tenant,cutoff(day,timezone),cutoff(day,timezone));if(rows.isEmpty())notFound("该证据在所选日期不可见");var e=rows.getFirst();if("not_authorized".equals(e.get("rights")))throw new ApiException(HttpStatus.FORBIDDEN,"EVIDENCE_NOT_AUTHORIZED","无权回放该材料，来源与缺口仍保留");return e;
    }
    private List<Map<String,Object>> media(long listing,LocalDateTime end,Long release){var rows=jdbc.queryForList("SELECT a.id,a.release_id,r.app_version,a.media_kind,a.status,CASE WHEN e.rights='not_authorized' THEN NULL ELSE a.source_url END source_url,e.rights,a.observed_at,a.available_at,a.evidence_id,a.content_sha256 FROM profile_media_asset a JOIN profile_evidence e ON e.id=a.evidence_id AND e.tenant_id=a.tenant_id LEFT JOIN release_record r ON r.id=a.release_id WHERE a.tenant_id=? AND a.listing_id=? AND a.observed_at<=? AND a.available_at<=?",tenant,listing,end,end);for(var r:rows){if("not_authorized".equals(r.get("rights")))r.put("status","not_authorized");else if(r.get("release_id")!=null&&!String.valueOf(r.get("release_id")).equals(String.valueOf(release)))r.put("status","stale");}return rows;}

    public List<Map<String,Object>> tasks(long listing){listing(listing);return jdbc.queryForList("SELECT * FROM profile_enrichment_task WHERE tenant_id=? AND listing_id=? ORDER BY priority,id",tenant,listing);}
    public List<Map<String,Object>> submissions(long listing){listing(listing);var rows=jdbc.queryForList("SELECT id,field_key,status,payload,revision,created_at FROM profile_material_submission WHERE tenant_id=? AND listing_id=? ORDER BY id DESC LIMIT 100",tenant,listing);rows.forEach(r->{var b=read(r.remove("payload"));if("not_authorized".equals(b.path("rights").asText())){r.put("material",Map.of("rights","not_authorized","sourceName",b.path("sourceName").asText()));}else r.put("material",b);r.put("reviews",jdbc.queryForList("SELECT reviewer,decision,reason,observation_id,created_at FROM profile_material_review WHERE tenant_id=? AND submission_id=? ORDER BY id",tenant,r.get("id")));});return rows;}
    @Transactional public Map<String,Object> plan(long listing,LocalDate day,String timezone){
        var s=listing(listing);jdbc.queryForObject("SELECT id FROM market_listing WHERE id=? AND tenant_id=? FOR UPDATE",Long.class,listing,tenant);
        var profile=profile(num(s,"product_id"),listing,null,null,day,timezone);
        var fields=(Map<String,Map<String,Object>>)profile.get("fields");Map<String,List<String>> gaps=new LinkedHashMap<>();
        fields.forEach((k,f)->{if(!Set.of("available","not_applicable").contains(f.get("status"))||"inference".equals(f.get("kind")))gaps.computeIfAbsent(String.valueOf(f.get("domain")),d->new ArrayList<>()).add(k);});
        int created=0;
        for(var g:gaps.entrySet()){
            int active=jdbc.queryForObject("SELECT COUNT(*) FROM profile_enrichment_task WHERE tenant_id=? AND listing_id=? AND domain_key=? AND status IN ('pending','in_progress','pending_review','unavailable')",Integer.class,tenant,listing,g.getKey());if(active>0)continue;
            String raw=json(g.getValue()),priority=Set.of("identity","monetization","gameplay","feasibility").contains(g.getKey())?"P0":"P1";
            jdbc.update("INSERT INTO profile_enrichment_task(tenant_id,listing_id,domain_key,field_keys,planned_cutoff,gap_sha256,priority,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'pending',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant,listing,g.getKey(),raw,day,MarketWeeklySkill.hash(raw),priority);created++;
        }
        return Map.of("created",created,"items",tasks(listing));
    }
    @Transactional public Map<String,Object> updateTask(long id,JsonNode b){
        String owner=text(b,"owner",100),status=text(b,"status",24),note=text(b,"note",2000),retry=b.path("retryCondition").asText("");
        if(!Set.of("pending","in_progress","pending_review","unavailable","completed").contains(status))throw new IllegalArgumentException("未知任务状态");
        if(status.equals("unavailable")&&(note.isBlank()||retry.isBlank()))throw new IllegalArgumentException("暂不可得需原因和复查条件");
        var rows=jdbc.queryForList("SELECT * FROM profile_enrichment_task WHERE id=? AND tenant_id=?",id,tenant);if(rows.isEmpty())notFound("任务不存在或无权访问");var old=rows.getFirst();
        String from=old.get("status").toString();var allowed=Map.of("pending",Set.of("pending","in_progress","unavailable"),"in_progress",Set.of("in_progress","pending_review","unavailable"),"pending_review",Set.of("pending_review","completed","in_progress","unavailable"),"unavailable",Set.of("unavailable","pending","in_progress"),"completed",Set.of("completed"));
        if(!allowed.get(from).contains(status))throw new IllegalArgumentException("不允许跳过补全任务阶段");
        if(jdbc.update("UPDATE profile_enrichment_task SET owner_name=?,status=?,note=?,retry_condition=?,revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=? AND revision=?",owner,status,note,retry,id,tenant,revision(b))!=1)conflict();
        return jdbc.queryForMap("SELECT * FROM profile_enrichment_task WHERE id=? AND tenant_id=?",id,tenant);
    }
    @Transactional public Map<String,Object> submit(JsonNode b,String requestKey){
        if(requestKey==null||requestKey.isBlank()||requestKey.length()>100)throw new IllegalArgumentException("材料提交需Idempotency-Key");
        long lid=b.path("listingId").asLong();listing(lid);String key=text(b,"fieldKey",64);catalog.field(key);
        jdbc.queryForObject("SELECT id FROM market_listing WHERE id=? AND tenant_id=? FOR UPDATE",Long.class,lid,tenant);
        String actor=text(b,"submitter",100),kind=text(b,"kind",24),status=b.path("status").asText("available");
        if(!Set.of("observed","manual","estimated","derived","inference").contains(kind))throw new IllegalArgumentException("资料类型无效");
        if(actor.isBlank())throw new IllegalArgumentException("提交人不能为空");catalog.validate(key,b.get("value"),status);
        if(b.has("score")||b.has("eligible")||b.has("reviewed")||b.has("availableAt")||b.has("tenantId"))throw new IllegalArgumentException("不能提交可信分数、资格、获知时间或组织");
        text(b,"sourceName",200);text(b,"originFamily",200);text(b,"excerpt",10000);text(b,"locator",1000);ProfileFieldCatalog.safeUrl(b.path("sourceUrl").asText(null));
        if(!Set.of("public_excerpt","authorized","internal","not_authorized").contains(text(b,"rights",32)))throw new IllegalArgumentException("必须说明保存授权");
        var observed=timestamp(b,"observedAt");if(observed.isAfter(LocalDateTime.now(ZoneOffset.UTC)))throw new IllegalArgumentException("观察时间不能晚于现在");
        if(b.hasNonNull("validUntil")&&timestamp(b,"validUntil").isBefore(observed))throw new IllegalArgumentException("有效期早于观察时间");
        if(!status.equals("available"))text(b,"reason",2000);
        if(catalog.sensitive(key)&&status.equals("available")&&!key.equals("version"))text(b,"appVersion",100);
        if(kind.equals("inference")&&!status.equals("available"))throw new IllegalArgumentException("AI草案仅保存类型化假设；缺口请提交人工状态材料");
        if(!kind.equals("inference")&&key.equals("mode")&&"IAA".equals(b.path("value").asText())&&(!b.path("scopeConfirmed").asBoolean()||b.path("noIapBasis").asText().isBlank()||b.path("adBasis").asText().isBlank()))throw new IllegalArgumentException("IAA材料需确认版本市场、广告和排除内购依据，仍须独立复核");
        if(kind.equals("inference")){var meta=b.path("modelMetadata");for(String f:List.of("model","promptVersion","schemaVersion","inputHash"))text(meta,f,200);}
        String raw=json(b);if(raw.length()>65536)throw new IllegalArgumentException("单份材料不得超过64K字符");String hash=MarketWeeklySkill.hash(raw);var old=jdbc.queryForList("SELECT id,input_sha256 FROM profile_material_submission WHERE tenant_id=? AND request_key=?",tenant,requestKey);
        if(!old.isEmpty()){if(!hash.equals(old.getFirst().get("input_sha256")))conflict();return Map.of("id",old.getFirst().get("id"),"reused",true);}
        long id=insert("INSERT INTO profile_material_submission(tenant_id,listing_id,request_key,input_sha256,field_key,payload,status,created_at) VALUES(?,?,?,?,?,?,'pending_review',UTC_TIMESTAMP(3))",tenant,lid,requestKey,hash,key,raw);
        return Map.of("id",id,"status","pending_review","reused",false);
    }
    @Transactional public Map<String,Object> review(long id,JsonNode request){
        var rows=jdbc.queryForList("SELECT * FROM profile_material_submission WHERE id=? AND tenant_id=? FOR UPDATE",id,tenant);if(rows.isEmpty())notFound("材料不存在或无权复核");var row=rows.getFirst();
        if(num(row,"revision")!=revision(request))conflict();
        if(!Set.of("pending_review","needs_material").contains(row.get("status")))conflict();
        String reviewer=text(request,"reviewer",100),reason=text(request,"reason",2000),decision=text(request,"decision",24);if(!Set.of("approved","rejected","conflict","needs_material").contains(decision))throw new IllegalArgumentException("复核决策无效");
        var b=read(row.get("payload"));long listing=num(row,"listing_id");listing(listing);String key=row.get("field_key").toString();Long oid=null;
        jdbc.queryForObject("SELECT id FROM market_listing WHERE id=? AND tenant_id=? FOR UPDATE",Long.class,listing,tenant);
        if(decision.equals("approved")||decision.equals("conflict")){
            if("not_authorized".equals(b.path("rights").asText()))throw new ApiException(HttpStatus.FORBIDDEN,"NO_MATERIAL_RIGHTS","材料无保存或使用权限，不能批准或转为证据");
            var observed=timestamp(b,"observedAt");String url=ProfileFieldCatalog.safeUrl(b.path("sourceUrl").asText(null));
            long evidence=insert("INSERT INTO profile_evidence(tenant_id,listing_id,source_name,origin_family,source_url,locator,excerpt,content_sha256,rights,observed_at,available_at,is_synthetic) VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?)",tenant,listing,b.path("sourceName").asText(),b.path("originFamily").asText(),url,b.path("locator").asText(),b.path("excerpt").asText(),MarketWeeklySkill.hash(json(b)),b.path("rights").asText(),observed,b.path("isSynthetic").asBoolean(false));
            Long release=null;
            String appVersion=b.path("appVersion").asText(null);
            boolean draft=b.path("kind").asText().equals("inference");
            if(draft&&decision.equals("conflict"))throw new IllegalArgumentException("AI草案不能作为事实冲突裁决");
            if(key.equals("version")&&decision.equals("approved")&&!draft&&b.path("status").asText("available").equals("available")){appVersion=b.path("value").asText();}
            if(appVersion!=null&&!appVersion.isBlank()){
                var rel=jdbc.queryForList("SELECT id FROM release_record WHERE tenant_id=? AND listing_id=? AND app_version=?",tenant,listing,appVersion);
                if(rel.isEmpty()){
                    if(draft||!key.equals("version")||!decision.equals("approved")||!b.path("status").asText("available").equals("available"))throw new IllegalArgumentException("先复核登记目标应用版本，再核查版本敏感字段");
                    release=insert("INSERT INTO release_record(tenant_id,listing_id,app_version,released_at,available_at,evidence_id) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?)",tenant,listing,appVersion,observed,evidence);
                }else release=num(rel.getFirst(),"id");
            }
            if(catalog.sensitive(key)&&release==null&&decision.equals("approved")&&b.path("status").asText("available").equals("available"))throw new IllegalArgumentException("版本敏感字段必须明确适用应用版本");
            Long supersedes=request.hasNonNull("supersedesId")?request.path("supersedesId").asLong():null;
            if(supersedes!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM profile_field_observation WHERE tenant_id=? AND listing_id=? AND field_key=? AND id=?",Integer.class,tenant,listing,key,supersedes)!=1)throw new IllegalArgumentException("只能显式替代同范围同字段的观察");
            if(b.path("kind").asText().equals("inference")){
                if(supersedes!=null)throw new IllegalArgumentException("AI草案不能裁决已有事实");
                oid=insert("INSERT INTO profile_claim(tenant_id,listing_id,release_id,field_key,typed_value,evidence_id,observed_at,available_at,valid_until,model_metadata,status) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?,?,'draft')",tenant,listing,release,key,json(b.get("value")),evidence,observed,b.hasNonNull("validUntil")?timestamp(b,"validUntil"):null,json(b.path("modelMetadata")));
            }else{
                String state=decision.equals("conflict")?"conflict":b.path("status").asText("available");
                oid=insert("INSERT INTO profile_field_observation(tenant_id,listing_id,release_id,field_key,typed_value,data_status,value_kind,observed_at,available_at,valid_until,evidence_id,reviewer,reason,supersedes_id,definition_version) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),?,?,?,?,?,?)",tenant,listing,release,key,state.equals("available")?json(b.get("value")):null,state,b.path("kind").asText(),observed,b.hasNonNull("validUntil")?timestamp(b,"validUntil"):null,evidence,reviewer,reason,supersedes,"profile-2.3.0");
            }
            if(!draft&&b.hasNonNull("mediaKind")&&!b.path("mediaKind").asText().isBlank()){
                String kind=b.path("mediaKind").asText();if(!Set.of("gameplay_screenshot","gameplay_video","ad_video","diagram").contains(kind))throw new IllegalArgumentException("媒体类型无效");
                insert("INSERT INTO profile_media_asset(tenant_id,listing_id,release_id,evidence_id,media_kind,source_url,status,observed_at,available_at) VALUES(?,?,?,?,?,?,?, ?,UTC_TIMESTAMP(3))",tenant,listing,release,evidence,kind,url,url==null?"missing":"external_only",observed);
            }
        }
        jdbc.update("UPDATE profile_material_submission SET status=?,revision=revision+1 WHERE id=? AND tenant_id=?",decision,id,tenant);
        insert("INSERT INTO profile_material_review(tenant_id,submission_id,reviewer,decision,reason,observation_id,created_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",tenant,id,reviewer,decision,reason,oid);
        return Map.of("id",id,"status",decision,"revision",revision(request)+1,"notice","复核产生新记录；AI仍为草案，任务完成不等于资格通过");
    }
    @Transactional public Map<String,Object> export(long product,Long listing,LocalDate day,String timezone){var p=profile(product,listing,null,null,day,timezone);var out=new LinkedHashMap<String,Object>();out.put("schemaVersion","game-profile-2.3.0");out.put("exportedAt",LocalDateTime.now(ZoneOffset.UTC));out.put("scope",Map.of("productId",product,"listingId",((Map<?,?>)p.get("listing")).get("id"),"asOf",day,"timezone",timezone==null?"Asia/Shanghai":timezone,"knowledgeMode","AS_KNOWN_AT"));out.put("marketProfile",p);out.put("currentCollaboration",p.remove("collaboration"));out.put("contentSha256",MarketWeeklySkill.hash(json(p)));return out;}
    private long insert(String sql,Object...args){var holder=new GeneratedKeyHolder();jdbc.update(c->{var ps=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)ps.setObject(i+1,args[i]);return ps;},holder);return Objects.requireNonNull(holder.getKey()).longValue();}
    private String text(JsonNode b,String key,int max){var v=b.path(key);if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>max)throw new IllegalArgumentException(key+"需填写且长度不超过"+max);return v.asText().trim();}
    private int revision(JsonNode b){if(!b.path("expectedRevision").isIntegralNumber()||b.path("expectedRevision").asInt()<0)throw new IllegalArgumentException("需expectedRevision乐观锁");return b.path("expectedRevision").asInt();}
    private LocalDateTime timestamp(JsonNode b,String key){String s=text(b,key,50);try{return s.length()==10?LocalDate.parse(s).atStartOfDay(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime():OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();}catch(Exception e){throw new IllegalArgumentException(key+"需日期或含时区的ISO时间");}}
    private void notFound(String message){throw new ApiException(HttpStatus.NOT_FOUND,"PROFILE_NOT_FOUND",message);}
    private void conflict(){throw new ApiException(HttpStatus.CONFLICT,"PROFILE_REVISION_CONFLICT","相同幂等键内容不同或记录已修改，请刷新");}
    private static long num(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private JsonNode read(Object raw){try{return raw==null?mapper.nullNode():mapper.readTree(String.valueOf(raw));}catch(Exception e){throw new IllegalStateException("存储JSON读取失败",e);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("JSON无效",e);}}
}
