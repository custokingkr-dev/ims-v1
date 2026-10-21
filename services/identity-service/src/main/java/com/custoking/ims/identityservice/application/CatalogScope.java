package com.custoking.ims.identityservice.application;

import java.util.Locale;
import java.util.regex.Pattern;

/** A catalogue category code such as NOTEBOOKS; the same shape the database check accepts. */
public final class CatalogScope {
    private static final Pattern SHAPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private CatalogScope() { }

    /** Upper-cases and validates a category code; blank means "no catalogue scope" and returns null. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (!SHAPE.matcher(code).matches()) {
            throw new IllegalArgumentException("catalogCategory must be a catalogue category code such as NOTEBOOKS");
        }
        return code;
    }

    /** The token-scope form of an item-specific assignment: {@code schoolId:CATEGORY}. */
    public static String key(long schoolId, String category) { return schoolId + ":" + category; }
}
