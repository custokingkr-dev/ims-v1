package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.template.ChecklistSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.FieldSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.GateSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.GateType;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Validates what an operator or superadmin saves into a stage, against the stage's template (design §3.3, D29, D30).
 * Only the stage's declared fields (legacy ones included) and checklists are accepted, each with its kind and limit, so
 * saved JSON never grows unchecked. The sign-off record is written only by the sign-off action, and a checklist's
 * sign-off item (NB Step 7 item 8) is ticked only by it: a data save can never set either.
 */
public final class StepDataRules {
    /** Written only by the sign-off action, never accepted from a client. */
    public static final String SIGN_OFF_KEY = "signOff";
    /** The longest URL any field accepts (D30). */
    public static final int URL_MAX_CHARS = 1000;
    /** The character cap of a {@code WORDS} field. */
    public static final int WORDS_MAX_CHARS = NoteRules.NOTE_MAX_CHARS;
    /** The longest {@code TEXT} field a template may declare. */
    public static final int TEXT_MAX_CHARS = 4000;

    /**
     * An {@code https://} link with a host name: letters, digits, dots and hyphens, an optional port, then any path, query
     * or fragment without whitespace. Opened by the browser in a new tab, so nothing else (http, javascript:, data:) is
     * ever stored.
     */
    private static final Pattern HTTPS_URL = Pattern.compile(
            "(?i)https://[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?(?::\\d{1,5})?(?:[/?#]\\S*)?");

    private StepDataRules() { }

    /** A recorded sign-off: who and when. */
    public record SignOff(long by, Instant at) { }

    /**
     * The cleaned values of a save, with fields sent blank or null left out.
     *
     * @throws WorkflowRuleViolation {@code INVALID_STEP_DATA} for a key the stage does not declare (or the sign-off record),
     *         or a value of the wrong kind or over its limit
     */
    public static Map<String, Object> validate(StepSpec step, Map<String, Object> raw) {
        Map<String, Object> clean = new LinkedHashMap<>();
        cleanEach(step, raw).forEach((key, value) -> value.ifPresent(present -> clean.put(key, present)));
        return clean;
    }

    /**
     * The stage's data after a partial save (design §3.5 {@code PUT .../data}): each key the save sends replaces the saved
     * value, a key it sends null or blank is cleared, and a key it leaves out keeps its saved value, so one card's save
     * never erases another card's fields. Saved keys the template no longer declares are kept untouched (D41: a merge
     * never drops data).
     *
     * <p>The sign-off approves the values it saw (D20). It survives only when the values are unchanged; any change voids
     * it: the record is removed and the sign-off items are unticked. A sign-off item sent by the client is ignored; it
     * follows the sign-off record. A completed stage keeps it for changes no gate reads ({@link #merge(StepSpec, Map, Map, boolean)}).
     *
     * @throws WorkflowRuleViolation {@code INVALID_STEP_DATA} as {@link #validate}
     */
    public static Map<String, Object> merge(StepSpec step, Map<String, Object> saved, Map<String, Object> raw) {
        return merge(step, saved, raw, false);
    }

    /**
     * As {@link #merge(StepSpec, Map, Map)}, for a stage that is {@code completed} or not. Before completion any change voids
     * the sign-off (D20). A completed stage is edited only by superadmin (D18), and there the sign-off stands for what the
     * stage's completion gates read: a later change to a field or checklist no gate reads keeps it. That is how superadmin
     * moves the invoice on after delivery (§2.7 NB Step 12, 9-step Step 9: the invoice status and number are not gated)
     * without the finished stage losing the consignment sign-off of a dispatch that has not changed. Changed evidence a gate
     * reads (the proof link, the delivery status, a ticked check) still voids it.
     *
     * @throws WorkflowRuleViolation {@code INVALID_STEP_DATA} as {@link #validate}
     */
    public static Map<String, Object> merge(StepSpec step, Map<String, Object> saved, Map<String, Object> raw, boolean completed) {
        Map<String, Optional<Object>> sent = cleanEach(step, raw);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (saved != null) saved.forEach((key, value) -> { if (!SIGN_OFF_KEY.equals(key) && value != null) merged.put(key, value); });
        sent.forEach((key, value) -> {
            if (value.isPresent()) merged.put(key, value.get());
            else merged.remove(key);
        });
        Object record = saved == null ? null : saved.get(SIGN_OFF_KEY);
        boolean keep = record != null && sameValues(signedPart(step, withoutSignOffItems(step, saved), completed),
                signedPart(step, withoutSignOffItems(step, merged), completed));
        if (keep) merged.put(SIGN_OFF_KEY, record);
        setSignOffItems(step, merged, keep);
        return merged;
    }

    /**
     * Whether two saved data maps hold the same values, ignoring the sign-off record. Numbers compare by value (JSON reads
     * them back at whatever width fits), and a checklist with nothing ticked is the same as no checklist.
     */
    public static boolean sameValues(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> left = comparable(a), right = comparable(b);
        return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> sameValue(left.get(key), right.get(key)));
    }

    /** The sign-off record the sign-off action stores under {@link #SIGN_OFF_KEY}. */
    public static Map<String, Object> signOff(long userId, Instant at) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("by", userId);
        record.put("at", at.toString());
        return record;
    }

    /** The stage's data with a sign-off recorded: the record is stored and the sign-off items are ticked (D20). */
    public static Map<String, Object> applySignOff(StepSpec step, Map<String, Object> saved, long userId, Instant at) {
        Map<String, Object> data = copy(saved);
        data.put(SIGN_OFF_KEY, signOff(userId, at));
        setSignOffItems(step, data, true);
        return data;
    }

    /** The stage's data with its sign-off taken back (a reopen voids it): no record and the sign-off items unticked. */
    public static Map<String, Object> clearSignOff(StepSpec step, Map<String, Object> saved) {
        Map<String, Object> data = copy(saved);
        data.remove(SIGN_OFF_KEY);
        setSignOffItems(step, data, false);
        return data;
    }

    /** Whether the data holds a sign-off record. */
    public static boolean signedOff(Map<String, Object> data) {
        return data != null && data.get(SIGN_OFF_KEY) instanceof Map<?, ?>;
    }

    /** The recorded sign-off, if the data holds a well-formed one. */
    public static Optional<SignOff> signOffOf(Map<String, Object> data) {
        if (data == null || !(data.get(SIGN_OFF_KEY) instanceof Map<?, ?> record)) return Optional.empty();
        Long by = wholeNumber(record.get("by"));
        if (by == null || !(record.get("at") instanceof String at)) return Optional.empty();
        try {
            return Optional.of(new SignOff(by, Instant.parse(at)));
        } catch (DateTimeParseException malformed) {
            return Optional.empty();
        }
    }

    /** The data as the screen shows it: everything but the sign-off record (which the view carries separately). */
    public static Map<String, Object> visible(Map<String, Object> data) {
        Map<String, Object> shown = copy(data);
        shown.remove(SIGN_OFF_KEY);
        return shown;
    }

    /** The ticked items of a checklist, as zero-based indexes; empty when nothing is saved. */
    public static Set<Integer> ticked(Map<String, Object> data, String checklist) {
        Set<Integer> ticked = new TreeSet<>();
        if (data != null && data.get(checklist) instanceof List<?> list) {
            for (Object item : list) {
                Long index = wholeNumber(item);
                if (index != null && index >= 0 && index < Integer.MAX_VALUE) ticked.add(index.intValue());
            }
        }
        return ticked;
    }

    /** A saved value counts as present when it is not null, not blank text and not an empty list. */
    public static boolean present(Map<String, Object> data, String key) {
        if (data == null) return false;
        Object value = data.get(key);
        if (value == null) return false;
        if (value instanceof String text) return !text.isBlank();
        if (value instanceof List<?> list) return !list.isEmpty();
        return true;
    }

    /** A saved text value, or null when it is missing, blank or not text. */
    public static String text(Map<String, Object> data, String key) {
        return data != null && data.get(key) instanceof String text && !text.isBlank() ? text : null;
    }

    /** A saved whole number, or null when it is missing or not a whole number. */
    public static Long whole(Map<String, Object> data, String key) {
        return data == null ? null : wholeNumber(data.get(key));
    }

    /** Whether the text is an {@code https://} link the URL fields accept (at most {@link #URL_MAX_CHARS} characters). */
    public static boolean isHttpsUrl(String text) {
        return text != null && text.length() <= URL_MAX_CHARS && HTTPS_URL.matcher(text).matches();
    }

    // ---- internals ------------------------------------------------------------------------------------------------------

    /** Each sent key with its cleaned value, or empty when the key is sent null or blank (a clear). */
    private static Map<String, Optional<Object>> cleanEach(StepSpec step, Map<String, Object> raw) {
        Map<String, Optional<Object>> clean = new LinkedHashMap<>();
        if (raw == null) return clean;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (SIGN_OFF_KEY.equals(key)) {
                throw invalid(key + " is recorded by the sign-off action and cannot be saved as data");
            }
            FieldSpec field = step.fields().get(key);
            ChecklistSpec checklist = step.checklists().get(key);
            if (field != null) clean.put(key, Optional.ofNullable(cleanField(field, entry.getValue())));
            else if (checklist != null) clean.put(key, Optional.ofNullable(cleanChecklist(key, checklist, entry.getValue())));
            else throw invalid(key + " is not a field of " + step.title());
        }
        return clean;
    }

    private static Object cleanField(FieldSpec field, Object value) {
        if (value == null) return null;
        String label = field.label();
        return switch (field.kind()) {
            case TEXT -> {
                String text = stripped(label, value);
                if (text != null && text.length() > limit(field, TEXT_MAX_CHARS)) {
                    throw invalid(label + " is limited to " + limit(field, TEXT_MAX_CHARS) + " characters");
                }
                yield text;
            }
            case WORDS -> {
                String text = stripped(label, value);
                long maxWords = limit(field, NoteRules.STAGE_NOTE_MAX_WORDS);
                if (text != null && NoteRules.wordCount(text) > maxWords) {
                    throw invalid(label + " is limited to " + maxWords + " words");
                }
                if (text != null && text.length() > WORDS_MAX_CHARS) {
                    throw invalid(label + " is limited to " + WORDS_MAX_CHARS + " characters");
                }
                yield text;
            }
            case URL -> {
                String text = stripped(label, value);
                if (text == null) yield null;
                long max = Math.min(limit(field, URL_MAX_CHARS), URL_MAX_CHARS);
                if (text.length() > max) throw invalid(label + " is limited to " + max + " characters");
                if (!HTTPS_URL.matcher(text).matches()) throw invalid(label + " must be an https:// link");
                yield text;
            }
            case DATE -> {
                String text = stripped(label, value);
                if (text == null) yield null;
                try {
                    yield LocalDate.parse(text).toString();
                } catch (DateTimeParseException bad) {
                    throw invalid(label + " must be a date (yyyy-mm-dd)");
                }
            }
            case WHOLE -> {
                if (value instanceof String text && text.isBlank()) yield null;
                Long number = wholeNumber(value);
                if (number == null) throw invalid(label + " must be a whole number");
                long min = field.min() == null ? 0 : field.min(), max = limit(field, Long.MAX_VALUE);
                if (number < min || number > max) throw invalid(label + " must be between " + min + " and " + max);
                yield number;
            }
            case NUMBER -> {
                if (value instanceof String text && text.isBlank()) yield null;
                double number = decimal(label, value);
                long min = field.min() == null ? 0 : field.min(), max = limit(field, Long.MAX_VALUE);
                if (number < min || number > max) throw invalid(label + " must be between " + min + " and " + max);
                yield number;
            }
            case CHOICE -> {
                if (value instanceof String text && text.isBlank()) yield null;
                if (!(value instanceof String text) || !field.choiceValues().contains(text)) {
                    throw invalid(label + " must be one of " + field.choiceValues());
                }
                yield text;
            }
        };
    }

    /** Distinct ticked indexes in ascending order; the sign-off item is dropped, since only the sign-off ticks it. */
    private static List<Long> cleanChecklist(String key, ChecklistSpec checklist, Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?> list)) throw invalid(key + " must be a list of item numbers");
        TreeSet<Long> picked = new TreeSet<>();
        for (Object item : list) {
            Long index = wholeNumber(item);
            if (index == null || index < 0 || index >= checklist.size()) {
                throw invalid(key + " items must be between 0 and " + (checklist.size() - 1));
            }
            picked.add(index);
        }
        if (checklist.signOffItem() != null) picked.remove(checklist.signOffItem().longValue());
        return new ArrayList<>(picked);
    }

    /** Ticks or unticks every checklist's sign-off item in place. */
    private static void setSignOffItems(StepSpec step, Map<String, Object> data, boolean ticked) {
        step.checklists().forEach((key, checklist) -> {
            if (checklist.signOffItem() == null) return;
            long item = checklist.signOffItem();
            boolean saved = data.get(key) instanceof List<?>;
            if (!saved && !ticked) return;
            TreeSet<Long> indexes = new TreeSet<>();
            ticked(data, key).forEach(index -> indexes.add(index.longValue()));
            if (ticked) indexes.add(item);
            else indexes.remove(item);
            data.put(key, new ArrayList<>(indexes));
        });
    }

    /** The data without the sign-off items, so a comparison sees only what a save can change. */
    private static Map<String, Object> withoutSignOffItems(StepSpec step, Map<String, Object> data) {
        Map<String, Object> copy = copy(data);
        step.checklists().forEach((key, checklist) -> {
            if (checklist.signOffItem() != null && copy.get(key) instanceof List<?>) {
                List<Long> indexes = new ArrayList<>();
                ticked(copy, key).stream().filter(index -> !index.equals(checklist.signOffItem()))
                        .forEach(index -> indexes.add(index.longValue()));
                copy.put(key, indexes);
            }
        });
        return copy;
    }

    /** What a sign-off approves: the whole data, or on a completed stage only the keys its gates read. */
    private static Map<String, Object> signedPart(StepSpec step, Map<String, Object> data, boolean completed) {
        if (!completed) return data;
        Set<String> gated = gatedKeys(step);
        Map<String, Object> part = new LinkedHashMap<>(data);
        part.keySet().retainAll(gated);
        return part;
    }

    /** The fields and checklists the stage's gates read. */
    private static Set<String> gatedKeys(StepSpec step) {
        Set<String> keys = new TreeSet<>();
        for (GateSpec gate : step.gates()) {
            if (gate.fields() != null) keys.addAll(gate.fields());
            if (gate.type() == GateType.COUNTS_CONSISTENT && (gate.fields() == null || gate.fields().size() != 3)) {
                keys.addAll(Gates.DEFAULT_COUNT_FIELDS);
            }
            if (gate.field() != null) keys.add(gate.field());
            if (gate.checklist() != null) keys.add(gate.checklist());
        }
        return keys;
    }

    private static Map<String, Object> comparable(Map<String, Object> data) {
        Map<String, Object> copy = copy(data);
        copy.remove(SIGN_OFF_KEY);
        copy.values().removeIf(value -> value instanceof List<?> list && list.isEmpty());
        return copy;
    }

    private static Map<String, Object> copy(Map<String, Object> data) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) data.forEach((key, value) -> { if (value != null) copy.put(key, value); });
        return copy;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!sameValue(x.get(i), y.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }

    private static long limit(FieldSpec field, long fallback) {
        return field.max() == null ? fallback : field.max();
    }

    private static String stripped(String label, Object value) {
        if (!(value instanceof String text)) throw invalid(label + " must be text");
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static Long wholeNumber(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) return ((Number) value).longValue();
        if (value instanceof Double d && !d.isInfinite() && !d.isNaN() && d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue();
        if (value instanceof BigDecimal big) {
            try {
                return big.longValueExact();
            } catch (ArithmeticException notWhole) {
                return null;
            }
        }
        if (value instanceof String text && text.strip().matches("\\d{1,15}")) return Long.parseLong(text.strip());
        return null;
    }

    private static double decimal(String label, Object value) {
        try {
            double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).strip());
            if (Double.isNaN(number) || Double.isInfinite(number)) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException bad) {
            throw invalid(label + " must be a number");
        }
    }

    private static WorkflowRuleViolation invalid(String message) {
        return new WorkflowRuleViolation(WorkflowRuleViolation.INVALID_STEP_DATA, message);
    }
}
