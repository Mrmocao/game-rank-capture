package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.api.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Append-only manual evidence workspace. All persisted availability timestamps are server assigned. */
@Service
public class ResearchService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final GameProfileService profiles;
    public ResearchService(JdbcTemplate jdbc,ObjectMapper mapper){this(jdbc,mapper,new GameProfileService(jdbc,mapper,"default"));}
    @org.springframework.beans.factory.annotation.Autowired
    public ResearchService(JdbcTemplate jdbc,ObjectMapper mapper,GameProfileService profiles){this.jdbc=jdbc;this.mapper=mapper;this.profiles=profiles;}
    public Map<String,Object> profile(){var row=jdbc.queryForMap("SELECT * FROM research_profile ORDER BY revision DESC LIMIT 1");row.put("parameters",read(row.remove("profile_json")));return row;}
    @Transactional public Map<String,Object> publish(JsonNode body){
        String actor=required(body,"actor");JsonNode parameters=body.path("parameters");var p=V22RecommendationEngine.Profile.from(map(parameters));
        if(p.target()>5)bad("当前业务约定每期最多5项，请将目标设为5；资料不足时不补齐");
        jdbc.queryForObject("SELECT id FROM insight_generation_lock WHERE id=1 FOR UPDATE",Integer.class);
        int rev=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM research_profile",Integer.class);
        String raw=json(p);jdbc.update("INSERT INTO research_profile(revision,profile_json,content_sha256,created_by,created_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3))",rev,raw,MarketWeeklySkill.hash(raw),actor);
        return profile();
    }
    public Object catalog(){try{return mapper.readTree(new ClassPathResource("research/information-catalog.json").getInputStream());}catch(Exception e){throw new IllegalStateException(e);}}
    @Transactional public Map<String,Object> importPacket(long version,JsonNode b){
        String actor=required(b,"actor"),market=required(b,"market");
        if(!Set.of("CN","US","GB","DE","FR").contains(market))bad("不支持的研究市场");
        var v=jdbc.queryForList("SELECT platform,external_id FROM platform_version WHERE id=?",version);if(v.isEmpty())bad("平台版本不存在");
        if("GOOGLE_PLAY".equals(v.getFirst().get("platform"))&&market.equals("CN"))bad("本方案不设Google Play中国榜单");
        LocalDate date=LocalDate.parse(required(b,"effectiveDate")),until=LocalDate.parse(required(b,"validUntil"));
        if(until.isBefore(date)||date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))bad("材料适用日期无效");
        if(b.has("score")||b.has("utilityRange")||b.has("validatedAlternativeDemand")||b.has("ranks"))bad("研究材料不能直接写入综合分、协议通过标记或榜单名次");
        if(!b.path("evidence").isArray()||b.path("evidence").isEmpty())bad("至少提供一条可定位证据");
        Set<String> ids=new HashSet<>();
        for(JsonNode e:b.path("evidence")){
            String id=required(e,"id");if(!ids.add(id))bad("证据ID重复："+id);
            required(e,"locator");required(e,"excerpt");required(e,"originFamily");required(e,"kind");
            String permission=required(e,"permission");if(!Set.of("public_excerpt","authorized","internal_test").contains(permission))bad("证据必须说明允许保存的范围");
            LocalDate observed=LocalDate.parse(required(e,"observedDate"));if(observed.isAfter(until)||observed.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))bad("证据观察日期无效");
        }
        JsonNode a=b.path("assessment");
        if(a.path("identity").asBoolean())refs(a.path("identityEvidence"),ids,"身份");
        if(a.path("gameplay").asBoolean())refs(a.path("gameplayEvidence"),ids,"实机");
        if(a.hasNonNull("unresolvedEvent"))refs(a.path("eventEvidence"),ids,"事件核查");
        String mode=a.path("mode").asText("unknown");
        if(!Set.of("unknown","IAA","mixed","IAP").contains(mode))bad("变现模式无效");
        if(mode.equals("IAA")){
            refs(a.path("adEvidence"),ids,"广告");refs(a.path("noIapEvidence"),ids,"排除内购");
            required(a,"noIapBasis");required(a,"reviewer");
            if(!a.path("scopeConfirmed").asBoolean())bad("纯IAA人工判定必须确认平台版本与市场范围");
        }else if(!mode.equals("unknown"))refs(a.path("monetizationEvidence"),ids,"非IAA");
        JsonNode cost=b.path("cost");
        if(cost.hasNonNull("lower")||cost.hasNonNull("upper")){
            if(!cost.path("lower").isNumber()||!cost.path("upper").isNumber()||cost.path("lower").asDouble()<0||cost.path("upper").asDouble()<cost.path("lower").asDouble())bad("工期需合法上下界");
            required(cost,"scope");required(cost,"estimator");refs(cost.path("evidenceIds"),ids,"成本估算");
        }
        for(String key:List.of("ads","gap"))if(b.path(key).hasNonNull("stage")){
            JsonNode stage=b.path(key).path("stage");if(!stage.isIntegralNumber()||stage.asInt()<0||stage.asInt()>4)bad("阶段必须是0～4整数");
            refs(b.path(key).path("evidenceIds"),ids,key);required(b.path(key),"rationale");required(b.path(key),"reviewer");
            if(!b.path(key).path("criteriaChecked").asBoolean())bad("阶段需逐项核对设计04中的证据锚点");
            JsonNode anchor=b.path(key).path("anchors");int level=stage.asInt();
            if(key.equals("ads")){
                if(level>=1&&!anchor.path("gameplayMatch").asBoolean())bad("素材阶段1需确认卖点与实机相符");
                if(level>=2&&anchor.path("creativeClusters").asInt()<2)bad("素材阶段2需至少两个去重创意方向");
                if(level>=3&&(anchor.path("observedDays").asInt()<7||anchor.path("persistentClusters").asInt()<2))bad("素材阶段3需7日观察和2个持续创意簇");
            }else{
                if(level>=2&&(anchor.path("distinctAuthors").asInt()<3||anchor.path("distinctDates").asInt()<2||anchor.path("peerCount").asInt()<3))bad("改进阶段2需3位独立反馈者、2个日期、3个竞品");
                if(level>=3&&(!anchor.path("positiveCoreMechanic").asBoolean()||!anchor.path("affordablePrototype").asBoolean()))bad("改进阶段3需核心玩法认可与可承担的原型材料");
            }
            if(level>=4){required(anchor,"experimentProtocol");refs(anchor.path("experimentEvidence"),ids,"自有对照实验");}
        }
        if(b.has("states")){if(!b.path("states").isObject())bad("states必须为字段状态对象");b.path("states").properties().forEach(e->{if(!Set.of("observed","observed_zero","partial","missing","not_supported","not_authorized","stale","unknown","conflict","source_error","invalid").contains(e.getValue().asText()))bad("未知输入状态："+e.getValue().asText());});}
        if(b.has("reviews")&&!b.path("reviews").isArray())bad("reviews必须是原始样本数组");
        Set<String> reviewIds=new HashSet<>();
        for(JsonNode r:b.path("reviews")){
            if(!reviewIds.add(required(r,"id")))bad("评价内容ID重复");
            required(r,"text");required(r,"clusterId");required(r,"sourceFamily");required(r,"language");required(r,"samplingScope");
            if(!Set.of("positive","negative","neutral","mixed","unknown").contains(required(r,"sentiment")))bad("情感状态无效");
            LocalDate rd=LocalDate.parse(required(r,"date"));if(rd.isAfter(until))bad("评价日期晚于材料有效期");
            refs(r.path("evidenceIds"),ids,"评价");
        }
        String raw=json(b),hash=MarketWeeklySkill.hash(raw);
        var old=jdbc.queryForList("SELECT id FROM research_packet WHERE platform_version_id=? AND market=? AND content_sha256=?",version,market,hash);
        if(!old.isEmpty())return Map.of("id",old.getFirst().get("id"),"reused",true);
        jdbc.update("INSERT INTO research_packet(platform_version_id,market,effective_date,valid_until,available_at,payload,content_sha256,created_by) VALUES(?,?,?,?,UTC_TIMESTAMP(3),?,?,?)",version,market,date,until,raw,hash,actor);
        long id=jdbc.queryForObject("SELECT id FROM research_packet WHERE platform_version_id=? AND market=? AND content_sha256=?",Long.class,version,market,hash);
        if(a.path("unresolvedEvent").asBoolean())constraint(version,market,id,"MAJOR_EVENT",Map.of("evidence",map(a),"reason","已确认重大事件待处置"),date);
        if(!Set.of("unknown","IAA").contains(mode))constraint(version,market,id,"NON_IAA",Map.of("mode",mode,"evidence",map(a)),date);
        if(cost.hasNonNull("lower"))constraint(version,market,id,"COST_FLOOR_OVER",Map.of("lower",cost.path("lower").asDouble(),"evidence",map(cost)),date);
        return Map.of("id",id,"reused",false,"state","MANUAL_REVIEW","notice","人工材料已登记；来源与判断按引用追溯，未声称AI自动验证");
    }
    private void constraint(long v,String m,long id,String code,Object detail,LocalDate date){jdbc.update("INSERT INTO research_constraint(platform_version_id,market,packet_id,code,detail_json,effective_date,created_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",v,m,id,code,json(detail),date);}
    @Transactional public Map<String,Object> resolve(long id,JsonNode b){
        String actor=required(b,"actor"),reason=required(b,"reason");long packet=b.path("packetId").asLong();
        var cs=jdbc.queryForList("SELECT * FROM research_constraint WHERE id=? FOR UPDATE",id);if(cs.isEmpty())bad("约束不存在");var c=cs.getFirst();
        var ps=jdbc.queryForList("SELECT * FROM research_packet WHERE id=? AND platform_version_id=? AND market=?",packet,c.get("platform_version_id"),c.get("market"));
        if(ps.isEmpty()||packet<=((Number)c.get("packet_id")).longValue())bad("解除需同版本同市场的新材料修订");
        JsonNode evidence=read(ps.getFirst().get("payload"));Set<String> ids=new HashSet<>();evidence.path("evidence").forEach(e->ids.add(e.path("id").asText()));refs(b.path("evidenceIds"),ids,"解除约束");
        if(c.get("resolved_at")!=null)bad("该约束已解除");
        jdbc.update("UPDATE research_constraint SET resolved_at=UTC_TIMESTAMP(3),resolution_json=? WHERE id=?",json(Map.of("actor",actor,"reason",reason,"packetId",packet,"evidenceIds",b.path("evidenceIds"))),id);
        return Map.of("id",id,"resolved",true);
    }
    public List<Map<String,Object>> packets(Long version,LocalDate day){
        String where=version==null?"":" AND p.platform_version_id=?";List<Object> args=new ArrayList<>();args.add(day);if(version!=null)args.add(version);
        var rows=jdbc.queryForList("SELECT p.*,v.name,v.product_id,v.platform FROM research_packet p JOIN platform_version v ON v.id=p.platform_version_id WHERE p.effective_date<=?"+where+" ORDER BY p.id DESC LIMIT 200",args.toArray());
        rows.forEach(r->{r.put("material",read(r.remove("payload")));r.put("state",LocalDate.parse(r.get("valid_until").toString()).isBefore(day)?"stale":"observed");});return rows;
    }
    /** Filter event time and server availability separately. Blank fields never imply a passed gate. */
    public void prepareProfiles(){profiles.synchronizeListings();}
    public Map<String,Object> enrich(Map<String,Object> c,LocalDate day,LocalDateTime availableCutoff){
        long v=((Number)c.get("version_id")).longValue();String market=String.valueOf(c.get("region"));
        Map<String,Object> input=new LinkedHashMap<>();input.put("id",v+":"+c.get("chart_id"));input.put("productId",c.get("product_id"));input.put("name",c.get("product_name"));
        input.put("cohort",c.get("platform")+":"+market+":"+c.get("chart_id")+":"+day);input.put("family","未知");input.put("identity",false);input.put("mode","unknown");
        input.put("iaaCurrent",false);input.put("gameplay",null);input.put("unresolvedEvent",null);
        input.put("ranks",((List<Map<String,Object>>)c.get("history")).stream().map(h->h.get("rank")!=null?h.get("rank"):"NOT_IN_COVERAGE".equals(h.get("state"))?0:null).toList());
        input.put("alreadyTracked",Boolean.TRUE.equals(c.get("already_producing"))||c.get("already_producing") instanceof Number n&&n.intValue()>0);
        var rows=jdbc.queryForList("SELECT * FROM research_packet WHERE platform_version_id=? AND market=? AND effective_date<=? AND available_at<=? ORDER BY available_at DESC,id DESC LIMIT 1",v,market,day,availableCutoff);
        if(!rows.isEmpty()){
            var row=rows.getFirst();JsonNode b=read(row.get("payload")),a=b.path("assessment");input.put("packetId",row.get("id"));input.put("packetHash",row.get("content_sha256"));input.put("packet",b);input.put("family",b.path("family").asText("未知"));
            boolean valid=!LocalDate.parse(row.get("valid_until").toString()).isBefore(day);
            for(JsonNode e:b.path("evidence"))if(LocalDate.parse(e.path("observedDate").asText()).isAfter(day))valid=false;
            if(valid){
                input.put("identity",a.path("identity").asBoolean(false));input.put("mode",a.path("mode").asText("unknown"));input.put("iaaCurrent","IAA".equals(a.path("mode").asText()));
                input.put("gameplay",a.hasNonNull("gameplay")?a.path("gameplay").asBoolean():null);input.put("unresolvedEvent",a.hasNonNull("unresolvedEvent")?a.path("unresolvedEvent").asBoolean():null);
                input.put("effortLower",b.path("cost").hasNonNull("lower")?b.path("cost").path("lower").asDouble():null);input.put("effortUpper",b.path("cost").hasNonNull("upper")?b.path("cost").path("upper").asDouble():null);
                input.put("adStage",b.path("ads").hasNonNull("stage")?b.path("ads").path("stage").asInt():null);input.put("gapStage",b.path("gap").hasNonNull("stage")?b.path("gap").path("stage").asInt():null);
                input.put("evidence",mapper.convertValue(b.path("evidence"),List.class));
                var opinions=opinionSummary(b,day);input.put("opinion",opinions);
                if(Boolean.TRUE.equals(opinions.get("comparable"))){input.put("positive",opinions.get("positive"));input.put("sample",opinions.get("denominator"));input.put("reviewScope","comparable");}
                if(b.path("states").isObject()){
                    input.put("states",map(b.path("states")));
                    for(String gate:List.of("identity","iaa","gameplay","event"))if(b.path("states").has(gate)&&!Set.of("observed","observed_zero").contains(b.path("states").path(gate).asText())){
                        switch(gate){case "identity"->input.put("identity",false);case "iaa"->input.put("iaaCurrent",false);case "gameplay"->input.put("gameplay",null);case "event"->input.put("unresolvedEvent",null);}
                    }
                }
            }else input.put("states",Map.of("reviews","stale","cost","stale","ads","stale","gap","stale"));
        }
        profiles.enrichRecommendation(input,((Number)c.get("product_id")).longValue(),v,market,day,availableCutoff);
        var constraints=jdbc.queryForList("SELECT id,code,detail_json,packet_id FROM research_constraint WHERE platform_version_id=? AND market=? AND effective_date<=? AND created_at<=? AND (resolved_at IS NULL OR resolved_at>?) ORDER BY id",v,market,day,availableCutoff,availableCutoff);
        List<Map<String,Object>> blockers=new ArrayList<>();
        for(var constraint:constraints){var detail=map(read(constraint.get("detail_json")));detail.put("code",constraint.get("code"));detail.put("constraintId",constraint.get("id"));detail.put("current",true);blockers.add(detail);if("NON_IAA".equals(constraint.get("code")))input.put("mode",detail.get("mode"));}
        input.put("knownBlockers",blockers);if("EXCLUDED".equals(c.get("pool_state")))input.put("outOfScope",true);return input;
    }
    public Map<String,Object> opinionSummary(JsonNode b,LocalDate day){
        Map<String,List<JsonNode>> clusters=new LinkedHashMap<>();Set<String> scopes=new HashSet<>();int raw=0;
        for(JsonNode r:b.path("reviews")){
            LocalDate rd=LocalDate.parse(r.path("date").asText());if(rd.isAfter(day)||rd.isBefore(day.minusDays(13)))continue;raw++;
            if(r.path("developerReply").asBoolean()||!r.path("experienced").asBoolean())continue;
            scopes.add(r.path("sourceFamily").asText()+":"+r.path("language").asText()+":"+r.path("samplingScope").asText());
            clusters.computeIfAbsent(r.path("sourceFamily").asText()+":"+r.path("clusterId").asText(),k->new ArrayList<>()).add(r);
        }
        int positive=0,negative=0,neutral=0,ambiguous=0;Map<String,Integer> topics=new TreeMap<>();
        for(var cluster:clusters.values()){
            Set<String> sentiments=new HashSet<>();cluster.forEach(r->sentiments.add(r.path("sentiment").asText()));
            if(sentiments.size()!=1){ambiguous++;continue;}
            switch(sentiments.iterator().next()){case "positive"->positive++;case "negative"->negative++;case "neutral"->neutral++;default->ambiguous++;}
            Set<String> ts=new HashSet<>();cluster.forEach(r->r.path("topics").forEach(t->ts.add(t.asText())));ts.forEach(t->topics.merge(t,1,Integer::sum));
        }
        boolean comparable=scopes.size()==1&&b.path("reviewSampling").path("comparable").asBoolean()&&!b.path("reviewSampling").path("method").asText().isBlank()&&!b.path("reviewSampling").path("method").asText().contains("hot");
        Map<String,Object> out=new LinkedHashMap<>();out.put("rawCount",raw);out.put("clusters",clusters.size());out.put("positive",positive);out.put("negative",negative);out.put("neutral",neutral);out.put("ambiguous",ambiguous);out.put("denominator",positive+negative+neutral);out.put("comparable",comparable);out.put("topics",topics);out.put("scopes",scopes);
        out.put("notice","分母为去重后可判断内容簇，混合/未知单列；多来源或热评只展示定性信息，不估全体玩家比例");return out;
    }
    public List<Map<String,Object>> opinions(LocalDate day){
        var result=new ArrayList<Map<String,Object>>();Set<String> seen=new HashSet<>();
        for(var packet:packets(null,day)){String key=packet.get("platform_version_id")+":"+packet.get("market");if(!seen.add(key))continue;var b=(JsonNode)packet.get("material");packet.put("summary",opinionSummary(b,day));result.add(packet);}return result;
    }
    public List<Map<String,Object>> constraints(){return jdbc.queryForList("SELECT id,platform_version_id,market,packet_id,code,detail_json,effective_date,resolved_at FROM research_constraint ORDER BY id DESC LIMIT 200");}
    @Transactional public Map<String,Object> outcome(long selection,String key,JsonNode b){
        if(key==null||key.isBlank()||key.length()>100)bad("反馈必须提供Idempotency-Key");
        String actor=required(b,"actor");required(b,"hypothesis");required(b,"protocol");required(b,"context");required(b,"result");
        if(!Set.of("unknown","pass","fail","stopped").contains(b.path("result").asText()))bad("测试结果无效");
        if(!b.path("result").asText().equals("unknown")&&(b.path("sample").asInt()<1||b.path("evidence").asText().isBlank()))bad("已知结局必须有测试样本和证据");
        if(b.path("result").asText().equals("unknown"))required(b,"unknownReason");
        LocalDate from=LocalDate.parse(required(b,"from")),to=LocalDate.parse(required(b,"to"));if(to.isBefore(from))bad("测试日期倒置");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM selection_record WHERE id=?",Integer.class,selection)==0)bad("选用记录不存在");
        String raw=json(b),hash=MarketWeeklySkill.hash(raw);var old=jdbc.queryForList("SELECT id,content_sha256 FROM research_outcome WHERE request_key=?",key);
        if(!old.isEmpty()){if(!hash.equals(old.getFirst().get("content_sha256")))throw new ApiException(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT","同键不同反馈");return old.getFirst();}
        jdbc.update("INSERT INTO research_outcome(selection_id,request_key,payload,content_sha256,created_by,created_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",selection,key,raw,hash,actor);
        return Map.of("saved",true,"notice","只记录该实验结局，不把制作完成或选用视为成功");
    }
    public List<Map<String,Object>> outcomes(long selection){var rows=jdbc.queryForList("SELECT * FROM research_outcome WHERE selection_id=? ORDER BY id DESC",selection);rows.forEach(r->r.put("payload",read(r.get("payload"))));return rows;}
    public Map<String,Object> simulation(JsonNode b){
        if(!b.path("isSimulation").asBoolean())bad("试算必须声明isSimulation=true");
        List<Map<String,Object>> cases=mapper.convertValue(b.path("candidates"),List.class);
        if(cases==null||cases.size()>1000)bad("试算候选不能为空或超过1000");
        cases.forEach(c->{c.remove("validatedAlternativeDemand");c.remove("alternativeDemand");});
        var result=V22RecommendationEngine.run(cases,V22RecommendationEngine.Profile.from(map(b.path("parameters"))));result.put("isSimulation",true);return result;
    }
    private void refs(JsonNode refs,Set<String> ids,String label){if(!refs.isArray()||refs.isEmpty())bad(label+"缺证据引用");for(var ref:refs)if(!ids.contains(ref.asText()))bad(label+"引用不存在："+ref.asText());}
    private String required(JsonNode b,String key){if(!b.path(key).isTextual()||b.path(key).asText().isBlank())bad("必填字段："+key);if(b.path(key).asText().length()>20000)bad(key+"超过长度限制");return b.path(key).asText().trim();}
    private void bad(String s){throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_RESEARCH_INPUT",s);}
    public JsonNode read(Object o){try{return mapper.readTree(String.valueOf(o));}catch(Exception e){throw new IllegalArgumentException("研究JSON无效",e);}}
    public Map<String,Object> map(JsonNode o){return o.isObject()?mapper.convertValue(o,LinkedHashMap.class):new LinkedHashMap<>();}
    public String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalArgumentException("无法序列化研究记录",e);}}
}
