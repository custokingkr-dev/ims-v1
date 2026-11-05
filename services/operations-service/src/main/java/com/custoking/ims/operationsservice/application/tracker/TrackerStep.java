package com.custoking.ims.operationsservice.application.tracker;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The twelve steps of the notebook order tracker. Steps 2-4 apply to customised notebooks only.
 * Default time slots and stage order follow the client workflow.
 */
public enum TrackerStep {
    ORDER_DETAILS(1, "Order Details", false, Sla.hours(2)),
    DESIGNING_ACTIVITY(2, "Designing Activity", true, Sla.hours(72)),
    CDR_CONVERSION(3, "CDR Conversion", true, Sla.hours(2)),
    HEAD_APPROVAL(4, "Head Approval", true, Sla.hours(2)),
    PRODUCTION_PLANNING(5, "Production Planning", false, Sla.hours(24)),
    BOARD_PROCUREMENT(6, "Board Procurement", false, Sla.hours(6)),
    PRE_PRINT_QUALITY_GATE(7, "Pre-Print Quality Check", false, Sla.hours(24)),
    PRINTING_PRESS(8, "Printing Press", false, Sla.hours(50)),
    FACTORY_HANDOVER(9, "Factory Handover", false, Sla.hours(4)),
    PRODUCTION_FOLLOW_UP(10, "Production Follow-up", false, Sla.hours(72)),
    VALIDATION_COUNT(11, "Validation Count", false, Sla.hours(2)),
    DELIVERY_INVOICING(12, "Delivery & Invoicing", false, Sla.hours(4));

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
