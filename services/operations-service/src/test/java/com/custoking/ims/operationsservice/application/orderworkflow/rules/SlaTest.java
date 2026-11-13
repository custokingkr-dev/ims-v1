package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Ported from the tracker's SlaTest; adds the list's SLA states and the column parsing. */
class SlaTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant();
    }

    // 2026-10-19 is a Monday, 2026-10-23 a Friday, 2026-10-24 a Saturday.

    @Test
    void hoursAreCountedAroundTheClockAcrossAWeekend() {
        assertEquals(at(2026, 10, 19, 13, 0), Sla.hours(4).deadline(at(2026, 10, 19, 9, 0), ZONE));
        assertEquals(at(2026, 10, 24, 2, 0), Sla.hours(4).deadline(at(2026, 10, 23, 22, 0), ZONE));
        assertEquals(at(2026, 10, 24, 19, 0), Sla.hours(4).deadline(at(2026, 10, 24, 15, 0), ZONE));
        assertEquals(at(2026, 10, 26, 22, 0), Sla.hours(72).deadline(at(2026, 10, 23, 22, 0), ZONE));
    }

    @Test
    void workingDaysSkipSaturdayAndSundayAndKeepTheTimeOfDay() {
        assertEquals(at(2026, 10, 26, 15, 0), Sla.workingDays(1).deadline(at(2026, 10, 23, 15, 0), ZONE));
        assertEquals(at(2026, 10, 26, 10, 0), Sla.workingDays(2).deadline(at(2026, 10, 22, 10, 0), ZONE));
        assertEquals(at(2026, 10, 21, 9, 0), Sla.workingDays(2).deadline(at(2026, 10, 19, 9, 0), ZONE));
        assertEquals(at(2026, 10, 27, 0, 0), Sla.workingDays(1).deadline(at(2026, 10, 24, 10, 0), ZONE));
    }

    @Test
    void aStageIsOverdueOnlyWhileOpenAndPastItsDeadline() {
        Sla sla = Sla.hours(4);
        Instant start = at(2026, 10, 19, 9, 0);
        assertFalse(sla.overdue(start, at(2026, 10, 19, 12, 59), true, ZONE));
        assertFalse(sla.overdue(start, at(2026, 10, 19, 13, 0), true, ZONE), "due exactly now is not yet breached");
        assertTrue(sla.overdue(start, at(2026, 10, 19, 13, 1), true, ZONE));
        assertFalse(sla.overdue(start, at(2026, 10, 19, 13, 1), false, ZONE));
    }

    @Test
    void theListStatesAreOverdueDueSoonOnTrackOrNoTimer() {
        Instant now = at(2026, 11, 13, 12, 0);
        assertEquals(Sla.State.NO_TIMER, Sla.state(null, now));
        assertEquals(Sla.State.OVERDUE, Sla.state(now.minusSeconds(1), now));
        assertEquals(Sla.State.DUE_SOON, Sla.state(now, now));
        assertEquals(Sla.State.DUE_SOON, Sla.state(now.plusSeconds(4 * 3600), now), "within four hours is due soon");
        assertEquals(Sla.State.ON_TRACK, Sla.state(now.plusSeconds(4 * 3600 + 1), now));
    }

    @Test
    void aStageWithoutATimerHasNoDeadline() {
        Instant start = at(2026, 11, 13, 12, 0);
        assertNull(Sla.dueAt(start, null, ZONE));
        assertEquals(at(2026, 11, 13, 14, 0), Sla.dueAt(start, Sla.hours(2), ZONE));
    }

    @Test
    void timeSlotsAreReadFromAPairOfColumns() {
        assertEquals(Optional.empty(), Sla.ofNullable(null, null));
        assertEquals(Optional.of(Sla.hours(24)), Sla.ofNullable(24, "HOURS"));
        assertEquals(Optional.of(Sla.workingDays(2)), Sla.ofNullable(2L, "WORKING_DAYS"));
        assertThrows(IllegalArgumentException.class, () -> Sla.ofNullable(24, null));
        assertThrows(IllegalArgumentException.class, () -> Sla.ofNullable(null, "HOURS"));
        assertThrows(IllegalArgumentException.class, () -> Sla.ofNullable(24, "MINUTES"));
    }

    @Test
    void aTimeSlotMustBePositiveAndAtMostTenThousand() {
        assertThrows(IllegalArgumentException.class, () -> Sla.hours(0));
        assertThrows(IllegalArgumentException.class, () -> Sla.hours(10_001));
        assertEquals(10_000, Sla.hours(10_000).value());
        assertThrows(IllegalArgumentException.class, () -> new Sla(1, null));
    }
}
