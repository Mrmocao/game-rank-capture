package com.youkia.iaa.service;
import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.api.ApiException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Single current V2.2 implementation; previous reports are read-only frozen records. */
@Service
public class WeeklyReportService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final MarketWeeklySkill skill;
    private final MarketInsightService insights;private final ResearchService research;private final String tenant;
    public WeeklyReportService(JdbcTemplate j,ObjectMapper m){this(j,m,new ResearchService(j,m));}
    public WeeklyReportService(JdbcTemplate j,ObjectMapper m,ResearchService r){this(j,m,r,"default");}
    @org.springframework.beans.factory.annotation.Autowired
    public WeeklyReportService(JdbcTemplate j,ObjectMapper m,ResearchService r,@org.springframework.beans.factory.annotation.Value("${PROFILE_TENANT_ID:default}") String tenant){jdbc=j;mapper=m;skill=new MarketWeeklySkill(m);insights=new MarketInsightService(j,m,skill);research=r;this.tenant=tenant;}
    public Map<String,Object> skill(){var s=new LinkedHashMap<>(skill.snapshot());s.put("researchProfile",research.profile());return s;}
    @Transactional public Map<String,Object> generate(LocalDate start,LocalDate end){return generate(start,end,null);}
    @Transactional public Map<String,Object> generate(LocalDate start,LocalDate end,LocalDateTime availableAt){
        if(start==null||end==null||!end.isAfter(start)||java.time.temporal.ChronoUnit.DAYS.between(start,end)>7||end.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_PERIOD","报告期左闭右开，最多7天，结束不超过明天");
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC),cutoff=availableAt==null?now:availableAt;
        if(cutoff.isAfter(now))throw new IllegalArgumentException("可用材料截止不能晚于当前时间UTC");
        jdbc.queryForObject("SELECT id FROM insight_generation_lock WHERE id=1 FOR UPDATE",Integer.class);
        var profile=research.profile();var effectiveParameters=new LinkedHashMap<>(research.map((JsonNode)profile.get("parameters")));
        effectiveParameters.put("target",5);effectiveParameters.put("explorationSlots",Math.min(5,((Number)effectiveParameters.getOrDefault("explorationSlots",2)).intValue()));
        var parameters=V22RecommendationEngine.Profile.from(effectiveParameters);
        Long rule=jdbc.queryForObject("SELECT id FROM rule_version ORDER BY version_number DESC LIMIT 1",Long.class);
        LocalDate day=end.minusDays(1);var analysis=insights.analyze(day,cutoff);
        List<Map<String,Object>> candidates=new ArrayList<>(analysis.candidates());Set<String> present=new HashSet<>();candidates.forEach(c->present.add(c.get("version_id")+":"+c.get("region")));
        var manual=jdbc.queryForList("""
            SELECT DISTINCT v.id version_id,v.product_id,v.platform,v.name product_name,p.pool_state,r.market region,
              EXISTS(SELECT 1 FROM selection_record sel WHERE sel.product_id=p.id AND sel.status IN ('IN_PROGRESS','COMPLETED')) already_producing
            FROM research_packet r JOIN platform_version v ON v.id=r.platform_version_id JOIN product p ON p.id=v.product_id
            WHERE r.effective_date<=? AND r.available_at<=?
            """,day,cutoff);
        for(var c:manual)if(present.add(c.get("version_id")+":"+c.get("region"))){
            c.put("chart_id","MANUAL");c.put("chart_name","榜外研究材料");c.put("business_date",day);c.put("history",missingHistory(day));candidates.add(c);
        }
        List<Map<String,Object>> inputs=new ArrayList<>();Map<String,Map<String,Object>> origins=new LinkedHashMap<>();
        research.prepareProfiles();
        for(var c:candidates){var input=research.enrich(c,day,cutoff);input.put("sourceFacts",c);inputs.add(input);origins.put(String.valueOf(input.get("id")),c);}
        var run=V22RecommendationEngine.run(inputs,parameters);
        reserveEarlyOpportunitySlots(run);
        var rows=(List<Map<String,Object>>)run.get("rows");
        List<Map<String,Object>> audit=new ArrayList<>();
        for(var row:rows){var c=new LinkedHashMap<>(origins.get(String.valueOf(row.get("id"))));c.put("v22",row);c.put("decision",Boolean.TRUE.equals(row.get("selected"))?"SELECTED":row.get("route"));audit.add(c);}
        Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("asOfDate",day.toString());coverage.put("sources",analysis.sources());coverage.put("publicObservations",analysis.publicSources());
        coverage.put("policy","14日同榜范围；资格先于排序；固定六维权重。各市场独立计算，跨队列按稳定键轮转，原产品去重；不跨榜比较总分。");
        coverage.put("shortfallReason",run.get("diagnostic"));coverage.put("historyNotice","本期采用"+(availableAt==null?"当前已知材料对历史统计日的修订":"指定可用时间回放")+"；保存可用时间截止，迟到资料不会改写旧报告。");
        var health=jdbc.queryForList("SELECT source_key,status,result_json,started_at,finished_at FROM insight_collection_attempt WHERE started_at<=? ORDER BY id DESC LIMIT 30",cutoff);
        Map<String,Object> frozen=new LinkedHashMap<>();frozen.put("algorithmVersion","2.2.0");frozen.put("profile",profile);frozen.put("inputs",inputs);frozen.put("result",run);frozen.put("health",health);frozen.put("isSimulation",false);
        Set<String> formalLabels=new HashSet<>();
        for(var row:(List<Map<String,Object>>)run.get("selected")){var c=origins.get(String.valueOf(row.get("id")));formalLabels.add(c.get("platform")+":"+c.get("product_name"));}
        int formalCount=((List<?>)run.get("selected")).size();
        var leads=new CaptureResearchLeadService(jdbc,mapper).build(tenant,day,cutoff,5-formalCount,formalCount,formalLabels);
        frozen.put("captureResearchLeads",leads);frozen.put("effectiveParameters",effectiveParameters);
        // The running clock is intentionally not hashed. A no-change retry reuses the old cutoff and content.
        String digest=MarketWeeklySkill.hash(canonical(Map.of("start",start.toString(),"end",end.toString(),"rule",rule,"skill",skill.sha256,"frozen",frozen,"coverage",coverage)));
        var previous=jdbc.queryForList("SELECT id FROM weekly_report WHERE period_start=? AND period_end=? AND input_sha256=? ORDER BY revision DESC LIMIT 1",Long.class,start,end,digest);
        if(!previous.isEmpty()){var out=report(previous.getFirst());out.put("reused",true);return out;}
        int revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM weekly_report WHERE period_start=? AND period_end=?",Integer.class,start,end);
        var skillSnapshot=new LinkedHashMap<>(skill.snapshot());skillSnapshot.put("researchProfile",profile);
        jdbc.update("""
            INSERT INTO weekly_report(period_start,period_end,revision,status,rule_version_id,coverage_json,model_metadata,skill_snapshot,candidate_audit,input_sha256,generated_at,published_at,research_profile_id,research_snapshot,available_cutoff)
            VALUES(?,?,?,'PUBLISHED',?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?,?)
            """,start,end,revision,rule,json(coverage),json(Map.of("algorithmVersion","2.2.0","aiStatus","NOT_CALLED","target",parameters.target(),"writer","V22_JAVA_RULE_TEMPLATE")),json(skillSnapshot),json(audit),digest,profile.get("id"),json(frozen),cutoff);
        long id=jdbc.queryForObject("SELECT id FROM weekly_report WHERE period_start=? AND period_end=? AND revision=?",Long.class,start,end,revision);
        int order=1;
        for(var row:(List<Map<String,Object>>)run.get("selected")){
            var c=origins.get(String.valueOf(row.get("id")));Map<String,Object> facts=new LinkedHashMap<>();
            facts.put("productName",c.get("product_name"));facts.put("platform",c.get("platform"));facts.put("region",c.get("region"));
            facts.put("chartName",c.get("chart_name"));facts.put("chartId",c.get("chart_id"));facts.put("rank",c.get("rank_position"));facts.put("businessDate",day.toString());
            facts.put("sourceUrl",c.get("source_url"));facts.put("thumbnailSourceUrl",c.get("thumbnail_source_url"));facts.put("v22",row);facts.put("history",c.get("history"));facts.put("monetization","PURE_IAA");
            facts.put("portfolioGroup","BENCHMARK");facts.put("portfolioLabel","成熟基准 · 核心50%");
            facts.put("whyNow","成熟基准，不代表新赛道。在14日观察窗内实际在榜"+row.get("topDays")+"天。"+String.join("；",(List<String>)row.get("reasons")));facts.put("nextAction",row.get("action"));facts.put("gaps",row.get("unresolvedFields"));
            String content=row.get("name")+"："+row.get("route")+"。"+facts.get("whyNow")+"。"+row.get("action")+"。研究优先级范围不是成功概率。";
            jdbc.update("INSERT INTO report_item(report_id,product_id,main_reference_version_id,section,display_order,frozen_facts,evidence_refs,content) VALUES(?,?,?,?,?,?,?,?)",id,c.get("product_id"),c.get("version_id"),"CN".equals(c.get("region"))?"DOMESTIC":"OVERSEAS",order++,json(facts),json(c.get("history")),content);
        }
        var out=report(id);out.put("reused",false);return out;
    }
    /** Keep formal qualification unchanged while reserving budget for early source research. */
    @SuppressWarnings("unchecked")
    public static void reserveEarlyOpportunitySlots(Map<String,Object> run){
        var selected=new ArrayList<>((List<Map<String,Object>>)run.get("selected"));
        for(int i=2;i<selected.size();i++){var row=selected.get(i);row.put("selected",false);row.put("slot",null);row.put("unselectedReason","成熟基准最多2席，其余席位保留早期机会；资格判断不变");}
        var retained=new ArrayList<>(selected.subList(0,Math.min(2,selected.size())));
        run.put("selected",retained);run.put("selectedIds",retained.stream().map(r->r.get("id")).toList());run.put("portfolioFormalCap",2);
        run.put("diagnostic",run.get("diagnostic")+"；组合层成熟基准最多2席，新增机会单独验据，不用热门补齐。");
    }
    private List<Map<String,Object>> missingHistory(LocalDate day){List<Map<String,Object>> h=new ArrayList<>();for(int i=13;i>=0;i--){var p=new LinkedHashMap<String,Object>();p.put("date",day.minusDays(i).toString());p.put("rank",null);p.put("state","MISSING_SNAPSHOT");h.add(p);}return h;}
    public Map<String,Object> history(int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("分页参数无效");
        // Sort only scalar keys. MySQL filesort can otherwise carry multi-MB frozen JSON and exceed sort_buffer_size.
        var ids=jdbc.queryForList("SELECT id FROM weekly_report ORDER BY period_start DESC,generated_at DESC,id DESC LIMIT ? OFFSET ?",Long.class,size,(page-1L)*size);
        List<Map<String,Object>> items=new ArrayList<>();
        if(!ids.isEmpty()){
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            var rows=jdbc.queryForList("SELECT r.id,r.period_start,r.period_end,r.revision,r.generated_at,r.status,r.model_metadata,(SELECT COUNT(*) FROM report_item i WHERE i.report_id=r.id) item_count,COALESCE(JSON_LENGTH(JSON_EXTRACT(r.research_snapshot,'$.captureResearchLeads.items')),0) research_lead_count FROM weekly_report r WHERE r.id IN ("+placeholders+")",ids.toArray());
            Map<Long,Map<String,Object>> byId=new HashMap<>();rows.forEach(row->byId.put(((Number)row.get("id")).longValue(),row));
            ids.forEach(id->{if(byId.containsKey(id))items.add(byId.get(id));});
        }
        return Map.of("items",items,"total",jdbc.queryForObject("SELECT COUNT(*) FROM weekly_report",Long.class),"page",page,"pageSize",size);
    }
    public Map<String,Object> latest(){try{return report(jdbc.queryForObject("SELECT id FROM weekly_report ORDER BY period_start DESC,generated_at DESC,id DESC LIMIT 1",Long.class));}catch(EmptyResultDataAccessException e){return Map.of("items",List.of(),"message","尚未生成周报");}}
    public Map<String,Object> report(long id){
        Map<String,Object> r;try{r=jdbc.queryForMap("SELECT * FROM weekly_report WHERE id=?",id);}catch(EmptyResultDataAccessException e){throw new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","周报不存在");}
        var items=jdbc.queryForList("SELECT id,product_id,main_reference_version_id,section,display_order,frozen_facts,evidence_refs,content FROM report_item WHERE report_id=? ORDER BY display_order",id);
        for(var item:items){var facts=research.read(item.get("frozen_facts"));item.put("thumbnailUrl",IconCache.display(facts.path("thumbnailSourceUrl").isTextual()?facts.path("thumbnailSourceUrl").asText():null));}
        var out=new LinkedHashMap<String,Object>();out.put("id",String.valueOf(id));out.put("reportId",String.valueOf(id));
        out.put("periodStart",r.get("period_start"));out.put("periodEnd",r.get("period_end"));out.put("revision",r.get("revision"));out.put("status",r.get("status"));
        out.put("ruleVersionId",String.valueOf(r.get("rule_version_id")));out.put("coverage",r.get("coverage_json"));out.put("generatedAt",r.get("generated_at"));out.put("items",items);
        for(var f:Map.of("modelMetadata","model_metadata","skillSnapshot","skill_snapshot","candidateAudit","candidate_audit","researchSnapshot","research_snapshot").entrySet())out.put(f.getKey(),r.get(f.getValue())==null?null:research.read(r.get(f.getValue())));
        JsonNode snapshot=(JsonNode)out.get("researchSnapshot");
        if(snapshot!=null&&snapshot.has("captureResearchLeads"))out.put("researchLeads",snapshot.path("captureResearchLeads"));
        out.put("availableCutoff",r.get("available_cutoff"));out.put("inputSha256",r.get("input_sha256"));
        out.put("domesticCount",items.stream().filter(x->"DOMESTIC".equals(x.get("section"))).count());out.put("overseasCount",items.stream().filter(x->"OVERSEAS".equals(x.get("section"))).count());out.put("observationCount",items.stream().filter(x->"OBSERVATION".equals(x.get("section"))).count());return out;
    }
    private String canonical(Object v){return json(sorted(mapper.valueToTree(v)));}
    private Object sorted(JsonNode node){if(node.isObject()){Map<String,Object> o=new TreeMap<>();node.properties().forEach(e->o.put(e.getKey(),sorted(e.getValue())));return o;}if(node.isArray()){List<Object> o=new ArrayList<>();node.forEach(n->o.add(sorted(n)));return o;}return node;}
    private String json(Object v){return research.json(v);}
}
