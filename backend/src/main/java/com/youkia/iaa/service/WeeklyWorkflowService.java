package com.youkia.iaa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/**
 * Turns the weekly shortlist into a traceable Monday handoff and Friday review.
 * It never sends external messages; the handoff package is persisted and can be
 * downloaded or acknowledged by the intended R&D team.
 */
@Service
public class WeeklyWorkflowService {
    public static final String MECHANISM_VERSION = "weekly-workflow-1.0.0";
    private static final String RECIPIENT = "研发";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WeeklyWorkflowService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> ensureDelivery(long reportId) {
        Map<String, Object> report = jdbc.queryForMap("SELECT id,week_start,revision,skill_snapshot,research_snapshot FROM weekly_report WHERE id=?", reportId);
        LocalDate week = date(report.get("week_start"));
        Map<String, Object> pack = deliveryPackage(report);
        String json = json(pack);
        jdbc.update("""
            INSERT INTO weekly_report_delivery(report_id,week_start,recipient_team,status,package_json,delivered_at)
            VALUES(?,?,?,'READY',?,UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE week_start=VALUES(week_start),package_json=VALUES(package_json),
              delivered_at=UTC_TIMESTAMP(3),status=IF(status='ACKNOWLEDGED','ACKNOWLEDGED','READY'),updated_at=UTC_TIMESTAMP(3)
            """, reportId, week, RECIPIENT, json);
        return delivery(reportId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> delivery(long reportId) {
        try {
            var row = jdbc.queryForMap("SELECT * FROM weekly_report_delivery WHERE report_id=? AND recipient_team=?", reportId, RECIPIENT);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", row.get("id")); out.put("reportId", row.get("report_id"));
            out.put("weekStart", row.get("week_start")); out.put("recipientTeam", row.get("recipient_team"));
            out.put("status", row.get("status")); out.put("package", read(row.get("package_json")));
            out.put("deliveredAt", row.get("delivered_at")); out.put("acknowledgedAt", row.get("acknowledged_at"));
            out.put("acknowledgedBy", row.get("acknowledged_by")); out.put("note", row.get("note"));
            return out;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Map.of("reportId", reportId, "status", "NOT_CREATED", "recipientTeam", RECIPIENT);
        }
    }

    @Transactional
    public Map<String, Object> acknowledge(long reportId, String actor, String note) {
        String who = actor == null || actor.isBlank() ? "研发成员" : actor.trim();
        jdbc.update("UPDATE weekly_report_delivery SET status='ACKNOWLEDGED',acknowledged_at=UTC_TIMESTAMP(3),acknowledged_by=?,note=?,updated_at=UTC_TIMESTAMP(3) WHERE report_id=? AND recipient_team=?", who, blankToNull(note), reportId, RECIPIENT);
        return delivery(reportId);
    }

    @Transactional
    public Map<String, Object> generateReview(long reportId) {
        Map<String, Object> report = jdbc.queryForMap("SELECT id,week_start,revision,skill_snapshot,research_snapshot,candidate_audit FROM weekly_report WHERE id=?", reportId);
        Map<String, Object> review = reviewDocument(report);
        jdbc.update("""
            INSERT INTO weekly_report_review(report_id,week_start,status,review_json,generated_at)
            VALUES(?,?,'PUBLISHED',?,UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE week_start=VALUES(week_start),review_json=VALUES(review_json),status='PUBLISHED',generated_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3)
            """, reportId, date(report.get("week_start")), json(review));
        return review(reportId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> review(long reportId) {
        try {
            var row = jdbc.queryForMap("SELECT * FROM weekly_report_review WHERE report_id=?", reportId);
            var out = new LinkedHashMap<String, Object>();
            out.put("id", row.get("id")); out.put("reportId", row.get("report_id")); out.put("weekStart", row.get("week_start"));
            out.put("status", row.get("status")); out.put("review", read(row.get("review_json"))); out.put("generatedAt", row.get("generated_at"));
            return out;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Map.of("reportId", reportId, "status", "NOT_CREATED");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latestReview() {
        try {
            long id = jdbc.queryForObject("SELECT report_id FROM weekly_report_review ORDER BY week_start DESC LIMIT 1", Long.class);
            return review(id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Map.of("status", "NOT_CREATED");
        }
    }

    private Map<String, Object> deliveryPackage(Map<String, Object> report) {
        JsonNode research = read(report.get("research_snapshot"));
        JsonNode leads = research.path("captureResearchLeads").path("items");
        List<Map<String, Object>> items = new ArrayList<>();
        JsonNode formal = research.path("result").path("selected");
        if (formal.isArray()) for (JsonNode n : formal) items.add(item(n, "正式候选"));
        if (leads.isArray()) for (JsonNode n : leads) items.add(item(n, "待核研究"));
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("title", "每周精选研发交付包"); pack.put("workflowVersion", MECHANISM_VERSION);
        pack.put("reportId", report.get("id")); pack.put("weekStart", report.get("week_start")); pack.put("revision", report.get("revision"));
        pack.put("recipientTeam", RECIPIENT); pack.put("mechanismSummary", mechanismSummary()); pack.put("items", items);
        pack.put("rAndDAction", List.of("先做产品身份与玩法核验", "再做版本级广告/内购验证", "通过验证后登记选用记录，不以榜单名次直接立项"));
        pack.put("sourceBoundary", "榜单名次是研究信号，不等于用户增长、纯IAA资格或成功概率；每项保留来源日期和缺口。");
        return pack;
    }

    private Map<String, Object> reviewDocument(Map<String, Object> report) {
        JsonNode research = read(report.get("research_snapshot"));
        JsonNode leads = research.path("captureResearchLeads");
        JsonNode leadItems = leads.path("items");
        JsonNode portfolio = leads.path("allocation");
        int formal = research.path("result").path("selected").isArray() ? research.path("result").path("selected").size() : 0;
        int researchCount = leadItems.isArray() ? leadItems.size() : 0;
        int unknownGameplay = 0, missingCurrent = 0, missingObserved = 0;
        if (leadItems.isArray()) for (JsonNode n : leadItems) {
            if ("未知".equals(n.path("family").asText())) unknownGameplay++;
            if ("MISSING_ON_DATE".equals(n.path("currentState").asText())) missingCurrent++;
            if (n.path("observationDays").asInt(0) < 7) missingObserved++;
        }
        List<String> findings = new ArrayList<>();
        if (formal == 0) findings.add("本周没有通过纯IAA正式资格的对象，研发交付应按待核研究线索处理。");
        if (researchCount < 5) findings.add("严格证据不足，精选未强行补齐到5项；空席不应由成熟热门替代。");
        if (unknownGameplay > 0) findings.add("有" + unknownGameplay + "项玩法仍待识别，需补来源描述和实机记录。");
        if (missingCurrent > 0) findings.add("有" + missingCurrent + "项截止日缺采，下一周先修复来源覆盖，不能沿用昨日名次。");
        if (missingObserved > 0) findings.add("有" + missingObserved + "项观察天数不足7日，趋势判断应标注为有限证据。");
        if (findings.isEmpty()) findings.add("本周证据覆盖达到当前规则要求，继续观察跨平台走势和研发验证结果。");
        List<String> suggestions = new ArrayList<>();
        if (missingCurrent > 0) suggestions.add("P0：周一生成前完成海外及国内来源截止日前补采，来源失败只保留缺失状态并告警。");
        if (unknownGameplay > 0) suggestions.add("P0：给入选项补稳定应用ID、玩法原文和实机截图；缺玩法不升级为新赛道结论。");
        suggestions.add("P1：周五把研发选用、24—48小时验证结果和未选原因回写，作为下一周规则回测样本。");
        suggestions.add("P1：累计4周后回测新入榜提前量、加速误报率和海外覆盖率，再决定是否调整阈值。");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "上周推荐规则总结与迭代说明"); out.put("workflowVersion", MECHANISM_VERSION);
        out.put("reportId", report.get("id")); out.put("weekStart", report.get("week_start")); out.put("revision", report.get("revision"));
        out.put("currentMechanism", mechanismSummary());
        out.put("resultSummary", Map.of("formalCount", formal, "researchCount", researchCount, "unknownGameplay", unknownGameplay, "missingCurrent", missingCurrent, "insufficientObservation", missingObserved));
        out.put("findings", findings); out.put("iterationSuggestions", suggestions);
        out.put("nextMondayChecklist", List.of("确认上周复盘已发布", "核对来源截止日覆盖", "生成本周最多5项清单", "生成研发交付包并等待确认"));
        out.put("limitations", "规则复盘基于冻结周报和已登记反馈，不把榜单名次变化解释为用户量或收益变化；没有研发反馈时，只能提出数据和流程迭代建议。");
        return out;
    }

    public static Map<String, Object> mechanismSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goal", "尽早发现可验证的新赛道、玩法和增长信号，避免只复刻成熟头部");
        m.put("quota", "核心4席：成熟基准2席（50%）、新入榜1席（25%）、单榜加速1席（25%）；严格席不足时研究补位，合计最多5项");
        m.put("evidence", "研究补位至少5/7日观察，最近3日连续有证据，Top20或上涨至少10名且Top50；整榜最多延迟1日，缺失不回填");
        m.put("crossPlatform", "统一产品关联后展示各平台独立证据卡；不平均跨榜名次、不跨榜合分，同平台同稳定应用ID跨国去重");
        m.put("gates", "纯IAA、玩法、身份和版本证据独立校验；未知保持待核，付费/畅销风险不进入研究补位");
        m.put("output", "周一冻结周报并生成研发交付包；周五复盘上周结果、证据缺口和规则迭代建议");
        return m;
    }

    private Map<String, Object> item(JsonNode n, String type) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("name", text(n, "name", text(n, "product_name", "未知产品"))); x.put("platform", text(n, "platform", "未知平台"));
        x.put("region", text(n, "region", "未知市场")); x.put("type", type); x.put("route", text(n, "route", text(n, "signalLabel", "待核")));
        x.put("whyNow", text(n, "whyNow", text(n, "action", "按冻结证据进一步核验"))); x.put("gaps", n.has("gaps") ? n.get("gaps") : n.path("unresolvedFields"));
        x.put("sourceDate", text(n, "latestObservedDate", text(n, "business_date", "未知"))); x.put("currentState", text(n, "currentState", "见冻结证据"));
        return x;
    }

    private JsonNode read(Object value) { try { return value instanceof JsonNode n ? n : mapper.readTree(value == null ? "{}" : String.valueOf(value)); } catch (Exception e) { return mapper.createObjectNode(); } }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("工作流摘要无法序列化", e); } }
    private LocalDate date(Object value) { return value instanceof LocalDate d ? d : LocalDate.parse(String.valueOf(value)); }
    private static String text(JsonNode n, String key, String fallback) { return n.hasNonNull(key) ? n.get(key).asText() : fallback; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
