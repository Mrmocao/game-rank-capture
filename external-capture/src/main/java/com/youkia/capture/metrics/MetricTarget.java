package com.youkia.capture.metrics;

import com.youkia.capture.CaptureException;
import java.net.URI;
import java.util.Set;

/** Native store identity only. No names or internal product identifiers. */
public record MetricTarget(String platform, String externalId, String region, String source) {
    public MetricTarget {
        if (platform == null || externalId == null || region == null || source == null || !Set.of("cn","us","gb","de","fr").contains(region)
                || !("IOS".equals(platform) && externalId.matches("[0-9]{5,20}") && Set.of("apple-lookup","apple-detail").contains(source)
                || "GOOGLE_PLAY".equals(platform) && externalId.length()<=200 && externalId.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") && source.equals("google-detail")))
            throw new CaptureException("METRIC_SCOPE_INVALID", "仅支持已核验商店ID及其所属平台；小游戏不按同名补值");
    }
    public URI url() {
        return URI.create(switch(source) {
            case "apple-lookup" -> "https://itunes.apple.com/lookup?id="+externalId+"&country="+region;
            case "apple-detail" -> "https://apps.apple.com/"+region+"/app/id"+externalId;
            default -> "https://play.google.com/store/apps/details?id="+externalId+"&hl=en&gl="+region.toUpperCase(java.util.Locale.ROOT);
        });
    }
}
