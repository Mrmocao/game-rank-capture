package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RankingCaptureTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC);
    final CaptureRequest request = new CaptureRequest("apple-games", "cn", 100, null);
    JdbcTemplate jdbc; RankingCaptureStore store;
    @BeforeEach void database() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        String migration;
        try (var input = getClass().getResourceAsStream("/db/migration/V13__external_ranking_capture.sql")) {
            migration = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace(" COLLATE utf8mb4_bin", "");
        }
        try (var connection = ds.getConnection()) { ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8))); }
        store = new RankingCaptureStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), mapper, clock);
    }
    String json(String name, String date) {
        return """
            {"feed":{"id":{"label":"https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json"},
            "updated":{"label":"%sT12:00:00+08:00"},"entry":[{"id":{"attributes":{"im:id":"123"}},"im:name":{"label":"%s"},"category":{"attributes":{"im:id":"6014"}}}]}}
            """.formatted(date, name);
    }
    RankingCaptureRunner runner(String raw) {
        return new RankingCaptureRunner(store, mapper, directory, clock,
                url -> new RawMaterial(url, clock.instant(), "synthetic-test", raw.getBytes(StandardCharsets.UTF_8)));
    }
    @SuppressWarnings("unchecked") Map<String, Object> first(Map<String, Object> result) { return ((List<Map<String, Object>>)result.get("attempts")).getFirst(); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    @Test void replayReusesObservationButKeepsBothRunsAndTenantIsolation() {
        var runner = runner(json("Fixture", "2026-11-02"));
        var one = first(runner.run("a", List.of(request))); var two = first(runner.run("a", List.of(request)));
        assertEquals("PARTIAL", one.get("status")); assertEquals("CURRENT", one.get("freshness"));
        assertEquals(false, one.get("reused")); assertEquals(true, two.get("reused"));
        assertEquals(one.get("observationId"), two.get("observationId"));
        assertEquals(2, count("external_capture_run")); assertEquals(1, count("external_ranking_entry"));
        assertThrows(CaptureException.class, () -> store.observation("b", ((Number)one.get("observationId")).longValue()));
        runner.run("b", List.of(request)); assertEquals(2, count("external_ranking_observation"));
    }
    @Test void rejectedEvidenceIsHiddenFromObservationQueriesButRetainedForAudit() {
        var result = first(runner(json("Fixture", "2026-11-02")).run("a", List.of(request)));
        long id = ((Number) result.get("observationId")).longValue();
        assertEquals(1, store.observations("a", null, null, null, null).size());
        jdbc.update("UPDATE external_capture_run SET status='FAILED',stage='EVIDENCE_REJECTED',error_code='EVIDENCE_LOCATOR_INVALID' WHERE id=?", result.get("runId"));
        assertTrue(store.observations("a", null, null, null, null).isEmpty());
        assertEquals("OBSERVATION_NOT_FOUND", assertThrows(CaptureException.class, () -> store.observation("a", id)).code());
        assertEquals(1, count("external_ranking_entry"));
        assertEquals("EVIDENCE_LOCATOR_INVALID", store.runs("a", null).getFirst().get("error_code"));
    }
    @Test void sourceRevisionAppendsAndOldObservationSurvives() {
        var one = first(runner(json("Fixture before", "2026-11-02")).run("a", List.of(request)));
        var two = first(runner(json("Fixture after", "2026-11-02")).run("a", List.of(request)));
        assertNotEquals(one.get("observationId"), two.get("observationId"));
        assertEquals("Fixture before", jdbc.queryForObject("SELECT name FROM external_ranking_entry WHERE observation_id=?", String.class, one.get("observationId")));
        assertEquals(2, count("external_ranking_observation"));
    }
    @Test void malformedResponseLeavesRawEvidenceAndFailureWithoutObservation() throws Exception {
        var result = first(runner("<html>login</html>").run("a", List.of(request)));
        assertEquals("FAILED", result.get("status")); assertEquals(0, count("external_ranking_observation"));
        var row = jdbc.queryForMap("SELECT status,raw_locator,raw_sha256,active_scope FROM external_capture_run");
        assertEquals("FAILED", row.get("status")); assertNull(row.get("active_scope"));
        assertEquals("<html>login</html>", Files.readString(directory.resolve(row.get("raw_locator").toString())));
        assertNotNull(row.get("raw_sha256"));
    }
    @Test void entryWriteFailureRollsBackObservationAndReleasesLease() {
        var result = first(runner(json("x".repeat(501), "2026-11-02")).run("a", List.of(request)));
        assertEquals("FAILED", result.get("status")); assertEquals(0, count("external_ranking_observation"));
        assertEquals(0, count("external_ranking_entry"));
        assertEquals("PARTIAL", first(runner(json("Fixture", "2026-11-02")).run("a", List.of(request))).get("status"));
    }
    @Test void oldSourceDateIsStoredAsStaleAndNeverRelabeledToday() {
        var result = first(runner(json("Fixture", "2026-11-01")).run("a", List.of(request)));
        assertEquals("STALE", result.get("freshness")); assertEquals(LocalDate.of(2026, 11, 1), result.get("businessDate"));
        assertTrue(store.observations("a", "IOS", LocalDate.of(2026, 11, 2), null, null).isEmpty());
    }
    @Test void activeRunBlocksSameScopeAndExpiredRunCanRecover() {
        String url = "https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json";
        String active = store.begin("a", UUID.randomUUID().toString(), request, url);
        assertEquals("COLLECTION_RUNNING", assertThrows(CaptureException.class, () -> store.begin("a", UUID.randomUUID().toString(), request, url)).code());
        jdbc.update("UPDATE external_capture_run SET lease_until=? WHERE id=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)), active);
        store.begin("a", UUID.randomUUID().toString(), request, url);
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT status FROM external_capture_run WHERE id=?", String.class, active));
    }
    @Test void badScopeIsRejectedBeforeAnyNetworkOrDatabaseRun() {
        AtomicInteger calls = new AtomicInteger();
        var runner = new RankingCaptureRunner(store, mapper, directory, clock, url -> { calls.incrementAndGet(); throw new AssertionError(); });
        assertThrows(CaptureException.class, () -> runner.run("a", List.of(new CaptureRequest("unknown", "cn", 100, null))));
        assertThrows(CaptureException.class, () -> runner.run("a", List.of(request, request)));
        assertEquals(0, calls.get()); assertEquals(0, count("external_capture_run"));
    }
    @Test void failureIsIsolatedAcrossSourcesAndSamePageFailureIsCached() {
        AtomicInteger calls = new AtomicInteger();
        var runner = new RankingCaptureRunner(store, mapper, directory, clock, url -> {
            calls.incrementAndGet(); if (url.getHost().equals("rank.gravity-engine.com")) throw new CaptureException("SOURCE_HTTP_403", "Denied");
            return new RawMaterial(url, clock.instant(), "synthetic-test", json("Fixture", "2026-11-02").getBytes(StandardCharsets.UTF_8));
        });
        var result = runner.run("a", CaptureSources.daily(100));
        assertEquals(7L, result.get("failedCount")); assertEquals(1, count("external_ranking_observation"));
        assertEquals(8, count("external_capture_run")); assertEquals(3, calls.get());
    }
    @Test void restEndpointRunsAndReturnsQueryableEvidenceWithoutBusinessTables() throws Exception {
        var controller = new com.youkia.iaa.api.RankingCaptureController(runner(json("Fixture", "2026-11-02")), store, "a");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new com.youkia.iaa.api.ApiExceptionHandler()).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/ranking-capture/run")
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of("requests", List.of(request)))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.attempts[0].status").value("PARTIAL"))
                .andReturn().getResponse();
        long id = mapper.readTree(response.getContentAsByteArray()).path("data").path("attempts").get(0).path("observationId").asLong();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/ranking-capture/observations/" + id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.bundle_json.observation.entries[0].name").value("Fixture"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/ranking-capture/observations").param("date", "2000-01-01"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/ranking-capture/run")
                        .contentType("application/json").content("{\"requests\":[null]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/ranking-capture/run")
                        .contentType("application/json").content("{\"requests\":[{\"sourceKey\":\"unknown\",\"region\":\"cn\",\"requestedTopN\":100,\"chartKey\":\"top-free\"}]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
    }
}
