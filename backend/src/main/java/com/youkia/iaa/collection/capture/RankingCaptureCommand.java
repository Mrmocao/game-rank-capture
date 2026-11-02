package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.*;
import com.youkia.capture.browser.GravityBrowserFetcher;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** One-shot command: no Spring application startup, web server, seed, scheduler or report callbacks. */
public final class RankingCaptureCommand {
    public static void main(String[] args) {
        try { execute(args); }
        catch (Exception e) { System.err.println("CAPTURE_COMMAND_FAILED: " + RankingCaptureRunner.errorCode(e)); System.exit(1); }
    }
    private static void execute(String[] args) throws Exception {
        Set<String> flags = new HashSet<>(); Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (Set.of("--migrate", "--run", "--inspect", "--session-status", "--tick", "--worker", "--schedule-status", "--help").contains(args[i])) flags.add(args[i]);
            else if (Set.of("--env-file", "--output", "--replay-dir", "--schedule", "--date", "--from", "--to").contains(args[i]) && i + 1 < args.length) options.put(args[i], args[++i]);
            else throw new IllegalArgumentException("Unknown argument");
        }
        if (args.length == 0 || flags.contains("--help")) {
            System.out.println("RankingCaptureCommand [--inspect] [--session-status] [--migrate] [--run] [--tick|--worker] [--schedule-status] [--schedule daily|twice|off] [--env-file .env] [--output data/external-captures] [--replay-dir BUNDLE_DIRECTORY]"); return;
        }
        Map<String, String> config = new HashMap<>(); Path envFile = Path.of(options.getOrDefault("--env-file", ".env"));
        if (Files.exists(envFile)) for (String line : Files.readAllLines(envFile)) {
            int split = line.indexOf('='); if (split > 0 && !line.stripLeading().startsWith("#")) config.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        for (String key : List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD", "PROFILE_TENANT_ID", "CAPTURE_MATERIAL_DIR",
                "CAPTURE_BROWSER_PROFILE_DIR", "CAPTURE_BROWSER_CHANNEL", "CAPTURE_BROWSER_HEADLESS"))
            if (System.getenv(key) != null) config.put(key, System.getenv(key));
        for (String key : List.of("DB_URL", "DB_USERNAME", "DB_PASSWORD")) if (!config.containsKey(key)) throw new IllegalArgumentException("Missing database configuration");
        var ds = new DriverManagerDataSource(config.get("DB_URL"), config.get("DB_USERNAME"), config.get("DB_PASSWORD"));
        var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(30);
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        if (flags.contains("--session-status")) {
            var states = new ArrayList<Map<String,Object>>();
            for (String source : List.of("gravity-wechat", "gravity-douyin")) {
                var latest = jdbc.queryForList("SELECT id,source_key,status,error_code,started_at,finished_at FROM external_capture_run WHERE tenant_id=? AND source_key=? ORDER BY started_at DESC,id DESC LIMIT 1",
                        config.getOrDefault("PROFILE_TENANT_ID", "default"), source);
                states.add(Map.of("source", source, "latestRun", latest, "requiresLogin", !latest.isEmpty() && "SOURCE_AUTH_REQUIRED".equals(latest.getFirst().get("error_code"))));
            }
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("sources", states,
                    "notice", "仅报告最近采集结果，未发起登录检查或网络抓取；需要重新登录时运行 scripts/login-ranking-capture.ps1")));
        }
        if (flags.contains("--inspect")) {
            var result = new LinkedHashMap<String, Object>();
            result.put("database", jdbc.queryForObject("SELECT DATABASE()", String.class));
            result.put("migrations", jdbc.queryForList("SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank"));
            result.put("captureTables", jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('external_capture_run','external_ranking_observation','external_ranking_entry')"));
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        }
        if (flags.contains("--migrate")) {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
            System.out.println("CAPTURE_SCHEMA_READY");
        }
        if (!flags.contains("--run")&&!flags.contains("--tick")&&!flags.contains("--worker")&&!flags.contains("--schedule-status")&&!options.containsKey("--schedule")) return;
        Clock clock = Clock.systemUTC();
        var store = new RankingCaptureStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)), mapper, clock);
        Path root = Path.of(options.getOrDefault("--output", config.getOrDefault("CAPTURE_MATERIAL_DIR", "data/external-captures")));
        MaterialFetcher fetcher = new HttpMaterialFetcher(clock);
        String browserProfile = config.getOrDefault("CAPTURE_BROWSER_PROFILE_DIR", "");
        if (!browserProfile.isBlank()) fetcher = new GravityBrowserFetcher(Path.of(browserProfile),
                config.getOrDefault("CAPTURE_BROWSER_CHANNEL", "chrome"),
                Boolean.parseBoolean(config.getOrDefault("CAPTURE_BROWSER_HEADLESS", "true")), clock, fetcher);
        List<CaptureRequest> replayRequests = new ArrayList<>();
        if (options.containsKey("--replay-dir")) {
            Map<URI, RawMaterial> materials = new HashMap<>();
            try (var paths = Files.walk(Path.of(options.get("--replay-dir")))) {
                for (Path file : paths.filter(path -> path.getFileName().toString().equals("bundle.json")).sorted().toList()) {
                    var bundle = mapper.readValue(Files.readString(file), Observation.Bundle.class);
                    replayRequests.add(bundle.request());
                    var evidence = bundle.evidence();
                    byte[] bytes;
                    try (var input = Files.newInputStream(file.resolveSibling("raw.bin"))) { bytes = input.readNBytes(RawMaterial.MAX_BYTES + 1); }
                    var material = new RawMaterial(evidence.sourceUrl(), evidence.capturedAt(), "verified-local-replay", bytes);
                    if (!material.sha256().equals(evidence.sha256())) throw new CaptureException("HASH_MISMATCH", "重放材料哈希不一致");
                    var prior = materials.putIfAbsent(evidence.sourceUrl(), material);
                    if (prior != null && !prior.sha256().equals(material.sha256())) throw new CaptureException("AMBIGUOUS_REPLAY", "同一URL存在多份材料，请选择单一批次");
                }
            }
            fetcher = url -> { var material = materials.get(url); if (material == null) throw new CaptureException("REPLAY_MISSING", "缺少重放材料"); return material; };
        }
        var runner = new RankingCaptureRunner(store, mapper, root, clock, fetcher);
        String tenant=config.getOrDefault("PROFILE_TENANT_ID","default");
        var workspaceStore=new CaptureWorkspaceStore(jdbc,mapper,clock);
        if(flags.contains("--worker")) {
            Path logs=envFile.toAbsolutePath().getParent().resolve("data/worker-logs");Files.createDirectories(logs);
            try(var channel=java.nio.channels.FileChannel.open(logs.resolve("worker.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
                if(lock==null)return;
                var engine=new CaptureScheduleEngine(workspaceStore,new CaptureWorkspaceService(workspaceStore,runner,mapper,!browserProfile.isBlank()));
                while(!Thread.currentThread().isInterrupted()) {
                    Map<String,Object> result;
                    try{result=engine.tick(tenant);}catch(Exception e){result=Map.of("executed",false,"errorCode",RankingCaptureRunner.errorCode(e));}
                    Path log=logs.resolve("worker-"+java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))+".log");
                    Files.writeString(log,mapper.writeValueAsString(Map.of("at",clock.instant(),"result",result))+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                    Thread.sleep(60000);
                }
            }
            return;
        }
        workspaceStore.initialize(tenant);
        if(options.containsKey("--schedule")) {
            var current=workspaceStore.schedule(tenant);String frequency=options.get("--schedule");
            workspaceStore.saveSchedule(tenant,new CaptureWorkspaceStore.ScheduleEdit(!frequency.equals("off"),frequency.equals("off")?"twice":frequency,((Number)current.get("revision")).intValue()));
        }
        if(flags.contains("--tick")) {
            var result=new CaptureScheduleEngine(workspaceStore,new CaptureWorkspaceService(workspaceStore,runner,mapper,!browserProfile.isBlank())).tick(tenant);
            System.out.println(mapper.writeValueAsString(result));
        }
        if(flags.contains("--schedule-status")||options.containsKey("--schedule"))System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(workspaceStore.schedule(tenant)));
        if(!flags.contains("--run"))return;
        java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        java.time.LocalDate from=java.time.LocalDate.parse(options.getOrDefault("--from", options.getOrDefault("--date",today.toString())));
        java.time.LocalDate to=java.time.LocalDate.parse(options.getOrDefault("--to", options.getOrDefault("--date",today.toString())));
        if(to.isBefore(from)||to.isAfter(today)||java.time.temporal.ChronoUnit.DAYS.between(from,to)>6)
            throw new CaptureException("INVALID_SCOPE","一次最多7日且不能是未来日期");
        boolean hasFailure=false;
        for(java.time.LocalDate day=from;!day.isAfter(to);day=day.plusDays(1)) {
        final java.time.LocalDate selectedDay=day;
        // Apple RSS has no historical selector. Replays retain their original dates.
        var requests=options.containsKey("--replay-dir")?replayRequests.stream().distinct().toList():CaptureSources.daily(100).stream()
            .filter(r->selectedDay.equals(today)||r.sourceKey().startsWith("gravity-"))
            .map(r->new CaptureRequest(r.sourceKey(),r.region(),r.requestedTopN(),r.sourceKey().startsWith("gravity-")?selectedDay:null,r.chartKey())).toList();
        var result = runner.run(tenant, requests);
        Files.createDirectories(root);
        Path report = root.resolve("batch-" + result.get("batchId") + ".json");
        Files.writeString(report, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardOpenOption.CREATE_NEW);
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("BATCH_REPORT=" + report.toAbsolutePath());
        if (((Number) result.get("failedCount")).intValue() > 0) hasFailure=true;
        }
        if(hasFailure)System.exit(2);
    }
}
