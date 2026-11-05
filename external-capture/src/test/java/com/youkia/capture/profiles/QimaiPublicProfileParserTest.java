package com.youkia.capture.profiles;

import com.youkia.capture.RawMaterial;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class QimaiPublicProfileParserTest {
    @Test void parsesOnlyVisiblePublicFieldsAndKeepsCandidateIdentity() {
        String html = """
            <div class='logo-wrap'><p class='p-title'>活动中 疯狂水世界</p><p class='app-subtitle'>全新海上末日模拟经营手游</p></div>
            <i id='avss'><span>开发者:</span><span class='pub'>益世界网络科技(上海)有限公司</span></i>
            <div class='app-info'>APP ID\n复制\n6757172630\n分类\n模拟/角色扮演\n价格\n免费\n内购\n10项\n记录\n8621个评分\n4.5\n模拟/角色扮演(免费)\n第3名\n昨日下载量\n56,725\n最近更新\n2026-11-02\n最早发布\n2026-01-21</div>
            """;
        var at = Instant.parse("2026-11-04T11:03:11.253Z");
        var raw = new RawMaterial(URI.create("https://www.qimai.cn/app/baseinfo/appid/6757172630/country/cn"), at, "fixture", html.getBytes(StandardCharsets.UTF_8));
        var p = QimaiPublicProfileParser.parse(raw, "6757172630", at);
        assertEquals("疯狂水世界", p.name());
        assertEquals("活动中 疯狂水世界", p.nameRaw());
        assertEquals("益世界网络科技(上海)有限公司", p.developer());
        assertEquals("模拟/角色扮演", p.categoryDisplay());
        assertEquals("免费", p.priceDisplay());
        assertEquals("10项", p.iapItemCountDisplay());
        assertEquals("4.5", p.ratingDisplay());
        assertEquals("8621个评分", p.ratingCountDisplay());
        assertEquals("56,725", p.yesterdayDownloadsDisplay());
        assertEquals("2026-11-02", p.updatedDateDisplay());
        assertEquals("2026-01-21", p.firstReleaseDateDisplay());
        assertEquals("UNVERIFIED_CROSS_PLATFORM_CANDIDATE", p.identityStatus());
    }

    @Test void rejectsHtmlWithoutTheRequestedAppId() {
        String html = "<p class='p-title'>无尽冬日</p><i id='avss'><span class='pub'>Publisher</span></i><div class='app-info'>APP ID 999999</div>";
        var raw = new RawMaterial(URI.create("https://www.qimai.cn/app/baseinfo/appid/6478492012/country/cn"), Instant.now(), "fixture", html.getBytes(StandardCharsets.UTF_8));
        assertThrows(RuntimeException.class, () -> QimaiPublicProfileParser.parse(raw, "6478492012", Instant.now()));
    }
}
