package com.custoking.ims.schoolcoreservice.catalog.domain;

/**
 * Limits on a notebook order's design drafts (spec v4, step 2): at most six current drafts, and a superadmin email
 * intent each time the count rises above four. Only current (not deleted) drafts are counted.
 */
public final class DesignDraftRules {
    public static final String KIND = "DESIGN_DRAFT";
    public static final int MAX_DRAFTS = 6;
    public static final int EMAIL_INTENT_ABOVE = 4;
    public static final String LIMIT_MESSAGE = "No more images allowed. Contact IT.";

    private DesignDraftRules() { }

    public static boolean hasRoom(int currentCount) { return currentCount < MAX_DRAFTS; }

    /** True when adding one draft takes the count from four or fewer to more than four. */
    public static boolean emailIntentDue(int countBefore, int countAfter) {
        return countBefore <= EMAIL_INTENT_ABOVE && countAfter > EMAIL_INTENT_ABOVE;
    }
}
