package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.CaptureException;
import java.util.*;

/** Read model for the independent capture workspace. No product/report writes. */
public final class CaptureWorkspaceService {
    private final CaptureWorkspaceStore store;
    private final RankingCaptureRunner runner;
    private final boolean browserConfigured;
    private final Object candidates;
    public CaptureWorkspaceService(CaptureWorkspaceStore store, RankingCaptureRunner runner, ObjectMapper mapper, boolean browserConfigured) {
        this.store=store; this.runner=runner; this.browserConfigured=browserConfigured;
        try(var in=getClass().getResourceAsStream("/collection/source-register.json")) {
            candidates=mapper.readTree(Objects.requireNonNull(in)).get("sources");
        } catch(Exception e) {throw new IllegalStateException("来源目录无法读取",e);}
    }
    public Map<String,Object> overview(String tenant) {
        store.initialize(tenant);
        var targets=store.targets(tenant);
        int planned=0,complete=0,rows=0,enabled=0;
        for(var t:targets) {
            String source=(String)t.get("source_key");
            boolean implemented=source!=null, browser=implemented&&source.startsWith("gravity-");
            var latest=implemented?store.latestRuns(tenant,source,(String)t.get("region")):List.<Map<String,Object>>of();
            t.put("implemented",implemented); t.put("browserRequired",browser);
            t.put("executionReady",implemented&&(!browser||browserConfigured)&&!"REMOTE".equals(t.get("execution_mode")));
            if("REMOTE".equals(t.get("execution_mode")))t.put("remoteWorkflowUrl","https://github.com/Mrmocao/game-rank-capture/actions/workflows/overseas-capture.yml");
            t.put("authority","apple-games".equals(source)?"官方":"第三方 / 待核验");
            t.put("latestRuns",latest);t.put("chartCount",implemented?store.requests(t).size():0);
            if(Boolean.TRUE.equals(t.get("enabled"))) {
                enabled++;planned+=(Integer)t.get("chartCount");
                for(var r:latest) if("SUCCEEDED".equals(r.get("status"))&&((Number)r.getOrDefault("contiguous_top_n",0)).intValue()>=100) {
                    complete++;rows+=((Number)r.get("actual_count")).intValue();
                }
            }
        }
        return Map.of("targets",targets,"tools",CaptureToolCatalog.all(),"candidates",candidates,
            "schedule",store.schedule(tenant),"browserConfigured",browserConfigured,
            "summary",Map.of("targetCount",targets.size(),"enabledTargets",enabled,"plannedCharts",planned,"completeCharts",complete,"latestRows",rows),
            "notice","统计为所选目标各榜最近一次运行，不表示同一来源日期。微信、抖音来自引力引擎第三方榜；抓取结果独立留存，不自动写入产品库。");
    }
    public Map<String,Object> run(String tenant,String targetKey) {
        store.initialize(tenant);
        if(targetKey!=null&&"REMOTE".equals(store.target(tenant,targetKey).get("execution_mode")))throw new CaptureException("REMOTE_EXECUTOR_REQUIRED","海外来源由云端任务执行，请打开云端运行入口；本机直连失败不回填数据");
        var requests=targetKey==null?store.enabledRequests(tenant):store.requests(store.target(tenant,targetKey));
        if(requests.isEmpty())throw new CaptureException("NO_ENABLED_TARGETS","没有已启用的采集目标");
        if(!browserConfigured&&requests.stream().anyMatch(r->r.sourceKey().startsWith("gravity-")))throw new CaptureException("BROWSER_NOT_CONFIGURED","当前执行器未配置专用浏览器会话");
        var result=runner.run(tenant,requests);
        CaptureRepairService.recordFailures(store,tenant,result);
        return result;
    }
}
