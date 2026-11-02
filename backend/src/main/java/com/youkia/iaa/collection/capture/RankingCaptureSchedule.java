package com.youkia.iaa.collection.capture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** No report generation or product ingestion; disabled unless explicitly configured. */
@Component
public class RankingCaptureSchedule {
    private final CaptureScheduleEngine engine;
    private final boolean enabled;
    private final String tenant;
    public RankingCaptureSchedule(CaptureScheduleEngine engine, @Value("${CAPTURE_WORKER_ENABLED:true}") boolean enabled,
            @Value("${PROFILE_TENANT_ID:default}") String tenant) { this.engine=engine; this.enabled=enabled; this.tenant=tenant; }
    @Scheduled(fixedDelay=60000,initialDelay=5000)
    public void poll() { if(enabled)engine.tick(tenant); }
}
