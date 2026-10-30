package com.youkia.capture;

import java.time.LocalDate;
import java.util.Locale;

/** expectedSourceDate is a constraint, never a date to assign to undated material. */
public record CaptureRequest(String sourceKey, String region, int requestedTopN, LocalDate expectedSourceDate) {
    public CaptureRequest {
        if (sourceKey == null || sourceKey.isBlank() || region == null || region.isBlank()
                || requestedTopN < 1 || requestedTopN > 100)
            throw new CaptureException("INVALID_SCOPE", "来源、地区及 TopN(1–100) 必须有效");
        region = region.toLowerCase(Locale.ROOT);
    }
}
