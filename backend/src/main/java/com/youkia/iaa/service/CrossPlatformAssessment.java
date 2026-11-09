package com.youkia.iaa.service;

import java.time.LocalDate;
import java.util.*;

/** Evidence synthesis only: ordinal ranks from different boards are never averaged. */
public final class CrossPlatformAssessment {
    private CrossPlatformAssessment() {}
    public static String key(Map<String,Object> r){return r.get("unifiedProductId")!=null?"product:"+r.get("unifiedProductId"):s(r,"entityKey");}
    public static int priority(Map<String,Object> r){return r.get("crossPlatformPriority") instanceof Number n?n.intValue():3;}
    public static Map<String,Object> apply(List<Map<String,Object>> audit,LocalDate day){
        Map<String,List<Map<String,Object>>> groups=new TreeMap<>();
        audit.forEach(r->groups.computeIfAbsent(key(r),k->new ArrayList<>()).add(r));
        Map<String,Object> products=new TreeMap<>();
        for(var group:groups.entrySet()){
            var members=group.getValue();boolean confirmed=group.getKey().startsWith("product:");
            var current=members.stream().filter(r->day.toString().equals(r.get("latestObservedDate"))).toList();
            Set<String> platforms=new TreeSet<>(),origins=new TreeSet<>(),heads=new TreeSet<>();
            for(var r:current){platforms.add(s(r,"platform"));if(!s(r,"originFamily").isBlank())origins.add(s(r,"originFamily"));
                if(n(r,"latestRank")<=20&&n(r,"observationDays")>=5&&continuous(r,day))heads.add(s(r,"platform"));}
            boolean divergent=current.stream().anyMatch(a->gain(a)>0&&current.stream().anyMatch(b->!s(a,"platform").equals(s(b,"platform"))&&gain(b)<0));
            boolean expansion=current.stream().anyMatch(a->Set.of("NEW_ENTRY","ACCELERATOR").contains(s(a,"portfolioGroup"))&&current.stream().anyMatch(b->!s(a,"platform").equals(s(b,"platform"))&&n(b,"observationDays")>=5&&continuous(b,day)&&earlier(b,a)));
            String type=heads.size()>=2?"MULTI_PLATFORM_ESTABLISHED":divergent?"DIVERGENT":expansion?"CROSS_PLATFORM_EXPANSION":platforms.size()>=2?"MULTI_PLATFORM_SIGNAL":confirmed?"SINGLE_PLATFORM":"IDENTITY_UNCONFIRMED";
            String message=switch(type){
                case "MULTI_PLATFORM_ESTABLISHED"->"至少两个平台当前Top20、近7日不少于5日且最近3日连续有记录。属于成熟基准，不占新增机会席位。";
                case "DIVERGENT"->"确认同一产品的不同平台最新日有升有降，优先核查分歧，不描述为全面增长。";
                case "CROSS_PLATFORM_EXPANSION"->"某平台满足新入榜或加速门槛，另一平台至少提前3日出现且有连续观察支持。可研究跨平台扩散，但不能推出迁移因果或新玩法。";
                case "MULTI_PLATFORM_SIGNAL"->"确认同一产品在多个平台当日可见；仅作为多平台佐证，不自动增加资格或认定新增。";
                case "SINGLE_PLATFORM"->"已建立统一产品身份，目前仅一个平台有当日证据；其他平台状态未知。";
                default->"尚未确认跨平台产品身份；同名只提示待核，不合并、不参与跨平台判断。";
            };
            List<Map<String,Object>> cards=new ArrayList<>();
            for(var r:members){Map<String,Object> card=new LinkedHashMap<>();
                for(String k:List.of("platform","region","chartKey","boardName","sourceKey","originFamily","authority","sourceUrl","name","developer","externalId","latestObservedDate","observationDays","history","entryEvidence","momentum","gameplayEvidence","contextualSignals","associationBasis","identityReview"))if(r.containsKey(k))card.put(k,r.get(k));
                boolean present=day.toString().equals(r.get("latestObservedDate"));card.put("rank",present?r.get("latestRank"):null);card.put("state",present?"OBSERVED":"MISSING_ON_DATE");
                card.put("monetization","UNKNOWN");cards.add(card);
            }
            var possible=audit.stream().filter(r->!group.getKey().equals(key(r))&&members.stream().anyMatch(m->!s(m,"platform").equals(s(r,"platform"))&&normalized(s(m,"name")).equals(normalized(s(r,"name"))))).map(r->Map.of("name",s(r,"name"),"platform",s(r,"platform"),"developer",s(r,"developer"),"entityKey",s(r,"entityKey"),"status","UNCONFIRMED_SAME_NAME")).distinct().toList();
            Map<String,Object> assessment=new LinkedHashMap<>();assessment.put("version","cross-platform-1.0.0");assessment.put("asOfDate",day.toString());assessment.put("type",type);assessment.put("reason",message);assessment.put("confirmedIdentity",confirmed);assessment.put("productId",members.getFirst().get("unifiedProductId"));assessment.put("productName",members.getFirst().get("unifiedProductName"));
            assessment.put("cards",cards);assessment.put("currentPlatformCount",platforms.size());assessment.put("originFamilyCount",origins.size());assessment.put("originFamilies",origins);
            assessment.put("missingPlatforms",List.of("WECHAT","DOUYIN","IOS","GOOGLE_PLAY").stream().filter(p->!platforms.contains(p)).toList());assessment.put("possibleMatches",possible);
            assessment.put("hasDivergence",divergent);assessment.put("boundary","不同榜单不平均名次、不合成总排名。微信和抖音若同属引力来源，仅计一个来源家族；平台数不代表独立供应商数。玩法和IAA结论不跨版本传播；缺失不代表未发行或无热度。");
            if(confirmed)products.put(group.getKey(),assessment);
            for(var r:members){r.put("crossPlatform",assessment);r.put("crossPlatformPriority",switch(type){case "CROSS_PLATFORM_EXPANSION"->0;case "MULTI_PLATFORM_SIGNAL"->1;case "DIVERGENT"->4;default->3;});
                if(type.equals("MULTI_PLATFORM_ESTABLISHED")&&Set.of("NEW_ENTRY","ACCELERATOR").contains(s(r,"portfolioGroup"))){
                    r.put("portfolioGroup","BENCHMARK");r.put("signalLabel","多平台成熟基准 · 非新增机会");r.put("opportunityAssessment",message);r.put("whyNow",message+" 各平台实际名次与变化见冻结证据卡。");r.put("nextAction","研究成熟产品的跨平台适配差异与竞争格局，不据此推断新赛道或早期空白市场。");
                }
            }
        }
        return products;
    }
    public static List<Map<String,Object>> diagnostics(List<Map<String,Object>> audit){
        List<Map<String,Object>> out=new ArrayList<>();
        for(String platform:List.of("WECHAT","DOUYIN","IOS","GOOGLE_PLAY")){
            var rows=audit.stream().filter(r->platform.equals(r.get("platform"))).toList();
            out.add(Map.of("platform",platform,"boardCandidates",rows.size(),"confirmedIdentity",rows.stream().filter(r->r.get("unifiedProductId")!=null).count(),"missingGameplay",rows.stream().filter(r->"未知".equals(r.get("family"))).count(),"latestObservedDate",rows.stream().map(r->s(r,"latestObservedDate")).max(String::compareTo).orElse("无记录"),"currentCandidates",rows.stream().filter(r->"OBSERVED".equals(r.get("currentState"))).count(),"missingCurrent",rows.stream().filter(r->"MISSING_ON_DATE".equals(r.get("currentState"))).count(),"selected",rows.stream().filter(r->Boolean.TRUE.equals(r.get("selected"))).count(),"notice","按来源榜单条目计数，同一产品可出现在多个榜；缺失玩法需带证据核验，不能凭名称猜测"));
        }return out;
    }
    @SuppressWarnings("unchecked") private static boolean continuous(Map<String,Object> r,LocalDate day){
        var history=(List<Map<String,Object>>)r.getOrDefault("history",List.of());
        for(int i=0;i<3;i++){String date=day.minusDays(i).toString();if(history.stream().noneMatch(p->date.equals(p.get("date"))&&p.get("rank")!=null))return false;}return true;
    }
    @SuppressWarnings("unchecked") private static int gain(Map<String,Object> r){var m=(Map<String,Object>)r.getOrDefault("momentum",Map.of());return n(m,"latestDailyGain");}
    @SuppressWarnings("unchecked") private static boolean earlier(Map<String,Object> b,Map<String,Object> a){
        var be=(Map<String,Object>)b.getOrDefault("entryEvidence",Map.of());var ae=(Map<String,Object>)a.getOrDefault("entryEvidence",Map.of());
        try{return !LocalDate.parse(s(be,"firstSeenIn28Days")).isAfter(LocalDate.parse(s(ae,"firstSeenIn28Days")).minusDays(3));}catch(Exception e){return false;}
    }
    private static String normalized(String v){return v.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    private static String s(Map<String,Object> r,String k){return Objects.toString(r.get(k),"");}
    private static int n(Map<String,Object> r,String k){return r.get(k) instanceof Number n?n.intValue():0;}
}
