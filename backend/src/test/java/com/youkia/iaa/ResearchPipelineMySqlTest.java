package com.youkia.iaa;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.youkia.iaa.api.*;
import com.youkia.iaa.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC transactions, all synthetic writes rolled back. Never call external AI or sources. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MYSQL_TESTS",matches="true")
class ResearchPipelineMySqlTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    record Ctx(JdbcTemplate jdbc,TransactionTemplate tx){}
    Ctx context()throws Exception{
        var env=new HashMap<String,String>();for(String l:Files.readAllLines(Path.of("../.env"))){int i=l.indexOf('=');if(i>0&&!l.startsWith("#"))env.put(l.substring(0,i),l.substring(i+1));}
        var ds=new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));
        return new Ctx(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }
    ObjectNode packet(LocalDate day,boolean event)throws Exception{
        var b=mapper.createObjectNode();b.put("actor","V22事务回滚测试");b.put("market","CN");b.put("effectiveDate",day.toString());b.put("validUntil",day.plusDays(30).toString());b.put("family","SYNTHETIC测试家族");
        b.set("evidence",mapper.valueToTree(List.of(Map.of("id","SYNTHETIC-E","kind","internal_test","locator","synthetic://rollback-test","excerpt","仅用于事务回滚集成验证，不是真实市场证据","originFamily","SYNTHETIC","permission","internal_test","observedDate",day.toString()))));
        b.set("assessment",mapper.valueToTree(Map.of("identity",true,"identityEvidence",List.of("SYNTHETIC-E"),"gameplay",true,"gameplayEvidence",List.of("SYNTHETIC-E"),"unresolvedEvent",event,"eventEvidence",List.of("SYNTHETIC-E"),"mode","unknown")));
        return b;
    }
    @Test void actualEvidenceReportSelectionFeedbackAndFreeze()throws Exception{
        var ctx=context();ctx.tx.executeWithoutResult(tx->{try{
            var j=ctx.jdbc;var service=new ResearchService(j,mapper);var reports=new WeeklyReportService(j,mapper);
            var version=j.queryForMap("SELECT v.id,v.product_id FROM platform_version v WHERE v.platform='IOS' AND v.external_id NOT LIKE 'demo-%' LIMIT 1");
            long v=((Number)version.get("id")).longValue(),product=((Number)version.get("product_id")).longValue();
            LocalDate day=LocalDate.parse(j.queryForObject("SELECT MAX(business_date) FROM ranking_snapshot WHERE status='PUBLISHED'",String.class));
            String oldReports=mapper.writeValueAsString(j.queryForList("SELECT id,input_sha256,candidate_audit FROM weekly_report"));
            var p=packet(day,true);long packetId=((Number)service.importPacket(v,p).get("id")).longValue();
            assertEquals(true,service.importPacket(v,p).get("reused"));
            var bad=p.deepCopy();bad.withObject("/assessment").putArray("identityEvidence").add("MISSING");assertThrows(ApiException.class,()->service.importPacket(v,bad));
            // Known event persists when the next packet loses the event input.
            p.withObject("/assessment").remove("unresolvedEvent");service.importPacket(v,p);
            Map<String,Object> c=new LinkedHashMap<>(Map.of("version_id",v,"product_id",product,"product_name","SYNTHETIC","platform","IOS","region","CN","chart_id",1));
            c.put("history",java.util.stream.IntStream.range(0,14).mapToObj(i->Map.<String,Object>of("rank",18,"state","OBSERVED")).toList());
            var input=service.enrich(c,day,LocalDateTime.now(ZoneOffset.UTC));assertFalse(((List<?>)input.get("knownBlockers")).isEmpty());
            assertEquals("paused",V22RecommendationEngine.evaluate(input,V22RecommendationEngine.Profile.defaults()).get("route"));
            var first=reports.generate(day.minusDays(6),day.plusDays(1));var repeat=reports.generate(day.minusDays(6),day.plusDays(1));
            assertEquals(first.get("id"),repeat.get("id"));assertEquals(true,repeat.get("reused"));assertNotNull(first.get("researchSnapshot"));
            String frozen=mapper.writeValueAsString(first.get("researchSnapshot"));
            p.put("family","SYNTHETIC新家族");service.importPacket(v,p);
            var next=reports.generate(day.minusDays(6),day.plusDays(1));assertNotEquals(first.get("id"),next.get("id"));
            assertEquals(frozen,mapper.writeValueAsString(reports.report(Long.parseLong(first.get("id").toString())).get("researchSnapshot")));
            // No production mutation may be hidden in the source classification.
            var selection=new SelectionService(j,mapper,new ProductQueryService(j));
            var one=selection.create(product,new SelectionRequest("测试甲",v,"SELECTED",day,null,null,"SYNTHETIC"),"V22-TEST-A");
            var two=selection.create(product,new SelectionRequest("测试乙",v,"SELECTED",day,null,null,"SYNTHETIC"),"V22-TEST-B");
            assertNotEquals(one.get("id"),two.get("id"));
            long sid=((Number)one.get("id")).longValue();
            assertThrows(ApiException.class,()->selection.update(sid,new SelectionPatch("",v,"SELECTED",day,null,null,"",0)));
            selection.update(sid,new SelectionPatch("测试甲",v,"COMPLETED",day,day,day,"尚未测试",0));
            assertTrue(service.outcomes(sid).isEmpty(),"制作完成不能自动生成测试通过");
            var outcome=mapper.valueToTree(Map.of("actor","测试甲","hypothesis","SYNTHETIC","protocol","SYNTHETIC预登记协议","context","事务回滚","result","unknown","unknownReason","未开展测试","from",day.toString(),"to",day.toString()));
            service.outcome(sid,"V22-OUTCOME",outcome);service.outcome(sid,"V22-OUTCOME",outcome);assertEquals(1,service.outcomes(sid).size());
            // Earlier evidence cutoff does not read a packet that was registered today.
            var historical=service.enrich(c,day,day.atStartOfDay());assertNull(historical.get("packetId"));
            int missing=j.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('research_profile','research_packet','research_constraint','research_outcome') AND column_comment=''",Integer.class);
            assertEquals(0,missing);
        }catch(Exception e){throw new RuntimeException(e);}finally{tx.setRollbackOnly();}});
    }
    @Test void reviewsAreComputedFromDedupedComparableInputs()throws Exception{
        var ctx=context();var service=new ResearchService(ctx.jdbc,mapper);LocalDate day=LocalDate.of(2026,10,27);
        ObjectNode b=packet(day,false);ArrayNode reviews=b.putArray("reviews");
        for(int i=0;i<5;i++)reviews.add(mapper.valueToTree(Map.of("id","r"+i,"date",day.toString(),"clusterId",i<2?"duplicate":"c"+i,"sourceFamily","one","language","zh","samplingScope","fixed","sentiment",i==4?"mixed":"positive","experienced",true)));
        b.set("reviewSampling",mapper.valueToTree(Map.of("method","time_stratified","comparable",true)));
        var summary=service.opinionSummary(b,day);assertEquals(5,summary.get("rawCount"));assertEquals(4,summary.get("clusters"));assertEquals(3,summary.get("denominator"));assertEquals(1,summary.get("ambiguous"));assertEquals(true,summary.get("comparable"));
        ((ObjectNode)reviews.get(0)).put("sourceFamily","another");assertEquals(false,service.opinionSummary(b,day).get("comparable"));
    }
}
