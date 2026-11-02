package com.youkia.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.adapters.GravityRankingAdapter;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GravityRankingAdapterTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC);
    private String page(String platform, String chart, String title) throws Exception {
        var icons = new ObjectMapper().readTree(getClass().getResourceAsStream("/gravity-podium.json"));
        StringBuilder rows = new StringBuilder();
        for (int rank = 1; rank <= 4; rank++) rows.append("<div class='rank-child-item'><div class='rank-left-item'>")
                .append(rank <= 3 ? "<img src=\"" + icons.get(rank - 1).asText().replace("&", "&amp;").replace("\"", "&quot;") + "\">" : "NO.4")
                .append("</div><span class='rank-app-name'>Fixture game ").append(rank).append("</span>")
                .append("<span class='app-publisher__name'>--</span><div class='rank-tags-row'><span class='categorize'>休闲 · 99名</span></div></div>");
        String label = platform.equals("wechat") ? "微信小游戏" : "抖音小游戏";
        return "<link rel='canonical' href='https://rank.gravity-engine.com/" + platform + "/day/'>"
                + "<a class='platform-tab active'>" + label + "</a><a class='rank-period-switch__button active'>日榜</a>"
                + "<input value='2026-11-02(今天)'><h2 id='rank-heading-" + chart + "'>" + title + "</h2>"
                + "<section class='rank-card' aria-labelledby='rank-heading-" + chart + "'>" + rows
                + "<p>仅展示前20条，登录查看完整榜单（共100条）</p></section>";
    }
    private Observation.Bundle parse(String platform, String chart, String raw) {
        var adapter = new GravityRankingAdapter(platform.toUpperCase(Locale.ROOT));
        var request = new CaptureRequest(adapter.key(), "cn", 100, null, chart);
        var material = new RawMaterial(adapter.sourceUrl(request), clock.instant(), "synthetic-html", raw.getBytes(StandardCharsets.UTF_8));
        return new CapturePipeline(List.of(adapter), url -> { throw new AssertionError("No network"); }, (b, m) -> {}, clock).inspect(request, material);
    }
    @Test void verifiedPodiumIsIndependentOfCategoryRankAndNameIsNotAnIdentity() throws Exception {
        var bundle = parse("wechat", "popularity", page("wechat", "popularity", "人气榜"));
        assertEquals(List.of(1, 2, 3, 4), bundle.observation().entries().stream().map(Observation.Entry::rank).toList());
        assertTrue(bundle.observation().entries().stream().allMatch(row -> row.externalId() == null && row.developer() == null));
        assertEquals("THIRD_PARTY", bundle.observation().entries().getFirst().sourceFields().get("sourceAuthority"));
        assertEquals("PARTIAL", bundle.coverage().status());
        assertEquals(96, bundle.coverage().missingRanks().size());
        assertTrue(bundle.observation().warnings().stream().anyMatch(w -> w.contains("登录")));
    }
    @Test void allSevenChartsHaveSeparateSourceIdentityAndTitles() throws Exception {
        var titles = Map.of("popularity", "热门榜", "bestseller", "畅销榜", "fresh_game", "新游榜", "publisher_heat", "发行人热度榜");
        for (var chart : titles.entrySet()) assertEquals(chart.getValue(), parse("douyin", chart.getKey(), page("douyin", chart.getKey(), chart.getValue())).observation().boardName());
        for (var chart : Map.of("popularity", "人气榜", "bestseller", "畅销榜", "most_played", "畅玩榜").entrySet())
            assertEquals("WECHAT", parse("wechat", chart.getKey(), page("wechat", chart.getKey(), chart.getValue())).observation().platform());
    }
    @Test void unknownPodiumCannotSilentlyBecomeFirstPlace() throws Exception {
        String raw = page("wechat", "popularity", "人气榜").replaceFirst("data:image/svg\\+xml,[^\"]+", "data:image/svg+xml,unknown");
        var error = assertThrows(CaptureException.class, () -> parse("wechat", "popularity", raw));
        assertEquals("RANK_MARKER_CHANGED", error.code());
    }
    @Test void pageScopeDateAndDuplicateRankChangesAreRejected() throws Exception {
        String raw = page("wechat", "popularity", "人气榜");
        for (String bad : List.of(raw.replace("/wechat/day/", "/douyin/day/"), raw.replace("微信小游戏", "抖音小游戏"),
                raw.replace(">日榜<", ">周榜<"), raw.replace("人气榜", "热门榜"), raw.replace("2026-11-02(今天)", ""),
                raw.replace("NO.4", "NO.1"), raw.replace("2026-11-02", "2026-11-03")))
            assertThrows(CaptureException.class, () -> parse("wechat", "popularity", bad));
    }
    @Test void boundedHttpSubscriberCancelsBeforeBufferingOversizeBody() {
        var subscriber = new HttpMaterialFetcher.LimitedBodySubscriber(4);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) {} public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[3])));
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[2])));
        assertTrue(cancelled.get());
        assertThrows(java.util.concurrent.CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
    }
    @Test void transportRejectsNonChartRoutesAndQueries() {
        for (String url : List.of("https://rank.gravity-engine.com/admin", "https://rank.gravity-engine.com/wechat/day/?token=x", "https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6000/json"))
            assertThrows(CaptureException.class, () -> HttpMaterialFetcher.validateUrl(java.net.URI.create(url)));
    }
    @Test void evidenceLocatorResolvesRowsInsideRepeatedWrapperContainers() throws Exception {
        String raw = page("wechat", "popularity", "人气榜");
        var doc = org.jsoup.Jsoup.parse(raw);
        // Production SSR wraps each row separately, so nth-child(rank) on the row is wrong.
        for (var row : doc.select(".rank-child-item")) row.wrap("<div class='row-wrapper'></div>");
        raw = doc.outerHtml(); var bundle = parse("wechat", "popularity", raw);
        var original = org.jsoup.Jsoup.parse(raw);
        for (var entry : bundle.observation().entries()) {
            var resolved = original.select(entry.evidenceLocator());
            assertEquals(1, resolved.size()); assertEquals(entry.name(), resolved.select(".rank-app-name").text());
        }
    }
}
