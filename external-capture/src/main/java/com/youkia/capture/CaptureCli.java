package com.youkia.capture;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.youkia.capture.adapters.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class CaptureCli {
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println(e instanceof CaptureException c ? c.code() + ": " + c.getMessage()
                    : "CAPTURE_FAILED: " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            System.out.println("--source apple-games|appbrain --region cn|us|gb|de|fr --top 100 --output DIR "
                    + "[--material FILE --captured-at ISO_OFFSET_TIME] [--expected-source-date YYYY-MM-DD]"); return;
        }
        Map<String, String> options = new HashMap<>();
        Set<String> allowed = Set.of("--source", "--region", "--top", "--output", "--material", "--captured-at", "--expected-source-date");
        for (int i = 0; i < args.length; i += 2) {
            if (!allowed.contains(args[i]) || i + 1 == args.length || options.putIfAbsent(args[i], args[i + 1]) != null)
                throw new CaptureException("INVALID_ARGUMENT", "参数未知、重复或缺值");
        }
        for (String required : List.of("--source", "--region", "--output"))
            if (!options.containsKey(required)) throw new CaptureException("INVALID_ARGUMENT", "缺少 " + required);
        boolean submitted = options.containsKey("--material");
        if (submitted != options.containsKey("--captured-at"))
            throw new CaptureException("INVALID_ARGUMENT", "本地材料必须提供其实际采集时间 --captured-at");
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        List<SourceAdapter> adapters = List.of(new AppleGamesAdapter(mapper), new AppBrainAdapter());
        var request = new CaptureRequest(options.get("--source"), options.get("--region"),
                Integer.parseInt(options.getOrDefault("--top", "100")), options.containsKey("--expected-source-date")
                ? LocalDate.parse(options.get("--expected-source-date")) : null);
        var sink = new FileCaptureSink(Path.of(options.get("--output")), mapper);
        var clock = Clock.systemUTC();
        var pipeline = new CapturePipeline(adapters, new HttpMaterialFetcher(clock), sink, clock);
        Observation.Bundle result;
        if (submitted) {
            var adapter = adapters.stream().filter(a -> a.key().equals(request.sourceKey())).findFirst()
                    .orElseThrow(() -> new CaptureException("UNSUPPORTED_SOURCE", "未注册的来源"));
            try (var in = Files.newInputStream(Path.of(options.get("--material")))) {
                result = pipeline.accept(request, new RawMaterial(adapter.sourceUrl(request),
                        OffsetDateTime.parse(options.get("--captured-at")).toInstant(), "submitted-material",
                        in.readNBytes(RawMaterial.MAX_BYTES + 1)));
            }
        } else result = pipeline.collect(request);
        System.out.println(result.coverage().status() + " entries=" + result.coverage().actualCount());
        System.out.println(sink.lastDirectory());
    }
}
