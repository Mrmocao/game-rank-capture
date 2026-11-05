package com.youkia.capture.profiles;

import com.youkia.capture.CaptureException;
import com.youkia.capture.RawMaterial;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only fields visibly rendered by a public Qimai detail page. */
public final class QimaiPublicProfileParser {
    private static final Pattern APP_ID = Pattern.compile("(?m)^\\s*(\\d{5,20})\\s*$");
    private static final Pattern UPDATED = Pattern.compile("最近更新\\s*([0-9]{4}-[0-9]{2}-[0-9]{2})");
    private static final Pattern RELEASED = Pattern.compile("最早发布\\s*([0-9]{4}-[0-9]{2}-[0-9]{2})");
    private static final Pattern RATING = Pattern.compile("记录\\s*(.*?)\\s+([0-9]+(?:\\.[0-9]+)?)\\s+");

    private QimaiPublicProfileParser() {}

    public static PublicProfile parse(RawMaterial material, String externalId, Instant capturedAt) {
        if (material == null || externalId == null || externalId.isBlank())
            throw new IllegalArgumentException("资料材料和应用ID必填");
        Document doc = Jsoup.parse(material.text(), material.sourceUrl().toString());
        String titleRaw = text(doc, ".p-title");
        String name = titleRaw.replaceFirst("^\\s*活动中\\s*", "").trim();
        String developer = text(doc, "#avss .pub");
        String appInfo = text(doc, ".app-info");
        if (name.isBlank() || developer.isBlank() || appInfo.isBlank()
                || !appInfo.contains(externalId))
            throw new CaptureException("PUBLIC_PROFILE_FIELDS_MISSING", "七麦公开详情缺少可核验的应用名称、开发者或App ID");

        String category = between(appInfo, "分类", "价格");
        String price = between(appInfo, "价格", "内购");
        String iap = between(appInfo, "内购", "记录");
        String ratingCount = "";
        String rating = "";
        Matcher ratingMatcher = RATING.matcher(appInfo.replace("\r", ""));
        if (ratingMatcher.find()) { ratingCount = ratingMatcher.group(1).trim(); rating = ratingMatcher.group(2).trim(); }
        String downloads = between(appInfo, "昨日下载量", "最近更新");
        String updated = match(UPDATED, appInfo);
        String released = match(RELEASED, appInfo);
        return new PublicProfile("qimai-public", "IOS", "cn", externalId,
                name, titleRaw, text(doc, ".app-subtitle"), developer, category, price, iap,
                rating, ratingCount, downloads, updated, released,
                "UNVERIFIED_CROSS_PLATFORM_CANDIDATE", material.sourceUrl(), capturedAt,
                material.sha256(), ".p-title + #avss .pub + .app-info");
    }

    private static String text(Document doc, String selector) {
        Element e = doc.selectFirst(selector);
        return e == null ? "" : e.text().replace('\u00a0', ' ').trim();
    }

    private static String between(String text, String start, String end) {
        int a = text.indexOf(start), b = a < 0 ? -1 : text.indexOf(end, a + start.length());
        return a < 0 || b < 0 ? "" : text.substring(a + start.length(), b).trim();
    }

    private static String match(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text.replace("\r", ""));
        return m.find() ? m.group(1).trim() : "";
    }
}
