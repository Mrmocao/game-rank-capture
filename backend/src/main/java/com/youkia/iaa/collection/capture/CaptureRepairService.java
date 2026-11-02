package com.youkia.iaa.collection.capture;

import com.youkia.capture.CaptureException;
import com.youkia.iaa.service.AiConfigService;
import java.sql.Timestamp;
import java.util.*;

/** Persistent, tenant-scoped diagnosis jobs. Only a real successful rerun can recover a job. */
public final class CaptureRepairService {
    private final CaptureWorkspaceStore store;private final CaptureWorkspaceService workspace;
    private final CaptureRepairAiClient client;private final AiConfigService ai;
    public CaptureRepairService(CaptureWorkspaceStore store,CaptureWorkspaceService workspace,CaptureRepairAiClient client,AiConfigService ai){this.store=store;this.workspace=workspace;this.client=client;this.ai=ai;}
    public List<Map<String,Object>> list(String tenant) {
        // A crashed request cannot leave an unclaimable job forever. Network and capture timeouts are shorter than these leases.
        store.jdbc.update("UPDATE capture_repair_job SET status='AI_FAILED',ai_error_code='AI_INTERRUPTED',updated_at=? WHERE tenant_id=? AND status='AI_RUNNING' AND updated_at<?",store.now(),tenant,Timestamp.from(store.clock.instant().minusSeconds(300)));
        store.jdbc.update("UPDATE capture_repair_job SET status='STILL_FAILING',ai_error_code='RETRY_INTERRUPTED',updated_at=? WHERE tenant_id=? AND status='RETRYING' AND updated_at<?",store.now(),tenant,Timestamp.from(store.clock.instant().minusSeconds(1800)));
        var rows=store.jdbc.queryForList("SELECT * FROM capture_repair_job WHERE tenant_id=? ORDER BY updated_at DESC LIMIT 100",tenant);
        rows.forEach(this::decode);return rows;
    }
    public Map<String,Object> get(String tenant,String id) {
        var rows=store.jdbc.queryForList("SELECT * FROM capture_repair_job WHERE tenant_id=? AND id=?",tenant,id);
        if(rows.isEmpty())throw new CaptureException("REPAIR_NOT_FOUND","修复工单不存在");var row=rows.getFirst();decode(row);return row;
    }
    private void decode(Map<String,Object> row){row.remove("tenant_id");row.put("context_json",store.read(row.get("context_json")));row.put("proposal_json",store.read(row.get("proposal_json")));}
    public Map<String,Object> create(String tenant,String runId) {
        return create(store,tenant,runId);
    }
    public static void recordFailures(CaptureWorkspaceStore store,String tenant,Map<String,Object> result) {
        if(!(result.get("attempts") instanceof List<?> attempts))return;
        for(Object value:attempts)if(value instanceof Map<?,?> attempt&&attempt.get("runId") instanceof String id&&Set.of("FAILED","PARTIAL","INTERRUPTED").contains(attempt.get("status")))create(store,tenant,id);
    }
    private static Map<String,Object> create(CaptureWorkspaceStore store,String tenant,String runId) {
        store.initialize(tenant);
        var runs=store.jdbc.queryForList("SELECT id,source_key,chart_key,region,status,error_code,requested_top_n,source_url,raw_sha256,raw_locator,requested_date FROM external_capture_run WHERE tenant_id=? AND id=?",tenant,runId);
        if(runs.isEmpty())throw new CaptureException("RUN_NOT_FOUND","运行不存在");var run=runs.getFirst();
        if(!Set.of("FAILED","PARTIAL","INTERRUPTED").contains(run.get("status")))throw new CaptureException("REPAIR_NOT_NEEDED","仅为失败或不完整的运行创建工单");
        var target=store.targets(tenant).stream().filter(t->Objects.equals(t.get("source_key"),run.get("source_key"))&&Objects.equals(t.get("region"),run.get("region"))).findFirst().orElseThrow(()->new CaptureException("TARGET_NOT_FOUND","未找到对应目标"));
        var tool=CaptureToolCatalog.get((String)target.get("tool_key"));
        String error=Objects.toString(run.get("error_code"),"INCOMPLETE_TOP100");
        var context=new LinkedHashMap<String,Object>();context.put("run",run);context.put("tool",tool);
        context.put("scope","仅修复外部抓取工具；不得修改产品/报告业务或补造数据。保留来源日、名次、原始材料哈希和证据位置。");
        context.put("evidencePolicy","只传来源范围、错误码、哈希和材料相对路径；不上传页面原文、Cookie、浏览器会话或账户信息。Agent在授权的本地材料目录读取证据。");
        context.put("acceptance",List.of("先用留存材料复现失败并添加规则回归测试","只修改tool.files范围内相关实现；新依赖需说明","通过工具测试后在工作台点击实际重试；连续1–100且全部请求成功才标恢复","登录或验证码失效交给用户手工处理"));
        store.jdbc.update("INSERT IGNORE INTO capture_repair_job(id,tenant_id,run_id,target_key,tool_key,error_code,status,context_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),tenant,runId,target.get("target_key"),tool.key(),error,"SOURCE_AUTH_REQUIRED".equals(error)?"NEEDS_LOGIN":"OPEN",store.json(context),store.now(),store.now());
        String id=store.jdbc.queryForObject("SELECT id FROM capture_repair_job WHERE tenant_id=? AND run_id=?",String.class,tenant,runId);
        var saved=store.jdbc.queryForMap("SELECT * FROM capture_repair_job WHERE tenant_id=? AND id=?",tenant,id);saved.remove("tenant_id");saved.put("context_json",store.read(saved.get("context_json")));saved.put("proposal_json",store.read(saved.get("proposal_json")));return saved;
    }
    public Map<String,Object> diagnose(String tenant,String id) {
        var job=get(tenant,id);
        if("NEEDS_LOGIN".equals(job.get("status")))throw new CaptureException("SOURCE_AUTH_REQUIRED","请手工重新登录专用Chrome后实际重试，AI不能代替登录");
        int n=store.jdbc.update("UPDATE capture_repair_job SET status='AI_RUNNING',ai_error_code=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status IN ('OPEN','AI_SUGGESTED','AI_FAILED','STILL_FAILING')",store.now(),tenant,id);
        if(n!=1)throw new CaptureException("REPAIR_BUSY","工单正在运行或已恢复");
        try {
            var proposal=client.diagnose(ai.runtime(),job.get("context_json"));
            store.jdbc.update("UPDATE capture_repair_job SET status='AI_SUGGESTED',proposal_json=?,updated_at=? WHERE tenant_id=? AND id=? AND status='AI_RUNNING'",store.json(proposal),store.now(),tenant,id);
        }catch(Exception e){store.jdbc.update("UPDATE capture_repair_job SET status='AI_FAILED',ai_error_code=?,updated_at=? WHERE tenant_id=? AND id=? AND status='AI_RUNNING'",RankingCaptureRunner.errorCode(e),store.now(),tenant,id);}
        return get(tenant,id);
    }
    public Map<String,Object> retry(String tenant,String id) {
        var job=get(tenant,id);
        int n=store.jdbc.update("UPDATE capture_repair_job SET status='RETRYING',updated_at=? WHERE tenant_id=? AND id=? AND status NOT IN ('AI_RUNNING','RETRYING','RECOVERED')",store.now(),tenant,id);
        if(n!=1)throw new CaptureException("REPAIR_BUSY","工单正在运行或已恢复");
        try {
            var result=workspace.run(tenant,(String)job.get("target_key"));
            store.jdbc.update("UPDATE capture_repair_job SET status=?,retry_batch_id=?,ai_error_code=NULL,updated_at=? WHERE tenant_id=? AND id=? AND status='RETRYING'",
                "SUCCEEDED".equals(result.get("status"))?"RECOVERED":"STILL_FAILING",result.get("batchId"),store.now(),tenant,id);
        }catch(Exception e){store.jdbc.update("UPDATE capture_repair_job SET status='STILL_FAILING',ai_error_code=?,updated_at=? WHERE tenant_id=? AND id=? AND status='RETRYING'",RankingCaptureRunner.errorCode(e),store.now(),tenant,id);}
        return get(tenant,id);
    }
    public Map<String,Object> aiStatus(){var config=ai.get();return Map.of("enabled",Boolean.TRUE.equals(config.get("enabled")),"configured",Boolean.TRUE.equals(config.get("apiKeyConfigured")),"model",Objects.toString(config.get("model"),""));}
}
