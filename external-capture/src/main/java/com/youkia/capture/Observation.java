package com.youkia.capture;

import java.net.URI;
import java.time.*;
import java.util.*;

/** Source facts only. An external ID is a source-provided identity, not a product mapping. */
public record Observation(String sourceKey, String parserVersion, String platform, String region,
                          String boardName, LocalDate sourceDate, LocalDate businessDate,
                          String datePolicy, List<Entry> entries, List<String> warnings) {
    public Observation { entries = List.copyOf(entries); warnings = List.copyOf(warnings); }

    public record Entry(int rank, String externalId, String name, String developer,
                        String evidenceLocator, Map<String, Object> sourceFields) {
        public Entry { sourceFields = Map.copyOf(sourceFields); }
    }
    public record Coverage(int requestedTopN, int actualCount, int maxObservedRank,
                           int contiguousTopN, List<Integer> missingRanks, String status) {
        public static Coverage calculate(int top, List<Entry> entries) {
            Set<Integer> ranks = new HashSet<>();
            entries.forEach(e -> ranks.add(e.rank()));
            List<Integer> missing = new ArrayList<>();
            for (int i = 1; i <= top; i++) if (!ranks.contains(i)) missing.add(i);
            int contiguous = 0;
            while (ranks.contains(contiguous + 1)) contiguous++;
            return new Coverage(top, entries.size(), ranks.stream().mapToInt(i -> i).max().orElse(0),
                    contiguous, List.copyOf(missing), missing.isEmpty() ? "REQUESTED_RANGE_COMPLETE" : "PARTIAL");
        }
    }
    public record Evidence(URI sourceUrl, Instant capturedAt, String transport, String sha256, int byteCount) {}
    public record Bundle(String schemaVersion, CaptureRequest request, Observation observation,
                         Coverage coverage, Evidence evidence) {}
}
