package com.youkia.iaa.service;

import java.util.*;
import java.util.stream.*;

/** V2.2 pure domain engine. Inputs must be evidence-validated by the service boundary.
 * Unrounded doubles are used for math; deterministic ordering normalizes to 1e-8.
 * Fixture rank=0 is only accepted here; production adapter uses explicit out-of-range status. */
public final class V22RecommendationEngine {
    public static final List<String> KEYS=List.of("D","M","P","F","A","O");
    public record Profile(List<Double> weights,int target,int familyCap,int explorationSlots,double budget,
                          int minTopDays,double minCoverage,double maxUtilityWidth,double maxReviewWidth,double z,double growthScale) {
        public Profile {
            weights=List.copyOf(weights);
            if(weights.size()!=6||weights.stream().anyMatch(w->!Double.isFinite(w)||w<0)||Math.abs(weights.stream().mapToDouble(Double::doubleValue).sum()-100)>1e-8
               ||target<5||target>10||familyCap<1||familyCap>10||explorationSlots<0||explorationSlots>target
               ||!Double.isFinite(budget)||budget<=0||minTopDays<1||minTopDays>14||!Double.isFinite(minCoverage)||minCoverage<0||minCoverage>1
               ||!Double.isFinite(maxUtilityWidth)||maxUtilityWidth<0||maxUtilityWidth>100||!Double.isFinite(maxReviewWidth)||maxReviewWidth<=0||maxReviewWidth>1
               ||!Double.isFinite(z)||z<=0||!Double.isFinite(growthScale)||growthScale<=0)
                throw new IllegalArgumentException("研究参数无效：六维权重合计100，数量5～10，探索不超过总量，预算/区间参数须合法");
        }
        public static Profile defaults(){return new Profile(List.of(25d,15d,20d,20d,10d,10d),8,2,2,20,7,.75,25,.30,1.96,.30);}
        public static Profile from(Map<String,Object> m){
            Profile d=defaults();
            List<Double> w=m.get("weights") instanceof List<?> l?l.stream().map(x->num(x,Double.NaN)).toList():d.weights;
            return new Profile(w,integer(m,"target",d.target),integer(m,"familyCap",d.familyCap),integer(m,"explorationSlots",d.explorationSlots),
                num(m.get("budget"),d.budget),integer(m,"minTopDays",d.minTopDays),num(m.get("minCoverage"),d.minCoverage),
                num(m.get("maxUtilityWidth"),d.maxUtilityWidth),num(m.get("maxReviewWidth"),d.maxReviewWidth),num(m.get("z"),d.z),num(m.get("growthScale"),d.growthScale));
        }
    }
    public record Range(String key,double lower,double upper,Double estimate,double inputCoverage,String status,String boundMethod,List<?> evidenceIds) {}
    public static Map<String,Object> evaluate(Map<String,Object> c,Profile p) {
        List<?> raw=c.get("ranks") instanceof List<?> l?l:Collections.nCopies(14,null);
        List<String> errors=new ArrayList<>();List<Double> ranks=new ArrayList<>();
        if(raw.size()!=14)errors.add("榜单窗口必须是14个自然日");
        for(int i=0;i<14;i++){
            Object v=i<raw.size()?raw.get(i):null;Double n=v==null?null:num(v,Double.NaN);
            if(n!=null&&(!Double.isFinite(n)||n<0||n>100||n!=Math.rint(n))){errors.add("无效原始名次");n=null;}
            ranks.add(n);
        }
        if(!usable(c,"ranks"))ranks=new ArrayList<>(Collections.nCopies(14,null));
        double sum=0,before=0,now=0;int observed=0,bDays=0,nDays=0,topDays=0;
        for(int i=0;i<14;i++){Double r=ranks.get(i);if(r==null)continue;double v=r==0?0:(101-r)/100;
            sum+=v;observed++;if(r>0)topDays++;if(i<7){before+=v;bDays++;}else{now+=v;nDays++;}}
        List<?> evidence=c.get("evidence") instanceof List<?> l?l:List.of();
        var ds=new LinkedHashMap<String,Range>();
        ds.put("D",range("D",100*sum/14,100*(sum+14-observed)/14,observed==14?100*sum/14:null,observed/14d,status(c,"ranks",observed,14),"整窗14日位置指数；榜外0、缺日[0,1]",evidence));
        double ml=g((now-(before+7-bDays))/7,p.growthScale),mu=g(((now+7-nDays)-before)/7,p.growthScale);
        ds.put("M",range("M",ml,mu,observed==14?ml:null,Math.min(bDays,nDays)/7d,status(c,"ranks",observed,14),"前7日与后7日分别求界，缺日不回填",evidence));
        Double k=nullable(c.get("positive")),n=nullable(c.get("sample"));
        if(k!=null&&n!=null&&(!Double.isFinite(k)||!Double.isFinite(n)||k<0||n<=0||k>n||k!=Math.rint(k)||n!=Math.rint(n))){errors.add("评价分子分母无效");k=null;n=null;}
        if(k!=null&&n!=null&&"comparable".equals(c.get("reviewScope"))&&usable(c,"reviews")){
            double q=k/n,den=1+p.z*p.z/n,center=(q+p.z*p.z/(2*n))/den,half=p.z*Math.sqrt(q*(1-q)/n+p.z*p.z/(4*n*n))/den;
            ds.put("P",range("P",100*Math.max(0,center-half),100*Math.min(1,center+half),100*q,1,"observed","Wilson比例范围，k="+k.intValue()+"/n="+n.intValue()+"；不能修复抽样偏差",evidence));
        }else ds.put("P",unknown("P",state(c,"reviews"),"无可比样本；热评/混合/未知不能冒充可判断分母"));
        Double low=nullable(c.get("effortLower")),high=nullable(c.get("effortUpper"));
        if(low!=null&&high!=null&&(!Double.isFinite(low)||!Double.isFinite(high)||low<0||high<low)){errors.add("工期范围无效");low=null;high=null;}
        String costState="unknown";
        if(low!=null&&high!=null&&usable(c,"cost")){
            ds.put("F",range("F",100*clip(1-high/p.budget),100*clip(1-low/p.budget),low.equals(high)?100*clip(1-low/p.budget):null,1,low.equals(high)?"observed":"bounded","原型"+low+"～"+high+"人日；预算"+p.budget,evidence));
            costState=low>p.budget?"over":high>p.budget?"crossing":"within";
        }else ds.put("F",unknown("F",state(c,"cost"),"工期未知，先估算范围、复用资产和人员能力"));
        for(String key:List.of("A","O")){
            String field=key.equals("A")?"adStage":"gapStage",group=key.equals("A")?"ads":"gap";
            Double stage=nullable(c.get(field));
            if(stage!=null&&(!Double.isFinite(stage)||stage<0||stage>4||stage!=Math.rint(stage))){errors.add("阶段必须是0～4整数");stage=null;}
            ds.put(key,stage!=null&&usable(c,group)&&!evidence.isEmpty()?range(key,25*stage,25*stage,25*stage,1,"observed","证据阶段×25；阶段0与缺失不同",evidence):unknown(key,state(c,group),"没有有效阶段支持材料"));
        }
        double lower=0,upper=0,coverage=0;
        for(int i=0;i<6;i++){Range r=ds.get(KEYS.get(i));double w=p.weights.get(i)/100;lower+=w*r.lower;upper+=w*r.upper;coverage+=w*r.inputCoverage;}
        double width=Math.max(0,upper-lower);
        List<String> reasons=new ArrayList<>();String mode=String.valueOf(c.get("mode")).toLowerCase(Locale.ROOT);String route;
        boolean knownOver=false,knownEvent=false;
        if(c.get("knownBlockers") instanceof List<?> blockers)for(Object item:blockers)if(item instanceof Map<?,?> b&&!Boolean.FALSE.equals(b.get("current"))){
            if("COST_FLOOR_OVER".equals(b.get("code"))&&num(b.get("lower"),0)>p.budget)knownOver=true;
            if("MAJOR_EVENT".equals(b.get("code")))knownEvent=true;
        }
        boolean demand=topDays>=p.minTopDays||Boolean.TRUE.equals(c.get("validatedAlternativeDemand"));
        if(Set.of("mixed","iap","hybrid").contains(mode)||Boolean.TRUE.equals(c.get("outOfScope"))){route="excluded";reasons.add("目标版本明确不是纯IAA或研究范围不匹配");}
        else if(knownEvent||Boolean.TRUE.equals(c.get("unresolvedEvent"))||knownOver||"over".equals(costState)){route="paused";reasons.add(knownEvent||Boolean.TRUE.equals(c.get("unresolvedEvent"))?"重大未处置事件需复核":"有效工期下界超出预算；缺失不撤销已知约束");}
        else if(!errors.isEmpty()){route="lead";reasons.add("坏记录隔离："+String.join("；",errors));}
        else if(!Boolean.TRUE.equals(c.get("identity"))||!Set.of("iaa","pure_iaa").contains(mode)||!Boolean.TRUE.equals(c.get("iaaCurrent"))){route="lead";reasons.add("身份或目标版本IAA依据尚未确认");}
        else if(!Boolean.TRUE.equals(c.get("gameplay"))||!Boolean.FALSE.equals(c.get("unresolvedEvent"))){route="lead";reasons.add("实机或事件核查尚未确认");}
        else if(!demand){route="lead";reasons.add("当前需求证据不足，仍可作为研究线索");}
        else {
            if(!"within".equals(costState))reasons.add("成本待估或跨预算");
            Range pr=ds.get("P");if(pr.inputCoverage==0||(pr.upper-pr.lower)/100>p.maxReviewWidth+1e-10)reasons.add("玩家证据不足或区间较宽");
            if(coverage+1e-10<p.minCoverage||width>p.maxUtilityWidth+1e-10)reasons.add("优先级范围较宽");
            route=reasons.isEmpty()?"ready":"conditional";
        }
        List<Map<String,Object>> tasks=new ArrayList<>();
        if(!Boolean.TRUE.equals(c.get("identity"))||!Boolean.TRUE.equals(c.get("iaaCurrent"))||!Set.of("iaa","pure_iaa").contains(mode))tasks.add(task("identity_iaa","先核验身份与目标版本纯IAA",null,true));
        if(!Boolean.FALSE.equals(c.get("unresolvedEvent"))||knownEvent)tasks.add(task("event","核查重大事件与相反证据",null,true));
        if(!Boolean.TRUE.equals(c.get("gameplay")))tasks.add(task("gameplay","补同版本实机与核心循环记录",null,true));
        if(!demand)tasks.add(task("demand","积累至少"+p.minTopDays+"日在榜需求或已校准行为协议",null,true));
        String[][] groups={{"ranks","榜单快照","D","M"},{"reviews","可比玩家样本","P"},{"cost","原型工期估算","F"},{"ads","素材验证","A"},{"gap","改进空间","O"}};
        List<Map<String,Object>> soft=new ArrayList<>();
        for(String[] group:groups){double impact=0;for(int j=2;j<group.length;j++){int ix=KEYS.indexOf(group[j]);Range rr=ds.get(group[j]);impact+=p.weights.get(ix)/100*(rr.upper-rr.lower);}if(impact>1e-10)soft.add(task(group[0],group[1],impact,false));}
        soft.sort(Comparator.comparingDouble((Map<String,Object> t)->num(t.get("impact"),0)).reversed());tasks.addAll(soft);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",c.get("id"));out.put("productId",c.getOrDefault("productId",c.get("id")));out.put("name",c.get("name"));out.put("family",c.getOrDefault("family","未知"));
        out.put("cohort",c.getOrDefault("cohort",c.getOrDefault("scope","default")));out.put("version","2.2.0");
        out.put("route",route);out.put("eligibleForValidation",Set.of("ready","conditional").contains(route));out.put("reasons",reasons);
        out.put("action",switch(route){case "ready"->"有限预算原型验证，事先登记样本、假设及继续/停止条件";case "conditional"->"先估算或低成本试玩补证，暂不扩大制作投入";case "paused"->"先处置已知问题、缩减范围或登记新预算方案";case "excluded"->"保留关联研究，不投入本纯IAA方案";default->"先核实前提，不作为纯IAA合格验证项";});
        out.put("dimensions",ds);out.put("utilityRange",Map.of("lower",lower,"upper",upper,"rangeWidth",width));out.put("inputCoverage",coverage);
        out.put("topDays",topDays);out.put("observedDays",observed);out.put("costState",costState);out.put("errors",errors);out.put("evidenceTasks",tasks);
        out.put("unresolvedFields",ds.values().stream().filter(r->r.inputCoverage<1).map(Range::key).toList());
        out.put("blockingFacts",c.getOrDefault("knownBlockers",List.of()));out.put("rankRange",null);out.put("slot",null);out.put("selected",false);out.put("alreadyTracked",Boolean.TRUE.equals(c.get("alreadyTracked")));
        out.put("unselectedReason",reasons.isEmpty()?"本期名额限制":String.join("；",reasons));
        return out;
    }
    /** Group independently by source/platform/market/definition/profile/window.
     * Global composition is deterministic round-robin across cohort keys, never a cross-market score sort. */
    public static Map<String,Object> run(List<Map<String,Object>> inputs,Profile p){
        Map<String,Long> counts=inputs.stream().collect(Collectors.groupingBy(c->String.valueOf(c.get("id")),TreeMap::new,Collectors.counting()));
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var c:inputs){var r=evaluate(c,p);if(c.get("id")==null||counts.get(String.valueOf(c.get("id")))>1){
            r.put("route","lead");r.put("eligibleForValidation",false);r.put("unselectedReason","身份ID缺失或重复，双方隔离待核验");r.put("action","核对稳定ID及来源，不能保留先到的一条");}rows.add(r);}
        Map<String,List<Map<String,Object>>> cohorts=new TreeMap<>();
        for(var r:rows)if(Boolean.TRUE.equals(r.get("eligibleForValidation"))&&!Boolean.TRUE.equals(r.get("alreadyTracked")))cohorts.computeIfAbsent(String.valueOf(r.get("cohort")),k->new ArrayList<>()).add(r);
        Comparator<Map<String,Object>> lower=Comparator.comparingDouble((Map<String,Object> r)->ordered(bound(r,"lower"))).reversed().thenComparing(r->String.valueOf(r.get("id")));
        Comparator<Map<String,Object>> upper=Comparator.comparingDouble((Map<String,Object> r)->ordered(bound(r,"upper"))).reversed().thenComparing(Comparator.comparingDouble((Map<String,Object> r)->bound(r,"rangeWidth")).reversed()).thenComparing(r->String.valueOf(r.get("id")));
        List<List<Map<String,Object>>> queues=new ArrayList<>();
        for(var pool:cohorts.values()){
            for(var r:pool){long best=1+pool.stream().filter(o->o!=r&&bound(o,"lower")>bound(r,"upper")+1e-10).count();long worst=1+pool.stream().filter(o->o!=r&&bound(o,"upper")+1e-10>=bound(r,"lower")).count();r.put("rankRange",List.of(best,worst));}
            pool.sort(lower);List<Map<String,Object>> selected=new ArrayList<>();Map<String,Integer> families=new HashMap<>();
            int reserve=Math.min(p.explorationSlots,(int)pool.stream().filter(V22RecommendationEngine::explorable).count());
            take(pool,selected,families,p.target-reserve,p.familyCap,"保守优先");
            take(pool.stream().filter(V22RecommendationEngine::explorable).sorted(upper).toList(),selected,families,Math.min(p.target,selected.size()+reserve),p.familyCap,"探索补证");
            take(pool,selected,families,p.target,p.familyCap,"保守补位");
            for(var r:pool)if(!selected.contains(r))r.put("unselectedReason",families.getOrDefault(family(r),0)>=p.familyCap?"玩法家族上限":"本期名额限制");
            queues.add(selected);
        }
        List<Map<String,Object>> selected=new ArrayList<>();Set<String> products=new HashSet<>();Map<String,Integer> globalFamilies=new HashMap<>();
        int max=queues.stream().mapToInt(List::size).max().orElse(0);
        for(int i=0;i<max;i++)for(var queue:queues)if(i<queue.size()){
            var r=queue.get(i);String product=String.valueOf(r.get("productId")),fam=family(r);
            if(products.contains(product)){r.put("unselectedReason","同原产品跨平台去重");continue;}
            if(globalFamilies.getOrDefault(fam,0)>=p.familyCap){r.put("unselectedReason","全期玩法家族上限");continue;}
            if(selected.size()>=p.target){r.put("unselectedReason","跨队列轮转配额已满");continue;}
            selected.add(r);products.add(product);globalFamilies.merge(fam,1,Integer::sum);r.put("selected",true);r.put("unselectedReason",null);
        }
        for(int i=0;i<inputs.size();i++)if(Boolean.TRUE.equals(inputs.get(i).get("alreadyTracked"))){rows.get(i).put("unselectedReason","已制作或无新增依据，移入已关注动态");}
        Map<String,Object> out=new LinkedHashMap<>();out.put("rows",rows);out.put("selectedIds",selected.stream().map(r->r.get("id")).toList());out.put("selected",selected);out.put("target",p.target);
        out.put("diagnostic",inputs.isEmpty()?"没有候选：恢复来源或导入可追溯材料":selected.isEmpty()?"本期没有验证推荐；已输出线索、补证和暂缓原因":"同队列保守/探索组合，跨队列按稳定队列顺序轮转；不比较总分");
        out.put("systemTasks",inputs.isEmpty()?List.of("恢复来源或导入有稳定身份的研究材料"):List.of());return out;
    }
    private static void take(List<Map<String,Object>> pool,List<Map<String,Object>> selected,Map<String,Integer> families,int limit,int cap,String slot){
        for(var r:pool){if(selected.size()>=limit)break;if(selected.contains(r)||families.getOrDefault(family(r),0)>=cap)continue;selected.add(r);families.merge(family(r),1,Integer::sum);r.put("slot",slot);}
    }
    private static String family(Map<String,Object> r){String f=String.valueOf(r.get("family"));return f.equals("未知")||f.equals("null")?"unknown:"+r.get("productId"):f;}
    private static boolean explorable(Map<String,Object> r){return "conditional".equals(r.get("route"))||num(r.get("inputCoverage"),0)<1-1e-10;}
    private static double bound(Map<String,Object> r,String key){return num(((Map<?,?>)r.get("utilityRange")).get(key),0);}
    private static double ordered(double n){return Math.rint(n*1e8)/1e8;}
    private static Map<String,Object> task(String key,String label,Double impact,boolean gate){Map<String,Object> t=new LinkedHashMap<>();t.put("key",key);t.put("label",label);t.put("impact",impact);t.put("prerequisite",gate);t.put("status","OPEN");t.put("reason",gate?"决定资格和行动，优先补证":"最多可能缩小的范围；不是预期收益");return t;}
    private static Range range(String k,double l,double u,Double e,double c,String s,String method,List<?> refs){return new Range(k,Math.max(0,l),Math.min(100,Math.max(l,u)),e,c,s,method,refs);}
    private static Range unknown(String k,String s,String reason){return range(k,0,100,null,0,s,reason,List.of());}
    private static boolean usable(Map<String,Object> c,String key){Object states=c.get("states");if(!(states instanceof Map<?,?> m)||m.get(key)==null)return true;return Set.of("observed","partial","observed_zero").contains(String.valueOf(m.get(key)));}
    private static String state(Map<String,Object> c,String key){return c.get("states") instanceof Map<?,?> m&&m.get(key)!=null?String.valueOf(m.get(key)):"missing";}
    private static String status(Map<String,Object> c,String key,int n,int all){return !usable(c,key)?state(c,key):n==all?"observed":n==0?"missing":"partial";}
    private static double g(double x,double scale){return 50+50*Math.max(-1,Math.min(1,x/scale));}
    private static double clip(double n){return Math.max(0,Math.min(1,n));}
    private static Double nullable(Object o){return o==null?null:num(o,Double.NaN);}
    private static double num(Object o,double fallback){return o instanceof Number n?n.doubleValue():fallback;}
    private static int integer(Map<String,Object> m,String key,int fallback){if(!m.containsKey(key))return fallback;double x=num(m.get(key),Double.NaN);if(!Double.isFinite(x)||x!=Math.rint(x))throw new IllegalArgumentException(key+"必须是整数");return (int)x;}
}
