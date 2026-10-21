package com.custoking.ims.operationsservice.application.tracker;

/** Word limits for stage notes and internal-note entries (100 words each). */
public final class NoteRules {
    public static final int STAGE_NOTE_MAX_WORDS = 100;
    public static final int INTERNAL_NOTE_MAX_WORDS = 100;

    private NoteRules() { }

    public static int wordCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    public static void requireWithin(String text, int maxWords, String what) {
        if (wordCount(text) > maxWords) {
            throw new TrackerRuleViolation("NOTE_TOO_LONG", what + " is limited to " + maxWords + " words");
        }
    }
}
