package com.youkia.iaa;

import com.youkia.iaa.service.WeeklyReportService;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class WeeklyReportWeekTest {
    @Test void exclusiveMondayBelongsToPreviousWeek(){
        assertEquals(LocalDate.parse("2026-10-26"),WeeklyReportService.weekStart(LocalDate.parse("2026-11-02")));
        assertEquals(LocalDate.parse("2026-11-02"),WeeklyReportService.weekStart(LocalDate.parse("2026-11-03")));
        assertEquals(LocalDate.parse("2026-11-02"),WeeklyReportService.weekStart(LocalDate.parse("2026-11-09")));
    }
    @Test void yearBoundaryUsesMondayDateInsteadOfWeekNumber(){
        assertEquals(LocalDate.parse("2025-12-29"),WeeklyReportService.weekStart(LocalDate.parse("2026-01-02")));
        assertEquals(LocalDate.parse("2025-12-29"),WeeklyReportService.weekStart(LocalDate.parse("2026-01-05")));
    }
}
