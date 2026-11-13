package com.custoking.ims.schoolcoreservice.catalog.domain;

import java.util.Set;

/**
 * Design files of an order workflow (design D26, D27): at most {@link #MAX_FILES} current files per order and
 * {@link #MAX_TOTAL_BYTES} across them, each a PDF, CorelDRAW, PNG or JPEG file. WEBP is no longer taken for new uploads;
 * files already stored stay. An order already over a limit keeps its files, but takes no new one until it is back under.
 * school-core checks these under a row lock on the order, so two concurrent uploads cannot overshoot either limit.
 */
public final class DesignDraftRules {
    public static final String KIND = "DESIGN_DRAFT";
    public static final int MAX_FILES = 4;
    public static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;
    public static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "application/vnd.corel-draw", "image/png", "image/jpeg");

    private DesignDraftRules() { }

    /**
     * Why one more file of {@code addedBytes} cannot be added to an order holding {@code currentCount} current files of
     * {@code currentBytes} in total, or null when it can.
     */
    public static String refusal(int currentCount, long currentBytes, long addedBytes) {
        if (currentCount >= MAX_FILES) return "An order takes at most " + MAX_FILES + " design files. Remove one before uploading another";
        if (currentBytes + addedBytes > MAX_TOTAL_BYTES) {
            return "Design files may total at most 25 MB per order. Remove a file or upload a smaller one";
        }
        return null;
    }

    /** Whether a detected content type may be uploaded as a design file. */
    public static boolean acceptedType(String contentType) {
        return CONTENT_TYPES.contains(contentType);
    }
}
