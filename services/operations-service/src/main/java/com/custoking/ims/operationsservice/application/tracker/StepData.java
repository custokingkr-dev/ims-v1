package com.custoking.ims.operationsservice.application.tracker;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Validates what an operator or superadmin saves into a step's work area. Only the prototype's fields are accepted,
 * each with a type and a size limit; anything else (including the reserved sign-off record) is rejected, so saved JSON
 * can never grow unchecked.
 */
public final class StepData {
    /** Written only by the sign-off action, never accepted from a client. */
    public static final String SIGN_OFF_KEY = "signOff";

    private enum Kind { TEXT, DATE, WHOLE, NUMBER, CHOICE, INDEXES }

    private record Field(Kind kind, long max, List<String> choices) { }

    private static Field text(int maxChars) { return new Field(Kind.TEXT, maxChars, List.of()); }
    private static Field date() { return new Field(Kind.DATE, 0, List.of()); }
    private static Field whole(long max) { return new Field(Kind.WHOLE, max, List.of()); }
    private static Field number(long max) { return new Field(Kind.NUMBER, max, List.of()); }
    private static Field choice(String... values) { return new Field(Kind.CHOICE, 0, List.of(values)); }
    private static Field indexes(int count) { return new Field(Kind.INDEXES, count, List.of()); }

    private static final Map<TrackerStep, Map<String, Field>> SCHEMAS = new EnumMap<>(TrackerStep.class);

    static {
        // Steps 1, 2, 3 and 10 save nothing here: step 1 is read-only, drafts and approval have their own records,
        // and follow-ups are repeated entries.
        SCHEMAS.put(TrackerStep.ORDER_DETAILS, Map.of());
        SCHEMAS.put(TrackerStep.DESIGNING_ACTIVITY, Map.of());
        SCHEMAS.put(TrackerStep.HEAD_APPROVAL, Map.of());
        SCHEMAS.put(TrackerStep.PRODUCTION_FOLLOW_UP, Map.of());
        SCHEMAS.put(TrackerStep.CDR_CONVERSION, fields(
                "cdrFilename", text(200), "cdrLink", text(1000), "cdrNotes", text(1000)));
        SCHEMAS.put(TrackerStep.PRODUCTION_PLANNING, fields(
                "paperGsm", text(50), "capacityConfirmed", text(50), "productionStart", date(),
                "targetCompletion", date(), "planningNotes", text(1000)));
        SCHEMAS.put(TrackerStep.BOARD_PROCUREMENT, fields(
                "totalTitleUnits", whole(100_000_000L), "piecesPerBoard", whole(1_000_000L),
                "wastagePercent", number(100), "availableBoards", whole(100_000_000L), "procurementReference", text(100)));
        SCHEMAS.put(TrackerStep.PRINTING_PRESS, fields(
                "jobReference", text(100), "quantitySent", whole(100_000_000L), "expectedReturnDate", date()));
        SCHEMAS.put(TrackerStep.PRE_PRINT_QUALITY_GATE, fields(
                "checks", indexes(8), "sampleLink", text(1000),
                "schoolVerification", choice("NOT_REQUIRED_OR_PENDING", "VERIFIED", "CHANGES_REQUIRED"),
                "qcComments", text(1000)));
        SCHEMAS.put(TrackerStep.FACTORY_HANDOVER, fields(
                "factory", text(200), "jobReference", text(100), "titlesSent", whole(100_000_000L),
                "titlesReceived", whole(100_000_000L), "factoryContact", text(32)));
        SCHEMAS.put(TrackerStep.VALIDATION_COUNT, fields(
                "producedCount", whole(100_000_000L), "acceptedCount", whole(100_000_000L),
                "dispatchCount", whole(100_000_000L), "discrepancyNote", text(1000)));
        SCHEMAS.put(TrackerStep.DELIVERY_INVOICING, fields(
                "transporter", text(200), "dispatchDate", date(), "lrNumber", text(100), "invoiceNumber", text(100),
                "proofLink", text(1000), "invoiceStatus", choice("NOT_INITIATED", "INITIATED", "SENT", "PAID")));
    }

    private StepData() { }

    private static Map<String, Field> fields(Object... pairs) {
        Map<String, Field> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Field) pairs[i + 1]);
        return Map.copyOf(map);
    }

    /** Names a step accepts, in no particular order. */
    public static java.util.Set<String> fieldNames(TrackerStep step) { return SCHEMAS.get(step).keySet(); }

    /** Returns the cleaned data (blank values dropped) or throws {@link TrackerRuleViolation} with code INVALID_STEP_DATA. */
    public static Map<String, Object> validate(TrackerStep step, Map<String, Object> raw) {
        Map<String, Field> schema = SCHEMAS.get(step);
        Map<String, Object> clean = new LinkedHashMap<>();
        if (raw == null) return clean;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Field field = schema.get(entry.getKey());
            if (field == null) throw invalid(entry.getKey(), "is not a field of " + step.title());
            Object value = clean(entry.getKey(), field, entry.getValue());
            if (value != null) clean.put(entry.getKey(), value);
        }
        return clean;
    }

    /** The sign-off record superadmin's action writes next to the saved data. */
    public static Map<String, Object> signOff(long userId, Instant at) {
        return Map.of("by", userId, "at", at.toString());
    }

    /**
     * Whether two work areas hold the same values, ignoring the sign-off record. Numbers compare by value: a save's
     * validated longs and doubles come back from JSON as whatever width fits.
     */
    public static boolean sameValues(Map<String, Object> saved, Map<String, Object> other) {
        Map<String, Object> left = new HashMap<>(saved), right = new HashMap<>(other);
        left.remove(SIGN_OFF_KEY);
        right.remove(SIGN_OFF_KEY);
        return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> sameValue(left.get(key), right.get(key)));
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!sameValue(x.get(i), y.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private static Object clean(String name, Field field, Object value) {
        if (value == null) return null;
        return switch (field.kind()) {
            case TEXT -> {
                if (!(value instanceof String text)) throw invalid(name, "must be text");
                String trimmed = text.strip();
                if (trimmed.length() > field.max()) throw invalid(name, "is limited to " + field.max() + " characters");
                yield trimmed.isEmpty() ? null : trimmed;
            }
            case DATE -> {
                if (!(value instanceof String text)) throw invalid(name, "must be a date (yyyy-mm-dd)");
                if (text.isBlank()) yield null;
                try { yield LocalDate.parse(text.strip()).toString(); }
                catch (DateTimeParseException bad) { throw invalid(name, "must be a date (yyyy-mm-dd)"); }
            }
            case WHOLE -> {
                Long number = wholeNumber(value);
                if (number == null) throw invalid(name, "must be a whole number");
                if (number < 0 || number > field.max()) throw invalid(name, "must be between 0 and " + field.max());
                yield number;
            }
            case NUMBER -> {
                double number = decimal(name, value);
                if (number < 0 || number > field.max()) throw invalid(name, "must be between 0 and " + field.max());
                yield number;
            }
            case CHOICE -> {
                if (value instanceof String text && text.isBlank()) yield null;
                if (!(value instanceof String text) || !field.choices().contains(text)) {
                    throw invalid(name, "must be one of " + field.choices());
                }
                yield text;
            }
            case INDEXES -> {
                if (!(value instanceof List<?> list)) throw invalid(name, "must be a list of item numbers");
                TreeSet<Long> picked = new TreeSet<>();
                for (Object item : list) {
                    Long index = wholeNumber(item);
                    if (index == null || index < 0 || index >= field.max()) {
                        throw invalid(name, "items must be between 0 and " + (field.max() - 1));
                    }
                    picked.add(index);
                }
                yield new ArrayList<>(picked);
            }
        };
    }

    private static Long wholeNumber(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) return ((Number) value).longValue();
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return d.longValue();
        if (value instanceof String text && text.strip().matches("\\d{1,12}")) return Long.parseLong(text.strip());
        return null;
    }

    private static double decimal(String name, Object value) {
        try {
            double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).strip());
            if (Double.isNaN(number) || Double.isInfinite(number)) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException bad) {
            throw invalid(name, "must be a number");
        }
    }

    private static TrackerRuleViolation invalid(String name, String message) {
        return new TrackerRuleViolation("INVALID_STEP_DATA", name + " " + message);
    }
}
