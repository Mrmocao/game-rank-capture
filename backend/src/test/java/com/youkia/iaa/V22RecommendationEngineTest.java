package com.youkia.iaa;
import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.service.V22RecommendationEngine;
import com.youkia.iaa.service.V22RecommendationEngine.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class V22RecommendationEngineTest {
    final ObjectMapper mapper=new ObjectMapper();
    JsonNode file(String s)throws Exception{return mapper.readTree(getClass().getResourceAsStream("/v22/"+s));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> cases()throws Exception{
        var rows=(List<Map<String,Object>>)mapper.convertValue(file("weekly-v22-cases.json"),List.class);
        // The published fixture's Q is a prevalidated protocol simulation ONLY.
        // Production never accepts alternativeDemand.passed as a decision.
        rows.forEach(c->{if("Q".equals(c.get("id")))c.put("validatedAlternativeDemand",true);});
        return rows;
    }
    @ParameterizedTest @ValueSource(strings={"baseline","no_reviews","no_ads_gap","no_cost","four_days","no_charts","charts_only","iaa_unknown","all_soft","cached_outage","uncached_outage"})
    void matchesAllEighteenPublishedCases(String scenario)throws Exception{
        var cs=cases();
        for(var c:cs){
            boolean all=Set.of("all_soft","uncached_outage").contains(scenario);
            if(all||Set.of("no_reviews","charts_only").contains(scenario)){c.put("positive",null);c.put("sample",null);}
            if(all||Set.of("no_ads_gap","charts_only").contains(scenario)){c.put("adStage",null);c.put("gapStage",null);}
            if(all||Set.of("no_cost","charts_only").contains(scenario)){c.put("effortLower",null);c.put("effortUpper",null);}
            if(all||scenario.equals("no_charts"))c.put("ranks",new ArrayList<>(Collections.nCopies(14,null)));
            if(all||scenario.equals("charts_only"))c.put("validatedAlternativeDemand",false);
            if(scenario.equals("four_days")){var rs=(List<?>)c.get("ranks");for(int i:List.of(1,4,8,12))rs.set(i,null);}
            if(scenario.equals("iaa_unknown")&&!"mixed".equals(c.get("mode"))){c.put("mode","unknown");c.put("iaaCurrent",false);}
            if(scenario.equals("uncached_outage"))c.put("states",Map.of("ranks","source_error","reviews","source_error","cost","source_error","ads","source_error","gap","source_error"));
        }
        var expected=file("weekly-v22-expected.json").path("scenarios");
        JsonNode ex=null;for(var e:expected)if(e.path("id").asText().equals(scenario))ex=e;
        assertNotNull(ex);
        var result=V22RecommendationEngine.run(cs,Profile.defaults());
        assertEquals(mapper.convertValue(ex.path("selectedIds"),List.class),result.get("selectedIds"),scenario+" selected");
        var actual=(List<Map<String,Object>>)result.get("rows");
        for(var e:ex.path("rows")){
            var a=actual.stream().filter(x->e.path("id").asText().equals(x.get("id"))).findFirst().orElseThrow();
            assertEquals(e.path("route").asText(),a.get("route"),scenario+"/"+e.path("id"));
            JsonNode n=mapper.valueToTree(a);var f=e.path("features");
            assertEquals(f.path("low").asDouble(),n.path("utilityRange").path("lower").asDouble(),1e-8);
            assertEquals(f.path("high").asDouble(),n.path("utilityRange").path("upper").asDouble(),1e-8);
            assertEquals(f.path("coverage").asDouble(),n.path("inputCoverage").asDouble(),1e-8);
            for(int i=0;i<6;i++){var r=n.path("dimensions").path(V22RecommendationEngine.KEYS.get(i));var er=f.path("dimensions").get(i);
                assertEquals(er.path("low").asDouble(),r.path("lower").asDouble(),1e-8);
                assertEquals(er.path("high").asDouble(),r.path("upper").asDouble(),1e-8);}
        }
    }
    @Test void missingZeroAndBadRecordsAreDistinct()throws Exception{
        var cs=cases();var c=cs.getFirst();c.put("adStage",null);
        var unknown=mapper.valueToTree(V22RecommendationEngine.evaluate(c,Profile.defaults()));
        c.put("adStage",0);var zero=mapper.valueToTree(V22RecommendationEngine.evaluate(c,Profile.defaults()));
        assertEquals(100,unknown.at("/dimensions/A/upper").asDouble());assertEquals(0,zero.at("/dimensions/A/upper").asDouble());
        c.put("sample",-1);assertEquals("lead",V22RecommendationEngine.evaluate(c,Profile.defaults()).get("route"));
        var duplicate=V22RecommendationEngine.run(List.of(cs.get(1),cs.get(1),cs.get(2)),Profile.defaults());
        assertTrue(((List<Map<String,Object>>)duplicate.get("rows")).subList(0,2).stream().allMatch(r->"lead".equals(r.get("route"))));
    }
    @Test void invalidConfigAndEmptyData(){
        assertThrows(IllegalArgumentException.class,()->Profile.from(Map.of("weights",List.of(26,15,20,20,10,10))));
        assertThrows(IllegalArgumentException.class,()->Profile.from(Map.of("explorationSlots",9)));
        assertFalse(((List<?>)V22RecommendationEngine.run(List.of(),Profile.defaults()).get("systemTasks")).isEmpty());
    }
    @Test void passedBooleanIsNotProductionProtocol()throws Exception{
        var c=cases().get(16);c.remove("validatedAlternativeDemand");
        assertEquals("lead",V22RecommendationEngine.evaluate(c,Profile.defaults()).get("route"));
    }
    @Test void conflictsAndBiasedSamplesRemainUnknown()throws Exception{
        var c=cases().getFirst();c.put("reviewScope","hot_only");
        assertEquals(0,mapper.valueToTree(V22RecommendationEngine.evaluate(c,Profile.defaults())).at("/dimensions/P/inputCoverage").asDouble());
        c.put("reviewScope","comparable");c.put("states",Map.of("reviews","conflict","cost","not_authorized"));
        var r=mapper.valueToTree(V22RecommendationEngine.evaluate(c,Profile.defaults()));
        assertEquals("conflict",r.at("/dimensions/P/status").asText());assertEquals(100,r.at("/dimensions/F/upper").asDouble());
    }
}
