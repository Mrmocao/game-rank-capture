package com.youkia.iaa.collection.capture;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

/** Reads actual production capture events; no browser access or report-bridge dependency. */
@Service
public class SourceSessionStatus {
    private final JdbcTemplate jdbc;private final String tenant;
    public SourceSessionStatus(JdbcTemplate jdbc,@Value("${PROFILE_TENANT_ID:default}") String tenant){this.jdbc=jdbc;this.tenant=tenant;}
    public Map<String,Object> read(){
        var failures=jdbc.queryForList("""
            SELECT r.id,r.source_key,r.chart_key,r.started_at,r.error_code
            FROM external_capture_run r
            WHERE r.tenant_id=? AND r.source_key IN ('gravity-wechat','gravity-douyin')
              AND r.error_code='SOURCE_AUTH_REQUIRED'
              AND NOT EXISTS(SELECT 1 FROM external_capture_run n WHERE n.tenant_id=r.tenant_id
                AND n.source_key=r.source_key AND n.chart_key=r.chart_key AND n.region=r.region
                AND n.status='SUCCEEDED' AND n.started_at>r.started_at)
            ORDER BY r.started_at,r.id LIMIT 1
            """,tenant);
        var recent=jdbc.queryForList("""
            SELECT r.id,r.source_key,r.chart_key,r.status,r.error_code,r.started_at,r.finished_at
            FROM external_capture_run r WHERE r.tenant_id=? AND r.source_key IN ('gravity-wechat','gravity-douyin')
            ORDER BY r.started_at DESC,r.id DESC LIMIT 1
            """,tenant);
        return Map.of("requiresLogin",!failures.isEmpty(),"event",failures.isEmpty()?Map.of():failures.getFirst(),
            "latestRun",recent.isEmpty()?Map.of():recent.getFirst(),"checkedAt",java.time.Instant.now().toString(),
            "notice","依据服务器实际采集记录；没有登录失败记录不代表当前会话仍有效。续登后需真实采集成功才解除提醒。");
    }
}
