package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.youkia.capture.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Workspace configuration; deliberately only accesses capture_* and external_* tables. */
public final class CaptureWorkspaceStore {
    final JdbcTemplate jdbc; final ObjectMapper mapper; final Clock clock;
    public CaptureWorkspaceStore(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) { this.jdbc=jdbc;this.mapper=mapper;this.clock=clock; }
    public void initialize(String tenant) {
        seed(tenant,"apple-cn","Apple · 中国游戏免费/付费/畅销榜","apple-games","cn","http-json","官方游戏免费、付费、畅销3榜各Top100、Apple ID、名称、开发者、图标、来源更新时间",true);
        seed(tenant,"wechat-cn","引力引擎 · 微信小游戏","gravity-wechat","cn","browser-scroll","人气、畅销、畅玩3榜各100；名称、名次、发行方、标签、变化原文",true);
        seed(tenant,"douyin-cn","引力引擎 · 抖音小游戏","gravity-douyin","cn","browser-scroll","热门、畅销、新游、发行人热度4榜各100；名称、名次、发行方、标签",true);
        for(String region:List.of("us","gb","de","fr")) seed(tenant,"appbrain-"+region,"AppBrain · "+region.toUpperCase(Locale.ROOT)+" 游戏五类榜单","appbrain",region,"http-html","第三方Google Play免费、付费、畅销、新免费、新付费5榜；公开缺行保留；云端采集回传",false);
        jdbc.update("INSERT IGNORE INTO capture_schedule_config(tenant_id,enabled,cron_expression,timezone,revision,updated_at) VALUES(?,FALSE,'0 30 8 * * *','Asia/Shanghai',0,?)",tenant,now());
    }
    private void seed(String tenant,String key,String name,String source,String region,String tool,String fields,boolean enabled) {
        CaptureRequest request = new CaptureRequest(source,region,100,null);
        String url = CaptureSources.adapters(mapper).stream().filter(a->a.key().equals(source)).findFirst().orElseThrow().sourceUrl(request).toString();
        jdbc.update("INSERT IGNORE INTO capture_target_config(tenant_id,target_key,name,source_url,source_key,region,tool_key,field_scope,notes,enabled,execution_mode,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
            tenant,key,name,url,source,region,tool,fields,"",enabled,source.equals("appbrain")?"REMOTE":"SERVER",now(),now());
    }
    public List<Map<String,Object>> targets(String tenant) { return jdbc.queryForList("SELECT target_key,name,source_url,source_key,region,tool_key,field_scope,notes,enabled,execution_mode,revision,updated_at FROM capture_target_config WHERE tenant_id=? ORDER BY created_at,target_key",tenant); }
    public Map<String,Object> target(String tenant,String key) { return targets(tenant).stream().filter(r->key.equals(r.get("target_key"))).findFirst().orElseThrow(()->new IllegalArgumentException("采集目标不存在")); }
    public record TargetEdit(String name,String url,String fields,String notes,String toolKey,boolean enabled,int revision) {}
    public Map<String,Object> update(String tenant,String key,TargetEdit edit) {
        var prior=target(tenant,key); validateEdit(edit);
        if(prior.get("source_key")!=null && (!edit.url().equals(prior.get("source_url")) || !edit.toolKey().equals(prior.get("tool_key"))))
            throw new CaptureException("FIXED_SOURCE_SCOPE","已实现适配器的URL与工具绑定受来源范围约束，请通过独立工具代码修改并验证");
        if(prior.get("source_key")==null && edit.enabled()) {
            if(!Set.of("firecrawl","web-http").contains(edit.toolKey()))throw new CaptureException("ADAPTER_NOT_IMPLEMENTED","请先选择支持在线规则的工具并发布规则");
            Integer published=jdbc.queryForObject("SELECT COUNT(*) FROM capture_web_rule WHERE tenant_id=? AND target_key=? AND published_version IS NOT NULL",Integer.class,tenant,key);
            if(published==null||published==0)throw new CaptureException("RULE_NOT_PUBLISHED","请先试抓并发布规则，再纳入定时范围");
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM capture_target_config WHERE tenant_id=? AND source_key IS NULL AND enabled=TRUE AND target_key<>?",Integer.class,tenant,key);
            if(count!=null&&count>=5)throw new CaptureException("WEB_TARGET_LIMIT","首版每批最多启用5个在线规则网站");
        }
        if(prior.get("source_key")==null&&!edit.toolKey().equals(prior.get("tool_key"))) {
            Integer rules=jdbc.queryForObject("SELECT COUNT(*) FROM capture_web_rule WHERE tenant_id=? AND target_key=?",Integer.class,tenant,key);
            if(rules!=null&&rules>0)throw new CaptureException("RULE_TOOL_FIXED","已有规则的网站需保持工具绑定；更换工具请登记新目标");
        }
        if(prior.get("source_key")==null&&!edit.url().equals(prior.get("source_url"))) {
            Integer rules=jdbc.queryForObject("SELECT COUNT(*) FROM capture_web_rule WHERE tenant_id=? AND target_key=?",Integer.class,tenant,key);
            if(rules!=null&&rules>0)throw new CaptureException("RULE_URL_MANAGED","请在在线规则中修改URL，试抓并发布后生效");
        }
        int changed=jdbc.update("UPDATE capture_target_config SET name=?,source_url=?,field_scope=?,notes=?,tool_key=?,enabled=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND target_key=? AND revision=?",
            edit.name().trim(),edit.url().trim(),edit.fields().trim(),edit.notes(),edit.toolKey(),edit.enabled(),now(),tenant,key,edit.revision());
        if(changed!=1) throw new CaptureException("CONFIG_CONFLICT","配置已被修改，请刷新后重试");
        return target(tenant,key);
    }
    public Map<String,Object> add(String tenant,TargetEdit edit) {
        validateEdit(edit); if(edit.enabled())throw new CaptureException("ADAPTER_NOT_IMPLEMENTED","新增网站先登记，不自动执行未知地址");
        String key="site-"+UUID.randomUUID();
        jdbc.update("INSERT INTO capture_target_config(tenant_id,target_key,name,source_url,source_key,region,tool_key,field_scope,notes,enabled,revision,created_at,updated_at) VALUES(?,?,?,?,NULL,'unknown',?,?,?,FALSE,0,?,?)",
            tenant,key,edit.name().trim(),edit.url().trim(),edit.toolKey(),edit.fields().trim(),edit.notes(),now(),now());
        return target(tenant,key);
    }
    private void validateEdit(TargetEdit e) {
        if(e==null||e.name()==null||e.name().isBlank()||e.name().length()>200||e.fields()==null||e.fields().length()>4000||e.notes()==null||e.notes().length()>4000||e.url()==null||e.url().length()>1000)throw new IllegalArgumentException("名称、字段说明、备注或URL无效");
        com.youkia.capture.web.PublicWebUrl.syntax(e.url().trim());
        CaptureToolCatalog.get(e.toolKey());
    }
    public List<CaptureRequest> requests(Map<String,Object> target) {
        String source=(String)target.get("source_key"); String region=(String)target.get("region");
        if(source==null)throw new CaptureException("ADAPTER_NOT_IMPLEMENTED","仅登记的网站尚无可执行适配器");
        if(source.startsWith("gravity-"))return CaptureSources.daily(100).stream().filter(r->source.equals(r.sourceKey())).toList();
        return (source.equals("apple-games")?StoreCharts.APPLE:StoreCharts.GOOGLE).stream().map(chart->new CaptureRequest(source,region,100,null,chart)).toList();
    }
    public List<CaptureRequest> enabledRequests(String tenant) { return targets(tenant).stream().filter(t->Boolean.TRUE.equals(t.get("enabled"))&&t.get("source_key")!=null&&!"REMOTE".equals(t.get("execution_mode"))).flatMap(t->requests(t).stream()).toList(); }
    public List<Map<String,Object>> latestRuns(String tenant,String source,String region) {
        return jdbc.queryForList("SELECT r.id,r.source_key,r.chart_key,r.region,r.status,r.error_code,r.freshness,r.started_at,r.finished_at,r.observation_id,o.actual_count,o.contiguous_top_n,o.business_date,o.source_authority FROM external_capture_run r LEFT JOIN external_ranking_observation o ON o.id=r.observation_id AND o.tenant_id=r.tenant_id WHERE r.tenant_id=? AND r.source_key=? AND r.region=? AND NOT EXISTS (SELECT 1 FROM external_capture_run newer WHERE newer.tenant_id=r.tenant_id AND newer.source_key=r.source_key AND newer.region=r.region AND newer.chart_key=r.chart_key AND (newer.started_at>r.started_at OR (newer.started_at=r.started_at AND newer.id>r.id))) ORDER BY r.chart_key",tenant,source,region);
    }
    public Map<String,Object> schedule(String tenant) {
        var row=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM capture_schedule_config WHERE tenant_id=?",tenant));
        row.remove("active_token");row.remove("lease_until");row.remove("tenant_id");
        Instant seen=instant(row.get("worker_seen_at"));
        row.put("workerOnline",seen!=null && seen.isAfter(clock.instant().minusSeconds(180)));
        row.put("last_result_json",read(row.get("last_result_json")));return row;
    }
    public record ScheduleEdit(boolean enabled,String frequency,int revision) {}
    public Map<String,Object> saveSchedule(String tenant,ScheduleEdit edit) {
        String cron=switch(edit.frequency()){case "daily"->"0 30 8 * * *";case "twice"->"0 30 8 * * *;0 0 23 * * *";default->throw new IllegalArgumentException("仅支持每日08:30或每日08:30及23:00");};
        if(edit.enabled()&&enabledRequests(tenant).isEmpty()&&targets(tenant).stream().noneMatch(t->OnlineWebCaptureService.dynamic(t)&&Boolean.TRUE.equals(t.get("enabled"))))throw new CaptureException("NO_ENABLED_TARGETS","请先选定至少一个已接入采集目标");
        var next=CaptureScheduleEngine.next(cron,clock.instant());
        int n=jdbc.update("UPDATE capture_schedule_config SET enabled=?,cron_expression=?,revision=revision+1,next_due_at=?,updated_at=? WHERE tenant_id=? AND revision=?",
            edit.enabled(),cron,edit.enabled()?Timestamp.from(next):null,now(),tenant,edit.revision());
        if(n!=1)throw new CaptureException("CONFIG_CONFLICT","计划已被修改，请刷新");return schedule(tenant);
    }
    Object read(Object raw){if(raw==null)return null;try{var n=mapper.readTree(raw instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):raw.toString());return n.isTextual()?mapper.readTree(n.asText()):n;}catch(Exception e){throw new IllegalStateException("采集JSON无法解析");}}
    String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    Timestamp now(){return Timestamp.from(clock.instant());}
    static Instant instant(Object value){if(value==null)return null;if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(value instanceof Instant t)return t;throw new IllegalArgumentException("Unsupported database timestamp");}
}
