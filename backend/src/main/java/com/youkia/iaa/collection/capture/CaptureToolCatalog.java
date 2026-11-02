package com.youkia.iaa.collection.capture;

import java.util.*;

/** Tool identity is independent of the original data source and its website. */
public final class CaptureToolCatalog {
    private CaptureToolCatalog() {}
    public record Tool(String key, String name, String method, boolean implemented, List<String> files, String validation, String repairBoundary) {}
    public static List<Tool> all() { return List.of(
        new Tool("http-json", "HTTP · JSON/RSS", "固定HTTPS端点 → 原始JSON → 日期与TopN校验", true,
            List.of("external-capture/src/main/java/com/youkia/capture/HttpMaterialFetcher.java", "external-capture/src/main/java/com/youkia/capture/adapters/AppleGamesAdapter.java"),
            "mvn test -pl external-capture -Dtest=CapturePipelineTest", "字段路径、来源日期；不修改排名事实"),
        new Tool("browser-scroll", "浏览器 · 登录与滚动", "复用本机Chrome会话 → 等待页面 → 滚动加载 → 连续Top100", true,
            List.of("browser-capture/src/main/java/com/youkia/capture/browser/GravityBrowserFetcher.java", "external-capture/src/main/java/com/youkia/capture/adapters/GravityRankingAdapter.java"),
            "mvn test -pl browser-capture -am -Dtest=GravityBrowserFetcherTest,BrowserDomTest,GravityRankingAdapterTest -Dsurefire.failIfNoSpecifiedTests=false", "DOM选择器、等待与滚动；登录/验证码交给用户"),
        new Tool("http-html", "HTTP · HTML解析", "固定公开页面 → Jsoup结构解析 → 来源范围与证据校验", true,
            List.of("external-capture/src/main/java/com/youkia/capture/HttpMaterialFetcher.java", "external-capture/src/main/java/com/youkia/capture/adapters/AppBrainAdapter.java"),
            "mvn test -pl external-capture -Dtest=CapturePipelineTest", "结构选择器；网络不可达不能伪装成规则修复"),
        new Tool("material-import", "材料导入", "已取得的HTML/JSON + 原采集时间 → 哈希验证与重放", true,
            List.of("external-capture/src/main/java/com/youkia/capture/CaptureCli.java"), "mvn test -pl external-capture", "保留原采集时间，不重标历史日期"),
        new Tool("firecrawl", "Firecrawl · 待接入", "网页Map/Scrape候选工具，当前未配置或执行", false,
            List.of(), "接入后需补真实页面、字段与覆盖验证", "当前仅登记，不声称已能执行")); }
    public static Tool get(String key) { return all().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException("未知抓取工具")); }
}
