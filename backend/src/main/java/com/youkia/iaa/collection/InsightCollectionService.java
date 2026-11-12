package com.youkia.iaa.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.iaa.api.ApiException;
import com.youkia.iaa.service.MarketInsightService;
import com.youkia.iaa.service.MarketWeeklySkill;
import com.youkia.iaa.service.WeeklyReportService;
import com.youkia.iaa.service.WeeklyWorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed public endpoints only. Source date is validated by the existing adapters. */
@Service @EnableScheduling
public class InsightCollectionService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;private final AppleGamesService apple;private final AppBrainService appBrain;private final WeeklyReportService reports;private final WeeklyWorkflowService workflow;
    private final boolean enabled,appBrainEnabled,weeklyEnabled;private final AtomicBoolean running=new AtomicBoolean();
    public InsightCollectionService(JdbcTemplate jdbc,ObjectMapper mapper,AppleGamesService apple,AppBrainService appBrain,WeeklyReportService reports,
      @Value("${INSIGHT_DAILY_ENABLED:false}") boolean enabled,@Value("${INSIGHT_APPBRAIN_ENABLED:false}") boolean appBrainEnabled,@Value("${INSIGHT_WEEKLY_ENABLED:false}") boolean weeklyEnabled){this(jdbc,mapper,apple,appBrain,reports,null,enabled,appBrainEnabled,weeklyEnabled);}
    @org.springframework.beans.factory.annotation.Autowired
    public InsightCollectionService(JdbcTemplate jdbc,ObjectMapper mapper,AppleGamesService apple,AppBrainService appBrain,WeeklyReportService reports,WeeklyWorkflowService workflow,
      @Value("${INSIGHT_DAILY_ENABLED:false}") boolean enabled,@Value("${INSIGHT_APPBRAIN_ENABLED:false}") boolean appBrainEnabled,@Value("${INSIGHT_WEEKLY_ENABLED:false}") boolean weeklyEnabled){this.jdbc=jdbc;this.mapper=mapper;this.apple=apple;this.appBrain=appBrain;this.reports=reports;this.workflow=workflow;this.enabled=enabled;this.appBrainEnabled=appBrainEnabled;this.weeklyEnabled=weeklyEnabled;}
    private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    @EventListener(ApplicationReadyEvent.class) public void recoverInterrupted(){jdbc.update("UPDATE insight_collection_attempt SET status='INTERRUPTED',finished_at=UTC_TIMESTAMP(3),result_json='{\"code\":\"PROCESS_RESTARTED\"}' WHERE status='RUNNING'");}
    @Scheduled(cron="${INSIGHT_DAILY_CRON:0 0 8 * * *}",zone="Asia/Shanghai") public void daily(){if(enabled)collect();}
    @Scheduled(cron="${INSIGHT_WEEKLY_CRON:0 0 9 * * MON}",zone="Asia/Shanghai") public void weekly(){if(weeklyEnabled){LocalDate end=today();attempt("WEEKLY_REPORT",()->{var report=reports.generate(end.minusDays(7),end);long id=((Number)report.get("id")).longValue();var delivery=workflow==null?Map.of("status","NOT_CONFIGURED"):workflow.ensureDelivery(id);return Map.of("reportId",id,"revision",report.get("revision"),"reused",report.get("reused"),"itemCount",((List<?>)report.get("items")).size(),"deliveryStatus",delivery.get("status"),"recipientTeam","研发");});}}
    @Scheduled(cron="${INSIGHT_WEEKLY_REVIEW_CRON:0 0 17 * * FRI}",zone="Asia/Shanghai") public void weeklyReview(){if(weeklyEnabled&&workflow!=null){LocalDate previous=WeeklyReportService.weekStart(today()).minusDays(7);var ids=jdbc.queryForList("SELECT id FROM weekly_report WHERE week_start=?",Long.class,previous);if(!ids.isEmpty())attempt("WEEKLY_REVIEW",()->Map.of("reportId",ids.getFirst(),"review",workflow.generateReview(ids.getFirst())));}}
    public Map<String,Object> collect(){
        if(!running.compareAndSet(false,true))throw new ApiException(HttpStatus.CONFLICT,"COLLECTION_RUNNING","免费来源正在采集，请稍后查看运行记录");
        try{List<Map<String,Object>> outcomes=new ArrayList<>();outcomes.add(attempt("APPLE_CN",()->apple.ingest("cn",100,null,fetch("https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json"))));
            if(appBrainEnabled)for(String region:List.of("us","gb","de","fr"))outcomes.add(attempt("APPBRAIN_"+region.toUpperCase(Locale.ROOT),()->appBrain.ingest(region,fetch("https://www.appbrain.com/stats/google-play-rankings/top_free/game/"+region))));
            return Map.of("attempts",outcomes,"appBrainEnabled",appBrainEnabled,"notice","来源日期决定快照日期；失败与旧日期材料不会伪装为今日数据。微信/抖音需提交真实渲染页面材料。");
        }finally{running.set(false);}
    }
    public Map<String,Object> status(LocalDate date){var analysis=new MarketInsightService(jdbc,mapper,new MarketWeeklySkill(mapper)).analyze(date);
        return Map.of("date",date,"dailyEnabled",enabled,"appBrainEnabled",appBrainEnabled,"weeklyEnabled",weeklyEnabled,"running",running.get(),"sources",analysis.sources(),"publicObservations",analysis.publicSources(),"attempts",jdbc.queryForList("SELECT id,source_key,requested_date,status,result_json,started_at,finished_at FROM insight_collection_attempt ORDER BY id DESC LIMIT 30"));}
    @FunctionalInterface interface Task {Map<String,Object> run() throws Exception;}
    Map<String,Object> attempt(String source,Task task){
        var holder=new GeneratedKeyHolder();jdbc.update(con->{var ps=con.prepareStatement("INSERT INTO insight_collection_attempt(source_key,requested_date,status,started_at) VALUES(?,?,'RUNNING',UTC_TIMESTAMP(3))",Statement.RETURN_GENERATED_KEYS);ps.setString(1,source);ps.setObject(2,today());return ps;},holder);long id=holder.getKey().longValue();
        Map<String,Object> result;String state;
        try{result=task.run();state="SUCCESS";}catch(Exception e){state="FAILED";result=Map.of("code",e instanceof java.net.http.HttpTimeoutException?"SOURCE_TIMEOUT":e instanceof ApiException a?a.code():e instanceof SourceFailure f?f.getMessage():e instanceof java.net.ConnectException?"SOURCE_CONNECT_FAILED":"SOURCE_UNAVAILABLE","errorType",e.getClass().getSimpleName(),"notice","本次未发布新快照；检查网络、公开页面结构及来源统计日期");}
        try{jdbc.update("UPDATE insight_collection_attempt SET status=?,result_json=?,finished_at=UTC_TIMESTAMP(3) WHERE id=?",state,mapper.writeValueAsString(result),id);}catch(Exception e){throw new IllegalStateException("采集结果无法留存",e);}
        return Map.of("id",id,"source",source,"status",state,"result",result);
    }
    private String fetch(String url) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        var req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).header("User-Agent","IaaRadar/1.0 public-source-research").GET().build();
        var response=client.send(req,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200)throw new SourceFailure("SOURCE_HTTP_"+response.statusCode());
        if(response.body().length>4_000_000)throw new SourceFailure("SOURCE_RESPONSE_TOO_LARGE");
        return new String(response.body(),StandardCharsets.UTF_8);
    }
    private static final class SourceFailure extends RuntimeException {SourceFailure(String code){super(code);}}
}
