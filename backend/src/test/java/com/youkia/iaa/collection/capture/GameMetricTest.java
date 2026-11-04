package com.youkia.iaa.collection.capture;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.*;
import com.youkia.iaa.service.SecretCipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GameMetricTest {
 @TempDir Path root;
 final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 final Clock clock=Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"),ZoneOffset.UTC);
 JdbcTemplate jdbc;GameMetricService service;boolean fail=false;int fetches=0;
 @BeforeEach void setup()throws Exception {
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");jdbc=new JdbcTemplate(ds);
  for(String f:List.of("V13__external_ranking_capture.sql","V14__capture_workspace.sql","V17__overseas_store_charts.sql","V19__game_metric_capture.sql"))try(var in=getClass().getResourceAsStream("/db/migration/"+f);var c=ds.getConnection()){
   String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace(" COLLATE utf8mb4_bin","").replace("UTC_TIMESTAMP(3)","CURRENT_TIMESTAMP(3)");ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
  }
  var store=new CaptureWorkspaceStore(jdbc,mapper,clock);store.initialize("a");jdbc.update("UPDATE capture_target_config SET enabled=TRUE WHERE source_key='appbrain'");
  service=new GameMetricService(store,(tenant,target)->{fetches++;if(fail)throw new CaptureException("SOURCE_AUTH_REQUIRED","test");String content=target.source().equals("apple-lookup")?"{\"resultCount\":1,\"results\":[{\"trackId\":123456,\"primaryGenreId\":6014,\"trackName\":\"Fixture\",\"price\":30,\"currency\":\"CNY\"}]}":"<link rel='canonical' href='https://apps.apple.com/cn/app/id123456'><script type='application/ld+json'>{\"@type\":\"SoftwareApplication\",\"name\":\"Fixture\",\"offers\":{\"price\":30,\"priceCurrency\":\"CNY\"}}</script>";return new RawMaterial(target.url(),clock.instant(),"fixture",content.getBytes(StandardCharsets.UTF_8));},root,new SecretCipher("test-key"),true);
 }
 long seed(String platform,String id,String chart)throws Exception {
  String source=platform.equals("IOS")?"apple-games":platform.equals("GOOGLE_PLAY")?"appbrain":"gravity-wechat",region=platform.equals("GOOGLE_PLAY")?"us":"cn";
  jdbc.update("INSERT INTO external_ranking_observation(tenant_id,dedupe_key,source_key,origin_family,source_authority,chart_key,board_name,platform,region,captured_at,available_at,parser_version,completeness,requested_top_n,actual_count,max_observed_rank,contiguous_top_n,source_url,raw_sha256,raw_locator,bundle_json) VALUES('a',?,?,'fixture','OFFICIAL',?,'fixture',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1','PARTIAL',100,1,1,1,'https://example.com',?,'fixture','{}')",UUID.randomUUID().toString(),source,chart,platform,region,"a".repeat(64));
  long observation=jdbc.queryForObject("SELECT MAX(id) FROM external_ranking_observation",Long.class);
  jdbc.update("INSERT INTO external_ranking_entry(observation_id,rank_position,external_id,name,evidence_locator,source_fields) VALUES(?,1,?,'Fixture','fixture','{}')",observation,id);
  jdbc.update("INSERT INTO external_capture_run(id,batch_id,tenant_id,source_key,chart_key,region,requested_top_n,requested_date,status,stage,source_url,observation_id,started_at) VALUES(?,?,'a',?,?,?,100,CURRENT_DATE,'SUCCEEDED','STORED','https://example.com',?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),UUID.randomUUID().toString(),source,chart,region,observation);return observation;
 }
 @Test void deduplicatesAcrossChartsAndPreservesUnknownMiniGameIdentity()throws Exception {
  seed("IOS","123456","free");seed("IOS","123456","grossing");seed("WECHAT",null,"free");seed("WECHAT",null,"grossing");
  assertEquals(3,service.overview("a",null,null,null,1).get("rankedGames"));assertEquals(2,service.enqueue("a",null).get("queued"));assertEquals(0,service.enqueue("a",null).get("queued"));
  assertEquals(0,service.overview("b",null,null,null,1).get("rankedGames"));assertEquals(0,service.enqueue("b",null).get("queued"));
 }
 @Test void successCacheFailureBackoffAndOldEvidenceSurvive()throws Exception {
  seed("IOS","123456","free");service.enqueue("a",null);assertEquals("SUCCEEDED",service.tick("a").get("status"));assertEquals("SUCCEEDED",service.tick("a").get("status"));assertEquals(2,fetches);assertEquals(0,service.enqueue("a",null).get("queued"));
  String id=jdbc.queryForObject("SELECT id FROM capture_metric_job WHERE source_key='apple-detail'",String.class);String success=jdbc.queryForObject("SELECT last_success_id FROM capture_metric_job WHERE id=?",String.class,id);
  jdbc.update("UPDATE capture_metric_job SET due_at=? WHERE id=?",java.sql.Timestamp.from(clock.instant().minusSeconds(1)),id);service.enqueue("a",null);fail=true;assertEquals("FAILED",service.tick("a").get("status"));assertEquals(success,jdbc.queryForObject("SELECT last_success_id FROM capture_metric_job WHERE id=?",String.class,id));assertEquals(0,service.enqueue("a",null).get("queued"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM capture_metric_observation",Integer.class));
  assertThrows(CaptureException.class,()->service.history("b",id));assertFalse(((List<?>)service.history("a",id).get("observations")).isEmpty());assertTrue(Files.walk(root).anyMatch(p->p.getFileName().toString().equals("raw.bin")));
 }
 @Test void inactiveSourcesCancelQueuedWork()throws Exception {
  seed("IOS","123456","free");service.enqueue("a",null);jdbc.update("UPDATE capture_target_config SET enabled=FALSE WHERE source_key='apple-games'");assertEquals(false,service.tick("a").get("executed"));assertEquals(0,fetches);assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM capture_metric_job WHERE status='CANCELLED'",Integer.class));
 }
 @Test void remoteLeaseTenantTimeAndReplayAreEnforced()throws Exception {
  seed("GOOGLE_PLAY","com.example.game","free");service.enqueue("a",null);assertEquals(false,service.tick("a").get("executed"));var job=service.claimRemote("a");assertEquals(true,job.get("available"));assertEquals(false,service.claimRemote("a").get("available"));
  String html="<link rel='canonical' href='https://play.google.com/store/apps/details?id=com.example.game'><script type='application/ld+json'>{\"@type\":\"SoftwareApplication\",\"name\":\"Fixture\",\"offers\":{\"price\":0,\"priceCurrency\":\"USD\"}}</script>";
  var result=new GameMetricService.RemoteResult((String)job.get("jobId"),(String)job.get("leaseToken"),clock.instant().toString(),Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)),null);
  assertThrows(CaptureException.class,()->service.acceptRemote("b",result));assertEquals("SUCCEEDED",service.acceptRemote("a",result).get("status"));assertThrows(CaptureException.class,()->service.acceptRemote("a",result));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM capture_metric_observation",Integer.class));assertEquals(0,fetches);
 }
 @Test void remoteOldMaterialCannotBecomeCurrent()throws Exception {
  seed("GOOGLE_PLAY","com.example.game","free");service.enqueue("a",null);var j=service.claimRemote("a");var r=service.acceptRemote("a",new GameMetricService.RemoteResult((String)j.get("jobId"),(String)j.get("leaseToken"),"2025-01-01T00:00:00Z","eA==",null));assertEquals("METRIC_CAPTURE_TIME",r.get("errorCode"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM capture_metric_observation",Integer.class));
 }
 @Test void configIsRevisionedAndPaidProviderNeverEnabledOrExposesKey() {
  service.saveConfig("a",new GameMetricService.ConfigEdit(true,24,"web-http",0));assertThrows(CaptureException.class,()->service.saveConfig("a",new GameMetricService.ConfigEdit(false,24,"web-http",0)));
  var providers=service.saveProvider("a","appfigures",new GameMetricService.ProviderEdit("fixture","secret-test",false,true,0));assertFalse(providers.toString().contains("secret-test"));assertTrue(providers.stream().noneMatch(p->Boolean.TRUE.equals(p.get("enabled"))));assertFalse(jdbc.queryForObject("SELECT api_key_ciphertext FROM capture_metric_provider",String.class).contains("secret-test"));
  assertThrows(CaptureException.class,()->service.saveProvider("a","appfigures",new GameMetricService.ProviderEdit("","",false,false,0)));
 }
 @Test void observationProjectionIsReadOnlyScopedAndKeepsHistoricalFields()throws Exception {
  long id=seed("IOS","123456","free");jdbc.update("UPDATE external_ranking_entry SET source_fields=? WHERE observation_id=?","{\"priceRaw\":{\"label\":\"¥6.00\"}}",id);
  service.enqueue("a",null);service.tick("a");service.tick("a");
  jdbc.update("UPDATE capture_target_config SET enabled=FALSE WHERE source_key='apple-games'");
  jdbc.update("UPDATE capture_metric_observation SET captured_at=?",java.sql.Timestamp.from(clock.instant().minusSeconds(90000)));
  String before=jdbc.queryForObject("SELECT bundle_json FROM external_ranking_observation WHERE id=?",String.class,id);
  var view=service.observationMetrics("a",id);assertEquals("LATEST_SUPPLEMENT",view.get("mode"));
  var rows=(List<Map<String,Object>>)view.get("rows");assertEquals(1,rows.size());assertEquals("¥6.00",((JsonNode)rows.getFirst().get("existing")).path("priceRaw").path("label").asText());
  var jobs=(List<Map<String,Object>>)rows.getFirst().get("jobs");assertEquals(2,jobs.size());assertTrue(jobs.stream().allMatch(j->Boolean.TRUE.equals(j.get("stale"))));assertFalse(view.toString().contains("lease_token"));
  assertEquals(before,jdbc.queryForObject("SELECT bundle_json FROM external_ranking_observation WHERE id=?",String.class,id));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM capture_metric_observation",Integer.class));
  assertThrows(CaptureException.class,()->service.observationMetrics("b",id));assertThrows(CaptureException.class,()->service.observationMetrics("a",9999));
  long mini=seed("WECHAT","123456","free");var miniRow=((List<Map<String,Object>>)service.observationMetrics("a",mini).get("rows")).getFirst();assertTrue(((List<?>)miniRow.get("jobs")).isEmpty());assertEquals(false,miniRow.get("supported"));
  long google=seed("GOOGLE_PLAY","123456","free");assertTrue(((List<?>)((List<Map<String,Object>>)service.observationMetrics("a",google).get("rows")).getFirst().get("jobs")).isEmpty());
  jdbc.update("UPDATE external_capture_run SET status='FAILED' WHERE observation_id=?",id);assertThrows(CaptureException.class,()->service.observationMetrics("a",id));
 }
}
