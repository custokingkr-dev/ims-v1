package com.custoking.ims.operationsservice.application.tracker;

import java.util.Set;

/** Step 2 draft-image limits (spec v4). Only current (not deleted) images count toward the cap. */
public final class DesignImageRules {
    public static final int MAX_IMAGES = 6;
    public static final int EMAIL_INTENT_ABOVE = 4;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final String LIMIT_MESSAGE = "No more images allowed. Contact IT.";
    private static final Set<String> TYPES = Set.of("image/png", "image/jpeg", "image/webp", "application/pdf");

    private DesignImageRules() { }

    public static boolean canAccept(int currentCount) { return currentCount < MAX_IMAGES; }

    public static void requireRoom(int currentCount) {
        if (!canAccept(currentCount)) throw new TrackerRuleViolation("IMAGE_LIMIT", LIMIT_MESSAGE);
    }

    public static boolean allowedType(String contentType) { return contentType != null && TYPES.contains(contentType); }

    /** PDFs count toward the cap but the design preview shows only the latest image. */
    public static boolean previewable(String contentType) { return allowedType(contentType) && !"application/pdf".equals(contentType); }

    /**
     * An email intent is created each time the count rises above 4 after having been 4 or fewer, so deleting back
     * to 4 and uploading again creates another one.
     */
    public static boolean emailIntentDue(int countBefore, int countAfter) {
        return countBefore <= EMAIL_INTENT_ABOVE && countAfter > EMAIL_INTENT_ABOVE;
    }
}
