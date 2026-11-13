package com.custoking.ims.operationsservice.application.orderworkflow.rules;

/**
 * Word limits on workflow text (design D29). A word is a whitespace-separated token, and every word-limited text also has
 * a character cap. The browser's {@code features/orders/shared/words.ts} counts exactly as {@link #wordCount} does, so
 * its "n/100 words" counter agrees with what the server accepts.
 */
public final class NoteRules {
    /** Stage notes ("Stage notes & next action"). */
    public static final int STAGE_NOTE_MAX_WORDS = 100;
    /** Order-level internal notes (sidebar). */
    public static final int INTERNAL_NOTE_MAX_WORDS = 100;
    /** Call log notes. */
    public static final int CALL_NOTE_MAX_WORDS = 100;
    /** Head Approval comments, approve and request changes alike (required). */
    public static final int APPROVAL_COMMENT_MAX_WORDS = 100;
    /** Reopen and send-back reasons. */
    public static final int REASON_MAX_WORDS = 100;

    /** The character cap of stage and internal notes and of {@code WORDS} fields. */
    public static final int NOTE_MAX_CHARS = 4000;
    /** Call notes are stored under a 1,000-character CHECK (workflow V20). */
    public static final int CALL_NOTE_MAX_CHARS = 1000;
    /** Head Approval comments are stored in a VARCHAR(1000) (workflow V18). */
    public static final int APPROVAL_COMMENT_MAX_CHARS = 1000;
    /** Reasons are recorded in {@code workflow_actions.notes}, a VARCHAR(1000). */
    public static final int REASON_MAX_CHARS = 1000;

    private NoteRules() { }

    /**
     * Blank text has no words; otherwise the trimmed text is split on runs of ASCII whitespace. A no-break space does not
     * separate words.
     */
    public static int wordCount(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    /** Refuses text over {@code maxWords} words with {@code NOTE_TOO_LONG}. */
    public static void requireWithin(String text, int maxWords, String what) {
        if (wordCount(text) > maxWords) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.NOTE_TOO_LONG, what + " is limited to " + maxWords + " words");
        }
    }

    /**
     * Optional text: the stripped text, or null when it is blank.
     *
     * @throws WorkflowRuleViolation {@code NOTE_TOO_LONG} over {@code maxWords} words or {@code maxChars} characters
     */
    public static String optional(String text, int maxWords, int maxChars, String what) {
        if (text == null || text.isBlank()) return null;
        String clean = text.strip();
        requireWithin(clean, maxWords, what);
        if (clean.length() > maxChars) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.NOTE_TOO_LONG, what + " is limited to " + maxChars + " characters");
        }
        return clean;
    }

    /**
     * Required text: the stripped text.
     *
     * @throws WorkflowRuleViolation {@code NOTE_REQUIRED} when it is blank, {@code NOTE_TOO_LONG} when it is too long
     */
    public static String required(String text, int maxWords, int maxChars, String what) {
        String clean = optional(text, maxWords, maxChars, what);
        if (clean == null) throw new WorkflowRuleViolation(WorkflowRuleViolation.NOTE_REQUIRED, what + " cannot be empty");
        return clean;
    }
}
