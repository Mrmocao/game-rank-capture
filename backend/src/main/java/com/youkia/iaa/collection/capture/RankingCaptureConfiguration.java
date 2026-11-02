package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.*;
import com.youkia.capture.browser.GravityBrowserFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
public class RankingCaptureConfiguration {
    @Bean CaptureWorkspaceStore captureWorkspaceStore(JdbcTemplate jdbc,ObjectMapper mapper){return new CaptureWorkspaceStore(jdbc,mapper,Clock.systemUTC());}
    @Bean CaptureWorkspaceService captureWorkspaceService(CaptureWorkspaceStore store,RankingCaptureRunner runner,ObjectMapper mapper,@Value("${CAPTURE_BROWSER_PROFILE_DIR:}")String profile){return new CaptureWorkspaceService(store,runner,mapper,!profile.isBlank());}
    @Bean CaptureScheduleEngine captureScheduleEngine(CaptureWorkspaceStore store,CaptureWorkspaceService workspace){return new CaptureScheduleEngine(store,workspace);}
    @Bean CaptureRepairAiClient captureRepairAiClient(ObjectMapper mapper){return new CaptureRepairAiClient(mapper);}
    @Bean CaptureRepairService captureRepairService(CaptureWorkspaceStore store,CaptureWorkspaceService workspace,CaptureRepairAiClient client,com.youkia.iaa.service.AiConfigService ai){return new CaptureRepairService(store,workspace,client,ai);}
    @Bean RankingCaptureStore rankingCaptureStore(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper mapper) {
        return new RankingCaptureStore(jdbc, new TransactionTemplate(transactions), mapper, Clock.systemUTC());
    }
    @Bean RankingCaptureRunner rankingCaptureRunner(RankingCaptureStore store, ObjectMapper mapper,
            @Value("${CAPTURE_MATERIAL_DIR:./data/external-captures}") String root,
            @Value("${CAPTURE_BROWSER_PROFILE_DIR:}") String profile,
            @Value("${CAPTURE_BROWSER_CHANNEL:chrome}") String channel,
            @Value("${CAPTURE_BROWSER_HEADLESS:true}") boolean headless) {
        var clock = Clock.systemUTC();
        MaterialFetcher fetcher = new HttpMaterialFetcher(clock);
        if (!profile.isBlank()) fetcher = new GravityBrowserFetcher(Path.of(profile), channel, headless, clock, fetcher);
        return new RankingCaptureRunner(store, mapper, Path.of(root), clock, fetcher);
    }
}
