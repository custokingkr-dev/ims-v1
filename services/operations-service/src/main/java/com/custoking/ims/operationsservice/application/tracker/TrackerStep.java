package com.custoking.ims.operationsservice.application.tracker;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The twelve steps of the notebook order tracker (spec v4). Steps 2-4 apply to customised notebooks only.
 * Default time slots are the prototype's values; steps 8 and 10 have none.
 */
public enum TrackerStep {
    ORDER_DETAILS(1, "Order Details", false, Sla.hours(4)),
    DESIGNING_ACTIVITY(2, "Designing Activity", true, Sla.workingDays(2)),
    HEAD_APPROVAL(3, "Head Approval", true, Sla.workingDays(1)),
    CDR_CONVERSION(4, "CDR Conversion", true, Sla.hours(4)),
    PRODUCTION_PLANNING(5, "Production Planning", false, Sla.hours(4)),
    BOARD_PROCUREMENT(6, "Board Procurement", false, Sla.workingDays(1)),
    PRINTING_PRESS(7, "Printing Press", false, Sla.hours(4)),
    PRE_PRINT_QUALITY_GATE(8, "Pre-Print Quality Gate", false, null),
    FACTORY_HANDOVER(9, "Factory Handover", false, Sla.hours(4)),
    PRODUCTION_FOLLOW_UP(10, "Production Follow-up", false, null),
    VALIDATION_COUNT(11, "Validation Count", false, Sla.hours(4)),
    DELIVERY_INVOICING(12, "Delivery & Invoicing", false, Sla.workingDays(1));

    private final int number;
    private final String title;
    private final boolean customisedOnly;
    private final Sla defaultSla;

    TrackerStep(int number, String title, boolean customisedOnly, Sla defaultSla) {
        this.number = number;
        this.title = title;
        this.customisedOnly = customisedOnly;
        this.defaultSla = defaultSla;
    }

    public int number() { return number; }
    public String title() { return title; }
    public boolean customisedOnly() { return customisedOnly; }
    public Optional<Sla> defaultSla() { return Optional.ofNullable(defaultSla); }

    public static Optional<TrackerStep> ofNumber(int number) {
        return Arrays.stream(values()).filter(step -> step.number == number).findFirst();
    }

    /** All twelve steps for a customised order; steps 2, 3 and 4 are omitted for a wholesale order. */
    public static List<TrackerStep> pathFor(boolean customised) {
        return Arrays.stream(values()).filter(step -> customised || !step.customisedOnly).toList();
    }
}
