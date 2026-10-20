package com.custoking.ims.schoolcoreservice.persistence;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.custoking.ims.schoolcoreservice.persistence.StudentPresentation.*;

/** Pure parsing and normalization shared by student command persistence. */
final class StudentValues {

    private StudentValues() {}

    static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    static Object firstPresent(Map<String, Object> request, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) return request.get(key);
        }
        return null;
    }

    static String requireText(Object value, String message) {
        String text = str(value, "").trim();
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }

    static Long longValue(Object value, Long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static Long requireLong(Object value, String message) {
        Long parsed = longValue(value, null);
        if (parsed == null) throw new IllegalArgumentException(message);
        return parsed;
    }

    static boolean boolOr(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(value));
    }

    static LocalDate parseDate(String value) {
        return StudentImportDateParser.parseOptional(value);
    }

    static LocalDate parseDateOfBirth(String value) {
        return StudentImportDateParser.parseDateOfBirth(value);
    }

    static boolean validInternationalPhone(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || !normalized.matches("\\+?[0-9() .-]+")) return false;
        return normalized.replaceAll("\\D+", "").matches("\\d{7,15}");
    }

    static int classSortOrder(String classId) {
        String digits = String.valueOf(classId).replaceAll("\\D+", "");
        return digits.isBlank() ? 0 : Integer.parseInt(digits);
    }

    static String canonicalClassLookupName(String className) {
        String normalized = className == null ? "" : className.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[_\\-]+", " ").replaceAll("\\s+", " ");
        return switch (normalized) {
            case "nursery", "pre nursery", "prenursery", "playgroup", "play group", "pre primary",
                    "preprimary", "nursery pre nursery playgroup" -> "pre-primary";
            case "lkg", "lower kindergarten" -> "lkg";
            case "ukg", "upper kindergarten" -> "ukg";
            default -> className;
        };
    }

    static int sectionIndex(String sectionName) {
        String normalized = str(sectionName, "").trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("[A-Z]")) {
            return normalized.charAt(0) - 'A' + 1;
        }
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        return 0;
    }

    static String joinAddress(String... parts) {
        return Arrays.stream(parts).filter(value -> value != null && !value.isBlank()).collect(Collectors.joining(", "));
    }

    static String preferredAddress(Object legacyAddress, String... structuredParts) {
        String structured = joinAddress(structuredParts);
        return structured.isBlank() ? str(legacyAddress, "") : structured;
    }
}
