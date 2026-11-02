package com.youkia.capture;

import java.time.LocalDate;
import java.util.Locale;

/** expectedSourceDate is a constraint, never a date to assign to undated material. */
public record CaptureRequest(String sourceKey, String region, int requestedTopN, LocalDate expectedSourceDate, String chartKey) {
    public CaptureRequest(String sourceKey, String region, int requestedTopN, LocalDate expectedSourceDate) {
        this(sourceKey, region, requestedTopN, expectedSourceDate,
                sourceKey != null && sourceKey.startsWith("gravity-") ? "popularity" : "top-free");
    }
    public CaptureRequest {
        if (sourceKey == null || sourceKey.isBlank() || region == null || region.isBlank()
                || chartKey == null || !chartKey.matches("[a-z][a-z0-9_-]{0,63}")
                || requestedTopN < 1 || requestedTopN > 100)
            throw new CaptureException("INVALID_SCOPE", "来源、地区及 TopN(1–100) 必须有效");
        region = region.toLowerCase(Locale.ROOT);
    }
}
