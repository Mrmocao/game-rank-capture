package com.youkia.capture.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import org.jsoup.Jsoup;
import static org.junit.jupiter.api.Assertions.*;

/** Offline browser integration; uses a fresh test profile, never the real login session. */
@EnabledIfEnvironmentVariable(named="RUN_CAPTURE_BROWSER_TESTS", matches="true")
class BrowserDomTest {
    @TempDir Path profile;
    @Test void historicalSelectionUsesPageControlAndPreservesActualCaptureTime() throws Exception {
        try(var browser=new BrowserProfile(profile,true,"chrome")) {
            browser.context.route("**/*",route->route.abort());
            var page=browser.context.newPage();
            page.setContent("""
                <input class="el-input__inner" placeholder="选择日期" value="2026-11-02">
                <section class="rank-card">current</section>
                <script>document.querySelector('input').addEventListener('keydown',e=>{
                  if(e.key==='Enter')document.querySelector('.rank-card').textContent='history-'+e.target.value;
                });</script>
                """);
            GravityBrowserFetcher.selectSourceDate(page,java.time.LocalDate.of(2026,10,27));
            var material=GravityBrowserFetcher.snapshot(page,URI.create("https://rank.gravity-engine.com/wechat/day/"),Clock.systemUTC());
            assertTrue(material.text().contains("history-2026-10-27"));
            assertTrue(material.capturedAt().isAfter(java.time.Instant.parse("2026-10-27T23:59:59Z")));
        }
    }
    @Test void waitsForAsynchronousRowsAfterDateControlIsAlreadyEnabled() throws Exception {
        try(var browser = new BrowserProfile(profile, true, "chrome")) {
            browser.context.route("**/*", route -> route.abort());
            var page = browser.context.newPage();
            page.setContent("""
                <input value="2026-11-02"><section class="rank-card"></section>
                <section class="rank-card"></section><section class="rank-card"></section>
                <script>setTimeout(() => { for(const card of document.querySelectorAll('.rank-card')) {
                  card.innerHTML = Array.from({length:20}, (_,i) => '<div class="rank-child-item">NO.'+(i+1)+'</div>').join('');
                } }, 700);</script>
                """);
            GravityBrowserFetcher.waitForRankRows(page, 3);
            assertEquals(60, page.locator(".rank-child-item").count());
        }
    }
    @Test void capturesLiveDatePropertyAndRankingDomWithoutLoginSecrets() throws Exception {
        try(var browser = new BrowserProfile(profile, true, "chrome")) {
            browser.context.route("**/*", route -> route.abort());
            var page = browser.context.newPage();
            page.setContent("""
                <link rel="canonical" href="https://rank.gravity-engine.com/wechat/day/">
                <a class="platform-tab active">微信小游戏</a><a class="rank-period-switch__button active">日榜</a>
                <input id="date"><input type="password" value="2026-11-02-synthetic-secret">
                <form>synthetic-login-form</form><script>document.getElementById('date').value='2026-11-02(今天)';</script>
                <h2 id="rank-heading-popularity">人气榜</h2>
                <section class="rank-card" aria-labelledby="rank-heading-popularity">
                  <div class="rank-child-item" data-session="synthetic-private-state" onclick="secret()">
                    <div class="rank-left-item">NO.100</div><span class="rank-app-name">Synthetic game</span>
                  </div>
                </section>
                """);
            var material = GravityBrowserFetcher.snapshot(page, URI.create("https://rank.gravity-engine.com/wechat/day/"), Clock.systemUTC());
            var doc = Jsoup.parse(material.text());
            assertEquals("2026-11-02(今天)", doc.select("input[value]").attr("value"));
            assertEquals("Synthetic game", doc.select(".rank-app-name").text());
            assertFalse(material.text().contains("synthetic-secret"));
            assertFalse(material.text().contains("synthetic-login-form"));
            assertFalse(material.text().contains("synthetic-private-state"));
            assertTrue(doc.select("script,form,[onclick]").isEmpty());
            assertEquals("persistent-browser-ranking-dom", material.transport());
        }
    }
}
