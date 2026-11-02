package com.youkia.iaa.collection.capture;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.support.CronExpression;

/** A durable schedule, claimed atomically across workers. Missed slots coalesce into one run. */
public final class CaptureScheduleEngine {
    private final CaptureWorkspaceStore store; private final CaptureWorkspaceService workspace;
    public CaptureScheduleEngine(CaptureWorkspaceStore store,CaptureWorkspaceService workspace){this.store=store;this.workspace=workspace;}
    public static Instant next(String expressions,Instant after) {
        return Arrays.stream(expressions.split(";")).map(cron->Objects.requireNonNull(CronExpression.parse(cron).next(after.atZone(ZoneId.of("Asia/Shanghai")))).toInstant()).min(Comparator.naturalOrder()).orElseThrow();
    }
    public Map<String,Object> tick(String tenant) {
        store.initialize(tenant);
        store.jdbc.update("UPDATE capture_schedule_config SET worker_seen_at=? WHERE tenant_id=?",store.now(),tenant);
        var s=store.jdbc.queryForMap("SELECT * FROM capture_schedule_config WHERE tenant_id=?",tenant);
        Instant due=CaptureWorkspaceStore.instant(s.get("next_due_at")),lease=CaptureWorkspaceStore.instant(s.get("lease_until"));
        if(!Boolean.TRUE.equals(s.get("enabled"))||due==null||due.isAfter(store.clock.instant())||(lease!=null&&lease.isAfter(store.clock.instant())))return Map.of("executed",false);
        String token=UUID.randomUUID().toString();
        int claimed=store.jdbc.update("UPDATE capture_schedule_config SET active_token=?,lease_until=?,last_started_at=?,next_due_at=? WHERE tenant_id=? AND enabled=TRUE AND revision=? AND next_due_at=? AND (lease_until IS NULL OR lease_until<=?)",
            token,Timestamp.from(store.clock.instant().plusSeconds(1800)),store.now(),Timestamp.from(next((String)s.get("cron_expression"),store.clock.instant())),tenant,s.get("revision"),Timestamp.from(due),store.now());
        if(claimed!=1)return Map.of("executed",false);
        Map<String,Object> result;
        try{result=workspace.run(tenant,null);}catch(Exception e){result=Map.of("status","FAILED","errorCode",RankingCaptureRunner.errorCode(e));}
        store.jdbc.update("UPDATE capture_schedule_config SET last_finished_at=?,last_result_json=?,active_token=NULL,lease_until=NULL,worker_seen_at=? WHERE tenant_id=? AND active_token=?",
            store.now(),store.json(result),store.now(),tenant,token);
        return Map.of("executed",true,"result",result);
    }
}
