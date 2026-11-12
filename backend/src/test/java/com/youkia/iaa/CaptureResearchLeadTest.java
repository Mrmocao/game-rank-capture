package com.youkia.iaa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.service.CaptureResearchLeadService;
import com.youkia.iaa.service.WeeklyReportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaptureResearchLeadTest {
    final LocalDate day=LocalDate.of(2026,11,2);
    final LocalDateTime cutoff=day.atTime(12,0);
    final CaptureResearchLeadService service=new CaptureResearchLeadService(mock(JdbcTemplate.class),new ObjectMapper());
    Map<String,Object> row(long observation,String chart,String platform,String name,String developer,LocalDate date,int rank,String family){
        Map<String,Object> r=new LinkedHashMap<>();r.put("observation_id",observation);r.put("source_key","gravity-"+platform.toLowerCase(Locale.ROOT));r.put("origin_family","gravity-engine");r.put("source_authority","THIRD_PARTY");
        r.put("chart_key",chart);r.put("board_name",chart);r.put("platform",platform);r.put("region","cn");r.put("business_date",date);r.put("captured_at",cutoff.minusMinutes(10));r.put("available_at",cutoff.minusMinutes(5));
        r.put("parser_version","SYNTHETIC-test");r.put("completeness","REQUESTED_RANGE_COMPLETE");r.put("requested_top_n",100);r.put("contiguous_top_n",100);r.put("source_url","https://example.com/SYNTHETIC");r.put("raw_sha256","SYNTHETIC-"+observation);
        r.put("rank_position",rank);r.put("name",name);r.put("developer",developer);r.put("external_id",null);r.put("evidence_locator","#synthetic-row-"+rank);r.put("source_fields","{\"tags\":[\"休闲\",\""+family+"\"]}");return r;
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> allItems(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    // Existing tests specifically assert the strict opportunity gates, alongside the fill-policy tests below.
    List<Map<String,Object>> items(Map<String,Object> result){return allItems(result).stream().filter(r->!"RESEARCH_FLEX".equals(r.get("allocatedSlot"))).toList();}
    @SuppressWarnings("unchecked") List<Map<String,Object>> audit(Map<String,Object> result){return (List<Map<String,Object>>)result.get("candidateAudit");}
    @Test void sourceLabelTrendRemainsUnverifiedAndEveryPointHasEvidence(){
        List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<7;i++)rows.add(row(i+1,"popularity","WECHAT","SYNTHETIC游戏","SYNTHETIC发行方",day.minusDays(6-i),40-i*5,"消除"));
        var result=service.evaluate(rows,day,cutoff,5);var lead=items(result).getFirst();
        assertEquals(7,lead.get("observationDays"));assertEquals(30,lead.get("rankChange"));assertEquals(true,lead.get("risingLabelSequence"));
        assertEquals("SOURCE_LABEL_MATCH_UNVERIFIED",lead.get("identityStatus"));assertEquals(false,lead.get("formalEligible"));assertEquals("UNKNOWN",lead.get("monetization"));assertFalse(lead.containsKey("productId"));
        var history=(List<Map<String,Object>>)lead.get("history");assertEquals(7,history.size());assertTrue(history.stream().allMatch(p->p.containsKey("evidence")));
        assertTrue(lead.get("whyNow").toString().contains("不等于用户量增长"));
    }
    @Test void cutoffExcludesLateKnowledgeAndNeverFillsMissingHistoricalDays(){
        var old=row(1,"popularity","WECHAT","SYNTHETIC","发行方",day.minusDays(1),30,"消除");
        var now=row(2,"popularity","WECHAT","SYNTHETIC","发行方",day,10,"消除");
        var late=row(3,"popularity","WECHAT","SYNTHETIC","发行方",day.minusDays(6),90,"消除");late.put("available_at",cutoff.plusMinutes(1));
        var result=service.evaluate(List.of(old,now,late),day,cutoff,5);assertTrue(items(result).isEmpty());var lead=audit(result).getFirst();assertEquals(2,lead.get("observationDays"));assertEquals(20,lead.get("rankChange"));assertEquals(false,lead.get("risingLabelSequence"));
        var history=(List<Map<String,Object>>)lead.get("history");assertNull(history.getFirst().get("rank"));assertEquals("MISSING_OR_IDENTITY_UNCONFIRMED",history.getFirst().get("state"));
        assertTrue(items(service.evaluate(List.of(old),day,cutoff,5)).isEmpty(),"yesterday's observation is not today's heat");
    }
    @Test void missingPublisherCannotCreateCrossDayGrowthAndPlatformsNeverMerge(){
        var before=row(1,"popularity","WECHAT","SYNTHETIC同名",null,day.minusDays(2),90,"益智");
        var current=row(2,"popularity","WECHAT","SYNTHETIC同名",null,day,5,"益智");
        var other=row(3,"popularity","DOUYIN","SYNTHETIC同名",null,day,2,"益智");
        var result=service.evaluate(List.of(before,current,other),day,cutoff,5);assertTrue(items(result).isEmpty());assertEquals(3,audit(result).size());
        assertTrue(audit(result).stream().allMatch(r->r.get("observationDays").equals(1)&&r.get("rankChange")==null));
        assertEquals(3,audit(result).stream().map(r->r.get("entityKey")).distinct().count());
    }
    @Test void bestsellerIsRiskNotDemandAndPaidSignalDoesNotBecomeIapFact(){
        var popular=row(1,"popularity","WECHAT","SYNTHETIC付费线索","发行方",day,1,"消除");
        var paid=row(2,"bestseller","WECHAT","SYNTHETIC付费线索","发行方",day,2,"消除");
        var promotion=row(3,"publisher_heat","DOUYIN","SYNTHETIC仅投放","发行方",day,1,"益智");
        var result=service.evaluate(List.of(popular,paid,promotion),day,cutoff,5);assertTrue(items(result).isEmpty());assertEquals(1,audit(result).size());
        assertEquals("UNKNOWN",audit(result).getFirst().get("monetization"));assertEquals(true,audit(result).getFirst().get("hasBestsellerSignal"));
    }
    @Test void latestPartialDoesNotBorrowEarlierRowsAndMissingDataCannotLookLikeZero(){
        var old=row(1,"popularity","WECHAT","SYNTHETIC旧榜行","发行方",day,8,"益智");old.put("available_at",cutoff.minusHours(1));
        var newer=row(2,"popularity","WECHAT","SYNTHETIC新榜行","发行方",day,1,"消除");newer.put("completeness","PARTIAL");newer.put("contiguous_top_n",20);
        var result=service.evaluate(List.of(old,newer),day,cutoff,5);assertTrue(items(result).isEmpty());assertEquals(1,audit(result).size());assertEquals("SYNTHETIC新榜行",audit(result).getFirst().get("name"));
        assertEquals(1,result.get("snapshotCount"));
    }
    @Test void storePaidChartsAreRiskOnlyAndNewFreeIsNotProvenNewOpportunity(){
        for(String chart:List.of("top-paid","top-grossing","top-new-paid")){
            var free=row(1,"top-new-free","GOOGLE_PLAY","SYNTHETIC","发行方",day,1,"消除");
            var paid=row(2,chart,"GOOGLE_PLAY","SYNTHETIC","发行方",day,2,"消除");
            assertTrue(audit(service.evaluate(List.of(paid),day,cutoff,5)).isEmpty());
            var result=service.evaluate(List.of(free,paid),day,cutoff,5);
            assertEquals(1,audit(result).size());assertTrue(items(result).isEmpty());
            assertEquals(true,audit(result).getFirst().get("hasBestsellerSignal"));
            assertEquals("UNKNOWN",audit(result).getFirst().get("monetization"));
        }
    }
    @Test void parserUpgradeDoesNotKeepOlderSameDayRowsAlive(){
        var old=row(1,"top-free","IOS","SYNTHETIC旧","发行方",day,1,"消除");old.put("parser_version","v1");
        var latest=row(2,"top-free","IOS","SYNTHETIC新","发行方",day,3,"消除");latest.put("parser_version","v2");
        latest.put("captured_at",cutoff.minusMinutes(1));latest.put("completeness","PARTIAL");
        var result=service.evaluate(List.of(old,latest),day,cutoff,5);
        assertEquals(1,result.get("snapshotCount"));assertEquals("SYNTHETIC新",audit(result).getFirst().get("name"));
    }
    @Test void paidRiskEarlierInWindowSurvivesAndJdbcBinaryJsonIsSupported(){
        var popular=row(1,"popularity","WECHAT","SYNTHETIC风险","发行方",day,1,"消除");
        popular.put("source_fields",popular.get("source_fields").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var paid=row(2,"bestseller","WECHAT","SYNTHETIC风险","发行方",day.minusDays(3),2,"消除");
        var result=service.evaluate(List.of(popular,paid),day,cutoff,5);
        assertTrue(items(result).isEmpty());assertEquals("消除",audit(result).getFirst().get("family"));
        assertEquals(true,audit(result).getFirst().get("hasBestsellerSignal"));
        assertEquals("UNKNOWN",audit(result).getFirst().get("monetization"));
    }
    @Test void shortlistUsesAvailableSlotsWithoutChangingFormalEligibilityAndIsDeterministic(){
        List<Map<String,Object>> rows=new ArrayList<>();String[] families={"益智","消除","塔防","跑酷","解谜","模拟经营"};
        for(int d=0;d<7;d++)for(int i=0;i<10;i++)rows.add(row(d+1,"popularity","WECHAT","SYNTHETIC游戏"+i,"发行方",day.minusDays(d),i+1,families[i%families.length]));
        assertEquals(1,allItems(service.evaluate(rows,day,cutoff,100)).size(),"mature hits cannot consume early opportunity slots");assertEquals(1,allItems(service.evaluate(rows,day,cutoff,2)).size());assertTrue(items(service.evaluate(rows,day,cutoff,0)).isEmpty());
        var first=items(service.evaluate(rows,day,cutoff,5)).stream().map(r->r.get("leadKey")).toList();Collections.reverse(rows);
        assertEquals(first,items(service.evaluate(rows,day,cutoff,5)).stream().map(r->r.get("leadKey")).toList());
    }

    @Test void researchFillCannotRelabelMatureCandidatesToBypassTwentyPercentCap(){
        List<Map<String,Object>> rows=new ArrayList<>();String[] families={"益智","消除","塔防","跑酷","解谜","模拟经营"};
        for(int d=0;d<7;d++)for(int i=0;i<10;i++)rows.add(row(d+1,"popularity","WECHAT","SYNTHETIC补位"+i,"发行方",day.minusDays(d),i+1,families[i%families.length]));
        var result=service.evaluate(rows,day,cutoff,5,0,Set.of());var selected=allItems(result);
        assertEquals(1,selected.size());
        assertEquals("BENCHMARK",selected.getFirst().get("allocatedSlot"));
        assertTrue(selected.stream().allMatch(r->Boolean.FALSE.equals(r.get("formalEligible"))));
    }

    @Test void productionResearchFillPrefersAvailableOverseasEvidenceForCoverage(){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int d=0;d<7;d++)for(int i=0;i<8;i++)rows.add(row(d+1,"popularity","WECHAT","SYNTHETIC国内"+i,"发行方",day.minusDays(d),i+1,new String[]{"益智","消除","塔防","跑酷","解谜","模拟经营","棋牌","音乐"}[i]));
        for(int d=1;d<=6;d++){
            var overseas=row(900+d,"top-free","GOOGLE_PLAY","SYNTHETIC海外","Overseas Publisher",day.minusDays(d),12,"未知");
            overseas.put("region","us");overseas.put("external_id","com.synthetic.overseas");rows.add(overseas);
            var another=new LinkedHashMap<>(overseas);another.put("region","de");another.put("observation_id",1900+d);rows.add(another);
        }
        var result=service.evaluate(rows,day,cutoff,5,0,Set.of());
        var foreign=allItems(result).stream().filter(r->"GOOGLE_PLAY".equals(r.get("platform"))).toList();
        assertEquals(1,foreign.size(),"same package in multiple countries occupies one seat");
        var lead=foreign.getFirst();assertEquals("RESEARCH_FLEX",lead.get("allocatedSlot"));assertFalse((boolean)lead.get("formalEligible"));
        assertNull(lead.get("currentRank"));assertEquals("MISSING_ON_DATE",lead.get("currentState"));assertEquals(day.minusDays(1).toString(),lead.get("latestObservedDate"));
        assertTrue(lead.get("strictExclusionReason").toString().contains("截止日无观察"));assertFalse(lead.get("whyNow").toString().contains("截止日第"));
        assertEquals("未知",lead.get("family"));assertTrue(lead.get("gaps").toString().contains("玩法待识别"));
        assertTrue(allItems(service.evaluate(rows,day.plusDays(1),cutoff,5)).stream().noneMatch(r->"GOOGLE_PLAY".equals(r.get("platform"))),"two-day stale evidence never fills");
        var onlyOne=rows.stream().filter(r->!"GOOGLE_PLAY".equals(r.get("platform"))||day.minusDays(1).equals(r.get("business_date"))).toList();
        assertTrue(allItems(service.evaluate(onlyOne,day,cutoff,5)).stream().noneMatch(r->"GOOGLE_PLAY".equals(r.get("platform"))),"one observation cannot fill overseas quota");
        // Today's chart exists but the prior game is absent: don't confuse drop-out with delayed source.
        for(String region:List.of("us","de")){
            var now=row(3000,"top-free","GOOGLE_PLAY","SYNTHETIC其他","Other",day,1,"未知");now.put("external_id","com.synthetic.other");now.put("region",region);rows.add(now);
        }
        assertTrue(allItems(service.evaluate(rows,day,cutoff,5)).stream().noneMatch(r->"GOOGLE_PLAY".equals(r.get("platform"))));
    }

    @Test void sparseEndpointRiseDoesNotDisplaceContinuousEvidenceAndDoesNotFillQuota(){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int d=0;d<7;d++)rows.add(row(d+1,"popularity","WECHAT","SYNTHETIC连续观察","发行方",day.minusDays(d),18+d/3,"塔防"));
        for(int d:new int[]{0,4,5})rows.add(row(20+d,"most_played","WECHAT","SYNTHETIC稀疏跳升","发行方",day.minusDays(d),d==0?87:98,"塔防"));
        var result=service.evaluate(rows,day,cutoff,5);
        assertEquals(List.of("SYNTHETIC连续观察"),items(result).stream().map(r->r.get("name")).toList());
        assertTrue(audit(result).stream().filter(r->r.get("name").equals("SYNTHETIC稀疏跳升")).allMatch(r->r.get("exclusionReason").toString().contains("连续观察不足")));
        rows.removeIf(r->r.get("name").equals("SYNTHETIC连续观察")&&r.get("business_date").equals(day.minusDays(1)));
        assertTrue(items(service.evaluate(rows,day,cutoff,5)).isEmpty(),"six days but yesterday missing must remain observation only");
    }
    @Test void minorLowRankMovementIsNotOpportunityAndRecentReversalIsDisclosed(){
        List<Map<String,Object>> rows=new ArrayList<>();
        int[] main={22,19,14,11,6,6,8};int[] other={15,16,18,20,23,24,25};
        for(int i=0;i<7;i++){
            rows.add(row(i+1,"most_played","WECHAT","SYNTHETIC增长回落","发行方",day.minusDays(6-i),main[i],"消除"));
            rows.add(row(20+i,"popularity","WECHAT","SYNTHETIC增长回落","发行方",day.minusDays(6-i),other[i],"消除"));
            rows.add(row(i+1,"most_played","WECHAT","SYNTHETIC低位波动","发行方",day.minusDays(6-i),i==6?31:33,"塔防"));
        }
        var result=service.evaluate(rows,day,cutoff,5);assertEquals(1,items(result).size());var selected=items(result).getFirst();
        assertEquals("RANK_GROWTH",selected.get("signalType"));assertTrue(selected.get("whyNow").toString().contains("#6回落到#8"));assertTrue(selected.get("whyNow").toString().contains("#15到#25"));
        assertTrue(audit(result).stream().filter(r->r.get("name").equals("SYNTHETIC低位波动")).allMatch(r->r.get("exclusionReason").toString().contains("未达到研究信号门槛")));
    }
    @Test void savedReportsReadFrozenLeadsAndLegacyReportsNeverQueryCurrentCaptures()throws Exception{
        var jdbc=mock(JdbcTemplate.class);var mapper=new ObjectMapper().findAndRegisterModules();
        var reports=new WeeklyReportService(jdbc,mapper);
        var leads=service.evaluate(List.of(row(1,"popularity","WECHAT","SYNTHETIC冻结名称","发行方",day,1,"益智")),day,cutoff,5);
        var saved=new LinkedHashMap<String,Object>();saved.put("research_snapshot",mapper.writeValueAsString(Map.of("captureResearchLeads",leads)));
        when(jdbc.queryForMap("SELECT * FROM weekly_report WHERE id=?",9L)).thenReturn(saved);
        var first=reports.report(9L);var frozen=mapper.writeValueAsString(first.get("researchLeads"));
        // A subsequent evaluator run sees different observations; reading the old report must not use them.
        service.evaluate(List.of(row(2,"popularity","WECHAT","SYNTHETIC新名称","发行方",day,1,"消除")),day,cutoff,5);
        assertEquals(frozen,mapper.writeValueAsString(reports.report(9L).get("researchLeads")));
        saved.put("research_snapshot","{\"algorithmVersion\":\"legacy\"}");
        assertFalse(reports.report(9L).containsKey("researchLeads"));
        assertTrue(mockingDetails(jdbc).getInvocations().stream().allMatch(i->i.getArguments()[0].toString().contains("weekly_report")||i.getArguments()[0].toString().contains("report_item")),"read must never recompute from live captures");
    }
    @Test void douyinExplicitDescriptionsQualifyWithoutInventingMetricOrIaa()throws Exception{
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<7;i++){
            var r=row(i+1,"popularity","DOUYIN","SYNTHETIC描述候选","发行方",day.minusDays(6-i),19-i,"未知");
            r.put("source_fields","{\"tags\":[],\"descriptionRaw\":\"画风简约的挪车消除小游戏，DAU百万为来源自述\"}");rows.add(r);
        }
        var selected=items(service.evaluate(rows,day,cutoff,5)).getFirst();
        assertEquals("消除",selected.get("family"));assertEquals("UNKNOWN",selected.get("monetization"));assertEquals(false,selected.get("formalEligible"));
        assertTrue(selected.get("gameplayEvidence").toString().contains("SOURCE_DESCRIPTION"));
        for(var r:rows)r.put("source_fields","{\"tags\":[],\"descriptionRaw\":\"DAU百万\"}");
        assertTrue(items(service.evaluate(rows,day,cutoff,5)).isEmpty());
    }
    @Test void lateArrivalOfOlderMaterialCannotReplaceNewerCapture(){
        var older=row(1,"popularity","WECHAT","SYNTHETIC旧材料","发行方",day,1,"消除");
        older.put("captured_at",cutoff.minusHours(4));older.put("available_at",cutoff.minusMinutes(1));
        var newer=row(2,"popularity","WECHAT","SYNTHETIC新材料","发行方",day,2,"消除");
        var result=service.evaluate(List.of(older,newer),day,cutoff,5);
        assertEquals("SYNTHETIC新材料",audit(result).getFirst().get("name"));assertEquals(1,audit(result).size());
    }
    List<Map<String,Object>> opportunityRows(int age,int[] ranks){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int d=0;d<=age+3;d++){
            long id=1000+d;
            for(int i=0;i<5;i++)rows.add(row(id,"popularity","WECHAT","SYNTHETIC基线"+i,"基线发行方",day.minusDays(d),i+60,"塔防"));
            if(d<=age)rows.add(row(id,"popularity","WECHAT","SYNTHETIC新增","新发行方",day.minusDays(d),ranks[age-d],"消除"));
        }
        return rows;
    }
    Map<String,Object> named(Map<String,Object> result,String name){return audit(result).stream().filter(r->name.equals(r.get("name"))).findFirst().orElseThrow();}
    @Test void newEntryNeedsPriorCompleteAbsenceButNotFiveDaysOfDelay(){
        var rows=opportunityRows(1,new int[]{75,58});var r=service.evaluate(rows,day,cutoff,5);var lead=named(r,"SYNTHETIC新增");
        assertEquals(true,lead.get("selected"));assertEquals("NEW_ENTRY",lead.get("allocatedSlot"));assertEquals(2,lead.get("observationDays"));assertEquals("UNVERIFIED",lead.get("noveltyStatus"));
        var entry=(Map<?,?>)lead.get("entryEvidence");assertEquals("OBSERVED_CHART_ENTRY",entry.get("status"));assertEquals(3,((List<?>)entry.get("priorCompleteAbsence")).size());
        rows.stream().filter(x->x.get("business_date").equals(day.minusDays(2))).forEach(x->x.put("completeness","PARTIAL"));
        var blocked=named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增");assertEquals(false,blocked.get("selected"));assertEquals("NEWNESS_UNKNOWN",((Map<?,?>)blocked.get("entryEvidence")).get("status"));
    }
    @Test void firstCaptureAndRenamedUnknownPublisherCannotBecomeNewEntry(){
        var rows=opportunityRows(1,new int[]{75,58});rows.removeIf(x->LocalDate.parse(x.get("business_date").toString()).isBefore(day.minusDays(1)));
        assertFalse((boolean)named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增").get("selected"));
        rows=opportunityRows(1,new int[]{75,58});rows.stream().filter(x->x.get("name").equals("SYNTHETIC新增")).forEach(x->x.put("developer",null));
        assertTrue(items(service.evaluate(rows,day,cutoff,5)).stream().noneMatch(x->x.get("name").equals("SYNTHETIC新增")));
    }
    @Test void accelerationUsesSameChartDenominatorAndRejectsReversalOrSmallCohort(){
        var rows=opportunityRows(4,new int[]{95,92,90,85,65});var lead=named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增");
        assertEquals("ACCELERATOR",lead.get("allocatedSlot"));var m=(Map<?,?>)lead.get("momentum");assertEquals(25,m.get("twoDayGain"));assertEquals(15,m.get("acceleration"));assertEquals(6,m.get("comparableCount"));assertEquals(1,m.get("withinChartOrder"));
        rows.removeIf(x->x.get("name").toString().startsWith("SYNTHETIC基线")&&!x.get("name").equals("SYNTHETIC基线0"));
        assertNotEquals("ACCELERATOR",named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增").get("portfolioGroup"));
        rows=opportunityRows(4,new int[]{95,92,90,60,65});assertNotEquals("ACCELERATOR",named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增").get("portfolioGroup"));
    }
    @Test void knownReturnAndLateBaselineNeverCountAsNew(){
        var rows=opportunityRows(1,new int[]{75,58});rows.add(row(2000,"popularity","WECHAT","SYNTHETIC新增","新发行方",day.minusDays(12),66,"消除"));
        var lead=named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增");assertEquals("REENTRY",((Map<?,?>)lead.get("entryEvidence")).get("status"));assertNotEquals("NEW_ENTRY",lead.get("portfolioGroup"));
        rows=opportunityRows(1,new int[]{75,58});rows.stream().filter(x->x.get("business_date").equals(day.minusDays(3))).forEach(x->x.put("available_at",cutoff.plusMinutes(1)));
        assertNotEquals("NEW_ENTRY",named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增").get("portfolioGroup"));
    }
    @Test void formalBudgetCannotCrowdOutEarlyResearchAndDuplicateIsExcluded(){
        List<Map<String,Object>> selected=new ArrayList<>();for(int i=0;i<5;i++)selected.add(new LinkedHashMap<>(Map.of("id",i,"selected",true,"eligibleForValidation",true)));
        var run=new LinkedHashMap<String,Object>();run.put("selected",selected);run.put("rows",selected);run.put("diagnostic","SYNTHETIC");WeeklyReportService.reserveEarlyOpportunitySlots(run);
        assertEquals(1,((List<?>)run.get("selected")).size());assertEquals(List.of(0),run.get("selectedIds"));assertEquals(false,selected.get(4).get("selected"));assertEquals(true,selected.get(4).get("eligibleForValidation"));
        var rows=opportunityRows(1,new int[]{75,58});var r=service.evaluate(rows,day,cutoff,4,1,Set.of("WECHAT:SYNTHETIC新增"));
        assertTrue(items(r).isEmpty());assertTrue(named(r,"SYNTHETIC新增").get("exclusionReason").toString().contains("正式精选"));
    }
    @Test void twoNewEntrySeatsReplaceTheOldExtraEarlySeatAndOrderingIsStable(){
        var rows=opportunityRows(1,new int[]{75,58});
        for(int i=0;i<3;i++)for(int d=0;d<=1;d++)rows.add(row(1000+d,"popularity","WECHAT","SYNTHETIC更多"+i,"发行方",day.minusDays(d),d==1?80:50+i,"益智"));
        var result=service.evaluate(rows,day,cutoff,5);assertEquals(2,items(result).size());assertEquals(Set.of("NEW_ENTRY"),new HashSet<>(items(result).stream().map(r->r.get("allocatedSlot")).toList()));
        var keys=items(result).stream().map(r->r.get("leadKey")).toList();Collections.shuffle(rows,new Random(7));assertEquals(keys,items(service.evaluate(rows,day,cutoff,5)).stream().map(r->r.get("leadKey")).toList());
    }

    @Test void otherParserOrOtherChartCannotSupplyMissingEntryBaseline(){
        var rows=opportunityRows(1,new int[]{75,58});rows.stream().filter(x->x.get("business_date").equals(day.minusDays(3))).forEach(x->x.put("parser_version","SYNTHETIC-other-parser"));
        assertNotEquals("NEW_ENTRY",named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增").get("portfolioGroup"));
        rows=opportunityRows(4,new int[]{95,92,90,85,65});rows.stream().filter(x->x.get("name").toString().startsWith("SYNTHETIC基线")).forEach(x->x.put("chart_key","most_played"));
        assertNotEquals("ACCELERATOR",named(service.evaluate(rows,day,cutoff,5),"SYNTHETIC新增").get("portfolioGroup"));
    }

    @Test void fullPortfolioUsesOneBenchmarkTwoNewEntriesAndTwoAccelerators(){
        var rows=opportunityRows(1,new int[]{75,58});
        var twins=new ArrayList<Map<String,Object>>();
        for(var r:rows)if("SYNTHETIC新增".equals(r.get("name"))){var copy=new LinkedHashMap<>(r);copy.put("name","SYNTHETIC新增2");copy.put("rank_position",((Number)r.get("rank_position")).intValue()+1);twins.add(copy);}
        rows.addAll(twins);
        var acceleration=opportunityRows(4,new int[]{95,92,90,85,65});
        var more=new ArrayList<Map<String,Object>>();
        for(var r:acceleration){r.put("chart_key","most_played");r.put("observation_id",((Number)r.get("observation_id")).longValue()+5000);
            if("SYNTHETIC新增".equals(r.get("name"))){r.put("name","SYNTHETIC加速1");r.put("source_fields","{\"tags\":[\"塔防\"]}");var copy=new LinkedHashMap<>(r);copy.put("name","SYNTHETIC加速2");copy.put("rank_position",((Number)r.get("rank_position")).intValue()-1);more.add(copy);}
            if("SYNTHETIC基线0".equals(r.get("name")))for(int i=5;i<9;i++){var copy=new LinkedHashMap<>(r);copy.put("name","SYNTHETIC基线"+i);copy.put("rank_position",60+i);more.add(copy);}
        }
        acceleration.addAll(more);rows.addAll(acceleration);
        for(int d=0;d<7;d++)rows.add(row(9000+d,"top-free","IOS","SYNTHETIC成熟","发行方",day.minusDays(d),5,"跑酷"));
        var result=service.evaluate(rows,day,cutoff,5);var selected=allItems(result);
        assertEquals(5,selected.size());
        assertEquals(1,selected.stream().filter(r->"BENCHMARK".equals(r.get("allocatedSlot"))).count());
        assertEquals(2,selected.stream().filter(r->"NEW_ENTRY".equals(r.get("allocatedSlot"))).count());
        assertEquals(2,selected.stream().filter(r->"ACCELERATOR".equals(r.get("allocatedSlot"))).count());
        assertTrue(selected.stream().noneMatch(r->"EARLY_FLEX".equals(r.get("allocatedSlot"))));
        var allocation=(List<Map<String,Object>>)result.get("portfolio");
        assertEquals(List.of(1,2,2),allocation.stream().filter(r->!"RESEARCH_FLEX".equals(r.get("group"))).map(r->r.get("target")).toList());
        var withFormal=allItems(service.evaluate(rows,day,cutoff,4,1,Set.of("product:formal")));
        assertEquals(4,withFormal.size());assertTrue(withFormal.stream().noneMatch(r->"BENCHMARK".equals(r.get("allocatedSlot"))));
    }

}
