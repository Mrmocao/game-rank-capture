package com.youkia.capture.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.youkia.capture.*;
import com.youkia.capture.adapters.GravityRankingAdapter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.*;
import java.time.Clock;
import java.util.*;

/** Persistent authenticated UI transport. Public Apple requests retain the HTTP transport. */
public final class GravityBrowserFetcher implements MaterialFetcher {
    private final Path profile;
    private final String channel;
    private final boolean headless;
    private final Clock clock;
    private final MaterialFetcher publicFetcher;
    public GravityBrowserFetcher(Path profile, String channel, boolean headless, Clock clock, MaterialFetcher publicFetcher) {
        this.profile = profile; this.channel = channel; this.headless = headless; this.clock = clock; this.publicFetcher = publicFetcher;
    }
    @Override public RawMaterial fetch(URI url) throws IOException, InterruptedException {
        return fetch(url, null);
    }
    @Override public RawMaterial fetch(URI url, LocalDate requestedDate) throws IOException, InterruptedException {
        if (!"rank.gravity-engine.com".equals(url.getHost())) return publicFetcher.fetch(url);
        if (!Set.of("https://rank.gravity-engine.com/wechat/day/", "https://rank.gravity-engine.com/douyin/day/").contains(url.toString()))
            throw new CaptureException("SOURCE_URL_REJECTED", "浏览器仅访问固定微信/抖音日榜");
        String stage = "START";
        try (var browser = new BrowserProfile(profile, headless, channel)) {
            Page page = browser.context.newPage();
            stage = "NAVIGATE";
            navigate(page, url);
            stage = "WAIT_CHART";
            page.locator(".rank-card").first().waitFor();
            // SSR initially contains the anonymous preview. Wait for authenticated UI hydration,
            // evidenced by the enabled source-date control, before scrolling rebuilt containers.
            stage = "WAIT_SESSION_UI";
            try {
                page.waitForFunction("() => [...document.querySelectorAll('input')].some(n => /^\\d{4}-\\d{2}-\\d{2}/.test(n.value) && !n.disabled)",
                        null, new Page.WaitForFunctionOptions().setTimeout(15_000));
            } catch (TimeoutError e) {
                if (page.locator("body").innerText().contains("登录查看完整榜单"))
                    throw new CaptureException("SOURCE_AUTH_REQUIRED", "引力引擎登录已失效，请运行 scripts/login-ranking-capture.ps1 重新登录");
                throw e;
            }
            stage = "WAIT_RANK_ROWS";
            waitForRankRows(page, url.getPath().startsWith("/wechat/") ? 3 : 4);
            if (requestedDate != null) {
                stage = "SELECT_SOURCE_DATE";
                selectSourceDate(page, requestedDate);
                waitForRankRows(page, url.getPath().startsWith("/wechat/") ? 3 : 4);
            }
            Instant deadline = clock.instant().plusSeconds(150);
            List<Locator> cards = page.locator(".rank-card").all();
            for (Locator card : cards) {
                stage = "SCROLL_" + card.getAttribute("aria-labelledby");
                int previous = -1, unchanged = 0;
                while (clock.instant().isBefore(deadline)) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int count = card.locator(".rank-child-item").count();
                    if (count >= 100) break;
                    if (count == 0) throw new CaptureException("NO_ENTRIES", "浏览器未显示榜单行");
                    card.locator(".rank-child-item").last().scrollIntoViewIfNeeded();
                    card.locator(".rank-child-item").last().hover();
                    page.mouse().wheel(0, 900);
                    page.waitForTimeout(700);
                    int after = card.locator(".rank-child-item").count();
                    unchanged = after == previous ? unchanged + 1 : 0; previous = after;
                    if (unchanged >= 4) break;
                }
            }
            RawMaterial material = snapshot(page, url, clock);
            stage = "VALIDATE";
            validateComplete(material, clock);
            return material;
        } catch (PlaywrightException e) {
            var network = java.util.regex.Pattern.compile("net::ERR_[A-Z_]+").matcher(Objects.toString(e.getMessage(), ""));
            String detail = network.find() ? network.group() : e.getClass().getSimpleName();
            Files.writeString(profile.resolve("last-capture-error.txt"), clock.instant()+" "+url.getPath()+" "+stage+" "+detail);
            throw new CaptureException("BROWSER_CAPTURE_FAILED", "浏览器取材失败，阶段："+stage);
        }
    }
    private static void navigate(Page page, URI url) {
        for (int attempt = 0; ; attempt++) {
            try {
                var response = page.navigate(url.toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                if (response != null && response.status() >= 400)
                    throw new CaptureException("SOURCE_HTTP_"+response.status(), "来源页面未返回成功响应");
                return;
            } catch (PlaywrightException e) {
                String message = Objects.toString(e.getMessage(), "");
                if (attempt > 0 || !message.matches("(?s).*net::ERR_(ABORTED|CONNECTION_CLOSED|CONNECTION_RESET|NETWORK_CHANGED|TIMED_OUT|EMPTY_RESPONSE|HTTP2_PROTOCOL_ERROR).*")) throw e;
                page.waitForTimeout(1000);
            }
        }
    }
    static void waitForRankRows(Page page, int expectedCharts) {
        page.waitForFunction("expected => { const cards = [...document.querySelectorAll('.rank-card')]; return cards.length === expected && cards.every(n => n.querySelectorAll('.rank-child-item').length >= 20); }",
                expectedCharts, new Page.WaitForFunctionOptions().setTimeout(30_000));
    }
    static void selectSourceDate(Page page, LocalDate requestedDate) {
        if (requestedDate.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw new CaptureException("INVALID_SCOPE", "不能采集未来日期");
        Locator input = page.locator("input.el-input__inner[placeholder='选择日期']");
        String date = requestedDate.toString();
        if (input.inputValue().startsWith(date)) return;
        // Use the site's visible date selector, never alter snapshot dates or call private APIs.
        input.fill(date);
        input.press("Enter");
        page.waitForTimeout(1500);
        page.waitForFunction("date => [...document.querySelectorAll('input')].some(n => n.value.startsWith(date)) && !document.querySelector('.el-loading-mask:not([style*=\"display: none\"])')",
                date, new Page.WaitForFunctionOptions().setTimeout(30_000));
        if (!input.inputValue().startsWith(date)) throw new CaptureException("SOURCE_DATE_MISMATCH", "来源未接受所选历史日期");
    }
    static RawMaterial snapshot(Page page, URI url, Clock clock) {
        // Capture only ranking DOM; never serialize application state, forms, cookies or storage.
        String html = (String)page.evaluate("""
            () => {
              const out = document.implementation.createHTMLDocument('Ranking DOM evidence');
              const canonical = document.querySelector('link[rel="canonical"]');
              if (canonical) out.head.append(canonical.cloneNode(true));
              for (const selector of ['.platform-tab.active', '.rank-period-switch__button.active', 'input', '.rank-card']) {
                for (const node of document.querySelectorAll(selector)) {
                  if (selector === 'input' && (node.type === 'password' || node.closest('form,[role="dialog"]') || !/^\\d{4}-\\d{2}-\\d{2}/.test(node.value))) continue;
                  const clone = node.cloneNode(true);
                  if (selector === 'input') clone.setAttribute('value', node.value);
                  out.body.append(clone);
                }
              }
              // Headings can be outside chart cards in the source layout.
              for (const heading of document.querySelectorAll('[id^="rank-heading-"]')) {
                if (!out.getElementById(heading.id)) out.body.prepend(heading.cloneNode(true));
              }
              for (const node of out.querySelectorAll('script,style,iframe,form')) node.remove();
              const allowed = new Set(['class','id','aria-labelledby','src','href','rel','value']);
              for (const node of out.querySelectorAll('*')) {
                for (const attribute of [...node.attributes]) if (!allowed.has(attribute.name)) node.removeAttribute(attribute.name);
              }
              return '<!doctype html>' + out.documentElement.outerHTML;
            }
            """);
        return new RawMaterial(url, clock.instant(), "persistent-browser-ranking-dom", html.getBytes(StandardCharsets.UTF_8));
    }
    static void validateComplete(RawMaterial material, Clock clock) {
        String platform = material.sourceUrl().getPath().startsWith("/wechat/") ? "WECHAT" : "DOUYIN";
        var adapter = new GravityRankingAdapter(platform);
        var requests = CaptureSources.daily(100).stream().filter(r -> r.sourceKey().equals(adapter.key())).toList();
        var pipeline = new CapturePipeline(List.of(adapter), ignored -> material, (b,m) -> {}, clock);
        for (var request : requests) {
            var bundle = pipeline.inspect(request, material);
            if (!bundle.coverage().status().equals("REQUESTED_RANGE_COMPLETE")) {
                if (material.text().contains("登录查看完整榜单"))
                    throw new CaptureException("SOURCE_AUTH_REQUIRED", "引力引擎登录已失效，请运行 scripts/login-ranking-capture.ps1 重新登录");
                throw new CaptureException("SOURCE_TOP100_INCOMPLETE", "滚动后仍未取得连续1至100名，未降级为20条成功结果");
            }
        }
    }
}
