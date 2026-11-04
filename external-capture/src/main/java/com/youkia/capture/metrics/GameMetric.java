package com.youkia.capture.metrics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class GameMetric {
    private GameMetric() {}
    /** A displayed count/range is never coerced into an exact sales number. */
    public record Value(String key, String rawDisplay, BigDecimal value, String unit, String currency,
                        String method, String geographicScope, String period, String evidenceLocator) {}
    public record Purchase(String name, String priceDisplay, String evidenceLocator) {}
    public record Snapshot(MetricTarget target, String name, String parserVersion, String sourceUrl,
                           Instant capturedAt, String rawSha256, List<Value> metrics,
                           List<Purchase> purchases, List<String> limitations) {}
}
