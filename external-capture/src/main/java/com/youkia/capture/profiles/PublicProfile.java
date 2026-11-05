package com.youkia.capture.profiles;

import java.net.URI;
import java.time.Instant;

/**
 * A public application profile read from a source detail page.  It is a
 * candidate observation only; it never asserts that the app is the same as a
 * mini-game with the same display name.
 */
public record PublicProfile(
        String source,
        String platform,
        String region,
        String externalId,
        String name,
        String nameRaw,
        String subtitle,
        String developer,
        String categoryDisplay,
        String priceDisplay,
        String iapItemCountDisplay,
        String ratingDisplay,
        String ratingCountDisplay,
        String yesterdayDownloadsDisplay,
        String updatedDateDisplay,
        String firstReleaseDateDisplay,
        String identityStatus,
        URI sourceUrl,
        Instant capturedAt,
        String rawSha256,
        String evidenceLocator) {
    public PublicProfile {
        if (source == null || source.isBlank() || platform == null || platform.isBlank()
                || region == null || region.isBlank() || externalId == null || externalId.isBlank()
                || name == null || name.isBlank() || sourceUrl == null || capturedAt == null
                || evidenceLocator == null || evidenceLocator.isBlank())
            throw new IllegalArgumentException("公开资料的来源、平台、ID、名称、来源URL和采集时间必填");
        identityStatus = identityStatus == null || identityStatus.isBlank()
                ? "UNVERIFIED_CROSS_PLATFORM_CANDIDATE" : identityStatus;
    }
}
