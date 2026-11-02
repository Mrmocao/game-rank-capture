package com.youkia.iaa.collection.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkia.capture.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Fixed-source batch coordinator. All writes stay in the capture store and evidence directory. */
public final class RankingCaptureRunner {
    private final RankingCaptureStore store;
    private final ObjectMapper mapper;
    private final Path root;
    private final Clock clock;
    private final MaterialFetcher fetcher;
    private final List<SourceAdapter> adapters;
    public RankingCaptureRunner(RankingCaptureStore store, ObjectMapper mapper, Path root, Clock clock, MaterialFetcher fetcher) {
        this.store = store; this.mapper = mapper; this.root = root.toAbsolutePath().normalize(); this.clock = clock; this.fetcher = fetcher;
        this.adapters = CaptureSources.adapters(mapper);
    }
    public List<Map<String, Object>> sources() {
        return CaptureSources.daily(100).stream().map(request -> {
            Map<String, Object> item = new LinkedHashMap<>(); item.put("sourceKey", request.sourceKey()); item.put("chartKey", request.chartKey());
            item.put("region", request.region()); item.put("requestedTopN", 100); item.put("url", adapter(request).sourceUrl(request));
            item.put("authority", request.sourceKey().equals("apple-games") ? "OFFICIAL" : "THIRD_PARTY");
            item.put("publicLimit", request.sourceKey().startsWith("gravity-") ? 20 : 100); return item;
        }).toList();
    }
    public Map<String, Object> run(String tenant, List<CaptureRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 20) throw new CaptureException("INVALID_SCOPE", "一次需要1至20个榜单请求");
        Set<String> scopes = new HashSet<>();
        for (var request : requests) {
            if (request == null) throw new CaptureException("INVALID_SCOPE", "榜单请求不能为null");
            adapter(request).sourceUrl(request);
            if (!scopes.add(request.sourceKey() + ":" + request.chartKey() + ":" + request.region()))
                throw new CaptureException("INVALID_SCOPE", "同一批次不能重复请求同榜同地区");
        }
        String batch = UUID.randomUUID().toString(); List<Map<String, Object>> outcomes = new ArrayList<>();
        Map<URI, RawMaterial> cache = new HashMap<>(); Map<URI, Exception> failures = new HashMap<>();
        for (var request : requests) {
            URI url = adapter(request).sourceUrl(request); String run = null;
            try {
                run = store.begin(tenant, batch, request, url.toString());
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (failures.containsKey(url)) throw failures.get(url);
                RawMaterial material = cache.get(url);
                if (material == null) {
                    try { material = fetcher.fetch(url, request.expectedSourceDate()); cache.put(url, material); }
                    catch (Exception e) { failures.put(url, e); throw e; }
                }
                Path runDir = root.resolve(run); Files.createDirectories(runDir);
                Path rawFile = runDir.resolve("received.bin");
                Files.write(rawFile, material.bytes(), StandardOpenOption.CREATE_NEW);
                Files.writeString(runDir.resolve("received.json"), mapper.writeValueAsString(Map.of("sourceUrl", url.toString(),
                        "capturedAt", material.capturedAt(), "sha256", material.sha256(), "transport", material.transport())), StandardOpenOption.CREATE_NEW);
                store.fetched(tenant, run, material, relative(rawFile));
                var sink = new FileCaptureSink(runDir, mapper);
                var bundle = new CapturePipeline(adapters, fetcher, sink, clock).accept(request, material);
                outcomes.add(store.complete(tenant, run, bundle, relative(sink.lastDirectory().resolve("raw.bin")),
                        relative(sink.lastDirectory().resolve("bundle.json"))));
            } catch (Exception e) {
                String code = errorCode(e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (run != null) store.fail(tenant, run, code);
                Map<String, Object> result = new LinkedHashMap<>(); result.put("runId", run); result.put("source", request.sourceKey());
                result.put("chart", request.chartKey()); result.put("status", "FAILED"); result.put("errorCode", code); outcomes.add(result);
                if (code.equals("SOURCE_AUTH_REQUIRED")) {
                    result.put("requiresLogin", true);
                    result.put("action", "请运行 scripts/login-ranking-capture.ps1 手动重新登录引力引擎，再执行采集");
                }
            }
        }
        long failed = outcomes.stream().filter(item -> "FAILED".equals(item.get("status"))).count();
        long partial = outcomes.stream().filter(item -> "PARTIAL".equals(item.get("status"))).count();
        Map<String,Object> result=Map.of("batchId", batch, "status", failed == outcomes.size() ? "FAILED" : failed > 0 || partial > 0 ? "PARTIAL" : "SUCCEEDED",
                "attempts", outcomes, "failedCount", failed, "partialCount", partial,
                "notice", "微信/抖音为第三方公开榜；PARTIAL表示请求范围不完整；freshness与覆盖独立。未自动关联产品库。");
        // Every entry point (timer, UI and CLI) publishes an atomic delivery manifest.
        // The companion only watches completed batch-*.json files, never half-written evidence.
        try {
            Files.createDirectories(root);Path pending=root.resolve("batch-"+batch+".pending");
            Files.writeString(pending,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
            Files.move(pending,root.resolve("batch-"+batch+".json"),StandardCopyOption.ATOMIC_MOVE);
        } catch(java.io.IOException e){throw new CaptureException("DELIVERY_MANIFEST_FAILED","来源运行已留存，但交付清单写入失败，需要恢复后重跑");}
        return result;
    }
    public static String errorCode(Exception error) {
        if (error instanceof CaptureException e) return e.code();
        if (error instanceof java.net.http.HttpTimeoutException) return "SOURCE_TIMEOUT";
        if (error instanceof InterruptedException) return "INTERRUPTED";
        if (error instanceof java.net.ConnectException) return "SOURCE_CONNECT_FAILED";
        if (error instanceof java.io.IOException) return "CAPTURE_IO_FAILED";
        if (error instanceof org.springframework.dao.DataAccessException) return "CAPTURE_DATABASE_FAILED";
        return "CAPTURE_FAILED";
    }
    private SourceAdapter adapter(CaptureRequest request) {
        return adapters.stream().filter(adapter -> adapter.key().equals(request.sourceKey())).findFirst()
                .orElseThrow(() -> new CaptureException("UNSUPPORTED_SOURCE", "未注册的来源"));
    }
    private String relative(Path file) { return root.relativize(file).toString().replace('\\', '/'); }
}
