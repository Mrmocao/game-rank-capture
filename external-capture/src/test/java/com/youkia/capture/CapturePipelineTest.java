package com.youkia.capture;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.adapters.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CapturePipelineTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC);
    private final AppleGamesAdapter apple = new AppleGamesAdapter(mapper);
    private final AppBrainAdapter appbrain = new AppBrainAdapter();
    private final CaptureRequest request = new CaptureRequest("apple-games", "cn", 100, null);
    private final AtomicInteger writes = new AtomicInteger();

    private CapturePipeline pipeline(CaptureSink sink) {
        return new CapturePipeline(List.of(apple, appbrain), url -> { throw new AssertionError("No network in tests"); }, sink, clock);
    }
    private String appleJson(String rows) {
        return """
                {"feed":{"id":{"label":"https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json"},
                "updated":{"label":"2026-10-26T23:30:00-07:00"},"entry":[%s]}}
                """.formatted(rows);
    }
    private String appleRow(String id, String name) {
        return """
                {"id":{"attributes":{"im:id":"%s"}},"im:name":{"label":"%s"},"category":{"attributes":{"im:id":"6014"}}}
                """.formatted(id, name);
    }
    private RawMaterial material(CaptureRequest req, String raw) {
        SourceAdapter adapter = req.sourceKey().equals("apple-games") ? apple : appbrain;
        return new RawMaterial(adapter.sourceUrl(req), clock.instant(), "local-fixture", raw.getBytes(StandardCharsets.UTF_8));
    }
    private Observation.Bundle accept(CaptureRequest req, String raw) throws Exception {
        return pipeline((b, m) -> writes.incrementAndGet()).accept(req, material(req, raw));
    }
    @Test void appleDatesAreSourceFactsAndCoverageDoesNotClaimWholeBoard() throws Exception {
        var bundle = accept(request, appleJson(appleRow("123", "Fixture game")));
        assertEquals(LocalDate.of(2026, 10, 26), bundle.observation().sourceDate());
        assertEquals(LocalDate.of(2026, 10, 27), bundle.observation().businessDate());
        assertEquals("PARTIAL", bundle.coverage().status());
        assertEquals(99, bundle.coverage().missingRanks().size());
        assertEquals(1, bundle.coverage().contiguousTopN());
        assertEquals(1, writes.get());
    }
    @Test void rejectedMaterialNeverReachesOutput() {
        String row = appleRow("123", "Fixture game");
        for (String bad : List.of("", "{}", appleJson(""), appleJson(row + "," + row),
                appleJson(row.replace("6014", "6000")), appleJson(appleRow("", "Game")))) {
            assertThrows(CaptureException.class, () -> accept(request, bad));
        }
        assertEquals(0, writes.get());
    }
    @Test void wrongSourceScopeAndRelabeledDateAreRejected() {
        String json = appleJson(appleRow("123", "Game"));
        var otherCountry = new CaptureRequest("apple-games", "us", 100, null);
        var otherDate = new CaptureRequest("apple-games", "cn", 100, LocalDate.of(2026, 10, 27));
        assertThrows(CaptureException.class, () -> accept(otherCountry, json));
        assertThrows(CaptureException.class, () -> accept(otherDate, json));
        var wrongUrl = new RawMaterial(URI.create("https://example.com"), clock.instant(), "fixture", json.getBytes(StandardCharsets.UTF_8));
        assertThrows(CaptureException.class, () -> pipeline((b, m) -> writes.incrementAndGet()).accept(request, wrongUrl));
        assertEquals(0, writes.get());
    }
    private String appbrainRow(int rank, String id) {
        return "<tr><td class='ranking-rank'>" + rank + "</td><td>=</td><td></td>"
                + "<td class='ranking-app-cell'><a href='/app/game/" + id + "'>Fixture Game</a>"
                + "<div class='ranking-app-cell-creator'><a>Studio</a></div></td>"
                + "<td class='ranking-rating-cell'><span>4.7</span></td><td>2 M</td><td>200 K</td></tr>";
    }
    private String appbrainPage(String rows) {
        return "<link rel='canonical' href='https://www.appbrain.com/stats/google-play-rankings/top_free/game/us'>"
                + "<div class='stats-pagetop-updated'><time>October 27, 2026</time></div>"
                + "<table id='rankings-table'><tbody>" + rows + "</tbody></table>";
    }
    @Test void sparseRanksStaySparseAndEstimatedMetricsStayRaw() throws Exception {
        var req = new CaptureRequest("appbrain", "us", 3, null);
        var bundle = accept(req, appbrainPage(appbrainRow(1, "com.fixture.a") + appbrainRow(3, "com.fixture.b")));
        assertEquals(List.of(2), bundle.coverage().missingRanks());
        assertEquals(3, bundle.coverage().maxObservedRank());
        assertEquals(1, bundle.coverage().contiguousTopN());
        assertEquals("200 K", bundle.observation().entries().getFirst().sourceFields().get("recentInstallsDisplay"));
        assertEquals(true, bundle.observation().entries().getFirst().sourceFields().get("recentInstallsEstimated"));
    }
    @Test void sourceStructureChangesAndDuplicateRanksStopOutput() {
        var req = new CaptureRequest("appbrain", "us", 100, null);
        assertThrows(CaptureException.class, () -> accept(req, "<html>Login required</html>"));
        assertThrows(CaptureException.class, () -> accept(req, appbrainPage(appbrainRow(1, "com.fixture.a").replace("<td>=</td>", ""))));
        assertThrows(CaptureException.class, () -> accept(req, appbrainPage(appbrainRow(1, "com.fixture.a") + appbrainRow(1, "com.fixture.b"))));
        assertEquals(0, writes.get());
    }
    @Test void requestedRangeCompleteDoesNotAssertEndOfBoard() {
        var entries = List.of(new Observation.Entry(1, null, "Game", null, "page:1", Map.of()));
        assertEquals("REQUESTED_RANGE_COMPLETE", Observation.Coverage.calculate(1, entries).status());
        assertEquals(List.of(), Observation.Coverage.calculate(1, entries).missingRanks());
    }
    @Test void originalBytesArePreservedAndOutputsNeverOverwrite(@TempDir Path dir) throws Exception {
        String raw = "\uFEFF" + appleJson(appleRow("123", "=SUM(1)"));
        var material = material(request, raw); var sink = new FileCaptureSink(dir, mapper);
        pipeline(sink).accept(request, material); Path first = sink.lastDirectory();
        assertArrayEquals(material.bytes(), Files.readAllBytes(first.resolve("raw.bin")));
        var manifest = mapper.readTree(Files.readString(first.resolve("bundle.json")));
        assertEquals(material.sha256(), manifest.path("evidence").path("sha256").asText());
        assertTrue(Files.readString(first.resolve("entries.csv")).contains("'=SUM(1)"));
        assertEquals("=SUM(1)", manifest.path("observation").path("entries").get(0).path("name").asText());
        pipeline(sink).accept(request, material);
        assertNotEquals(first, sink.lastDirectory());
        assertTrue(Files.exists(first.resolve("bundle.json")));
    }
    @Test void invalidEncodingAndOversizedMaterialAreRejected() {
        assertThrows(CaptureException.class, () -> new RawMaterial(apple.sourceUrl(request), clock.instant(), "fixture", new byte[RawMaterial.MAX_BYTES + 1]));
        var invalid = new RawMaterial(apple.sourceUrl(request), clock.instant(), "fixture", new byte[] {(byte) 0xc3, 0x28});
        assertThrows(CaptureException.class, invalid::text);
    }
    @Test void futureDatesAndCaptureTimesAreRejected() {
        String json = appleJson(appleRow("123", "Game"));
        assertThrows(CaptureException.class, () -> accept(request, json.replace("2026-10-26", "2026-10-29")));
        var future = new RawMaterial(apple.sourceUrl(request), clock.instant().plusSeconds(1), "fixture", json.getBytes(StandardCharsets.UTF_8));
        assertThrows(CaptureException.class, () -> pipeline((b, m) -> writes.incrementAndGet()).accept(request, future));
        assertEquals(0, writes.get());
    }
    @Test void transportGetsOnlyValidatedSourceUrl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var p = new CapturePipeline(List.of(apple), url -> {
            calls.incrementAndGet(); assertEquals(apple.sourceUrl(request), url);
            return material(request, appleJson(appleRow("123", "Game")));
        }, (b, m) -> writes.incrementAndGet(), clock);
        p.collect(request); assertEquals(1, calls.get()); assertEquals(1, writes.get());
        assertThrows(CaptureException.class, () -> p.collect(new CaptureRequest("apple-games", "xx", 100, null)));
        assertEquals(1, calls.get());
    }
    @Test void unregisteredTransportTargetsAreRejectedBeforeNetwork() {
        var fetcher = new HttpMaterialFetcher(clock);
        for (String url : List.of("http://itunes.apple.com/", "https://127.0.0.1/", "https://user@itunes.apple.com/", "https://itunes.apple.com:443/"))
            assertThrows(CaptureException.class, () -> fetcher.fetch(URI.create(url)));
    }
}
