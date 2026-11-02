package com.youkia.capture.browser;

import com.youkia.capture.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GravityBrowserFetcherTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
    private RawMaterial fixture(String platform, int rows, boolean login, int omit) {
        var charts = platform.equals("wechat") ? Map.of("popularity","人气榜","bestseller","畅销榜","most_played","畅玩榜")
                : Map.of("popularity","热门榜","bestseller","畅销榜","fresh_game","新游榜","publisher_heat","发行人热度榜");
        String url = "https://rank.gravity-engine.com/"+platform+"/day/";
        var html = new StringBuilder("<link rel='canonical' href='"+url+"'><a class='platform-tab active'>"+(platform.equals("wechat")?"微信小游戏":"抖音小游戏")+"</a><a class='rank-period-switch__button active'>日榜</a><input value='2026-11-02'>");
        for (var chart:charts.entrySet()) {
            html.append("<h2 id='rank-heading-").append(chart.getKey()).append("'>").append(chart.getValue()).append("</h2><section class='rank-card' aria-labelledby='rank-heading-").append(chart.getKey()).append("'>");
            for(int rank=1;rank<=rows;rank++) if(rank!=omit) html.append("<div class='wrapper'><div class='rank-child-item'><div class='rank-left-item'>NO.").append(rank).append("</div><span class='rank-app-name'>Synthetic ").append(rank).append("</span></div></div>");
            if(login) html.append("<p>仅展示前20条，登录查看完整榜单（共100条）</p>");
            html.append("</section>");
        }
        return new RawMaterial(URI.create(url),clock.instant(),"synthetic-test",html.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test void acceptsAllSevenChartsAtContinuousTop100() {
        for(String platform:List.of("wechat","douyin")) assertDoesNotThrow(()->GravityBrowserFetcher.validateComplete(fixture(platform,100,false,-1),clock));
    }
    @Test void expiredSessionIsActionableAndNeverFallsBackToPublic20() {
        var error=assertThrows(CaptureException.class,()->GravityBrowserFetcher.validateComplete(fixture("wechat",20,true,-1),clock));
        assertEquals("SOURCE_AUTH_REQUIRED",error.code());
        assertTrue(error.getMessage().contains("login-ranking-capture.ps1"));
    }
    @Test void stoppedLoadingIsIncompleteRatherThanInventedAuthenticationFailure() {
        assertEquals("SOURCE_TOP100_INCOMPLETE",assertThrows(CaptureException.class,()->GravityBrowserFetcher.validateComplete(fixture("douyin",20,false,-1),clock)).code());
    }
    @Test void visibleRank100DoesNotHideMissingMiddleRank() {
        assertEquals("SOURCE_TOP100_INCOMPLETE",assertThrows(CaptureException.class,()->GravityBrowserFetcher.validateComplete(fixture("wechat",100,false,51),clock)).code());
    }
}
