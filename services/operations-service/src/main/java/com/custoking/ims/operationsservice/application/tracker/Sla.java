package com.custoking.ims.operationsservice.application.tracker;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A step's time slot. Hour-based slots count continuously; working-day slots count Monday-Friday
 * (holidays are not excluded) in the supplied zone.
 */
public record Sla(long value, Unit unit) {

    public enum Unit { HOURS, WORKING_DAYS }

    public Sla {
        if (value <= 0) throw new IllegalArgumentException("A time slot must be positive");
        if (unit == null) throw new IllegalArgumentException("A time slot needs a unit");
    }

    public static Sla hours(long value) { return new Sla(value, Unit.HOURS); }
    public static Sla workingDays(long value) { return new Sla(value, Unit.WORKING_DAYS); }

    /** The instant by which a step that started at {@code start} should be completed. */
    public Instant deadline(Instant start, ZoneId zone) {
        ZonedDateTime cursor = start.atZone(zone);
        return (unit == Unit.HOURS ? cursor.plusHours(value) : addWorkingDays(skipWeekend(cursor), value)).toInstant();
    }

    /** A step is overdue only while it is still open and past its deadline; a finished step is never overdue. */
    public boolean overdue(Instant start, Instant now, boolean open, ZoneId zone) {
        return open && now.isAfter(deadline(start, zone));
    }

    private static boolean weekend(ZonedDateTime time) {
        DayOfWeek day = time.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private static ZonedDateTime skipWeekend(ZonedDateTime time) {
        ZonedDateTime cursor = time;
        while (weekend(cursor)) cursor = cursor.toLocalDate().plusDays(1).atStartOfDay(cursor.getZone());
        return cursor;
    }

    /** Adds weekdays while keeping the time of day: Friday 15:00 plus one working day is Monday 15:00. */
    private static ZonedDateTime addWorkingDays(ZonedDateTime start, long days) {
        ZonedDateTime cursor = start;
        long remaining = days;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (!weekend(cursor)) remaining--;
        }
        return cursor;
    }
}
