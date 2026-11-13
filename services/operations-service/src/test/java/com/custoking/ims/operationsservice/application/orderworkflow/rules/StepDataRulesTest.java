package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.nb;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.nine;
import static org.junit.jupiter.api.Assertions.*;

/** Ported from the tracker's StepDataTest, against template-declared fields (design §3.3, D29, D30, D41). */
class StepDataRulesTest {
    private static final StepSpec MINIMAL = TemplateFixtures.load("minimal/MINIMAL_WORKFLOW.json").step(1).orElseThrow();
    private static final Instant AT = Instant.parse("2026-11-13T10:00:00Z");

    private static WorkflowRuleViolation rejected(StepSpec step, Map<String, Object> raw) {
        WorkflowRuleViolation violation = assertThrows(WorkflowRuleViolation.class, () -> StepDataRules.validate(step, raw));
        assertEquals("INVALID_STEP_DATA", violation.code());
        assertEquals(400, violation.httpStatus());
        return violation;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    @Test
    void nullAndEmptyInputGiveEmptyData() {
        assertTrue(StepDataRules.validate(nb(3), null).isEmpty());
        assertTrue(StepDataRules.validate(nb(3), Map.of()).isEmpty());
    }

    @Test
    void unknownFieldsAndTheSignOffRecordAreRefused() {
        assertTrue(rejected(nb(3), Map.of("surprise", "x")).getMessage().contains("surprise is not a field of CDR Conversion"));
        assertTrue(rejected(nb(7), Map.of(StepDataRules.SIGN_OFF_KEY, Map.of("by", 1))).getMessage().contains("sign-off action"));
        rejected(nb(2), Map.of("anything", "x"));
        rejected(nb(3), Map.of("checks", List.of(0)));
    }

    @Test
    void legacyFieldsAreAcceptedOnSave() {
        assertEquals(Map.of("cdrFilename", "cover.cdr"), StepDataRules.validate(nb(3), Map.of("cdrFilename", " cover.cdr ")));
        assertEquals(1500L, StepDataRules.validate(nb(5), Map.of("workbookKing", 1500)).get("workbookKing"));
        rejected(nb(3), Map.of("cdrFilename", "x".repeat(201)));
    }

    @Test
    void textIsTrimmedBlankIsDroppedAndTheLimitIsEnforced() {
        assertEquals(Map.of("cdrNotes", "Fonts outlined"), StepDataRules.validate(nb(3), map("cdrNotes", "  Fonts outlined ", "cdrLink", "   ")));
        assertEquals("Conversion comments is limited to 1000 characters", rejected(nb(3), Map.of("cdrNotes", "x".repeat(1001))).getMessage());
        assertEquals("Conversion comments must be text", rejected(nb(3), Map.of("cdrNotes", 7)).getMessage());
    }

    @Test
    void urlFieldsAcceptOnlyHttpsLinks() {
        assertEquals("https://contoso.sharepoint.com/:f:/r/sites/Design/Shared%20Documents/CK-1015?csf=1&web=1",
                StepDataRules.validate(nb(3), Map.of("cdrLink", " https://contoso.sharepoint.com/:f:/r/sites/Design/Shared%20Documents/CK-1015?csf=1&web=1 "))
                        .get("cdrLink"));
        assertEquals("https://drive.google.com/drive/folders/1AbC", StepDataRules.validate(nb(3),
                Map.of("cdrLink", "https://drive.google.com/drive/folders/1AbC")).get("cdrLink"));
        assertEquals("https://example.com:8443", StepDataRules.validate(nb(3), Map.of("cdrLink", "https://example.com:8443")).get("cdrLink"));
        for (String bad : List.of("http://drive.google.com/x", "javascript:alert(1)", "https://", "https:// drive.google.com",
                "https://drive.google.com/a b", "ftp://files.example.com/x", "drive.google.com/x", "https://-bad-.example.com",
                "data:text/html,hi")) {
            assertEquals("Controlled storage link must be an https:// link", rejected(nb(3), Map.of("cdrLink", bad)).getMessage(), bad);
        }
        assertEquals("Controlled storage link is limited to 1000 characters",
                rejected(nb(3), Map.of("cdrLink", "https://example.com/" + "a".repeat(990))).getMessage());
        assertTrue(StepDataRules.isHttpsUrl("https://example.com/x"));
        assertFalse(StepDataRules.isHttpsUrl("http://example.com/x"));
        assertFalse(StepDataRules.isHttpsUrl(null));
    }

    @Test
    void wordFieldsAreLimitedByWordsAndByCharacters() {
        String twoHundred = "word ".repeat(200).trim();
        assertEquals(twoHundred, StepDataRules.validate(nb(11), Map.of("discrepancyNote", twoHundred)).get("discrepancyNote"));
        assertEquals("Discrepancy / approval notes is limited to 200 words",
                rejected(nb(11), Map.of("discrepancyNote", twoHundred + " more")).getMessage());
        assertEquals("Discrepancy / approval notes is limited to 4000 characters",
                rejected(nb(11), Map.of("discrepancyNote", "x".repeat(4001))).getMessage());
        assertEquals("Final validation notes is limited to 100 words",
                rejected(nine(8), Map.of("finalValidationNotes", "word ".repeat(101))).getMessage());
        assertEquals("Notes is limited to 100 words", rejected(MINIMAL, Map.of("notes", "a ".repeat(101))).getMessage(),
                "a WORDS field without a max gets the default 100 words");
    }

    @Test
    void datesMustBeIsoDates() {
        assertEquals("2026-12-15", StepDataRules.validate(nb(5), Map.of("targetCompletion", " 2026-12-15 ")).get("targetCompletion"));
        assertEquals("Target completion date must be a date (yyyy-mm-dd)", rejected(nb(5), Map.of("targetCompletion", "15/12/2026")).getMessage());
        rejected(nb(5), Map.of("targetCompletion", "2026-02-30"));
        assertFalse(StepDataRules.validate(nb(5), Map.of("targetCompletion", "")).containsKey("targetCompletion"));
    }

    @Test
    void wholeNumbersAcceptNumbersAndDigitStringsWithinRange() {
        assertEquals(500L, StepDataRules.validate(MINIMAL, Map.of("copies", 500)).get("copies"));
        assertEquals(500L, StepDataRules.validate(MINIMAL, Map.of("copies", "500")).get("copies"));
        assertEquals(500L, StepDataRules.validate(MINIMAL, Map.of("copies", 500.0)).get("copies"));
        assertEquals("Copies must be between 0 and 500", rejected(MINIMAL, Map.of("copies", 501)).getMessage());
        rejected(MINIMAL, Map.of("copies", -1));
        assertEquals("Copies must be a whole number", rejected(MINIMAL, Map.of("copies", 1.5)).getMessage());
        rejected(MINIMAL, Map.of("copies", "12a"));
        assertEquals(990L, StepDataRules.validate(nb(11), Map.of("producedCount", 990)).get("producedCount"));
        rejected(nb(11), Map.of("producedCount", 100_000_001L));
    }

    @Test
    void decimalsAreBoundedByTheirMinimumAndMaximum() {
        assertEquals(7.5, StepDataRules.validate(MINIMAL, Map.of("weight", 7.5)).get("weight"));
        assertEquals(7.5, StepDataRules.validate(MINIMAL, Map.of("weight", "7.5")).get("weight"));
        assertEquals("Weight must be between 1 and 50", rejected(MINIMAL, Map.of("weight", 0.5)).getMessage());
        rejected(MINIMAL, Map.of("weight", 51));
        assertEquals("Weight must be a number", rejected(MINIMAL, Map.of("weight", "NaN")).getMessage());
        rejected(MINIMAL, Map.of("weight", "lots"));
    }

    @Test
    void choicesMustMatchExactly() {
        assertEquals("VERIFIED", StepDataRules.validate(nb(7), Map.of("schoolVerification", "VERIFIED")).get("schoolVerification"));
        assertEquals("School verification must be one of [NOT_REQUIRED, PENDING, VERIFIED, CHANGES_REQUIRED]",
                rejected(nb(7), Map.of("schoolVerification", "verified")).getMessage());
        rejected(nb(7), Map.of("schoolVerification", "NOT_REQUIRED_OR_PENDING"));
        assertEquals("PAID", StepDataRules.validate(nb(12), Map.of("invoiceStatus", "PAID")).get("invoiceStatus"));
        rejected(nb(12), Map.of("invoiceStatus", "DONE"));
        assertFalse(StepDataRules.validate(nb(12), Map.of("invoiceStatus", " ")).containsKey("invoiceStatus"));
    }

    @Test
    void checklistItemsAreDistinctSortedIndexes() {
        assertEquals(List.of(0L, 2L, 7L), StepDataRules.validate(nb(8), Map.of("pressChecks", List.of(7, 0, 2, 2))).get("pressChecks"));
        assertEquals("pressChecks items must be between 0 and 7", rejected(nb(8), Map.of("pressChecks", List.of(8))).getMessage());
        rejected(nb(8), Map.of("pressChecks", List.of(-1)));
        assertEquals("pressChecks must be a list of item numbers", rejected(nb(8), Map.of("pressChecks", "0,1")).getMessage());
        assertEquals(List.of(), StepDataRules.validate(nb(8), Map.of("pressChecks", List.of())).get("pressChecks"));
    }

    /** "A data save cannot tick item 8" (D20): only the reviewer sign-off releases bulk printing. */
    @Test
    void theSignOffItemCannotBeSetByData() {
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L),
                StepDataRules.validate(nb(7), Map.of("checks", List.of(0, 1, 2, 3, 4, 5, 6, 7))).get("checks"));
        Map<String, Object> merged = StepDataRules.merge(nb(7), Map.of(), Map.of("checks", List.of(7)));
        assertEquals(List.of(), merged.get("checks"));
        assertFalse(StepDataRules.signedOff(merged));
    }

    @Test
    void aSaveReplacesWhatItSendsClearsWhatItSendsEmptyAndKeepsTheRest() {
        Map<String, Object> saved = map("cdrLink", "https://x.example.com/old", "cdrNotes", "Fonts outlined", "cdrChecks", List.of(0, 2),
                "cdrFilename", "cover.cdr", "retiredKey", "kept");
        Map<String, Object> raw = new HashMap<>();
        raw.put("cdrLink", " https://x.example.com/new ");
        raw.put("cdrNotes", "");
        raw.put("cdrChecks", List.of(1));
        assertEquals(map("cdrLink", "https://x.example.com/new", "cdrChecks", List.of(1L), "cdrFilename", "cover.cdr", "retiredKey", "kept"),
                StepDataRules.merge(nb(3), saved, raw), "legacy and undeclared saved keys are never dropped (D41)");

        Map<String, Object> clear = new HashMap<>();
        clear.put("cdrLink", null);
        assertFalse(StepDataRules.merge(nb(3), saved, clear).containsKey("cdrLink"));
        assertEquals(saved, StepDataRules.merge(nb(3), saved, null));
        WorkflowRuleViolation invalid = assertThrows(WorkflowRuleViolation.class,
                () -> StepDataRules.merge(nb(3), saved, Map.of("cdrLink", "http://insecure.example.com")));
        assertEquals("INVALID_STEP_DATA", invalid.code());
    }

    @Test
    void aSignOffSurvivesASaveOfTheSameValuesAndAChangeVoidsIt() {
        Map<String, Object> evidence = map("checks", List.of(0, 1, 2, 3, 4, 5, 6), "sampleLink", "https://x.example.com/s",
                "schoolVerification", "VERIFIED");
        Map<String, Object> signed = StepDataRules.applySignOff(nb(7), evidence, 42L, AT);
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), signed.get("checks"), "the sign-off ticks item 8");
        assertEquals(new StepDataRules.SignOff(42L, AT), StepDataRules.signOffOf(signed).orElseThrow());

        // The checklist autosave resends the whole list, item 8 included: nothing changed, so the sign-off stands.
        Map<String, Object> resaved = StepDataRules.merge(nb(7), signed, Map.of("checks", List.of(0, 1, 2, 3, 4, 5, 6, 7),
                "schoolVerification", "VERIFIED"));
        assertTrue(StepDataRules.signedOff(resaved));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), resaved.get("checks"));
        assertTrue(StepDataRules.sameValues(signed, resaved));

        // Unticking item 1 is a change: the sign-off is void and item 8 unticks with it.
        Map<String, Object> changed = StepDataRules.merge(nb(7), signed, Map.of("checks", List.of(1, 2, 3, 4, 5, 6, 7)));
        assertFalse(StepDataRules.signedOff(changed));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), changed.get("checks"));

        // So is a new comment.
        Map<String, Object> commented = StepDataRules.merge(nb(7), signed, Map.of("qcComments", "Margins slightly off"));
        assertFalse(StepDataRules.signedOff(commented));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L), commented.get("checks"));
        assertFalse(StepDataRules.sameValues(signed, commented));
    }

    @Test
    void anOldTickOfTheSignOffItemWithoutASignOffIsCleared() {
        Map<String, Object> legacy = map("checks", List.of(0, 1, 2, 3, 4, 5, 6, 7));
        Map<String, Object> saved = StepDataRules.merge(nb(7), legacy, Map.of("sampleLink", "https://x.example.com/s"));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L), saved.get("checks"));
    }

    @Test
    void aSignOffOnAStepWithoutASignOffItemOnlyRecordsWhoAndWhen() {
        Map<String, Object> dispatch = map("transporter", "VRL", "dispatchDate", "2026-11-20", "proofLink", "https://x.example.com/pod");
        Map<String, Object> signed = StepDataRules.applySignOff(nb(12), dispatch, 7L, AT);
        assertEquals(Map.of("by", 7L, "at", "2026-11-13T10:00:00Z"), signed.get(StepDataRules.SIGN_OFF_KEY));
        assertTrue(StepDataRules.signedOff(StepDataRules.merge(nb(12), signed, Map.of("transporter", " VRL "))));
        Map<String, Object> paid = StepDataRules.merge(nb(12), signed, Map.of("invoiceStatus", "PAID"));
        assertFalse(StepDataRules.signedOff(paid), "any change voids the consignment sign-off");
        assertEquals(dispatch, StepDataRules.visible(StepDataRules.clearSignOff(nb(12), signed)));
    }

    /**
     * §2.7 NB Step 12 and 9-step Step 9: the invoice status need not be Paid at completion and superadmin moves it on
     * afterwards. On the completed stage that must not take back the consignment sign-off of a dispatch that has not
     * changed, but changed evidence a gate reads still voids it; before completion every change does (D20).
     */
    @Test
    void aCompletedStageKeepsItsSignOffWhenOnlyDetailsNoGateReadsChange() {
        Map<String, Object> dispatch = map("transporter", "VRL", "dispatchDate", "2026-11-20", "proofLink", "https://x.example.com/pod",
                "invoiceStatus", "INITIATED");
        Map<String, Object> signed = StepDataRules.applySignOff(nb(12), dispatch, 7L, AT);

        Map<String, Object> paid = StepDataRules.merge(nb(12), signed,
                map("invoiceStatus", "PAID", "invoiceNumber", "INV-42", "lrNumber", "LR-7"), true);
        assertEquals(signed.get(StepDataRules.SIGN_OFF_KEY), paid.get(StepDataRules.SIGN_OFF_KEY), "who and when are kept");
        assertEquals("PAID", paid.get("invoiceStatus"));
        assertEquals("INV-42", paid.get("invoiceNumber"));
        assertFalse(StepDataRules.signedOff(StepDataRules.merge(nb(12), signed, map("proofLink", "https://x.example.com/other"), true)),
                "a gated proof link is what the sign-off approved");
        assertFalse(StepDataRules.signedOff(StepDataRules.merge(nb(12), signed, map("transporter", null), true)), "so is clearing it");
        assertFalse(StepDataRules.signedOff(StepDataRules.merge(nb(12), signed, map("invoiceStatus", "PAID"), false)),
                "before completion any change voids it");

        // Nine-step delivery: Delivered is a gate, the invoice status is not.
        Map<String, Object> delivered = StepDataRules.applySignOff(nine(9), map("transporter", "VRL", "dispatchDate", "2026-11-20",
                "proofLink", "https://x.example.com/pod", "deliveryStatus", "DELIVERED"), 7L, AT);
        assertTrue(StepDataRules.signedOff(StepDataRules.merge(nine(9), delivered, map("invoiceStatus", "PAID"), true)));
        assertFalse(StepDataRules.signedOff(StepDataRules.merge(nine(9), delivered, map("deliveryStatus", "PENDING"), true)));

        // The QC sign-off: the reviewer's comments are not gated, the checks are; item 8 follows the record either way.
        Map<String, Object> qc = StepDataRules.applySignOff(nb(7), map("checks", List.of(0, 1, 2, 3, 4, 5, 6),
                "sampleLink", "https://x.example.com/s", "schoolVerification", "VERIFIED"), 7L, AT);
        Map<String, Object> commented = StepDataRules.merge(nb(7), qc, map("qcComments", "Margins fine"), true);
        assertTrue(StepDataRules.signedOff(commented));
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L), commented.get("checks"));
        Map<String, Object> unticked = StepDataRules.merge(nb(7), qc, map("checks", List.of(1, 2, 3, 4, 5, 6)), true);
        assertFalse(StepDataRules.signedOff(unticked));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), unticked.get("checks"));
    }

    @Test
    void clearingASignOffRemovesTheRecordAndUnticksItsItem() {
        Map<String, Object> signed = StepDataRules.applySignOff(nb(7), map("checks", List.of(0, 1)), 1L, AT);
        Map<String, Object> cleared = StepDataRules.clearSignOff(nb(7), signed);
        assertFalse(StepDataRules.signedOff(cleared));
        assertEquals(List.of(0L, 1L), cleared.get("checks"));
        assertEquals(Map.of("checks", List.of(0L, 1L, 7L)), StepDataRules.visible(signed),
                "the screen sees item 8 ticked but never the sign-off record itself");
    }

    @Test
    void savedDataComparesByValueIgnoringTheSignOff() {
        Map<String, Object> clean = StepDataRules.validate(nb(11), Map.of("producedCount", 1000, "discrepancyNote", "None"));
        Map<String, Object> saved = map("producedCount", 1000, "discrepancyNote", "None", StepDataRules.SIGN_OFF_KEY, Map.of("by", 1));
        assertTrue(StepDataRules.sameValues(saved, clean));
        assertTrue(StepDataRules.sameValues(Map.of("checks", List.of(0, 2)), Map.of("checks", List.of(0L, 2L))));
        assertTrue(StepDataRules.sameValues(Map.of("weight", 2), Map.of("weight", 2.0)));
        assertFalse(StepDataRules.sameValues(saved, Map.of("producedCount", 1001L, "discrepancyNote", "None")));
        assertFalse(StepDataRules.sameValues(saved, Map.of("producedCount", 1000L)), "a cleared field is a change");
        assertFalse(StepDataRules.sameValues(Map.of("lrNumber", "70"), Map.of("lrNumber", 70L)), "text is not a number");
    }

    @Test
    void anEmptyChecklistIsTheSameAsNoChecklist() {
        assertTrue(StepDataRules.sameValues(Map.of("sampleLink", "x"), Map.of("sampleLink", "x", "checks", List.of())));
        assertTrue(StepDataRules.sameValues(Map.of("checks", List.of()), Map.of()));
        assertFalse(StepDataRules.sameValues(Map.of(), Map.of("checks", List.of(0L))));
    }

    @Test
    void savedValuesAreReadSafely() {
        Map<String, Object> data = map("checks", List.of(0, 3L, "x", -1), "transporter", "  ", "proofLink", "https://x.example.com",
                "producedCount", 990, "empty", List.of());
        assertEquals(java.util.Set.of(0, 3), StepDataRules.ticked(data, "checks"));
        assertEquals(java.util.Set.of(), StepDataRules.ticked(data, "missing"));
        assertFalse(StepDataRules.present(data, "transporter"));
        assertFalse(StepDataRules.present(data, "empty"));
        assertTrue(StepDataRules.present(data, "proofLink"));
        assertNull(StepDataRules.text(data, "transporter"));
        assertEquals(990L, StepDataRules.whole(data, "producedCount"));
        assertTrue(StepDataRules.signOffOf(Map.of(StepDataRules.SIGN_OFF_KEY, Map.of("by", 1, "at", "not a time"))).isEmpty());
        assertFalse(StepDataRules.signedOff(null));
    }
}
