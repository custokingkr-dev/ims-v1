package com.custoking.ims.operationsservice.application.tracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {

    @Test
    void wordsAreCountedByWhitespace() {
        assertEquals(0, NoteRules.wordCount(null));
        assertEquals(0, NoteRules.wordCount("   "));
        assertEquals(3, NoteRules.wordCount("  one  two\nthree "));
    }

    @Test
    void aHundredWordsIsAllowedAndAHundredAndOneIsNot() {
        String hundred = "word ".repeat(100).trim();
        NoteRules.requireWithin(hundred, NoteRules.STAGE_NOTE_MAX_WORDS, "Stage note");
        TrackerRuleViolation tooLong = assertThrows(TrackerRuleViolation.class,
                () -> NoteRules.requireWithin(hundred + " extra", NoteRules.STAGE_NOTE_MAX_WORDS, "Stage note"));
        assertEquals("NOTE_TOO_LONG", tooLong.code());
    }

    @Test
    void theCapIsSixImagesAndTheSeventhIsRejectedWithTheAgreedMessage() {
        assertTrue(DesignImageRules.canAccept(5));
        assertFalse(DesignImageRules.canAccept(6));
        TrackerRuleViolation limit = assertThrows(TrackerRuleViolation.class, () -> DesignImageRules.requireRoom(6));
        assertEquals("No more images allowed. Contact IT.", limit.getMessage());
    }

    @Test
    void anEmailIntentIsDueEachTimeTheCountRisesAboveFour() {
        assertFalse(DesignImageRules.emailIntentDue(3, 4));
        assertTrue(DesignImageRules.emailIntentDue(4, 5));
        assertFalse(DesignImageRules.emailIntentDue(5, 6));
        assertFalse(DesignImageRules.emailIntentDue(6, 5));
        // Deleting back to four and uploading again raises the count above four a second time.
        assertTrue(DesignImageRules.emailIntentDue(4, 5));
    }

    @Test
    void onlyPngJpegWebpAndPdfAreAllowedAndPdfsAreNotPreviewed() {
        for (String type : new String[]{"image/png", "image/jpeg", "image/webp", "application/pdf"}) {
            assertTrue(DesignImageRules.allowedType(type));
        }
        assertFalse(DesignImageRules.allowedType("image/gif"));
        assertFalse(DesignImageRules.allowedType(null));
        assertTrue(DesignImageRules.previewable("image/png"));
        assertFalse(DesignImageRules.previewable("application/pdf"));
        assertEquals(5L * 1024 * 1024, DesignImageRules.MAX_BYTES);
    }

    @Test
    void countsMatchOnlyWhenAcceptedEqualsDispatchWithinProducedWithinOrdered() {
        assertFalse(new Counts(1000, 1000, 1000, 1000).mismatch());
        assertFalse(new Counts(1000, 990, 980, 980).mismatch());
        assertTrue(new Counts(1000, 990, 980, 970).mismatch());
        assertTrue(new Counts(1000, 990, 995, 995).mismatch());
        assertTrue(new Counts(1000, 1010, 1010, 1010).mismatch());
    }

    @Test
    void incompleteOrNegativeCountsAreNotComplete() {
        assertFalse(new Counts(1000, null, 1, 1).complete());
        assertFalse(new Counts(1000, -1, 1, 1).complete());
        assertThrows(IllegalStateException.class, () -> new Counts(1000, null, 1, 1).mismatch());
    }
}
