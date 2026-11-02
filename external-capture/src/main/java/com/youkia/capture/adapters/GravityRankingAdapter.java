package com.youkia.capture.adapters;

import com.youkia.capture.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Anonymous server-rendered daily charts; no private API, decryption or login bypass. */
public final class GravityRankingAdapter implements SourceAdapter {
    private final String platform;
    private final Map<String, String> charts;
    // SHA-256 of the three visible NO.1/NO.2/NO.3 SVG src attributes, visually verified 2026-11-02.
    // Unknown artwork is rejected instead of inferring ranks from row order or category sub-ranks.
    private static final Map<String, Integer> PODIUM = Map.of(
            "f18e654bf309324952714281aed48e19d83706ead1120a237fdbe7f177fce59d", 1,
            "7a21a2d49e1fdb22e56b16925d9f198cd28ff1b310ba877c9bfcc4add7f6ca07", 2,
            "6718d19a21a13697197df32d13d29793bc44ec58922ceeaba5b4e4983762197c", 3);

    public GravityRankingAdapter(String platform) {
        if (!Set.of("WECHAT", "DOUYIN").contains(platform)) throw new IllegalArgumentException("Unsupported platform");
        this.platform = platform;
        this.charts = platform.equals("WECHAT")
                ? Map.of("popularity", "人气榜", "bestseller", "畅销榜", "most_played", "畅玩榜")
                : Map.of("popularity", "热门榜", "bestseller", "畅销榜", "fresh_game", "新游榜", "publisher_heat", "发行人热度榜");
    }
    @Override public String key() { return "gravity-" + platform.toLowerCase(Locale.ROOT); }
    @Override public URI sourceUrl(CaptureRequest request) {
        if (!request.sourceKey().equals(key()) || !request.region().equals("cn") || !charts.containsKey(request.chartKey()))
            throw new CaptureException("INVALID_SCOPE", "引力引擎平台、地区或日榜类型无效");
        return URI.create("https://rank.gravity-engine.com/" + platform.toLowerCase(Locale.ROOT) + "/day/");
    }
    @Override public Observation parse(CaptureRequest request, RawMaterial material) {
        try {
            String url = sourceUrl(request).toString();
            var doc = Jsoup.parse(material.text(), url);
            String label = platform.equals("WECHAT") ? "微信小游戏" : "抖音小游戏";
            if (!url.equals(doc.select("link[rel=canonical]").attr("href"))
                    || !label.equals(doc.select(".platform-tab.active").text())
                    || !"日榜".equals(doc.select(".rank-period-switch__button.active").text()))
                throw new CaptureException("SOURCE_SCOPE_MISMATCH", "来源页面平台、日榜或canonical不匹配");
            var dates = doc.select("input[value]").eachAttr("value").stream()
                    .filter(v -> v.matches("\\d{4}-\\d{2}-\\d{2}.*")).map(v -> v.substring(0, 10)).distinct().toList();
            if (dates.size() != 1) throw new CaptureException("SOURCE_DATE_INVALID", "来源显示日期缺失或有冲突");
            LocalDate date = LocalDate.parse(dates.getFirst());
            String headingId = "rank-heading-" + request.chartKey();
            var heading = doc.getElementById(headingId);
            String title = charts.get(request.chartKey());
            if (heading == null || !title.equals(heading.text())) throw new CaptureException("CHART_CHANGED", "来源原榜名已变化");
            var cards = doc.select(".rank-card[aria-labelledby=" + headingId + "]");
            if (cards.size() != 1) throw new CaptureException("CHART_CHANGED", "来源榜单容器不唯一");
            var card = cards.first(); var sourceRows = card.select(".rank-child-item");
            if (sourceRows.isEmpty() || sourceRows.size() > 200) throw new CaptureException("NO_ENTRIES", "页面没有可核验的公开榜单行");
            List<Observation.Entry> rows = new ArrayList<>(); Set<Integer> ranks = new HashSet<>();
            for (int i = 0; i < sourceRows.size(); i++) {
                Element row = sourceRows.get(i); int rank = rank(row);
                if (!ranks.add(rank)) throw new CaptureException("INVALID_ENTRY", "来源出现重复名次");
                if (rank > request.requestedTopN()) continue;
                String name = row.select(".rank-app-name").text();
                if (name.isBlank()) throw new CaptureException("INVALID_ENTRY", "来源游戏名称缺失");
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("originFamily", "gravity-engine"); fields.put("sourceAuthority", "THIRD_PARTY");
                fields.put("identityState", "UNVERIFIED"); fields.put("rankBasis", request.chartKey());
                fields.put("period", "day"); fields.put("tags", row.select(".rank-tags-row .categorize").eachText());
                fields.put("descriptionRaw", row.select(".rank-app-desc, .rank-app-description, .app-description").text());
                fields.put("changeRaw", row.select(".rank-right-item").text());
                fields.put("rowText", row.text());
                String icon = row.select(".rank-app-icon img").attr("src");
                if (icon.startsWith("https://")) fields.put("thumbnailUrl", icon);
                String locator = row.cssSelector();
                var resolved = doc.select(locator);
                if (resolved.size() != 1 || resolved.first() != row)
                    throw new CaptureException("EVIDENCE_LOCATOR_INVALID", "原始DOM证据定位必须唯一命中该行");
                rows.add(new Observation.Entry(rank, null, name, nullable(row.select(".app-publisher__name").text()), locator, fields));
            }
            rows.sort(Comparator.comparingInt(Observation.Entry::rank));
            List<String> warnings = new ArrayList<>(List.of("第三方引力引擎公开榜单，不是微信/抖音官方客户端榜。",
                    "来源没有已核验的平台appid，不按名称合并产品，不推断IAA、收入或下载量。"));
            if (card.text().contains("仅展示前")) {
                var note = java.util.regex.Pattern.compile("仅展示前\\d+条[^）]*[）)]?").matcher(card.text());
                warnings.add(note.find() ? note.group() : "页面提示仅展示公开前段，完整榜单需要登录。");
            }
            return new Observation(key(), "gravity-ssr-v2", platform, "cn", title, date, date,
                    "沿用页面所选日榜日期；来源未声明时区，采集时间另存UTC", rows, warnings);
        } catch (CaptureException e) { throw e; }
        catch (Exception e) { throw new CaptureException("INVALID_MATERIAL", "引力引擎公开HTML结构无法核验"); }
    }
    private static String nullable(String text) { return text.isBlank() || text.equals("--") ? null : text; }
    private static int rank(Element row) throws Exception {
        String text = row.select(".rank-left-item").text().replaceAll("\\s+", "");
        if (text.matches("NO\\.[1-9][0-9]*")) return Integer.parseInt(text.substring(3));
        String image = row.select(".rank-left-item img").attr("src");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.getBytes(StandardCharsets.UTF_8)));
        Integer rank = PODIUM.get(hash);
        if (rank == null) throw new CaptureException("RANK_MARKER_CHANGED", "名次图形未核验，不按顺序或分类名次猜测排名");
        return rank;
    }
}
