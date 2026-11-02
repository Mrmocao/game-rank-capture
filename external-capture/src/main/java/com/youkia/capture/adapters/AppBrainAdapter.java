package com.youkia.capture.adapters;

import com.youkia.capture.*;
import org.jsoup.Jsoup;
import java.net.URI;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Only the public table. Missing ranks and estimated install strings remain unchanged. */
public final class AppBrainAdapter implements SourceAdapter {
    @Override public String key() { return "appbrain"; }
    @Override public URI sourceUrl(CaptureRequest request) {
        if (!key().equals(request.sourceKey()) || !"top-free".equals(request.chartKey()) || !Set.of("us", "gb", "de", "fr").contains(request.region()))
            throw new CaptureException("INVALID_SCOPE", "AppBrain 只支持 us/gb/de/fr");
        return URI.create("https://www.appbrain.com/stats/google-play-rankings/top_free/game/" + request.region());
    }
    @Override public Observation parse(CaptureRequest request, RawMaterial material) {
        try {
            String url = sourceUrl(request).toString(); var doc = Jsoup.parse(material.text(), url);
            if (!url.equals(doc.select("link[rel=canonical]").attr("href")))
                throw new CaptureException("SOURCE_SCOPE_MISMATCH", "AppBrain 国家或榜单 canonical 不匹配");
            LocalDate date = LocalDate.parse(doc.select(".stats-pagetop-updated time").text(),
                    DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH));
            List<Observation.Entry> rows = new ArrayList<>();
            for (var tr : doc.select("#rankings-table tbody tr")) {
                if (tr.select(".ranking-rank").isEmpty()) continue;
                int rank = Integer.parseInt(tr.select(".ranking-rank").text());
                if (rank < 1) throw new CaptureException("INVALID_ENTRY", "AppBrain 名次必须为正整数");
                if (rank > request.requestedTopN()) continue;
                var link = tr.selectFirst(".ranking-app-cell > a");
                if (link == null) throw new CaptureException("MISSING_ID", "AppBrain 缺少来源身份链接");
                String href = link.attr("href"), id = href.substring(href.lastIndexOf('/') + 1);
                if (!id.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
                    throw new CaptureException("INVALID_ENTRY", "AppBrain 包名无效");
                var cells = tr.select("td");
                if (cells.size() != 7) throw new CaptureException("COLUMN_CHANGED", "AppBrain 列结构已变化");
                Map<String, Object> fields = new LinkedHashMap<>();
                String rating = tr.select(".ranking-rating-cell > span").text();
                if (!rating.isBlank()) {
                    BigDecimal value = new BigDecimal(rating);
                    if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(5)) > 0)
                        throw new CaptureException("INVALID_RATING", "AppBrain 评分不在 0–5 范围");
                    fields.put("rating", value);
                }
                fields.put("installsDisplay", cells.get(5).text());
                fields.put("recentInstallsDisplay", cells.get(6).text());
                fields.put("recentPeriodDays", 30); fields.put("recentInstallsEstimated", true);
                fields.put("sourcePage", link.absUrl("href")); fields.put("rankBasis", "STORE_FREE_RANK");
                rows.add(new Observation.Entry(rank, id, link.text(), tr.select(".ranking-app-cell-creator a").text(),
                        "#rankings-table .ranking-rank = " + rank, fields));
            }
            rows.sort(Comparator.comparingInt(Observation.Entry::rank));
            return new Observation(key(), "appbrain-html-v1", "GOOGLE_PLAY", request.region(),
                    "AppBrain Google Play游戏免费榜", date, date,
                    "沿用来源显示更新日；来源时区未声明，不做时区换算",
                    rows, List.of("第三方公开榜单，缺失名次保留；近30日安装量为来源估算原文。", "不构成 IAA 或收益结论。"));
        } catch (CaptureException e) { throw e; }
        catch (Exception e) { throw new CaptureException("INVALID_MATERIAL", "AppBrain 页面结构或日期无效"); }
    }
}
