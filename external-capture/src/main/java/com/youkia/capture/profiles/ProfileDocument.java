package com.youkia.capture.profiles;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Source facts about a store app, never an assertion of mini-game identity. */
public record ProfileDocument(String source, String platform, String region, String externalId,
        String name, String developer, Map<String,String> fields, URI sourceUrl,
        Instant capturedAt, String rawSha256, List<String> limitations) {}
