package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Read-only source registry from the V2.7 collection contract.  A catalog row is
 * configuration evidence, never a successful fetch; successful runs come only
 * from the append-only insight_collection_attempt table.
 */
@Service
public class CollectionCatalogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JsonNode catalog;
    private final JsonNode portalFields;

    public CollectionCatalogService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        try {
            catalog = mapper.readTree(new ClassPathResource("collection/source-register.json").getInputStream());
            portalFields = mapper.readTree(new ClassPathResource("collection/portal-field-template.json").getInputStream());
        } catch (Exception e) {
            throw new IllegalStateException("采集来源目录无法加载", e);
        }
    }

    @PostConstruct
    void persistRegistry() {
        try {
            for (JsonNode source : catalog.path("sources")) {
                jdbc.update("""
                    INSERT INTO source_definition(source_id,definition_version,name,origin_family,platform,region,verification_level,state,definition_json,production_enabled,enabled,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                    ON DUPLICATE KEY UPDATE name=VALUES(name),origin_family=VALUES(origin_family),platform=VALUES(platform),region=VALUES(region),verification_level=VALUES(verification_level),state=VALUES(state),definition_json=VALUES(definition_json),production_enabled=VALUES(production_enabled),enabled=VALUES(enabled),updated_at=UTC_TIMESTAMP(3)
                    """, source.path("sourceId").asText(), catalog.path("schemaVersion").asText(), source.path("name").asText(), source.path("originFamily").asText(), source.path("platform").asText(), source.path("region").asText(), source.path("verificationLevel").asText(), source.path("state").asText(), source.toString(), source.path("productionEnabled").asBoolean(false), source.path("enabled").asBoolean(false));
            }
        } catch (Exception ignored) {
            // Unit-test H2 and pre-V12 development databases can still read the checked-in catalog.
        }
    }

    public Map<String, Object> catalog(String query, String state, String platform) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String wantedState = state == null ? "" : state.trim();
        String wantedPlatform = platform == null ? "" : platform.trim();
        Map<String, Map<String, Object>> runtime = runtimeSummary();
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode source : catalog.path("sources")) {
            String name = source.path("name").asText();
            String sourceId = source.path("sourceId").asText();
            String searchable = (sourceId + " " + name + " " + source.path("site").asText() + " " + source.path("fields").asText()).toLowerCase(Locale.ROOT);
            if (!q.isBlank() && !searchable.contains(q)) continue;
            if (!wantedState.isBlank() && !wantedState.equals(source.path("state").asText())) continue;
            if (!wantedPlatform.isBlank() && !wantedPlatform.equals(source.path("platform").asText())) continue;
            Map<String, Object> item = toMap(source);
            List<String> keys = runtimeKeys(sourceId);
            item.put("runtimeKeys", keys);
            Map<String, Object> last = latestRuntime(keys, runtime);
            item.put("runtime", last == null ? Map.of(
                    "runCount", 0,
                    "state", "NOT_EXECUTED",
                    "label", "尚未执行"
            ) : last);
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", catalog.path("schemaVersion").asText());
        result.put("checkedAt", catalog.path("checkedAt").asText());
        result.put("sourceCount", catalog.path("sourceCount").asInt(items.size()));
        result.put("portalFieldTemplate", portalFields.path("schemaVersion").asText());
        result.put("portalFieldCount", portalFields.path("fieldCount").asInt());
        result.put("productionEnabledCount", items.stream().filter(item -> Boolean.TRUE.equals(item.get("productionEnabled")) && Boolean.TRUE.equals(item.get("enabled"))).count());
        result.put("registryStorage", "source_definition / Flyway V12");
        result.put("items", items);
        result.put("notice", "来源登记是能力和权限目录；未执行来源显示尚未执行，不生成成功率。真实运行以run_id和执行日志为准。");
        return result;
    }

    public Map<String, Object> source(String id) {
        for (JsonNode source : catalog.path("sources")) {
            if (source.path("sourceId").asText().equals(id)) {
                Map<String, Object> result = new LinkedHashMap<>(toMap(source));
                result.put("portalFields", source.path("platform").asText().equals("WEB_PORTAL") ? portalFields.path("fields") : mapper.createArrayNode());
                result.put("runtimeKeys", runtimeKeys(id));
                result.put("relatedRuns", runs(null, null, id).get("items"));
                return result;
            }
        }
        throw new IllegalArgumentException("来源不存在: " + id);
    }

    public Map<String, Object> runs(LocalDate date, String status, String sourceId) {
        StringBuilder sql = new StringBuilder("SELECT id,source_key,requested_date,status,started_at,finished_at,result_json FROM insight_collection_attempt WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (date != null) { sql.append(" AND requested_date=?"); args.add(date); }
        if (status != null && !status.isBlank()) { sql.append(" AND status=?"); args.add(status); }
        if (sourceId != null && !sourceId.isBlank()) {
            List<String> keys = runtimeKeys(sourceId);
            sql.append(" AND source_key IN (").append("?,".repeat(keys.size()).replaceAll(",$", "")).append(")");
            args.addAll(keys);
        }
        sql.append(" ORDER BY id DESC LIMIT 200");
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> item : items) {
            item.put("sourceName", sourceName(String.valueOf(item.get("source_key"))));
            item.put("executionState", executionState(String.valueOf(item.get("status"))));
            item.put("resultJson", safeResult(item.remove("result_json")));
        }
        return Map.of("date", date == null ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : date, "items", items,
                "notice", "执行日志只反映实际尝试；尚未执行的来源不会被填充为成功。");
    }

    public Map<String, Object> portalFields() {
        return mapper.convertValue(portalFields, Map.class);
    }

    private Map<String, Object> toMap(JsonNode source) {
        return mapper.convertValue(source, LinkedHashMap.class);
    }

    private String sourceName(String id) {
        for (JsonNode source : catalog.path("sources")) if (source.path("sourceId").asText().equals(id)) return source.path("name").asText();
        for (JsonNode source : catalog.path("sources")) if (runtimeKeys(source.path("sourceId").asText()).contains(id)) return source.path("name").asText();
        return "未登记来源";
    }

    /** Runtime adapters use stable operational keys while the catalog uses Sxx IDs. */
    private List<String> runtimeKeys(String sourceId) {
        return switch (sourceId) {
            case "S03" -> List.of("APPLE_CN");
            case "S06" -> List.of("APPBRAIN_US", "APPBRAIN_GB", "APPBRAIN_DE", "APPBRAIN_FR");
            default -> List.of(sourceId);
        };
    }

    private Map<String, Map<String, Object>> runtimeSummary() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT source_key, COUNT(*) run_count,
                           MAX(started_at) last_started_at,
                           MAX(finished_at) last_finished_at,
                           MAX(CASE WHEN status='SUCCESS' THEN finished_at END) last_success_at,
                           SUBSTRING_INDEX(GROUP_CONCAT(status ORDER BY id DESC), ',', 1) last_status
                    FROM insight_collection_attempt GROUP BY source_key
                    """);
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) out.put(String.valueOf(row.get("source_key")), row);
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> latestRuntime(List<String> keys, Map<String, Map<String, Object>> runtime) {
        if (keys.isEmpty()) return null;
        long count = 0;
        Map<String, Object> latest = null;
        for (String key : keys) {
            Map<String, Object> row = runtime.get(key);
            if (row == null) continue;
            count += ((Number) row.getOrDefault("run_count", 0)).longValue();
            if (latest == null || String.valueOf(row.get("last_started_at")).compareTo(String.valueOf(latest.get("last_started_at"))) > 0) latest = row;
        }
        if (latest == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runCount", count);
        out.put("state", latest.get("last_status"));
        out.put("label", executionState(String.valueOf(latest.get("last_status"))));
        out.put("lastStartedAt", latest.get("last_started_at"));
        out.put("lastFinishedAt", latest.get("last_finished_at"));
        out.put("lastSuccessAt", latest.get("last_success_at"));
        return out;
    }

    private String executionState(String state) {
        return switch (state) {
            case "SUCCESS" -> "成功";
            case "FAILED" -> "失败";
            case "RUNNING" -> "运行中";
            case "INTERRUPTED" -> "中断";
            default -> state;
        };
    }

    private Object safeResult(Object raw) {
        if (raw == null) return Map.of();
        try { return mapper.readTree(String.valueOf(raw)); }
        catch (Exception ignored) { return Map.of("notice", "结果记录无法解析"); }
    }
}
