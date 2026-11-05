package com.custoking.ims.operationsservice.application.tracker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class SlaTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    // 2026-10-19 is a Monday, 2026-10-23 a Friday, 2026-10-24 a Saturday.

    @Test
    void hoursWithinOneWeekdayAreContinuous() {
        assertEquals(at(2026, 10, 19, 13, 0), Sla.hours(4).deadline(at(2026, 10, 19, 9, 0), ZONE));
    }

    @Test
    void elapsedHoursContinueAcrossAWeekend() {
        assertEquals(at(2026, 10, 24, 2, 0), Sla.hours(4).deadline(at(2026, 10, 23, 22, 0), ZONE));
    }

    @Test
    void elapsedHoursStartedOnAWeekendContinueImmediately() {
        assertEquals(at(2026, 10, 24, 19, 0), Sla.hours(4).deadline(at(2026, 10, 24, 15, 0), ZONE));
    }

    @Test
    void workingDaysSkipSaturdayAndSundayAndKeepTheTimeOfDay() {
        assertEquals(at(2026, 10, 26, 15, 0), Sla.workingDays(1).deadline(at(2026, 10, 23, 15, 0), ZONE));
        assertEquals(at(2026, 10, 26, 10, 0), Sla.workingDays(2).deadline(at(2026, 10, 22, 10, 0), ZONE));
        assertEquals(at(2026, 10, 21, 9, 0), Sla.workingDays(2).deadline(at(2026, 10, 19, 9, 0), ZONE));
    }

    @Test
    void workingDaysStartedOnAWeekendCountFromMondayMidnight() {
        assertEquals(at(2026, 10, 27, 0, 0), Sla.workingDays(1).deadline(at(2026, 10, 24, 10, 0), ZONE));
    }

    @Test
    void aStepIsOverdueOnlyWhileOpenAndPastItsDeadline() {
        Sla sla = Sla.hours(4);
        Instant start = at(2026, 10, 19, 9, 0);
        assertFalse(sla.overdue(start, at(2026, 10, 19, 12, 59), true, ZONE));
        assertTrue(sla.overdue(start, at(2026, 10, 19, 13, 1), true, ZONE));
        assertFalse(sla.overdue(start, at(2026, 10, 19, 13, 1), false, ZONE));
    }

    @Test
    void defaultsMatchTheClientWorkflow() {
        assertEquals(java.util.List.of(2L, 72L, 2L, 2L, 24L, 6L, 24L, 50L, 4L, 72L, 2L, 4L),
                java.util.Arrays.stream(TrackerStep.values()).map(step -> step.defaultSla().orElseThrow().value()).toList());
        assertEquals("CDR Conversion", TrackerStep.ofNumber(3).orElseThrow().title());
        assertEquals("Head Approval", TrackerStep.ofNumber(4).orElseThrow().title());
        assertEquals("Pre-Print Quality Check", TrackerStep.ofNumber(7).orElseThrow().title());
        assertEquals("Printing Press", TrackerStep.ofNumber(8).orElseThrow().title());
    }

    @Test
    void aTimeSlotMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Sla.hours(0));
    }
}
