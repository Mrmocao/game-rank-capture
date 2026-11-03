package com.youkia.iaa;
import com.youkia.iaa.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CrossPlatformAssessmentTest {
 final LocalDate day=LocalDate.of(2026,11,2);
 Map<String,Object> candidate(String platform,Long product,int rank,int gain,int days,int age,String group){
  Map<String,Object> r=new LinkedHashMap<>();r.put("entityKey",platform+":synthetic");r.put("name","SYNTHETIC同名");r.put("platform",platform);r.put("region","CN");r.put("originFamily",platform.equals("IOS")?"apple":"gravity-engine");
  if(product!=null){r.put("unifiedProductId",product);r.put("unifiedProductName","SYNTHETIC统一产品");}
  r.put("latestRank",rank);r.put("latestObservedDate",day.toString());r.put("observationDays",days);r.put("portfolioGroup",group);r.put("momentum",Map.of("latestDailyGain",gain));r.put("entryEvidence",Map.of("firstSeenIn28Days",day.minusDays(age).toString()));
  List<Map<String,Object>> history=new ArrayList<>();for(int i=0;i<days;i++)history.add(Map.of("date",day.minusDays(i).toString(),"rank",rank+i));r.put("history",history);return r;
 }
 Map<String,Object> cross(Map<String,Object> r){return (Map<String,Object>)r.get("crossPlatform");}
 @Test void equalNamesNeverUnifyOrAddCorroboration(){
  var a=candidate("WECHAT",null,3,1,7,6,"BENCHMARK");var b=candidate("DOUYIN",null,10,2,7,6,"BENCHMARK");
  assertTrue(CrossPlatformAssessment.apply(List.of(a,b),day).isEmpty());assertEquals("IDENTITY_UNCONFIRMED",cross(a).get("type"));assertEquals(1,cross(a).get("currentPlatformCount"));assertEquals(1,((List<?>)cross(a).get("possibleMatches")).size());assertNotEquals(CrossPlatformAssessment.key(a),CrossPlatformAssessment.key(b));
 }
 @Test void confirmedThreePlatformEvidencePreservesRanksAndSingleVendorCount(){
  var a=candidate("WECHAT",1L,3,1,7,8,"BENCHMARK");var b=candidate("DOUYIN",1L,10,2,7,8,"BENCHMARK");var c=candidate("IOS",1L,2,1,7,8,"BENCHMARK");
  var result=CrossPlatformAssessment.apply(List.of(a,b,c),day);assertEquals(1,result.size());var e=cross(a);assertEquals("MULTI_PLATFORM_ESTABLISHED",e.get("type"));assertEquals(3,e.get("currentPlatformCount"));assertEquals(2,e.get("originFamilyCount"));assertEquals(List.of(3,10,2),((List<Map<String,Object>>)e.get("cards")).stream().map(r->r.get("rank")).toList());assertEquals(List.of("GOOGLE_PLAY"),e.get("missingPlatforms"));assertEquals(CrossPlatformAssessment.key(a),CrossPlatformAssessment.key(b));
 }
 @Test void expansionPrioritizedOnlyAfterLocalEntryEligibility(){
  var a=candidate("WECHAT",1L,25,1,7,10,"BENCHMARK");var b=candidate("DOUYIN",1L,45,15,2,1,"NEW_ENTRY");
  CrossPlatformAssessment.apply(List.of(a,b),day);assertEquals("CROSS_PLATFORM_EXPANSION",cross(b).get("type"));assertEquals(0,CrossPlatformAssessment.priority(b));
  b.put("portfolioGroup","OBSERVE");CrossPlatformAssessment.apply(List.of(a,b),day);assertEquals("MULTI_PLATFORM_SIGNAL",cross(b).get("type"));assertEquals("OBSERVE",b.get("portfolioGroup"));
 }
 @Test void divergenceAndMissingDateAreNotPositiveEvidence(){
  var a=candidate("WECHAT",1L,30,2,7,10,"BENCHMARK");var b=candidate("DOUYIN",1L,50,-10,7,10,"BENCHMARK");
  CrossPlatformAssessment.apply(List.of(a,b),day);assertEquals("DIVERGENT",cross(a).get("type"));assertEquals(4,CrossPlatformAssessment.priority(a));
  b.put("latestObservedDate",day.minusDays(1).toString());CrossPlatformAssessment.apply(List.of(a,b),day);assertEquals("SINGLE_PLATFORM",cross(a).get("type"));assertNull(((List<Map<String,Object>>)cross(a).get("cards")).get(1).get("rank"));assertTrue(((List<?>)cross(a).get("missingPlatforms")).contains("DOUYIN"));
 }
 @Test void establishedPlatformsRemoveEarlySeatAndKeepContradiction(){
  var a=candidate("WECHAT",1L,3,2,7,10,"BENCHMARK");var b=candidate("DOUYIN",1L,10,-1,5,4,"ACCELERATOR");
  CrossPlatformAssessment.apply(List.of(a,b),day);assertEquals("BENCHMARK",b.get("portfolioGroup"));assertEquals(true,cross(a).get("hasDivergence"));assertFalse(b.get("whyNow").toString().contains("满足新入榜"));
 }
 @Test void realSelectionDeduplicatesConfirmedProductAndFormalProduct(){
  var fixture=new CaptureResearchLeadTest();List<Map<String,Object>> rows=new ArrayList<>();
  for(int i=0;i<7;i++)for(String platform:List.of("WECHAT","DOUYIN")){
   var r=fixture.row(i+1+(platform.equals("DOUYIN")?100:0),"popularity",platform,"SYNTHETIC不同名"+platform,"发行方",day.minusDays(6-i),12-i,"消除");r.put("unified_product_id",99L);r.put("unified_product_name","SYNTHETIC统一");rows.add(r);
  }
  var result=fixture.service.evaluate(rows,day,fixture.cutoff,5);assertEquals(1,fixture.items(result).size());
  var formal=fixture.service.evaluate(rows,day,fixture.cutoff,4,1,Set.of("product:99"));assertTrue(fixture.items(formal).isEmpty());
 }
 @Test void classificationIsPerSourceAndHeavyDoesNotQualify(){
  var fixture=new CaptureResearchLeadTest();List<Map<String,Object>> rows=new ArrayList<>();
  for(int i=0;i<7;i++)for(String p:List.of("WECHAT","DOUYIN")){var r=fixture.row(i+1+(p.equals("DOUYIN")?100:0),"popularity",p,"SYNTHETIC","发行方",day.minusDays(6-i),5,"未知");r.put("unified_product_id",99L);if(p.equals("WECHAT"))r.put("reviewed_gameplay",Map.of("family","消除"));rows.add(r);}
  var result=fixture.service.evaluate(rows,day,fixture.cutoff,5);assertEquals(1,fixture.items(result).size());assertEquals("WECHAT",fixture.items(result).getFirst().get("platform"));
  for(var r:rows)r.put("reviewed_gameplay",Map.of("family","其他/重度"));assertTrue(fixture.items(fixture.service.evaluate(rows,day,fixture.cutoff,5)).isEmpty());
 }
}
