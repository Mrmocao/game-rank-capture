package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.*;
import com.youkia.iaa.service.AiConfigService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CaptureWorkspaceTest {
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T00:30:00Z"),ZoneOffset.UTC);
    JdbcTemplate jdbc;CaptureWorkspaceStore store;CaptureWorkspaceService workspace;RankingCaptureStore ranking;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");jdbc=new JdbcTemplate(ds);
        for(String file:List.of("V13__external_ranking_capture.sql","V14__capture_workspace.sql","V17__overseas_store_charts.sql"))try(var in=getClass().getResourceAsStream("/db/migration/"+file);var c=ds.getConnection()){
            String sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8).replace(" COLLATE utf8mb4_bin","").replace("UTC_TIMESTAMP(3)","CURRENT_TIMESTAMP(3)");
            ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
        }
        store=new CaptureWorkspaceStore(jdbc,mapper,clock);store.initialize("a");workspace=mock(CaptureWorkspaceService.class);
        when(workspace.run(anyString(),isNull())).thenReturn(Map.of("status","SUCCEEDED","batchId","fixture"));
        ranking=new RankingCaptureStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),mapper,clock);
    }
    @Test void seedsAreIdempotentAndOnlyTenServerChartsEnabled() {
        store.initialize("a");assertEquals(7,store.targets("a").size());assertEquals(10,store.enabledRequests("a").size());
        assertEquals(false,store.schedule("a").get("enabled"));assertEquals(false,store.schedule("a").get("workerOnline"));assertTrue(store.targets("other").isEmpty());
    }
    @Test void candidateCannotExecuteOrBeEnabledAndFixedSourceBindingCannotDrift() {
        var edit=new CaptureWorkspaceStore.TargetEdit("Candidate","https://example.org/games","names","","firecrawl",false,0);
        var row=store.add("a",edit);assertNull(row.get("source_key"));assertThrows(CaptureException.class,()->store.requests(row));
        assertThrows(CaptureException.class,()->store.update("a",(String)row.get("target_key"),new CaptureWorkspaceStore.TargetEdit(edit.name(),edit.url(),edit.fields(),edit.notes(),edit.toolKey(),true,0)));
        assertThrows(CaptureException.class,()->store.update("a","apple-cn",edit));
        assertThrows(IllegalArgumentException.class,()->store.target("other",(String)row.get("target_key")));
    }
    @Test void enabledRemoteTargetsNeverRunOnServerWorker() {
        var target=store.targets("a").stream().filter(t->"appbrain".equals(t.get("source_key"))).findFirst().orElseThrow();
        jdbc.update("UPDATE capture_target_config SET enabled=TRUE WHERE tenant_id='a' AND target_key=?",target.get("target_key"));
        assertEquals("REMOTE",target.get("execution_mode"));
        assertEquals(5,store.requests(target).size());
        assertEquals(10,store.enabledRequests("a").size());
        var service=new com.youkia.iaa.service.MarketCaptureService(jdbc,mapper,"a","unused");
        assertEquals(List.of(target.get("region")),service.remotePlan().get("regions"));
    }
    @Test void editUsesOptimisticLockAndRejectsCredentialUrls() {
        var old=store.target("a","apple-cn");var edit=new CaptureWorkspaceStore.TargetEdit("Updated",(String)old.get("source_url"),"ranks","","http-json",false,0);
        assertEquals(1,store.update("a","apple-cn",edit).get("revision"));assertThrows(CaptureException.class,()->store.update("a","apple-cn",edit));assertEquals(7,store.enabledRequests("a").size());
        assertThrows(IllegalArgumentException.class,()->store.add("a",new CaptureWorkspaceStore.TargetEdit("bad","https://user:password@example.org","","","http-html",false,0)));
    }
    @Test void twiceMeansExactlyEightThirtyAndTwentyThreeBeijing() {
        assertEquals(clock.instant(),CaptureWorkspaceStore.instant(LocalDateTime.of(2026,11,2,0,30)));
        assertEquals(clock.instant(),CaptureWorkspaceStore.instant(Timestamp.from(clock.instant())));
        assertEquals(Instant.parse("2026-11-02T00:30:00Z"),CaptureScheduleEngine.next("0 30 8 * * *;0 0 23 * * *",Instant.parse("2026-11-02T00:29:59Z")));
        assertEquals(Instant.parse("2026-11-02T15:00:00Z"),CaptureScheduleEngine.next("0 30 8 * * *;0 0 23 * * *",clock.instant()));
        assertEquals(Instant.parse("2026-11-03T00:30:00Z"),CaptureScheduleEngine.next("0 30 8 * * *;0 0 23 * * *",Instant.parse("2026-11-02T15:00:00Z")));
    }
    void due(){store.saveSchedule("a",new CaptureWorkspaceStore.ScheduleEdit(true,"twice",0));jdbc.update("UPDATE capture_schedule_config SET next_due_at=? WHERE tenant_id='a'",Timestamp.from(clock.instant()));}
    @Test void disabledHeartbeatDoesNotFetchAndDueSlotRunsOnlyOnce() {
        var engine=new CaptureScheduleEngine(store,workspace);assertEquals(false,engine.tick("a").get("executed"));verifyNoInteractions(workspace);assertEquals(true,store.schedule("a").get("workerOnline"));
        due();assertEquals(true,engine.tick("a").get("executed"));assertEquals(false,engine.tick("a").get("executed"));verify(workspace,times(1)).run("a",null);
        assertEquals(Timestamp.from(Instant.parse("2026-11-02T15:00:00Z")),store.schedule("a").get("next_due_at"));
    }
    @Test void concurrentWorkersCannotClaimSameSlot() throws Exception {
        due();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(workspace.run("a",null)).thenAnswer(inv->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return Map.of("status","SUCCEEDED");});
        var engine=new CaptureScheduleEngine(store,workspace);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->engine.tick("a"));assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(false,engine.tick("a").get("executed"));release.countDown();assertEquals(true,first.get(10,TimeUnit.SECONDS).get("executed"));
        }finally{release.countDown();}verify(workspace,times(1)).run("a",null);
    }
    @Test void scheduleFailureIsRecordedAndReleasesLeaseWithoutRetryStorm() {
        due();when(workspace.run("a",null)).thenThrow(new CaptureException("SOURCE_AUTH_REQUIRED","login"));
        var engine=new CaptureScheduleEngine(store,workspace);engine.tick("a");assertEquals(false,engine.tick("a").get("executed"));
        assertNull(jdbc.queryForMap("SELECT * FROM capture_schedule_config WHERE tenant_id='a'").get("active_token"));
        var result=(JsonNode)store.schedule("a").get("last_result_json");assertEquals("SOURCE_AUTH_REQUIRED",result.path("errorCode").asText());
    }
    String failed(String code){var request=new CaptureRequest("apple-games","cn",100,null);String id=ranking.begin("a",UUID.randomUUID().toString(),request,"https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json");ranking.fail("a",id,code);return id;}
    CaptureRepairService repair(){return new CaptureRepairService(store,workspace,new CaptureRepairAiClient(mapper),mock(AiConfigService.class));}
    @Test void repairIsIdempotentScopedAndDoesNotSendRawEvidence() {
        var repair=repair();String run=failed("PARSE_FAILED");var job=repair.create("a",run);assertEquals(job.get("id"),repair.create("a",run).get("id"));assertEquals(1,repair.list("a").size());
        assertThrows(CaptureException.class,()->repair.create("other",run));assertThrows(CaptureException.class,()->repair.get("other",(String)job.get("id")));
        String context=job.get("context_json").toString();assertFalse(context.contains("browser-sessions"));assertTrue(context.contains("AppleGamesAdapter.java"));
    }
    @Test void loginFailureRequiresHumanAndRecoveryRequiresCompleteRealRetry() {
        var repair=repair();var job=repair.create("a",failed("SOURCE_AUTH_REQUIRED"));String id=(String)job.get("id");assertEquals("NEEDS_LOGIN",job.get("status"));assertThrows(CaptureException.class,()->repair.diagnose("a",id));
        when(workspace.run("a","apple-cn")).thenReturn(Map.of("status","PARTIAL","batchId","partial"));assertEquals("STILL_FAILING",repair.retry("a",id).get("status"));
        when(workspace.run("a","apple-cn")).thenReturn(Map.of("status","SUCCEEDED","batchId","verified"));var done=repair.retry("a",id);assertEquals("RECOVERED",done.get("status"));assertEquals("verified",done.get("retry_batch_id"));
    }
    @Test void successfulRunCannotBeMarkedForRepair() {
        String id=failed("fixture");jdbc.update("UPDATE external_capture_run SET status='SUCCEEDED' WHERE id=?",id);assertThrows(CaptureException.class,()->repair().create("a",id));
    }
}
