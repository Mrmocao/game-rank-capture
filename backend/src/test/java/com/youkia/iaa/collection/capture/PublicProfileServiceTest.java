package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.web.WebTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PublicProfileServiceTest {
    JdbcTemplate jdbc;
    PublicProfileService service;
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        for (String f : new String[]{"V13__external_ranking_capture.sql", "V14__capture_workspace.sql", "V17__overseas_store_charts.sql", "V19__game_metric_capture.sql", "V20__public_profile_capture.sql"})
            try (var in = getClass().getResourceAsStream("/db/migration/" + f); var c = ds.getConnection()) {
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(" COLLATE utf8mb4_bin", "").replace("UTC_TIMESTAMP(3)", "CURRENT_TIMESTAMP(3)");
                ScriptUtils.executeSqlScript(c, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        var store = new CaptureWorkspaceStore(jdbc, mapper, Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC));
        store.initialize("a");
        service = new PublicProfileService(store, new WebTransport(mapper, Clock.systemUTC(), ""), mapper, Path.of("target/public-profile-test"), Clock.systemUTC());
    }

    @Test void resolvesExistingIosIndexAsAnUnverifiedCandidate() {
        jdbc.update("INSERT INTO external_ranking_observation(tenant_id,dedupe_key,source_key,origin_family,source_authority,chart_key,board_name,platform,region,captured_at,available_at,parser_version,completeness,requested_top_n,actual_count,max_observed_rank,contiguous_top_n,source_url,raw_sha256,raw_locator,bundle_json) VALUES('a',?,'gravity-wechat','gravity-engine','THIRD_PARTY','bestseller','畅销榜','WECHAT','cn',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1','PARTIAL',100,1,1,1,'https://example.com',?,'x','{}')", UUID.randomUUID().toString(), "a".repeat(64));
        long seed = jdbc.queryForObject("SELECT MAX(id) FROM external_ranking_observation", Long.class);
        jdbc.update("INSERT INTO external_ranking_entry(observation_id,rank_position,name,developer,evidence_locator,source_fields) VALUES(?,1,'无尽冬日','点点互动（北京）科技有限公司','fixture','{}')", seed);
        jdbc.update("INSERT INTO external_ranking_observation(tenant_id,dedupe_key,source_key,origin_family,source_authority,chart_key,board_name,platform,region,captured_at,available_at,parser_version,completeness,requested_top_n,actual_count,max_observed_rank,contiguous_top_n,source_url,raw_sha256,raw_locator,bundle_json) VALUES('a',?,'apple-games','apple','OFFICIAL','top-grossing','畅销榜','IOS','cn',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1','PARTIAL',100,1,1,1,'https://example.com',?,'x','{}')", UUID.randomUUID().toString(), "b".repeat(64));
        long ios = jdbc.queryForObject("SELECT MAX(id) FROM external_ranking_observation", Long.class);
        jdbc.update("INSERT INTO external_ranking_entry(observation_id,rank_position,external_id,name,developer,evidence_locator,source_fields) VALUES(?,1,'6478492012','无尽冬日','Shanghai Qingdi Technology Company Limited','fixture','{}')", ios);
        var result = service.candidates("a", seed, 20);
        assertEquals("qimai-public", result.get("source"));
        assertEquals(1, ((java.util.List<?>) result.get("rows")).size());
        var row = (java.util.Map<?, ?>) ((java.util.List<?>) result.get("rows")).getFirst();
        var matches = (java.util.List<?>) row.get("matches");
        assertEquals(1, matches.size());
        assertEquals("6478492012", ((java.util.Map<?, ?>) matches.getFirst()).get("candidate_external_id"));
        assertTrue(result.get("notice").toString().contains("未核验"));
    }
}
