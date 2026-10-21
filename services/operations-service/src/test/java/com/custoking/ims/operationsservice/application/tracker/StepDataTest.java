package com.custoking.ims.operationsservice.application.tracker;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StepDataTest {

    private static TrackerRuleViolation rejected(TrackerStep step, Map<String, Object> raw) {
        TrackerRuleViolation violation = assertThrows(TrackerRuleViolation.class, () -> StepData.validate(step, raw));
        assertEquals("INVALID_STEP_DATA", violation.code());
        return violation;
    }

    @Test
    void nullAndEmptyInputGiveEmptyData() {
        assertTrue(StepData.validate(TrackerStep.CDR_CONVERSION, null).isEmpty());
        assertTrue(StepData.validate(TrackerStep.CDR_CONVERSION, Map.of()).isEmpty());
    }

    @Test
    void unknownFieldsAreRejectedIncludingTheReservedSignOffRecord() {
        assertTrue(rejected(TrackerStep.CDR_CONVERSION, Map.of("surprise", "x")).getMessage().contains("surprise"));
        rejected(TrackerStep.PRE_PRINT_QUALITY_GATE, Map.of(StepData.SIGN_OFF_KEY, Map.of("by", 1)));
        rejected(TrackerStep.DELIVERY_INVOICING, Map.of(StepData.SIGN_OFF_KEY, "x"));
    }

    @Test
    void stepsWithNoWorkAreaDataAcceptNothing() {
        for (TrackerStep step : new TrackerStep[]{TrackerStep.ORDER_DETAILS, TrackerStep.DESIGNING_ACTIVITY,
                TrackerStep.HEAD_APPROVAL, TrackerStep.PRODUCTION_FOLLOW_UP}) {
            assertTrue(StepData.fieldNames(step).isEmpty(), step.title());
            rejected(step, Map.of("anything", "x"));
        }
    }

    @Test
    void textIsTrimmedBlankIsDroppedAndTheLimitIsEnforced() {
        assertEquals(Map.of("cdrFilename", "cover.cdr"),
                StepData.validate(TrackerStep.CDR_CONVERSION, Map.of("cdrFilename", "  cover.cdr ", "cdrNotes", "   ")));
        rejected(TrackerStep.CDR_CONVERSION, Map.of("cdrFilename", "x".repeat(201)));
        rejected(TrackerStep.CDR_CONVERSION, Map.of("cdrFilename", 7));
    }

    @Test
    void datesMustBeIsoDates() {
        assertEquals("2026-12-15", StepData.validate(TrackerStep.PRODUCTION_PLANNING,
                Map.of("productionStart", "2026-12-15")).get("productionStart"));
        rejected(TrackerStep.PRODUCTION_PLANNING, Map.of("productionStart", "15/12/2026"));
        rejected(TrackerStep.PRODUCTION_PLANNING, Map.of("targetCompletion", "2026-02-30"));
    }

    @Test
    void wholeNumbersAcceptNumbersAndDigitStringsWithinRange() {
        assertEquals(500L, StepData.validate(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", 500)).get("quantitySent"));
        assertEquals(500L, StepData.validate(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", "500")).get("quantitySent"));
        assertEquals(500L, StepData.validate(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", 500.0)).get("quantitySent"));
        rejected(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", -1));
        rejected(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", 1.5));
        rejected(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", "12a"));
        rejected(TrackerStep.PRINTING_PRESS, Map.of("quantitySent", 100_000_001L));
    }

    @Test
    void decimalsAreBoundedToZeroToOneHundred() {
        assertEquals(7.5, StepData.validate(TrackerStep.BOARD_PROCUREMENT, Map.of("wastagePercent", 7.5)).get("wastagePercent"));
        rejected(TrackerStep.BOARD_PROCUREMENT, Map.of("wastagePercent", 101));
        rejected(TrackerStep.BOARD_PROCUREMENT, Map.of("wastagePercent", "NaN"));
        rejected(TrackerStep.BOARD_PROCUREMENT, Map.of("wastagePercent", "lots"));
    }

    @Test
    void choicesMustMatchExactly() {
        assertEquals("VERIFIED", StepData.validate(TrackerStep.PRE_PRINT_QUALITY_GATE,
                Map.of("schoolVerification", "VERIFIED")).get("schoolVerification"));
        rejected(TrackerStep.PRE_PRINT_QUALITY_GATE, Map.of("schoolVerification", "verified"));
        rejected(TrackerStep.DELIVERY_INVOICING, Map.of("invoiceStatus", "DONE"));
        assertEquals("PAID", StepData.validate(TrackerStep.DELIVERY_INVOICING, Map.of("invoiceStatus", "PAID")).get("invoiceStatus"));
    }

    @Test
    void checklistItemsAreDistinctSortedIndexesOfTheEightItems() {
        assertEquals(List.of(0L, 2L, 7L), StepData.validate(TrackerStep.PRE_PRINT_QUALITY_GATE,
                Map.of("checks", List.of(7, 0, 2, 2))).get("checks"));
        rejected(TrackerStep.PRE_PRINT_QUALITY_GATE, Map.of("checks", List.of(8)));
        rejected(TrackerStep.PRE_PRINT_QUALITY_GATE, Map.of("checks", List.of(-1)));
        rejected(TrackerStep.PRE_PRINT_QUALITY_GATE, Map.of("checks", "0,1"));
    }

    @Test
    void validationCountFieldsAreTheTypedCountsAndTheNote() {
        assertEquals(Map.of("producedCount", 990L, "acceptedCount", 980L, "dispatchCount", 980L, "discrepancyNote", "Ten damaged"),
                StepData.validate(TrackerStep.VALIDATION_COUNT, Map.of("producedCount", 990, "acceptedCount", 980,
                        "dispatchCount", 980, "discrepancyNote", "Ten damaged")));
    }

    @Test
    void savedDataComparesByValueIgnoringTheSignOff() {
        // A save's validated values against the same values read back from JSON, where whole numbers come back narrower.
        Map<String, Object> clean = StepData.validate(TrackerStep.BOARD_PROCUREMENT,
                Map.of("totalTitleUnits", 1000, "wastagePercent", 2.5, "procurementReference", "PO-1"));
        Map<String, Object> saved = Map.of("totalTitleUnits", 1000, "wastagePercent", 2.5, "procurementReference", "PO-1",
                StepData.SIGN_OFF_KEY, Map.of("by", 1));
        assertTrue(StepData.sameValues(saved, clean));
        assertTrue(StepData.sameValues(Map.of("checks", List.of(0, 2)), Map.of("checks", List.of(0L, 2L))));
        assertTrue(StepData.sameValues(Map.of("wastagePercent", 2), Map.of("wastagePercent", 2.0)));

        assertFalse(StepData.sameValues(saved, Map.of("totalTitleUnits", 1001L, "wastagePercent", 2.5, "procurementReference", "PO-1")));
        assertFalse(StepData.sameValues(saved, Map.of("totalTitleUnits", 1000L, "wastagePercent", 2.5)), "a cleared field is a change");
        assertFalse(StepData.sameValues(Map.of("checks", List.of(0, 2)), Map.of("checks", List.of(0L))));
        assertFalse(StepData.sameValues(Map.of("paperGsm", "70"), Map.of("paperGsm", 70L)), "text is not a number");
    }

    @Test
    void signOffRecordCarriesWhoAndWhen() {
        Map<String, Object> signOff = StepData.signOff(9L, Instant.parse("2026-10-19T10:00:00Z"));
        assertEquals(9L, signOff.get("by"));
        assertEquals("2026-10-19T10:00:00Z", signOff.get("at"));
    }
}
