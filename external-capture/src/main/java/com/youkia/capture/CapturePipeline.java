package com.youkia.capture;

import java.io.IOException;
import java.time.*;
import java.util.*;

public final class CapturePipeline {
    private final Map<String, SourceAdapter> adapters;
    private final MaterialFetcher fetcher;
    private final CaptureSink sink;
    private final Clock clock;

    public CapturePipeline(List<SourceAdapter> adapters, MaterialFetcher fetcher, CaptureSink sink, Clock clock) {
        Map<String, SourceAdapter> indexed = new HashMap<>();
        for (var adapter : adapters) {
            if (indexed.putIfAbsent(adapter.key(), adapter) != null)
                throw new IllegalArgumentException("Duplicate source: " + adapter.key());
        }
        this.adapters = Map.copyOf(indexed); this.fetcher = fetcher; this.sink = sink; this.clock = clock;
    }

    public Observation.Bundle collect(CaptureRequest request) throws IOException, InterruptedException {
        return accept(request, fetcher.fetch(adapter(request).sourceUrl(request)));
    }

    public Observation.Bundle accept(CaptureRequest request, RawMaterial material) throws IOException {
        var adapter = adapter(request);
        if (!adapter.sourceUrl(request).equals(material.sourceUrl()))
            throw new CaptureException("SOURCE_SCOPE_MISMATCH", "材料来源 URL 与请求不符");
        if (material.capturedAt().isAfter(clock.instant()))
            throw new CaptureException("INVALID_CAPTURE_TIME", "材料采集时间不能在未来");
        var observation = adapter.parse(request, material);
        if (!request.sourceKey().equals(observation.sourceKey()) || !request.region().equals(observation.region()))
            throw new CaptureException("SOURCE_SCOPE_MISMATCH", "解析结果来源或地区不符");
        if (request.expectedSourceDate() != null && !request.expectedSourceDate().equals(observation.sourceDate()))
            throw new CaptureException("SOURCE_DATE_MISMATCH", "来源日期与约束不符，不允许重标历史日期");
        LocalDate capturedDay = material.capturedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        if (observation.sourceDate() != null && observation.sourceDate().isAfter(capturedDay)
                || observation.businessDate() != null && observation.businessDate().isAfter(capturedDay))
            throw new CaptureException("SOURCE_DATE_INVALID", "来源日期不能晚于材料采集日");
        if (observation.entries().isEmpty()) throw new CaptureException("NO_ENTRIES", "空材料不输出成功观察");
        Set<Integer> ranks = new HashSet<>(); Set<String> ids = new HashSet<>();
        for (var entry : observation.entries()) {
            if (entry.rank() < 1 || entry.rank() > request.requestedTopN() || !ranks.add(entry.rank())
                    || entry.name() == null || entry.name().isBlank()
                    || entry.evidenceLocator() == null || entry.evidenceLocator().isBlank()
                    || entry.externalId() != null && (entry.externalId().isBlank() || !ids.add(entry.externalId())))
                throw new CaptureException("INVALID_ENTRY", "排名、身份或证据定位缺失/重复");
        }
        var bundle = new Observation.Bundle("1.0", request, observation,
                Observation.Coverage.calculate(request.requestedTopN(), observation.entries()),
                new Observation.Evidence(material.sourceUrl(), material.capturedAt(), material.transport(),
                        material.sha256(), material.bytes().length));
        sink.save(bundle, material);
        return bundle;
    }

    private SourceAdapter adapter(CaptureRequest request) {
        var adapter = adapters.get(request.sourceKey());
        if (adapter == null) throw new CaptureException("UNSUPPORTED_SOURCE", "未注册的来源: " + request.sourceKey());
        return adapter;
    }
}
