package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Word limits (D29), ported from the tracker's RulesTest. The counting cases mirror the browser's {@code words.test.ts}
 * so the "n/100 words" counter and the server agree.
 */
class NoteRulesTest {

    @Test
    void wordsAreCountedByWhitespaceExactlyAsTheBrowserCountsThem() {
        assertEquals(0, NoteRules.wordCount(null));
        assertEquals(0, NoteRules.wordCount(""));
        assertEquals(0, NoteRules.wordCount("   "));
        assertEquals(0, NoteRules.wordCount("\n\t \n"));
        assertEquals(3, NoteRules.wordCount("  one  two\nthree "));
        assertEquals(2, NoteRules.wordCount("one\r\n\r\ntwo"));
        assertEquals(1, NoteRules.wordCount("one two"), "a no-break space does not separate words");
    }

    @Test
    void aHundredWordsIsAllowedAndAHundredAndOneIsNot() {
        String hundred = "word ".repeat(100).trim();
        NoteRules.requireWithin(hundred, NoteRules.STAGE_NOTE_MAX_WORDS, "A stage note");
        WorkflowRuleViolation tooLong = assertThrows(WorkflowRuleViolation.class,
                () -> NoteRules.requireWithin(hundred + " extra", NoteRules.STAGE_NOTE_MAX_WORDS, "A stage note"));
        assertEquals("NOTE_TOO_LONG", tooLong.code());
        assertEquals("A stage note is limited to 100 words", tooLong.getMessage());
        assertEquals(400, tooLong.httpStatus());
    }

    @Test
    void theLimitsAreTheDesignsLimits() {
        assertEquals(List.of(100, 100, 100, 100), List.of(NoteRules.STAGE_NOTE_MAX_WORDS, NoteRules.INTERNAL_NOTE_MAX_WORDS,
                NoteRules.CALL_NOTE_MAX_WORDS, NoteRules.APPROVAL_COMMENT_MAX_WORDS));
        assertEquals(4000, NoteRules.NOTE_MAX_CHARS);
        assertEquals(1000, NoteRules.CALL_NOTE_MAX_CHARS);
        assertEquals(1000, NoteRules.APPROVAL_COMMENT_MAX_CHARS);
    }

    @Test
    void optionalTextIsStrippedAndBlankIsNull() {
        assertNull(NoteRules.optional(null, 100, 1000, "Call notes"));
        assertNull(NoteRules.optional(" \n ", 100, 1000, "Call notes"));
        assertEquals("Called the principal", NoteRules.optional("  Called the principal \n", 100, 1000, "Call notes"));
    }

    @Test
    void aFewLongWordsAreCaughtByTheCharacterCap() {
        String longWords = ("x".repeat(400) + " ").repeat(3).strip();
        WorkflowRuleViolation tooLong = assertThrows(WorkflowRuleViolation.class,
                () -> NoteRules.optional(longWords, NoteRules.CALL_NOTE_MAX_WORDS, NoteRules.CALL_NOTE_MAX_CHARS, "Call notes"));
        assertEquals("NOTE_TOO_LONG", tooLong.code());
        assertEquals("Call notes is limited to 1000 characters", tooLong.getMessage());
    }

    @Test
    void requiredTextRefusesBlankWithItsOwnCode() {
        WorkflowRuleViolation empty = assertThrows(WorkflowRuleViolation.class,
                () -> NoteRules.required("   ", NoteRules.APPROVAL_COMMENT_MAX_WORDS, NoteRules.APPROVAL_COMMENT_MAX_CHARS, "An approval comment"));
        assertEquals("NOTE_REQUIRED", empty.code());
        assertEquals("An approval comment cannot be empty", empty.getMessage());
        assertEquals(400, empty.httpStatus());
        assertEquals("Approved as v3", NoteRules.required(" Approved as v3 ", 100, 1000, "An approval comment"));
        assertEquals("NOTE_TOO_LONG", assertThrows(WorkflowRuleViolation.class,
                () -> NoteRules.required("word ".repeat(101), 100, 1000, "An approval comment")).code());
    }

    @Test
    void ruleViolationsMapToOneHttpStatusPerCode() {
        assertEquals(403, new WorkflowRuleViolation("SUPERADMIN_ONLY", "x").httpStatus());
        assertEquals(403, new WorkflowRuleViolation("NOT_ASSIGNEE", "x").httpStatus());
        assertEquals(400, new WorkflowRuleViolation("INVALID_STEP_DATA", "x").httpStatus());
        assertEquals(400, new WorkflowRuleViolation("INVALID_INVITE", "x").httpStatus());
        for (String conflict : List.of("GATE_UNMET", "STEP_NOT_EDITABLE", "STEP_NOT_ACTIVE", "HEAD_APPROVAL_STALE", "STEP_NOT_COMPLETED")) {
            assertEquals(409, new WorkflowRuleViolation(conflict, "x").httpStatus(), conflict);
        }
        assertEquals(List.of(), new WorkflowRuleViolation("STEP_NOT_ACTIVE", "x").unmet());
        assertThrows(IllegalArgumentException.class, () -> WorkflowRuleViolation.gateUnmet("Order Details", List.of()));
        WorkflowRuleViolation unmet = WorkflowRuleViolation.gateUnmet("Order Details",
                List.of(new GateFailure("FIELDS_PRESENT", "Save the school order PDF link"), new GateFailure("ALL_CHECKED", "Tick \"Sent\"")));
        assertEquals("Order Details cannot be completed yet: Save the school order PDF link; Tick \"Sent\"", unmet.getMessage());
        assertEquals(2, unmet.unmet().size());
    }
}
