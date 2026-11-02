package com.youkia.capture.adapters;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Games genre 6014 only; deliberately separate from Apple Marketing Tools all-apps RSS. */
public final class AppleGamesAdapter implements SourceAdapter {
    private final ObjectMapper mapper;
    public AppleGamesAdapter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public String key() { return "apple-games"; }
    @Override public URI sourceUrl(CaptureRequest request) {
        if (!key().equals(request.sourceKey()) || !"top-free".equals(request.chartKey()) || !Set.of("cn", "us", "gb", "de", "fr").contains(request.region()))
            throw new CaptureException("INVALID_SCOPE", "Apple 游戏榜只支持 cn/us/gb/de/fr");
        return URI.create("https://itunes.apple.com/" + request.region()
                + "/rss/topfreeapplications/limit=" + request.requestedTopN() + "/genre=6014/json");
    }
    @Override public Observation parse(CaptureRequest request, RawMaterial material) {
        try {
            var feed = mapper.readTree(material.text()).path("feed");
            if (!sourceUrl(request).toString().equals(feed.path("id").path("label").asText()))
                throw new CaptureException("SOURCE_SCOPE_MISMATCH", "Apple 材料国家、分类或 TopN 不匹配");
            var updated = OffsetDateTime.parse(feed.path("updated").path("label").asText());
            var entries = feed.path("entry");
            // RSS serializes a single result as an object when limit=1.
            if (request.requestedTopN() == 1 && entries.isObject()) entries = mapper.createArrayNode().add(entries);
            if (!entries.isArray() || entries.isEmpty() || entries.size() > request.requestedTopN())
                throw new CaptureException("INVALID_ENTRIES", "Apple 条数无效");
            List<Observation.Entry> rows = new ArrayList<>(); int rank = 0;
            for (var entry : entries) {
                String id = entry.path("id").path("attributes").path("im:id").asText();
                if (!id.matches("[0-9]+") || !"6014".equals(entry.path("category").path("attributes").path("im:id").asText()))
                    throw new CaptureException("INVALID_ENTRY", "Apple ID 或游戏分类无效");
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("sourceUpdated", updated.toString());
                fields.put("storeUrl", entry.path("id").path("label").asText());
                fields.put("summary", entry.path("summary").path("label").asText());
                fields.put("releaseDateRaw", entry.path("im:releaseDate").path("label").asText());
                fields.put("rankBasis", "STORE_FREE_RANK");
                fields.put("originFamily", "apple"); fields.put("sourceAuthority", "OFFICIAL");
                fields.put("identityState", "SOURCE_ID_PROVIDED");
                var images = entry.path("im:image");
                if (images.isArray() && !images.isEmpty()) fields.put("thumbnailUrl", images.get(images.size() - 1).path("label").asText());
                rows.add(new Observation.Entry(++rank, id, entry.path("im:name").path("label").asText(),
                        entry.path("im:artist").path("label").asText(), "feed.entry[" + (rank - 1) + "]", fields));
            }
            return new Observation(key(), "apple-games-v1", "IOS", request.region(),
                    "Apple公开游戏免费榜（RSS分类6014）", updated.toLocalDate(),
                    updated.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate(),
                    "sourceDate 为来源偏移日期；businessDate 为来源更新时间转换 Asia/Shanghai",
                    rows, List.of("免费排名不构成 IAA、下载量或收入证据。"));
        } catch (CaptureException e) { throw e; }
        catch (Exception e) { throw new CaptureException("INVALID_MATERIAL", "Apple 游戏 RSS 结构或更新时间无效"); }
    }
}
