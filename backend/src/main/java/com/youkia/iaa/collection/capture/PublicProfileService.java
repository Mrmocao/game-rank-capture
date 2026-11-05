package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.CaptureException;
import com.youkia.capture.RawMaterial;
import com.youkia.capture.profiles.PublicProfile;
import com.youkia.capture.profiles.QimaiPublicProfileParser;
import com.youkia.capture.web.WebCaptureRule;
import com.youkia.capture.web.WebTransport;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Public profile enrichment starts from a captured mini-game observation and
 * searches the existing iOS index by name. Candidate identity remains
 * explicitly unverified until a human confirms it.
 */
public final class PublicProfileService {
    private final CaptureWorkspaceStore store;
    private final WebTransport transport;
    private final ObjectMapper mapper;
    private final Path root;
    private final Clock clock;

    public PublicProfileService(CaptureWorkspaceStore store, WebTransport transport, ObjectMapper mapper, Path root, Clock clock) {
        this.store = store; this.transport = transport; this.mapper = mapper;
        this.root = root.toAbsolutePath().normalize(); this.clock = clock;
    }

    public Map<String, Object> candidates(String tenant, long observationId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("公开资料一次最多读取100个候选");
        var seedRows = store.jdbc.queryForList("""
            SELECT o.platform,o.region,e.rank_position,e.name,e.developer
            FROM external_ranking_observation o JOIN external_ranking_entry e ON e.observation_id=o.id
            WHERE o.tenant_id=? AND o.id=? AND o.platform IN ('WECHAT','DOUYIN')
            ORDER BY e.rank_position LIMIT ?
            """, tenant, observationId, limit);
        if (seedRows.isEmpty()) throw new CaptureException("PUBLIC_PROFILE_SEED_NOT_FOUND", "只支持有效的微信或抖音榜单观察");
        var out = new ArrayList<Map<String, Object>>();
        for (var seed : seedRows) {
            String name = Objects.toString(seed.get("name"), "").trim();
            var row = new LinkedHashMap<String, Object>(); row.put("seed", seed);
            var matches = store.jdbc.queryForList("""
                SELECT e.external_id candidate_external_id,e.name candidate_name,e.developer candidate_developer,
                       o.captured_at candidate_captured_at,o.source_url candidate_source_url
                FROM external_ranking_observation o JOIN external_ranking_entry e ON e.observation_id=o.id
                WHERE o.tenant_id=? AND o.platform='IOS' AND o.region='cn' AND e.external_id IS NOT NULL
                  AND (e.name=? OR e.name LIKE CONCAT(?, '%'))
                ORDER BY CASE WHEN e.name=? THEN 0 ELSE 1 END,o.captured_at DESC,o.id DESC LIMIT 3
                """, tenant, name, name, name);
            row.put("matches", matches);
            var previous = store.jdbc.queryForList("SELECT id,status,candidate_external_id,candidate_name,candidate_developer,source_url,error_code,captured_at,profile_json FROM capture_public_profile_observation WHERE tenant_id=? AND seed_observation_id=? AND seed_rank_position=? ORDER BY updated_at DESC LIMIT 1", tenant, observationId, seed.get("rank_position"));
            if (!previous.isEmpty()) { var p = previous.getFirst(); if (p.get("profile_json") != null) p.put("profile", store.read(p.remove("profile_json"))); row.put("latest", p); }
            out.add(row);
        }
        return Map.of("observationId", observationId, "source", "qimai-public", "rows", out, "notice", "候选来自现有中国iOS索引的名称匹配；跨平台身份未核验，公开下载预估不回填微信/抖音指标。");
    }

    public record CaptureRequest(long observationId, List<String> candidateExternalIds, int limit) {}

    public Map<String, Object> capture(String tenant, CaptureRequest request) {
        if (request == null || request.observationId() <= 0) throw new IllegalArgumentException("榜单观察ID必填");
        int limit = request.limit() <= 0 ? 20 : Math.min(request.limit(), 100);
        var candidates = candidates(tenant, request.observationId(), limit);
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) candidates.get("rows");
        Set<String> selected = request.candidateExternalIds() == null ? Set.of() : Set.copyOf(request.candidateExternalIds());
        int succeeded = 0, failed = 0, noCandidate = 0;
        for (var row : rows) {
            @SuppressWarnings("unchecked") var matches = (List<Map<String, Object>>) row.get("matches");
            Map<String, Object> match = matches.stream().filter(m -> selected.isEmpty() || selected.contains(Objects.toString(m.get("candidate_external_id"), ""))).findFirst().orElse(null);
            if (match == null && !selected.isEmpty()) continue;
            if (match == null) { saveNoCandidate(tenant, request.observationId(), row); noCandidate++; continue; }
            if (captureOne(tenant, request.observationId(), row, match)) succeeded++; else failed++;
        }
        return Map.of("observationId", request.observationId(), "source", "qimai-public", "succeeded", succeeded, "failed", failed, "noCandidate", noCandidate, "notice", "成功仅表示公开详情字段已取得；候选仍是跨平台未核验资料。");
    }

    private boolean captureOne(String tenant, long observationId, Map<String, Object> row, Map<String, Object> match) {
        var seed = (Map<?, ?>) row.get("seed"); String id = Objects.toString(match.get("candidate_external_id"), "");
        String url = "https://www.qimai.cn/app/baseinfo/appid/" + id + "/country/cn";
        var now = clock.instant(); String observation = UUID.randomUUID().toString();
        try {
            var rule = new WebCaptureRule(url, "body", List.of(new WebCaptureRule.Field("body", "body", "text", true)), 1, 1, 0, false);
            RawMaterial raw = transport.fetch("web-http", rule, null);
            PublicProfile profile = QimaiPublicProfileParser.parse(raw, id, now);
            Path relative = Path.of("public-profiles", observation, "raw.html"); Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.write(file, raw.bytes(), StandardOpenOption.CREATE_NEW);
            Path json = file.resolveSibling("profile.json"); Files.writeString(json, mapper.writeValueAsString(profile), StandardOpenOption.CREATE_NEW);
            save(tenant, observationId, seed, match, url, "SUCCEEDED", null, profile, relative, now);
            return true;
        } catch (Exception e) {
            String code = e instanceof CaptureException c ? c.code() : "PUBLIC_PROFILE_FETCH_FAILED";
            save(tenant, observationId, seed, match, url, code.contains("LOGIN") ? "LOGIN_REQUIRED" : "FAILED", code, null, null, now);
            return false;
        }
    }

    private void saveNoCandidate(String tenant, long observationId, Map<String, Object> row) {
        var seed = (Map<?, ?>) row.get("seed"); save(tenant, observationId, seed, null, null, "NO_CANDIDATE", "NO_IOS_NAME_MATCH", null, null, clock.instant());
    }

    private void save(String tenant, long observationId, Map<?, ?> seed, Map<String, Object> match, String url, String status, String error, PublicProfile profile, Path raw, Instant at) {
        String candidateId = match == null ? null : Objects.toString(match.get("candidate_external_id"), null);
        String id = UUID.nameUUIDFromBytes((tenant + "|" + observationId + "|" + seed.get("rank_position") + "|qimai-public|" + Objects.toString(candidateId, "none")).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String candidateName = match == null ? null : Objects.toString(match.get("candidate_name"), null), candidateDeveloper = match == null ? null : Objects.toString(match.get("candidate_developer"), null);
        String json = profile == null ? null : store.json(profile);
        String hash = profile == null ? null : profile.rawSha256();
        store.jdbc.update("""
            INSERT INTO capture_public_profile_observation(id,tenant_id,seed_observation_id,seed_rank_position,seed_platform,seed_region,seed_name,seed_developer,candidate_platform,candidate_external_id,candidate_name,candidate_developer,source_key,source_url,status,error_code,captured_at,raw_sha256,raw_locator,profile_json,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE status=VALUES(status),error_code=VALUES(error_code),captured_at=VALUES(captured_at),raw_sha256=VALUES(raw_sha256),raw_locator=VALUES(raw_locator),profile_json=VALUES(profile_json),updated_at=VALUES(updated_at)
            """, id, tenant, observationId, seed.get("rank_position"), seed.get("platform"), seed.get("region"), seed.get("name"), seed.get("developer"), profile == null ? null : profile.platform(), candidateId, profile == null ? candidateName : profile.name(), profile == null ? candidateDeveloper : profile.developer(), "qimai-public", url, status, error, profile == null ? null : Timestamp.from(at), hash, raw == null ? null : raw.toString().replace('\\', '/'), json, Timestamp.from(at), Timestamp.from(at));
    }
}
