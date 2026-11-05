package com.custoking.ims.schoolcoreservice.catalog.domain;

/**
 * Design draft review threshold. The client workflow has no hard draft-count limit and flags orders at four uploads.
 */
public final class DesignDraftRules {
    public static final String KIND = "DESIGN_DRAFT";
    public static final int REVIEW_THRESHOLD = 4;

    private DesignDraftRules() { }

    /** True when the current uploaded count reaches the client's review threshold. */
    public static boolean requiresReview(int currentCount) { return currentCount >= REVIEW_THRESHOLD; }

    /** True when adding one draft takes the count from below the review threshold to the threshold or above. */
    public static boolean emailIntentDue(int countBefore, int countAfter) {
        return countBefore < REVIEW_THRESHOLD && countAfter >= REVIEW_THRESHOLD;
    }
}
