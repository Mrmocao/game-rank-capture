package com.youkia.iaa;
import com.fasterxml.jackson.databind.*;
import com.youkia.iaa.service.MarketCaptureService;
import com.youkia.iaa.api.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads real source rows; every synthetic selection and snapshot change is rolled back. */
@EnabledIfEnvironmentVariable(named="RUN_LIVE_MYSQL_TESTS",matches="true")
class MarketCaptureMySqlTest {
 final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
 record Context(JdbcTemplate jdbc,TransactionTemplate tx,String tenant){}
 Context context()throws Exception{
  var env=new HashMap<String,String>();for(String l:Files.readAllLines(Path.of("../.env"))){int i=l.indexOf('=');if(i>0&&!l.startsWith("#"))env.put(l.substring(0,i),l.substring(i+1));}
  var ds=new DriverManagerDataSource(env.get("DB_URL"),env.get("DB_USERNAME"),env.get("DB_PASSWORD"));return new Context(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),env.getOrDefault("PROFILE_TENANT_ID","default"));
 }
 MarketCaptureService service(Context c){return new MarketCaptureService(c.jdbc,mapper,c.tenant,"../data/TEST-NO-RECEIPTS");}
 @Test void realProjectionSeparateBoardsMissingDateAndTenant()throws Exception{
  var c=context();var s=service(c);var day=s.latest();assertNotNull(day);
  var coverage=s.catalog(day);assertEquals(30,((List<?>)coverage.get("charts")).size());
  for(String platform:List.of("WECHAT","DOUYIN")){
   var r=s.candidates(day,platform,null);var items=(List<Map<String,Object>>)r.get("items");assertFalse(items.isEmpty());
   assertTrue(items.stream().allMatch(i->platform.equals(i.get("platform"))&&"UNKNOWN".equals(i.get("monetization"))));
   for(var candidate:items)for(var board:(List<Map<String,Object>>)candidate.get("rankings"))assertEquals(7,((List<?>)board.get("history")).size());
  }
  assertEquals(0,s.catalog(LocalDate.of(2000,1,1)).get("rankingEntries"));assertEquals(0,s.candidates(LocalDate.of(2000,1,1),null,null).get("total"));
  var other=new MarketCaptureService(c.jdbc,mapper,"SYNTHETIC-EMPTY-TENANT","../data/NONE");assertNull(other.latest());assertEquals(0,other.candidates(day,null,null).get("total"));
 }
 @Test void selectionsAreMultiPersonFrozenTenantScopedAndIdempotent()throws Exception{
  var c=context();c.tx.executeWithoutResult(tx->{try{
   var s=service(c);var day=s.latest();var r=(Map<String,Object>)((List<?>)s.candidates(day,"DOUYIN",null).get("items")).getFirst();
   var body=mapper.createObjectNode().put("owner","SYNTHETIC回滚甲").put("status","SELECTED").put("note","事务回滚，不是真实选用").put("observationId",((Number)r.get("observationId")).longValue()).put("rank",((Number)r.get("rank")).intValue());
   String key=UUID.randomUUID().toString();var a=s.select(body,key);assertEquals(true,s.select(body,key).get("reused"));
   body.put("owner","SYNTHETIC回滚乙");assertThrows(ApiException.class,()->s.select(body,key));var b=s.select(body,UUID.randomUUID().toString());assertNotEquals(a.get("id"),b.get("id"));
   String frozen=c.jdbc.queryForObject("SELECT source_snapshot FROM source_candidate_selection WHERE id=?",String.class,a.get("id"));
   c.jdbc.update("UPDATE external_ranking_entry SET name='SYNTHETIC改名回滚' WHERE observation_id=? AND rank_position=?",r.get("observationId"),r.get("rank"));
   assertEquals(frozen,c.jdbc.queryForObject("SELECT source_snapshot FROM source_candidate_selection WHERE id=?",String.class,a.get("id")));
   var other=new MarketCaptureService(c.jdbc,mapper,"SYNTHETIC-OTHER","../data/NONE");assertThrows(ApiException.class,()->other.select(body,UUID.randomUUID().toString()));
   assertEquals(0,c.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_candidate_selection' AND column_comment=''",Integer.class));
  }finally{tx.setRollbackOnly();}});
 }
 @Test void supersededObservationStillUsesItsOwnFrozenBundle()throws Exception{
  var c=context();c.tx.executeWithoutResult(tx->{try{
   var rows=c.jdbc.queryForList("""
    SELECT o.id,o.bundle_json FROM external_ranking_observation o
    WHERE o.tenant_id=? AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.observation_id=o.id AND r.status IN ('SUCCEEDED','PARTIAL'))
    AND EXISTS(SELECT 1 FROM external_ranking_observation n WHERE n.tenant_id=o.tenant_id AND n.source_key=o.source_key AND n.chart_key=o.chart_key AND n.region=o.region AND n.business_date=o.business_date AND n.captured_at>o.captured_at
      AND EXISTS(SELECT 1 FROM external_capture_run r WHERE r.observation_id=n.id AND r.status IN ('SUCCEEDED','PARTIAL')))
    LIMIT 1
    """,c.tenant);
   assertFalse(rows.isEmpty(),"real baseline needs an earlier accepted capture");var original=rows.getFirst();
   var bundle=mapper.readTree(original.get("bundle_json").toString());var entry=bundle.path("observation").path("entries").get(0);
   var body=mapper.createObjectNode().put("owner","SYNTHETIC历史回滚").put("observationId",((Number)original.get("id")).longValue()).put("rank",entry.path("rank").asInt());
   var saved=service(c).select(body,UUID.randomUUID().toString());var frozen=mapper.readTree(c.jdbc.queryForObject("SELECT source_snapshot FROM source_candidate_selection WHERE id=?",String.class,saved.get("id")));
   assertEquals(entry.path("name"),frozen.path("name"));assertEquals(bundle.path("evidence").path("sha256"),frozen.path("rawSha256"));assertEquals(body.path("observationId").asLong(),frozen.path("observationId").asLong());
  }catch(Exception e){throw new RuntimeException(e);}finally{tx.setRollbackOnly();}});
 }
}
