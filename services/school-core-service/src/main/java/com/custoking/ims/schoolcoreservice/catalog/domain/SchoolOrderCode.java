package com.custoking.ims.schoolcoreservice.catalog.domain;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A school's order code (design D12): {@code <Initials>_<Location>}, the school part of an order number such as
 * {@code NB-DPS_GUR-26-11-13-0042}. It has no hyphen, so an order number splits cleanly on "-".
 *
 * <p>The derivation is the suggestion for a new school and the rule tenant_school V31 backfilled with
 * ({@code tenant_school.school_order_code_base}); keep the two in step. Superadmin may set any code that matches
 * {@link #FORMAT}.
 */
public final class SchoolOrderCode {
    public static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{2,8}_[A-Z0-9]{2,6}$");
    public static final int MAX_INITIALS = 8;
    private static final Set<String> STOP_WORDS = Set.of("OF", "THE", "AND", "FOR");

    private SchoolOrderCode() { }

    /**
     * The derived code, before any collision suffix. Initials: the first letter of each word of the name (A-Z only),
     * ignoring OF, THE, AND, FOR and "&", up to 8; with fewer than 2, the first 3 letters of the first such word, padded
     * with X. Location: the first 3 letters of the city, else of the state, else IN.
     */
    public static String derive(String name, String city, String state) {
        StringBuilder initials = new StringBuilder();
        String firstWord = null;
        for (String word : upper(name).strip().split("\\s+")) {
            String letters = lettersOnly(word);
            if (letters.isEmpty() || STOP_WORDS.contains(letters)) continue;
            if (firstWord == null) firstWord = letters;
            if (initials.length() < MAX_INITIALS) initials.append(letters.charAt(0));
        }
        String head = initials.toString();
        if (head.length() < 2) head = firstWord == null ? "" : firstWord.substring(0, Math.min(3, firstWord.length()));
        if (head.length() < 2) head = (head + "XX").substring(0, 2);
        String location = firstLetters(city);
        if (location.length() < 2) location = firstLetters(state);
        if (location.length() < 2) location = "IN";
        return head + "_" + location;
    }

    /** The code with collision suffix {@code n} (2 or more) on its initials, shortened to keep them within 8 characters. */
    public static String withSuffix(String code, int n) {
        if (n < 2) throw new IllegalArgumentException("A collision suffix starts at 2");
        int separator = code.indexOf('_');
        String initials = code.substring(0, separator);
        String suffix = Integer.toString(n);
        return initials.substring(0, Math.min(initials.length(), MAX_INITIALS - suffix.length())) + suffix + code.substring(separator);
    }

    /** The code itself when it is free, else the first free suffixed form (DPS2_GUR, DPS3_GUR ...). */
    public static String firstFree(String code, Predicate<String> taken) {
        String candidate = code;
        for (int n = 2; taken.test(candidate); n++) candidate = withSuffix(code, n);
        return candidate;
    }

    /**
     * A code a person typed, trimmed and upper-cased; null when blank. Throws {@link IllegalArgumentException} with a
     * message for the screen when it does not match the format.
     */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(code).matches()) {
            throw new IllegalArgumentException("Order code must be 2-8 letters or digits, an underscore, then 2-6 letters or digits, such as DPS_GUR");
        }
        return code;
    }

    private static String firstLetters(String value) {
        String letters = lettersOnly(upper(value));
        return letters.substring(0, Math.min(3, letters.length()));
    }

    private static String lettersOnly(String value) {
        return value.replaceAll("[^A-Z]", "");
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
