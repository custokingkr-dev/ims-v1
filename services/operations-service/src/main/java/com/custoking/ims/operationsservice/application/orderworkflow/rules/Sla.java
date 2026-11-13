package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * A stage's time slot (design D23). Hour-based slots count 24/7 calendar hours; working-day slots count Monday-Friday
 * (holidays are not excluded) in the school's zone. The clock starts when the previous stage completes (Step 1's at
 * placement) and restarts when a stage is reopened; a stage is breached when now is after its deadline.
 */
public record Sla(long value, Unit unit) {

    public enum Unit { HOURS, WORKING_DAYS }

    /** The list's SLA filter and chips: past due, due within {@link #DUE_SOON_WITHIN}, further away, or no deadline. */
    public enum State { OVERDUE, DUE_SOON, ON_TRACK, NO_TIMER }

    /** The largest value a time slot may have, in either unit. */
    public static final long MAX_VALUE = 10_000;

    /** "Due soon" means due within four hours (design §4.2). */
    public static final Duration DUE_SOON_WITHIN = Duration.ofHours(4);

    public Sla {
        if (value <= 0) throw new IllegalArgumentException("A time slot must be positive");
        if (value > MAX_VALUE) throw new IllegalArgumentException("A time slot is limited to " + MAX_VALUE);
        if (unit == null) throw new IllegalArgumentException("A time slot needs a unit");
    }

    public static Sla hours(long value) { return new Sla(value, Unit.HOURS); }
    public static Sla workingDays(long value) { return new Sla(value, Unit.WORKING_DAYS); }

    /**
     * A time slot from a pair of nullable columns ({@code *_sla_value}, {@code *_sla_unit}): both null is "no timer".
     *
     * @throws IllegalArgumentException when only one is set, the unit is unknown or the value is out of range
     */
    public static Optional<Sla> ofNullable(Number value, String unit) {
        if (value == null && unit == null) return Optional.empty();
        if (value == null || unit == null) throw new IllegalArgumentException("A time slot needs both a value and a unit");
        Unit parsed;
        try {
            parsed = Unit.valueOf(unit);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown time slot unit " + unit);
        }
        return Optional.of(new Sla(value.longValue(), parsed));
    }

    /** The instant by which a stage that started at {@code start} should be completed. */
    public Instant deadline(Instant start, ZoneId zone) {
        ZonedDateTime cursor = start.atZone(zone);
        return (unit == Unit.HOURS ? cursor.plusHours(value) : addWorkingDays(skipWeekend(cursor), value)).toInstant();
    }

    /** The deadline of a stage started at {@code start} under {@code sla}, or null when the stage has no timer. */
    public static Instant dueAt(Instant start, Sla sla, ZoneId zone) {
        return sla == null ? null : sla.deadline(start, zone);
    }

    /** A stage is overdue only while it is still open and past its deadline; a finished stage is never overdue. */
    public boolean overdue(Instant start, Instant now, boolean open, ZoneId zone) {
        return open && now.isAfter(deadline(start, zone));
    }

    /**
     * The SLA state of an open stage with this deadline. A null deadline (no timer, or the order is finished) is
     * {@link State#NO_TIMER}; a deadline exactly now is not yet breached.
     */
    public static State state(Instant dueAt, Instant now) {
        if (dueAt == null) return State.NO_TIMER;
        if (now.isAfter(dueAt)) return State.OVERDUE;
        return Duration.between(now, dueAt).compareTo(DUE_SOON_WITHIN) <= 0 ? State.DUE_SOON : State.ON_TRACK;
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
