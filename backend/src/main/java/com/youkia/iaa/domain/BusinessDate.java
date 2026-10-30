package com.youkia.iaa.domain;

import java.time.*;

public final class BusinessDate {
    private BusinessDate() {}
    public static LocalDate parseOrToday(String value, Clock clock, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (value == null || value.isBlank()) return today;
        LocalDate date = LocalDate.parse(value);
        if (date.isAfter(today)) throw new IllegalArgumentException("date不得晚于业务今天");
        return date;
    }
    public static void validateOrder(LocalDate selected, LocalDate started, LocalDate finished) {
        if (started != null && started.isBefore(selected)) throw new IllegalArgumentException("startedOn不得早于selectedOn");
        if (finished != null && finished.isBefore(selected)) throw new IllegalArgumentException("finishedOn不得早于selectedOn");
        if (started != null && finished != null && finished.isBefore(started)) throw new IllegalArgumentException("finishedOn不得早于startedOn");
    }
}
