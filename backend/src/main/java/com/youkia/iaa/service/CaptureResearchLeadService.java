package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Research leads from immutable source observations. Never assigns product identities or IAA eligibility. */
public final class CaptureResearchLeadService {
    public static final String VERSION = "capture-research-leads-2.3.0";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public CaptureResearchLeadService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public Map<String,Object> build(String tenant, LocalDate day, LocalDateTime knowledgeCutoff, int remainingSlots) {
        return build(tenant,day,knowledgeCutoff,remainingSlots,0,Set.of());
    }
    public Map<String,Object> build(String tenant, LocalDate day, LocalDateTime knowledgeCutoff, int remainingSlots,int formalCount,Set<String> formalLabels) {
        var rows=jdbc.queryForList("""
            SELECT o.id observation_id,o.source_key,o.origin_family,o.source_authority,o.chart_key,o.board_name,
              o.platform,o.region,o.business_date,o.captured_at,o.available_at,o.parser_version,o.completeness,
              o.requested_top_n,o.contiguous_top_n,o.source_url,o.raw_sha256,
              e.rank_position,e.external_id,e.name,e.developer,e.evidence_locator,e.source_fields
            FROM external_ranking_observation o JOIN external_ranking_entry e ON e.observation_id=o.id
            WHERE o.tenant_id=? AND o.business_date>=? AND o.business_date<=? AND o.available_at<=? AND o.captured_at<=?
              AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.tenant_id=o.tenant_id AND r.observation_id=o.id AND r.status IN ('SUCCEEDED','PARTIAL'))

            """,tenant,day.minusDays(27),day,knowledgeCutoff,knowledgeCutoff);
        new SourceIdentityService(jdbc,mapper,tenant).enrich(rows,day,knowledgeCutoff);
        return evaluate(rows, day, knowledgeCutoff, remainingSlots,formalCount,formalLabels);
    }

    /** Pure observation evaluation, also used by the contract tests. All timestamps remain in the frozen evidence. */
    public Map<String,Object> evaluate(List<Map<String,Object>> raw, LocalDate day, LocalDateTime cutoff, int remainingSlots) {
        return evaluate(raw,day,cutoff,remainingSlots,0,Set.of());
    }
    public Map<String,Object> evaluate(List<Map<String,Object>> raw,LocalDate day,LocalDateTime cutoff,int remainingSlots,int formalCount,Set<String> formalLabels) {
        int limit=Math.max(0,Math.min(5,remainingSlots));
        // Latest accepted observation per source/chart/market/day. A later PARTIAL replaces the earlier view;
        // it cannot borrow missing rows from an older scrape and claim complete coverage.
        Map<String,Long> latest=new HashMap<>();Map<Long,Map<String,Object>> observations=new TreeMap<>();
        for(var r:raw) {
            LocalDate date=date(r.get("business_date"));
            if(date==null||date.isAfter(day)||date.isBefore(day.minusDays(27))||time(r.get("available_at")).isAfter(cutoff)||time(r.get("captured_at")).isAfter(cutoff))continue;
            long id=number(r.get("observation_id"));String key=snapshotScope(r)+":"+date;
            Long prev=latest.get(key);
            if(prev==null||newer(r,observations.get(prev)))latest.put(key,id);
            observations.putIfAbsent(id,r);
        }
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var r:raw)if(Objects.equals(latest.get(snapshotScope(r)+":"+r.get("business_date")),number(r.get("observation_id"))))rows.add(r);
        Map<String,List<Map<String,Object>>> groups=new TreeMap<>();
        Map<String,List<Map<String,Object>>> entities=new HashMap<>();
        for(var r:rows)entities.computeIfAbsent(entity(r),k->new ArrayList<>()).add(r);
        for(var r:rows)groups.computeIfAbsent(scope(r)+":"+identity(r),k->new ArrayList<>()).add(r);
        List<Map<String,Object>> audit=new ArrayList<>();
        for(var entry:groups.entrySet()) {
            var history=new ArrayList<>(entry.getValue().stream().filter(r->!date(r.get("business_date")).isBefore(day.minusDays(6))).toList());
            if(history.isEmpty())continue;history.sort(Comparator.comparing(r->date(r.get("business_date"))));
            var last=history.getLast();String chart=str(last,"chart_key");
            if(!Set.of("popularity","top-free","top-new-free","most_played").contains(chart))continue;
            var gameplay=last.get("reviewed_gameplay") instanceof Map<?,?> reviewed?reviewed:SourceGameplay.read(fields(last));String family=gameplay.get("family").toString();boolean casual=SourceIdentityService.FAMILIES.contains(family)&&!family.equals("其他/重度");
            boolean current=day.equals(date(last.get("business_date")));
            String candidateKey=entity(last);boolean tentative=str(last,"external_id").isBlank();
            // Preserve other-chart risk evidence across this whole window; disappearance today does not clear it.
            // Contextual matches are same source/platform/market/name/developer only, never a cross-platform ID claim.
            List<Map<String,Object>> context=entities.getOrDefault(candidateKey,List.of()).stream()
                    .filter(r->!str(r,"chart_key").equals(chart)).sorted(Comparator.comparing((Map<String,Object> r)->str(r,"chart_key"))
                            .thenComparing(r->date(r.get("business_date"))).thenComparingLong(r->number(r.get("observation_id")))).toList();
            boolean paidSignal=context.stream().anyMatch(r->Set.of("bestseller","top-paid","top-grossing","top-new-paid").contains(r.get("chart_key")));
            boolean freshGame=context.stream().anyMatch(r->Set.of("fresh_game","top-new-free","top-new-paid").contains(r.get("chart_key")));
            boolean promotional=context.stream().anyMatch(r->"publisher_heat".equals(r.get("chart_key")));
            Set<LocalDate> observed=new TreeSet<>();history.forEach(r->observed.add(date(r.get("business_date"))));
            var first=history.getFirst();int startRank=(int)number(first.get("rank_position")),lastRank=(int)number(last.get("rank_position"));
            Integer delta=observed.size()>1?startRank-lastRank:null;
            boolean recentContinuous=observed.contains(day)&&observed.contains(day.minusDays(1))&&observed.contains(day.minusDays(2));
            boolean sufficientHistory=observed.size()>=5&&recentContinuous;
            boolean rise=sufficientHistory&&delta!=null&&delta>0;
            boolean growth=sufficientHistory&&delta!=null&&delta>=10&&lastRank<=50;
            boolean headHeat=lastRank<=20;
            List<String> gaps=new ArrayList<>();if(tentative)gaps.add(last.get("unified_product_id")!=null?"跨平台产品已有身份关联，但来源未提供平台稳定ID；同来源跨日仍按名称与发行方匹配，需排查更名":"缺少平台稳定ID，名称与发行方匹配仅是来源内待核线索");
            gaps.add("目标版本广告与排除内购证据未核验，纯IAA资格未知");gaps.add("缺少同版本实机、可比玩家评价与团队工期估算");
            if("SOURCE_DESCRIPTION".equals(gameplay.get("basis")))gaps.add("玩法依据为来源描述明确词句，未经实机验证；描述中的DAU/收入等数字未经独立核验");
            if(!current)gaps.add("截止日"+day+"无该对象记录；最近观察日为"+str(last,"business_date")+"，不得视作当日排名");
            if(!casual)gaps.add("玩法待识别，榜单名称不能证明核心机制或轻度品类；先补描述及实机核验");
            if(observed.size()<7)gaps.add("7日窗口只有"+observed.size()+"个有记录日，缺日不回填");
            if(paidSignal)gaps.add("28日回看窗口同来源付费/畅销榜出现匹配记录，需优先核查付费/混合变现；榜单本身不是内购事实");
            if(promotional)gaps.add("28日回看窗口同来源发行人热度榜出现该线索，投放信号不等于玩家需求或盈利");
            String exclusion=!current?"截止日无观察，不用旧榜冒充当前热度":!casual?"缺少明确的轻度玩法标签或描述依据，暂不进入本周短名单":paidSignal?"存在同来源付费/畅销信号，先核查付费模式再评估IAA机会":!sufficientHistory?"连续观察不足：至少5/7日且最近3日连续有记录；留在观察池，不以首末排名凑数":!headHeat&&!growth?"未达到研究信号门槛：当前同榜Top20，或上升至少10名且进入Top50；保留观察":"";
            Map<String,Object> item=new LinkedHashMap<>();
            for(var field:Map.of("unified_product_id","unifiedProductId","unified_product_name","unifiedProductName","association_basis","associationBasis","identity_review","identityReview").entrySet())if(last.containsKey(field.getKey()))item.put(field.getValue(),last.get(field.getKey()));
            item.put("scopeKey",scope(last));item.put("leadKey",MarketWeeklySkill.hash(entry.getKey()));item.put("entityKey",candidateKey);item.put("classification","RESEARCH_LEAD");
            item.put("name",last.get("name"));item.put("developer",last.get("developer"));item.put("platform",last.get("platform"));item.put("region",str(last,"region").toUpperCase(Locale.ROOT));
            item.put("sourceKey",last.get("source_key"));item.put("originFamily",last.get("origin_family"));item.put("authority",last.get("source_authority"));item.put("sourceUrl",last.get("source_url"));
            item.put("chartKey",chart);item.put("boardName",last.get("board_name"));item.put("family",family.isBlank()?"未知":family);item.put("sourceTags",fields(last).path("tags"));
            item.put("identityStatus",last.get("unified_product_id")!=null?"CONFIRMED_PRODUCT_ASSOCIATION":tentative?"SOURCE_LABEL_MATCH_UNVERIFIED":"SOURCE_ID_OBSERVED");item.put("externalId",last.get("external_id"));item.put("monetization","UNKNOWN");item.put("formalEligible",false);
            item.put("windowStart",day.minusDays(6).toString());item.put("windowEnd",day.toString());item.put("observationDays",observed.size());item.put("firstObservedDate",str(first,"business_date"));item.put("latestObservedDate",str(last,"business_date"));
            item.put("currentRank",current?lastRank:null);item.put("currentState",current?"OBSERVED":"MISSING_ON_DATE");
            item.put("firstRank",startRank);item.put("latestRank",lastRank);item.put("rankChange",delta);item.put("risingLabelSequence",rise);item.put("hasBestsellerSignal",paidSignal);item.put("hasPromotionSignal",promotional);
            item.put("hasSourceNewGameSignal",freshGame);
            if(freshGame)gaps.add("同来源新游榜出现匹配记录，仅为第三方新游分类佐证，不证明首发日期或新玩法");
            item.put("signalType",growth?"RANK_GROWTH":headHeat?"HEAD_HEAT":"OBSERVE_ONLY");
            item.put("signalLabel",growth?"榜单增势研究":headHeat?"头部热度 / 基准观察":"继续观察");
            item.put("thumbnailUrl",fields(last).path("thumbnailUrl").asText(""));
            item.put("gameplayEvidence",gameplay);item.put("sourceDescription",fields(last).path("descriptionRaw").asText(""));
            item.put("whyNow",str(last,"board_name")+"在"+str(last,"business_date")+"观察为第"+lastRank+"名；7日窗实际观察"+observed.size()+"天"+(delta==null?"，只有一次观察，不能判断增长":(tentative?"；同名同发行方的来源记录":"；同来源ID记录")+"由第"+startRank+"到第"+lastRank+"名，"+(delta>0?"上升"+delta:delta<0?"下降"+(-delta):"持平")+"名")+"。来源名次变化不等于用户量增长。");
            StringBuilder caution=new StringBuilder();
            if(history.size()>1){var previous=history.get(history.size()-2);int prev=(int)number(previous.get("rank_position"));
                if(lastRank>prev)caution.append("最新一次由#").append(prev).append("回落到#").append(lastRank).append("；不能描述为持续单边上涨。");}
            Map<String,List<Map<String,Object>>> otherDemand=new TreeMap<>();
            for(var r:context)if(Set.of("popularity","top-free","top-new-free","most_played").contains(str(r,"chart_key")))otherDemand.computeIfAbsent(str(r,"chart_key"),k->new ArrayList<>()).add(r);
            for(var other:otherDemand.values())if(other.size()>1){var a=other.getFirst();var b=other.getLast();
                if(day.equals(date(b.get("business_date")))&&number(b.get("rank_position"))>number(a.get("rank_position")))caution.append("同来源").append(str(b,"board_name")).append("回看窗口由#").append(a.get("rank_position")).append("到#").append(b.get("rank_position")).append("，走势转弱；不同榜单不合成总分。");}
            if(!growth&&headHeat)caution.append("此项作为头部热度基准，不宣称新增增长突破。");
            item.put("counterEvidence",caution.toString());
            item.put("whyNow",item.get("whyNow")+caution.toString());
            item.put("gaps",gaps);item.put("nextAction","先确认平台ID与当前应用版本，试玩并留存核心循环；核查广告与所有内购入口。资格通过后估算原型人日，再决定是否正式选用。");
            item.put("exclusionReason",exclusion);item.put("selected",false);
            List<Map<String,Object>> points=new ArrayList<>();
            for(LocalDate d=day.minusDays(6);!d.isAfter(day);d=d.plusDays(1)) {
                LocalDate pointDay=d;var found=history.stream().filter(r->pointDay.equals(date(r.get("business_date")))).findFirst();
                Map<String,Object> p=new LinkedHashMap<>();p.put("date",d.toString());p.put("rank",found.map(r->r.get("rank_position")).orElse(null));p.put("state",found.isPresent()?"OBSERVED":"MISSING_OR_IDENTITY_UNCONFIRMED");
                found.ifPresent(r->p.put("evidence",evidence(r)));points.add(p);
            }
            item.put("history",points);item.put("contextualSignals",context.stream().map(r->Map.of("chartKey",r.get("chart_key"),"boardName",r.get("board_name"),"rank",r.get("rank_position"),"evidence",evidence(r))).toList());
            audit.add(item);
        }
        var portfolio=OpportunityPortfolio.select(audit,rows,day,limit,formalCount,formalLabels);
        @SuppressWarnings("unchecked") var selected=(List<Map<String,Object>>)portfolio.get("items");
        Map<String,Object> result=new LinkedHashMap<>();result.put("algorithmVersion",VERSION);result.put("windowStart",day.minusDays(6).toString());result.put("windowEnd",day.toString());result.put("limit",limit);
        result.put("thresholds",Map.of("benchmark",Map.of("minimumObservedDays",5,"recentContinuousDays",3,"headHeatTopN",20,"growthMinimumRankGain",10,"growthCurrentTopN",50),
            "entry",Map.of("lookbackDays",28,"priorCompleteAbsenceDays",3,"topN",100,"maximumAgeDays",3,"minimumGain",10),
            "acceleration",Map.of("minimumAgeDays",4,"maximumAgeDays",14,"recentContinuousDays",3,"twoDayGain",15,"minimumAcceleration",5,"minimumComparables",5,"topFraction",0.2),"familyCap",2));
        result.put("productEvidence",portfolio.get("productEvidence"));result.put("platformDiagnostics",CrossPlatformAssessment.diagnostics(audit));
        result.put("policy",OpportunityPortfolio.POLICY);result.put("portfolio",portfolio.get("allocation"));
        result.put("dataRequirements",OpportunityPortfolio.DATA_REQUIREMENTS);result.put("lookbackStart",day.minusDays(27).toString());
        result.put("items",selected);result.put("candidateAudit",audit);result.put("snapshotCount",latest.size());
        result.put("diagnostic",selected.isEmpty()?"当前资料未产生符合线索条件的短名单；正式IAA门槛保持，需补身份、实机与变现证据":"提供"+selected.size()+"项待核研究线索，均不是纯IAA正式推荐；按证据缺口完成核验后才能决定选用");
        return result;
    }
    private boolean newer(Map<String,Object> a,Map<String,Object> b){int c=time(a.get("captured_at")).compareTo(time(b.get("captured_at")));if(c==0)c=time(a.get("available_at")).compareTo(time(b.get("available_at")));return c>0||c==0&&number(a.get("observation_id"))>number(b.get("observation_id"));}
    private static String str(Map<String,Object> r,String k){return Objects.toString(r.get(k),"");}
    private static long number(Object o){return o instanceof Number n?n.longValue():Long.parseLong(o.toString());}
    private static LocalDate date(Object o){return o==null?null:LocalDate.parse(o.toString());}
    private static LocalDateTime time(Object o){if(o instanceof Timestamp t)return t.toLocalDateTime();if(o instanceof LocalDateTime t)return t;return LocalDateTime.parse(o.toString().replace(' ','T'));}
    private static String snapshotScope(Map<String,Object> r){return str(r,"source_key")+":"+str(r,"platform")+":"+str(r,"region")+":"+str(r,"chart_key");}
    private static String scope(Map<String,Object> r){return str(r,"source_key")+":"+str(r,"platform")+":"+str(r,"region")+":"+str(r,"chart_key")+":"+str(r,"parser_version");}
    private static String identity(Map<String,Object> r){return !str(r,"external_id").isBlank()?"id:"+str(r,"external_id"):"label:"+str(r,"name")+":"+str(r,"developer")+(str(r,"developer").isBlank()?":observation:"+r.get("observation_id"):"");}
    private static String entity(Map<String,Object> r){return str(r,"source_key")+":"+str(r,"platform")+":"+str(r,"region")+":"+identity(r);}
    private JsonNode fields(Map<String,Object> r){try{Object v=r.get("source_fields");return v instanceof JsonNode n?n:v instanceof byte[] bytes?mapper.readTree(bytes):mapper.readTree(Objects.toString(v,"{}"));}catch(Exception e){throw new IllegalStateException("来源字段无法解析",e);}}
    private Map<String,Object> evidence(Map<String,Object> r){Map<String,Object> e=new LinkedHashMap<>();for(String key:List.of("observation_id","source_url","raw_sha256","evidence_locator","business_date","captured_at","available_at","parser_version","completeness","requested_top_n","contiguous_top_n"))e.put(key,r.get(key));return e;}
}
