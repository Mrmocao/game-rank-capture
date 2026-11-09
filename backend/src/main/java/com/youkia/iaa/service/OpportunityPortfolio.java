package com.youkia.iaa.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Evidence-gated opportunity allocation. Rank movement never establishes market or gameplay novelty. */
public final class OpportunityPortfolio {
    private OpportunityPortfolio() {}
    public static final String POLICY="核心4席：成熟基准2席（50%）、新入榜1席（25%）、单榜加速1席（25%）。第5席只增补合格早期机会；严格机会席不足时，用研究补位席补足最多5项，但补位对象仍标记待核，不提升为纯IAA正式推荐。正式精选占成熟基准席，不降低IAA资格。新入榜须此前连续3日同口径完整Top100未出现，入榜不超过3日、至少2日连续记录且实际上升≥10名。单榜加速须同样的入榜基线、入榜4—14日，最近3日连续、两日上升≥15名、最新日增速比前日多≥5名，且在至少5个可比样本的同榜两日增幅前20%。28日内回归不算新入榜；名称与发行方匹配仍待身份核验。研究补位须至少5/7日、最近观察连续3日、同榜Top20或涨≥10且Top50；最多容忍整榜延迟1天。海外缺少玩法时只作待识别研究，须有稳定应用ID。跨榜优先覆盖最多2项海外，其后补不同平台；榜内按趋势、观察天数、名次排序，同平台同应用跨国只占一席。缺失日期不回填。不同榜不合分，优先早期席、同榜排序、跨榜稳定轮转、单玩法最多2项。排名上涨不是用户增长，首次入榜不是首次上线，更不证明新赛道。阈值为待回测研究默认值。已确认统一产品跨平台只占一席，保留各榜独立名次；同名仅提示待核。跨平台扩散同组优先，走势分歧降序，多平台成熟头部只占基准席；不跨平台传播IAA/玩法结论。";
    public static final List<Map<String,String>> DATA_REQUIREMENTS=List.of(
        Map.of("priority","P0","data","连续28—60天同榜完整Top100（条件允许Top500）及业务日期、发布时间、采集时间、完整度","purpose","区分首次观察、真正新入榜、重返榜单和缺采；按日发现无需等到周末","availability","现有来源继续积累；历史回采受来源可访问范围约束"),
        Map.of("priority","P0","data","平台稳定appid、发行方、首发日期、版本更新时间及旧名称","purpose","排除改名/换皮/重发；首次入榜不等于新上线","availability","当前微信/抖音榜单缺稳定ID，须补官方详情或发行方证据"),
        Map.of("priority","P0","data","前5—10分钟实机录屏、核心循环/操作/关卡规则、广告与内购入口；至少3款最接近竞品的逐项差异","purpose","判断新机制还是美术换皮，提出可验证原型假设；单一消除/塔防标签不能证明新赛道","availability","需试玩留证与人工复核，AI仅整理带引用的事实"),
        Map.of("priority","P1","data","同口径活跃/新增/留存及广告曝光、投放起始日、素材数量、达人活动时间","purpose","区分自然需求增长和买量/活动推升；榜单速度不能替代真实用户增长","availability","发行方授权数据或明确字段及保存权限的数据服务；当前未接入，不自动购买"),
        Map.of("priority","P1","data","同机制竞品数量、首次出现日期、发行方分布、头部集中度、目标地区及样本覆盖边界","purpose","核查样本内拥挤度与玩法扩散；不能由未采到竞品推出市场空白","availability","需先建立经过实机复核的机制分类和竞品库"),
        Map.of("priority","P1","data","每次推荐后7/14/28日同榜名次、退出/缺采状态、人工研究结论、原型成本与结果","purpose","时间外回测发现提前量、误报和机会持续性，再调整阈值","availability","报告与日快照已冻结；需随时间积累并登记研究结果")
    );

    public static Map<String,Object> select(List<Map<String,Object>> audit,List<Map<String,Object>> rows,LocalDate day,int limit) {
        return select(audit,rows,day,limit,0,Set.of());
    }
    public static Map<String,Object> select(List<Map<String,Object>> audit,List<Map<String,Object>> rows,LocalDate day,int limit,int formalCount,Set<String> formalLabels) {
        // All entry points use the same production policy; tests exercise it unchanged.
        Map<String,Map<LocalDate,Map<String,Object>>> snapshots=new TreeMap<>();
        Map<String,List<Map<String,Object>>> histories=new HashMap<>();
        for(var r:rows){String scope=scope(r);LocalDate d=LocalDate.parse(r.get("business_date").toString());
            snapshots.computeIfAbsent(scope,k->new TreeMap<>()).put(d,r);
            if(n(r,"rank_position")<=100)histories.computeIfAbsent(scope+":"+entity(r),k->new ArrayList<>()).add(r);
        }
        Map<String,List<Map<String,Object>>> comparisons=new TreeMap<>();
        for(var item:audit){
            String scope=s(item,"scopeKey");var history=histories.getOrDefault(scope+":"+s(item,"entityKey"),List.of());
            TreeMap<LocalDate,Map<String,Object>> days=new TreeMap<>();history.forEach(r->days.put(LocalDate.parse(r.get("business_date").toString()),r));
            if(days.isEmpty())continue;
            LocalDate first=days.firstKey(),runStart=day;while(days.containsKey(runStart.minusDays(1)))runStart=runStart.minusDays(1);
            int age=(int)ChronoUnit.DAYS.between(first,day);
            boolean current=days.containsKey(day),reentry=current&&!runStart.equals(first);
            List<Map<String,Object>> baseline=new ArrayList<>();
            for(int i=3;i>=1;i--){var snapshot=snapshots.get(scope).get(first.minusDays(i));
                if(snapshot!=null&&"REQUESTED_RANGE_COMPLETE".equals(s(snapshot,"completeness"))&&n(snapshot,"requested_top_n")==100&&n(snapshot,"contiguous_top_n")>=100)baseline.add(evidence(snapshot));
            }
            boolean identity=!s(item,"externalId").isBlank()||!s(item,"developer").isBlank();
            boolean confirmed=baseline.size()==3&&identity&&!reentry;
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("status",reentry?"REENTRY":confirmed?"OBSERVED_CHART_ENTRY":"NEWNESS_UNKNOWN");
            entry.put("firstSeenIn28Days",first.toString());entry.put("ageDays",age);entry.put("firstObservedRank",days.get(first).get("rank_position"));entry.put("firstObservedEvidence",presenceEvidence(days.get(first)));entry.put("priorCompleteAbsence",baseline);
            entry.put("meaning","仅指28日可见窗口内首次进入该来源Top100；不证明新上线或历史从未入榜。名称/发行方更名仍需排查。");
            entry.put("reason",reentry?"28日内已有更早记录，中间退出或缺采后回归，不算新增":confirmed?"入榜前3个连续业务日完整Top100没有此来源身份/标签":"缺少入榜前连续3日完整Top100或可匹配身份，无法确认新入榜");
            item.put("entryEvidence",entry);item.put("portfolioGroup","OBSERVE");
            item.put("noveltyStatus","UNVERIFIED");item.put("opportunityHypothesis","针对“"+s(item,"name")+"”的来源分类“"+s(item,"family")+"”，核对核心操作、目标/失败条件和关卡机制，与至少3款近似竞品逐项对照。先验证玩法差异是否成立，再验证能否解释本榜走势；当前标签与排名不足以认定新赛道。");
            Integer gain1=gain(days,day.minusDays(1),day),gain0=gain(days,day.minusDays(2),day.minusDays(1));
            Map<String,Object> momentum=new LinkedHashMap<>();momentum.put("latestDailyGain",gain1);momentum.put("previousDailyGain",gain0);
            momentum.put("twoDayGain",gain1!=null&&gain0!=null?gain1+gain0:null);momentum.put("acceleration",gain1!=null&&gain0!=null?gain1-gain0:null);
            momentum.put("comparableCount",0);momentum.put("withinChartOrder",null);item.put("momentum",momentum);
            if(gain1!=null&&gain0!=null)comparisons.computeIfAbsent(scope,k->new ArrayList<>()).add(item);
            boolean eligible=current&&SourceIdentityService.FAMILIES.contains(s(item,"family"))&&!"其他/重度".equals(s(item,"family"))&&!Boolean.TRUE.equals(item.get("hasBestsellerSignal"))&&!formalLabels.contains(s(item,"platform")+":"+s(item,"name"));
            item.put("earlyEligible",eligible&&confirmed);
            if(eligible&&confirmed&&age>=1&&age<=3&&days.size()==age+1&&gain1!=null&&gain1>0&&n(days.get(first),"rank_position")-n(days.get(day),"rank_position")>=10){
                item.put("portfolioGroup","NEW_ENTRY");item.put("exclusionReason","");
            }else if(eligible&&s(item,"exclusionReason").isBlank())item.put("portfolioGroup","BENCHMARK");
            if(formalLabels.contains(s(item,"platform")+":"+s(item,"name")))item.put("exclusionReason","正式精选已有同平台同名项，保守避重；待身份核验后再关联");
        }
        for(var cohort:comparisons.values()){
            cohort.sort(Comparator.<Map<String,Object>>comparingInt(r->(int)momentum(r).get("twoDayGain")).reversed().thenComparing(r->s(r,"leadKey")));
            for(int i=0;i<cohort.size();i++){var item=cohort.get(i);var m=momentum(item);m.put("comparableCount",cohort.size());m.put("withinChartOrder",i+1);
                @SuppressWarnings("unchecked") var entry=(Map<String,Object>)item.get("entryEvidence");int age=(int)entry.get("ageDays");
                if(Boolean.TRUE.equals(item.get("earlyEligible"))&&age>=4&&age<=14&&cohort.size()>=5&&i+1<=Math.ceil(cohort.size()*0.2)&&(int)m.get("twoDayGain")>=15&&(int)m.get("acceleration")>=5&&(int)m.get("latestDailyGain")>0&&(int)m.get("previousDailyGain")>=0){
                    item.put("portfolioGroup","ACCELERATOR");item.put("exclusionReason","");
                }
            }
        }
        for(var item:audit){String group=s(item,"portfolioGroup");
            @SuppressWarnings("unchecked") var entry=(Map<String,Object>)item.get("entryEvidence");
            String assessment=entry==null?"当前没有同口径Top100记录":!"OBSERVED_CHART_ENTRY".equals(entry.get("status"))?entry.get("reason").toString():
                (int)entry.get("ageDays")<=3?"新入榜组需入榜1—3日、至少2日连续、实际首末涨≥10且最新日上涨":
                "加速组需入榜4—14日、最近3日连续、两日涨≥15、加速度≥5、前日不跌、至少5个同榜样本前20%";
            item.put("opportunityAssessment",Set.of("NEW_ENTRY","ACCELERATOR").contains(group)?"满足本组来源研究门槛；新游戏/新玩法与IAA仍待核验":assessment);
            if(group.equals("OBSERVE")&&!s(item,"exclusionReason").isBlank())item.put("exclusionReason",item.get("exclusionReason")+"；早期机会："+assessment);
            if(Set.of("NEW_ENTRY","ACCELERATOR").contains(group)){
                item.put("signalType",group);item.put("signalLabel",group.equals("NEW_ENTRY")?"新入榜 · 早期研究":"单榜加速 · 早期研究");
                item.put("whyNow",item.get("signalLabel")+"；"+item.get("whyNow")+"入榜与增速依据见本期冻结证据；不代表新游戏或新玩法。");
                item.put("nextAction","24—48小时内核实appid/首发日期，试玩核心循环，对照3款最接近竞品；核查是否活动/投放拉升及变现方式，再决定是否制作差异化原型。");
            }else if(group.equals("BENCHMARK"))item.put("signalLabel","成熟基准 · 不等于新机会");
        }
        var productEvidence=CrossPlatformAssessment.apply(audit,day);
        for(var item:audit)if(formalLabels.contains(CrossPlatformAssessment.key(item))){item.put("exclusionReason","已确认统一产品已在正式精选中，跨平台只占一席");item.put("portfolioGroup","OBSERVE");}
        List<Map<String,Object>> selected=new ArrayList<>();Set<String> used=new HashSet<>();Map<String,Integer> families=new HashMap<>();
        take(audit,"NEW_ENTRY",1,limit,selected,used,families);take(audit,"ACCELERATOR",1,limit,selected,used,families);
        take(audit,"BENCHMARK",Math.max(0,2-formalCount),limit,selected,used,families);
        // The fifth place can only fund more early investigation; empty core categories stay empty.
        int beforeFlex=selected.size();
        take(audit,"EARLY_FLEX",1,Math.min(limit,beforeFlex+1),selected,used,families);
        int researchQuota=Math.max(0,limit-selected.size());
        if(researchQuota>0) fillResearchFlex(audit,rows,selected,used,families,formalLabels,limit,day);
        for(var item:audit){item.remove("earlyEligible");if(!Boolean.TRUE.equals(item.get("selected"))&&s(item,"exclusionReason").isBlank())item.put("exclusionReason","分组配额/玩法上限或同来源身份去重；早期名额不回填成熟热门");}
        List<String> displayGroups=List.of("NEW_ENTRY","ACCELERATOR","EARLY_FLEX","RESEARCH_FLEX","BENCHMARK");
        selected.sort(Comparator.comparingInt(r->displayGroups.indexOf(s(r,"allocatedSlot"))));
        for(int i=0;i<selected.size();i++)selected.get(i).put("displayOrder",i+1);
        List<Map<String,Object>> allocation=new ArrayList<>();
        for(String g:List.of("BENCHMARK","NEW_ENTRY","ACCELERATOR","EARLY_FLEX","RESEARCH_FLEX")){
            long count=selected.stream().filter(r->g.equals(r.get("allocatedSlot"))).count()+(g.equals("BENCHMARK")?formalCount:0);
            int target=g.equals("BENCHMARK")?2:g.equals("RESEARCH_FLEX")?researchQuota:1;
            allocation.add(Map.of("group",g,"label",label(g),"target",target,"selected",count,"shortfall",Math.max(0,target-count),"reason",count<target?"符合证据、去重与玩法上限的候选不足，留空；详见未选原因":"已按证据分配"));
        }
        return Map.of("items",selected,"allocation",allocation,"productEvidence",productEvidence);
    }

    /** Fill only from continuously evidenced signals. Never turn the fill into a newness or IAA claim. */
    private static void fillResearchFlex(List<Map<String,Object>> audit,List<Map<String,Object>> rows,List<Map<String,Object>> selected,
                                         Set<String> used,Map<String,Integer> families,Set<String> formalLabels,int limit,LocalDate day){
        Set<String> todayScopes=new HashSet<>();
        for(var row:rows)if(day.toString().equals(s(row,"business_date")))todayScopes.add(scope(row));
        Set<String> usedListings=new HashSet<>();selected.forEach(r->usedListings.add(listingKey(r)));
        Map<String,List<Map<String,Object>>> queues=new TreeMap<>();
        for(var r:audit)if(!Boolean.TRUE.equals(r.get("selected"))&&researchEligible(r,day,todayScopes)
                &&!formalLabels.contains(CrossPlatformAssessment.key(r))&&!formalLabels.contains(s(r,"platform")+":"+s(r,"name")))
            queues.computeIfAbsent(s(r,"scopeKey"),k->new ArrayList<>()).add(r);
        // Rank is compared only inside a source/chart/country queue. Across scopes prefer
        // unrepresented markets/platforms, then rotate by a stable key.
        queues.values().forEach(q->q.sort(Comparator.<Map<String,Object>,Boolean>comparing(r->Boolean.TRUE.equals(r.get("risingLabelSequence"))).reversed()
                .thenComparing(Comparator.comparingInt((Map<String,Object> r)->n(r,"observationDays")).reversed())
                .thenComparingInt(r->n(r,"latestRank")).thenComparing(r->s(r,"leadKey"))));
        Set<String> visited=new HashSet<>();
        while(selected.size()<limit&&!queues.isEmpty()){
            if(visited.containsAll(queues.keySet()))visited.clear();
            var next=queues.entrySet().stream().filter(e->!visited.contains(e.getKey()))
                    .min(Comparator.<Map.Entry<String,List<Map<String,Object>>>>comparingInt(e->marketPriority(e.getValue().getFirst(),selected))
                            .thenComparing(Map.Entry::getKey)).orElseThrow();
            String key=next.getKey();var q=next.getValue();visited.add(key);
            while(!q.isEmpty()){
                var r=q.removeFirst();String entity=CrossPlatformAssessment.key(r),family=s(r,"family"),listing=listingKey(r);
                if(used.contains(entity)||usedListings.contains(listing)||families.getOrDefault(family,0)>=2)continue;
                used.add(entity);usedListings.add(listing);families.merge(family,1,Integer::sum);
                String gap=s(r,"exclusionReason");
                r.put("strictExclusionReason",gap);r.put("exclusionReason","");
                String reason="严格机会席不足，按可追溯榜单材料补入研究清单；不占已验证新入榜/加速席，也不是纯IAA正式推荐。";
                if(!gap.isBlank())reason+="尚未通过严格筛选："+gap;
                r.put("researchSelectionReason",reason);r.put("selected",true);r.put("allocatedSlot","RESEARCH_FLEX");
                r.put("portfolioLabel",label("RESEARCH_FLEX"));r.put("signalLabel","研究补位 · 待核");r.put("formalEligible",false);
                if("未知".equals(family)){
                    r.put("opportunityHypothesis","先获取该应用的玩法说明和实机记录，确认是否轻度玩法，再与3款同机制产品比较；当前只确认榜单信号，不能判断新赛道。");
                    r.put("nextAction","优先补齐该平台应用ID对应的玩法描述和实机记录，再核查广告/内购入口；玩法与IAA资格确认前不建议制作复刻。");
                }
                selected.add(r);break;
            }
            if(q.isEmpty())queues.remove(key);
        }
    }
    @SuppressWarnings("unchecked") private static boolean researchEligible(Map<String,Object> r,LocalDate day,Set<String> todayScopes){
        int age;try{age=(int)ChronoUnit.DAYS.between(LocalDate.parse(s(r,"latestObservedDate")),day);}catch(Exception e){return false;}
        if(age<0||age>1||Boolean.TRUE.equals(r.get("hasBestsellerSignal")))return false;
        if(age==1&&todayScopes.contains(s(r,"scopeKey")))return false; // A missing game in today's chart is not a delayed chart.
        boolean knownFamily=SourceIdentityService.FAMILIES.contains(s(r,"family"))&&!"其他/重度".equals(s(r,"family"));
        boolean overseasIdentification="GOOGLE_PLAY".equals(s(r,"platform"))&&!"CN".equalsIgnoreCase(s(r,"region"))
                &&!s(r,"externalId").isBlank()&&"未知".equals(s(r,"family"));
        if(!knownFamily&&!overseasIdentification)return false;
        if(s(r,"externalId").isBlank()&&s(r,"developer").isBlank())return false;
        if(n(r,"observationDays")<5)return false;
        var history=(List<Map<String,Object>>)r.getOrDefault("history",List.of());
        for(int i=0;i<3;i++){String date=day.minusDays(age+i).toString();
            if(history.stream().noneMatch(p->date.equals(p.get("date"))&&p.get("rank")!=null&&p.get("evidence")!=null))return false;
        }
        return n(r,"latestRank")<=20||(r.get("rankChange") instanceof Number gain&&gain.intValue()>=10&&n(r,"latestRank")<=50);
    }
    private static String listingKey(Map<String,Object> r){return s(r,"externalId").isBlank()?CrossPlatformAssessment.key(r):s(r,"platform")+":"+s(r,"externalId");}
    private static int marketPriority(Map<String,Object> r,List<Map<String,Object>> selected){
        boolean foreign=!"CN".equalsIgnoreCase(s(r,"region"));long count=selected.stream().filter(x->!"CN".equalsIgnoreCase(s(x,"region"))).count();
        if(foreign&&count<2)return 0;
        if(selected.stream().noneMatch(x->s(x,"platform").equals(s(r,"platform"))))return 1;
        return foreign?3:2;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> momentum(Map<String,Object> r){return (Map<String,Object>)r.get("momentum");}
    private static void take(List<Map<String,Object>> audit,String group,int quota,int limit,List<Map<String,Object>> selected,Set<String> used,Map<String,Integer> families){
        Map<String,List<Map<String,Object>>> queues=new TreeMap<>();
        for(var r:audit)if(s(r,"exclusionReason").isBlank()&&!Boolean.TRUE.equals(r.get("selected"))&&(group.equals(s(r,"portfolioGroup"))||group.equals("EARLY_FLEX")&&Set.of("NEW_ENTRY","ACCELERATOR").contains(s(r,"portfolioGroup"))))queues.computeIfAbsent(s(r,"scopeKey"),k->new ArrayList<>()).add(r);
        Comparator<Map<String,Object>> order=Comparator.<Map<String,Object>>comparingInt(r->momentum(r).get("twoDayGain") instanceof Number n?-n.intValue():0).thenComparingInt(r->n(r,"latestRank")).thenComparing(r->s(r,"leadKey"));
        Comparator<Map<String,Object>> benchmarkOrder=Comparator.<Map<String,Object>,Boolean>comparing(r->Boolean.TRUE.equals(r.get("risingLabelSequence"))).reversed()
                .thenComparing(Comparator.comparingInt((Map<String,Object> r)->n(r,"observationDays")).reversed()).thenComparingInt(r->n(r,"latestRank")).thenComparing(r->s(r,"leadKey"));
        Comparator<Map<String,Object>> priority=Comparator.comparingInt(CrossPlatformAssessment::priority);
        queues.values().forEach(q->q.sort(priority.thenComparing(group.equals("BENCHMARK")?benchmarkOrder:order)));
        var orderedQueues=new ArrayList<>(queues.values());orderedQueues.sort(Comparator.comparingInt(q->CrossPlatformAssessment.priority(q.getFirst())));int added=0;boolean progress=true;
        while(added<quota&&selected.size()<limit&&progress){progress=false;for(var q:orderedQueues){
            while(!q.isEmpty()&&added<quota&&selected.size()<limit){var r=q.removeFirst();String entity=CrossPlatformAssessment.key(r),family=s(r,"family");
                if(used.contains(entity)||families.getOrDefault(family,0)>=2)continue;
                used.add(entity);families.merge(family,1,Integer::sum);r.put("selected",true);r.put("allocatedSlot",group);r.put("portfolioLabel",label(group));r.put("displayOrder",selected.size()+1);selected.add(r);added++;progress=true;break;
            }
        }}
    }
    private static String label(String g){return switch(g){case "BENCHMARK"->"成熟基准 · 核心50%";case "NEW_ENTRY"->"新入榜机会 · 核心25%";case "ACCELERATOR"->"单榜加速机会 · 核心25%";case "RESEARCH_FLEX"->"研究补位 · 待核";default->"额外早期研究 · 最多1席";};}
    private static Integer gain(Map<LocalDate,Map<String,Object>> days,LocalDate a,LocalDate b){return days.containsKey(a)&&days.containsKey(b)?n(days.get(a),"rank_position")-n(days.get(b),"rank_position"):null;}
    private static String s(Map<String,Object> r,String k){return Objects.toString(r.get(k),"");}
    private static int n(Map<String,Object> r,String k){return r.get(k) instanceof Number n?n.intValue():Integer.parseInt(r.get(k).toString());}
    private static String scope(Map<String,Object> r){return s(r,"source_key")+":"+s(r,"platform")+":"+s(r,"region")+":"+s(r,"chart_key")+":"+s(r,"parser_version");}
    private static String entity(Map<String,Object> r){return s(r,"source_key")+":"+s(r,"platform")+":"+s(r,"region")+":"+(!s(r,"external_id").isBlank()?"id:"+s(r,"external_id"):"label:"+s(r,"name")+":"+s(r,"developer")+(s(r,"developer").isBlank()?":observation:"+r.get("observation_id"):""));}
    private static Map<String,Object> presenceEvidence(Map<String,Object> r){var e=evidence(r);e.put("state","OBSERVED");e.put("evidence_locator",r.get("evidence_locator"));return e;}
    private static Map<String,Object> evidence(Map<String,Object> r){var e=new LinkedHashMap<String,Object>();for(String k:List.of("observation_id","source_url","raw_sha256","business_date","captured_at","available_at","parser_version","completeness","requested_top_n","contiguous_top_n"))e.put(k,r.get(k));e.put("state","ABSENT_FROM_COMPLETE_TOP100");return e;}
}
