package com.custoking.ims.schoolcoreservice.persistence;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Shared response formatting; never accesses storage, tenant state or a database. */
final class StudentPresentation {
    private StudentPresentation() {}

    static boolean blankOrAll(String value) {
        return value == null || value.isBlank() || "All".equalsIgnoreCase(value);
    }

    static String initials(String fullName) {
        return Arrays.stream(str(fullName, "").split(" "))
                .filter(value -> !value.isBlank())
                .limit(2)
                .map(value -> value.substring(0, 1).toUpperCase(Locale.ROOT))
                .collect(Collectors.joining());
    }

    static String photoReference(long studentId, String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String trimmed = stored.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "/students/" + studentId + "/photo/content?v=" + photoVersion(trimmed);
    }

    static String photoVersion(String stored) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception ex) {
            return Integer.toHexString(stored.hashCode());
        }
    }

    static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    static Map<String, Object> row(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("row requires key/value pairs");
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

}
