package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Database adapter owned by the host. No product, rule, report or profile writes. */
public final class RankingCaptureStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Clock clock;
    public RankingCaptureStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.tx = tx; this.mapper = mapper; this.clock = clock;
    }
    public String begin(String tenant, String batch, CaptureRequest request, String sourceUrl) {
        String id = UUID.randomUUID().toString(); Instant now = clock.instant();
        String scope = hash(json(List.of(tenant, request.sourceKey(), request.chartKey(), request.region())));
        jdbc.update("UPDATE external_capture_run SET status='INTERRUPTED',stage='EXPIRED',active_scope=NULL,finished_at=?,error_code='LEASE_EXPIRED' WHERE active_scope=? AND status='RUNNING' AND lease_until<?",
                timestamp(now), scope, timestamp(now));
        try {
            jdbc.update("""
                INSERT INTO external_capture_run(id,batch_id,tenant_id,source_key,chart_key,region,requested_top_n,requested_date,status,stage,active_scope,lease_until,source_url,started_at)
                VALUES(?,?,?,?,?,?,?,?,'RUNNING','FETCHING',?,?,?,?)
                """, id, batch, tenant, request.sourceKey(), request.chartKey(), request.region(), request.requestedTopN(),
                    LocalDate.ofInstant(now, ZoneId.of("Asia/Shanghai")), scope, timestamp(now.plusSeconds(600)), sourceUrl, timestamp(now));
        } catch (DuplicateKeyException e) { throw new CaptureException("COLLECTION_RUNNING", "同一来源榜单正在采集"); }
        return id;
    }
    public void fetched(String tenant, String run, RawMaterial material, String locator) {
        int count = jdbc.update("UPDATE external_capture_run SET stage='FETCHED',captured_at=?,raw_sha256=?,raw_locator=? WHERE id=? AND tenant_id=? AND status='RUNNING' AND lease_until>=?",
                timestamp(material.capturedAt()), material.sha256(), locator, run, tenant, timestamp(clock.instant()));
        if (count != 1) throw new CaptureException("LEASE_LOST", "采集运行已过期");
    }
    public Map<String, Object> complete(String tenant, String run, Observation.Bundle bundle, String rawLocator, String bundleLocator) {
        return tx.execute(ignored -> {
            var request = bundle.request(); var observation = bundle.observation(); var coverage = bundle.coverage();
            String key = hash(json(Arrays.asList(tenant, request.sourceKey(), request.chartKey(), request.region(), request.requestedTopN(),
                    observation.parserVersion(), observation.sourceDate(), observation.businessDate(), bundle.evidence().sha256())));
            var prior = jdbc.queryForList("SELECT id FROM external_ranking_observation WHERE dedupe_key=?", Long.class, key);
            long observationId; boolean reused = !prior.isEmpty();
            if (reused) observationId = prior.getFirst();
            else {
                String origin = request.sourceKey().startsWith("gravity-") ? "gravity-engine" : request.sourceKey().equals("apple-games") ? "apple" : "appbrain";
                String authority = request.sourceKey().equals("apple-games") ? "OFFICIAL" : "THIRD_PARTY";
                observationId = insert("""
                    INSERT INTO external_ranking_observation(tenant_id,dedupe_key,source_key,origin_family,source_authority,chart_key,board_name,platform,region,source_date,business_date,captured_at,available_at,parser_version,completeness,requested_top_n,actual_count,max_observed_rank,contiguous_top_n,source_url,raw_sha256,raw_locator,bundle_json)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, tenant, key, request.sourceKey(), origin, authority, request.chartKey(), observation.boardName(), observation.platform(), request.region(),
                        observation.sourceDate(), observation.businessDate(), timestamp(bundle.evidence().capturedAt()), timestamp(clock.instant()), observation.parserVersion(),
                        coverage.status(), coverage.requestedTopN(), coverage.actualCount(), coverage.maxObservedRank(), coverage.contiguousTopN(),
                        bundle.evidence().sourceUrl().toString(), bundle.evidence().sha256(), rawLocator, json(bundle));
                for (var row : observation.entries()) jdbc.update("INSERT INTO external_ranking_entry(observation_id,rank_position,external_id,name,developer,evidence_locator,source_fields) VALUES(?,?,?,?,?,?,?)",
                        observationId, row.rank(), row.externalId(), row.name(), row.developer(), row.evidenceLocator(), json(row.sourceFields()));
            }
            String status = coverage.status().equals("REQUESTED_RANGE_COMPLETE") ? "SUCCEEDED" : "PARTIAL";
            LocalDate requestedDay = jdbc.queryForObject("SELECT requested_date FROM external_capture_run WHERE id=? AND tenant_id=?", LocalDate.class, run, tenant);
            String freshness = observation.businessDate() == null ? "UNKNOWN" : observation.businessDate().equals(requestedDay) ? "CURRENT" : "STALE";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", run); result.put("observationId", observationId); result.put("source", request.sourceKey());
            result.put("chart", request.chartKey()); result.put("boardName", observation.boardName()); result.put("platform", observation.platform());
            result.put("status", status); result.put("freshness", freshness); result.put("sourceDate", observation.sourceDate());
            result.put("businessDate", observation.businessDate()); result.put("entries", coverage.actualCount()); result.put("coverage", coverage);
            result.put("reused", reused); result.put("rawSha256", bundle.evidence().sha256()); result.put("bundleLocator", bundleLocator);
            int count = jdbc.update("UPDATE external_capture_run SET status=?,stage='STORED',freshness=?,observation_id=?,reused=?,result_json=?,finished_at=?,active_scope=NULL WHERE id=? AND tenant_id=? AND status='RUNNING' AND lease_until>=?",
                    status, freshness, observationId, reused, json(result), timestamp(clock.instant()), run, tenant, timestamp(clock.instant()));
            if (count != 1) throw new CaptureException("LEASE_LOST", "运行已过期，禁止提交观察");
            return result;
        });
    }
    public void fail(String tenant, String run, String code) {
        jdbc.update("UPDATE external_capture_run SET status='FAILED',stage='FAILED',error_code=?,finished_at=?,active_scope=NULL WHERE id=? AND tenant_id=? AND status='RUNNING'",
                code, timestamp(clock.instant()), run, tenant);
    }
    public List<Map<String, Object>> runs(String tenant, String batch) {
        var rows = batch == null ? jdbc.queryForList("SELECT * FROM external_capture_run WHERE tenant_id=? ORDER BY started_at DESC,id DESC LIMIT 200", tenant)
                : jdbc.queryForList("SELECT * FROM external_capture_run WHERE tenant_id=? AND batch_id=? ORDER BY started_at,id", tenant, batch);
        rows.forEach(row -> { row.remove("active_scope"); row.remove("lease_until"); row.put("result_json", read(row.get("result_json"))); });
        return rows;
    }
    public List<Map<String, Object>> observations(String tenant, String platform, LocalDate date, String source, String chart) {
        StringBuilder sql = new StringBuilder("SELECT id,source_key,origin_family,source_authority,chart_key,board_name,platform,region,source_date,business_date,captured_at,available_at,parser_version,completeness,requested_top_n,actual_count,max_observed_rank,contiguous_top_n,source_url,raw_sha256 FROM external_ranking_observation o WHERE tenant_id=? AND EXISTS (SELECT 1 FROM external_capture_run r WHERE r.observation_id=o.id AND r.tenant_id=o.tenant_id AND r.status IN ('SUCCEEDED','PARTIAL'))");
        List<Object> args = new ArrayList<>(); args.add(tenant);
        if (platform != null) { sql.append(" AND platform=?"); args.add(platform); }
        if (date != null) { sql.append(" AND business_date=?"); args.add(date); }
        if (source != null) { sql.append(" AND source_key=?"); args.add(source); }
        if (chart != null) { sql.append(" AND chart_key=?"); args.add(chart); }
        sql.append(" ORDER BY available_at DESC,id DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
    public Map<String, Object> observation(String tenant, long id) {
        var rows = jdbc.queryForList("SELECT id,bundle_json,raw_locator,available_at FROM external_ranking_observation o WHERE tenant_id=? AND id=? AND EXISTS (SELECT 1 FROM external_capture_run r WHERE r.observation_id=o.id AND r.tenant_id=o.tenant_id AND r.status IN ('SUCCEEDED','PARTIAL'))", tenant, id);
        if (rows.isEmpty()) throw new CaptureException("OBSERVATION_NOT_FOUND", "来源观察不存在");
        var result = new LinkedHashMap<>(rows.getFirst()); result.put("bundle_json", read(result.get("bundle_json")));
        return result;
    }
    private Object read(Object value) {
        if (value == null) return null;
        try {
            var node = mapper.readTree(value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString());
            // H2 represents JDBC string-to-JSON assignment as a JSON string; MySQL stores the object.
            return node.isTextual() ? mapper.readTree(node.asText()) : node;
        }
        catch (Exception e) { throw new IllegalStateException("Stored capture JSON is invalid", e); }
    }
    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> { var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]); return statement; }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
