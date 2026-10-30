package com.youkia.iaa;

import com.youkia.iaa.domain.BusinessDate;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessDateTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC);
    @Test void defaultsToAsiaShanghaiBusinessToday() { assertEquals(LocalDate.of(2026,10,27), BusinessDate.parseOrToday(null,clock,ZoneId.of("Asia/Shanghai"))); }
    @Test void rejectsFutureDate() { assertThrows(IllegalArgumentException.class, () -> BusinessDate.parseOrToday("2026-10-28",clock,ZoneId.of("Asia/Shanghai"))); }
    @Test void movesNaturallyAcrossMissingDay() { assertEquals(LocalDate.of(2026,10,23), LocalDate.parse("2026-10-24").minusDays(1)); }
    @Test void validatesSelectionOrder() { assertThrows(IllegalArgumentException.class, () -> BusinessDate.validateOrder(LocalDate.of(2026,10,25),LocalDate.of(2026,10,24),null)); assertThrows(IllegalArgumentException.class, () -> BusinessDate.validateOrder(LocalDate.of(2026,10,25),LocalDate.of(2026,10,26),LocalDate.of(2026,10,25))); }
}
