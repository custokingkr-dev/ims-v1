package com.custoking.ims.schoolcoreservice.catalog.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Notebook title-card quantities (design D24), computed from an order's placed lines. King plus Jumbo King, and Long, need
 * one print sheet per 8 notebooks plus 13 for wastage; Jumbo Long and FA one per 4 plus 25. A size with no notebooks needs
 * no sheets. The client's sample order (8,600 King, 4,000 Long, 3,000 Jumbo Long, no FA) gives 1,088 / 513 / 775 / 0.
 *
 * <p>Lines are matched to a size by the text of their selections, exactly as the retired screen did
 * ({@code titleCardQuantities} in the notebook tracker's steps.ts): the codes and labels of every selection, upper-cased.
 * A line that matches no title-card size (a drawing book, say) is listed as "Not on a title card" with its count.
 */
public final class TitleCardQuantities {
    private static final Pattern FA = Pattern.compile("\\bFA\\b|FORMATIVE|ASSESSMENT");
    private static final Pattern KING = Pattern.compile("\\bKING\\b");
    private static final Pattern LONG = Pattern.compile("\\bLONG\\b");

    private TitleCardQuantities() { }

    /** One placed line: its selections as stored (group code to a {code, label} snapshot, or plain text) and its count. */
    public record Line(int lineNo, Map<String, Object> selections, int bookCount) { }

    /** A table row: notebooks of this type in the order, and the print sheets they need. */
    public record Row(String type, String label, String size, int notebooks, int sheets) { }

    /** A line that matches no title-card size. */
    public record Unmatched(int lineNo, String label, int notebooks) { }

    /**
     * {@code counts}: notebooks per size (king, long, jumboKing, jumboLong, fa, other) for the summary tiles. {@code rows}:
     * always the four table rows, in table order.
     */
    public record Result(Map<String, Integer> counts, List<Row> rows, List<Unmatched> unmatched) { }

    public static Result compute(List<Line> lines) {
        int king = 0, longSize = 0, jumboKing = 0, jumboLong = 0, fa = 0, other = 0;
        List<Unmatched> unmatched = new ArrayList<>();
        for (Line line : lines) {
            String text = text(line.selections());
            int quantity = Math.max(0, line.bookCount());
            if (FA.matcher(text).find()) fa += quantity;
            else if (text.contains("JUMBO") && text.contains("LONG")) jumboLong += quantity;
            else if (text.contains("JUMBO") && text.contains("KING")) jumboKing += quantity;
            else if (KING.matcher(text).find()) king += quantity;
            else if (LONG.matcher(text).find()) longSize += quantity;
            else {
                other += quantity;
                unmatched.add(new Unmatched(line.lineNo(), label(line), quantity));
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("king", king);
        counts.put("long", longSize);
        counts.put("jumboKing", jumboKing);
        counts.put("jumboLong", jumboLong);
        counts.put("fa", fa);
        counts.put("other", other);
        List<Row> rows = List.of(
                new Row("KING_JUMBO_KING", "KING/JUMBO KING", "80*105", king + jumboKing, sheets(king + jumboKing, 8, 13)),
                new Row("LONG", "LONG", "72*112", longSize, sheets(longSize, 8, 13)),
                new Row("JUMBO_LONG", "JUMBO LONG", "63.5*76", jumboLong, sheets(jumboLong, 4, 25)),
                new Row("FA", "FA", "23*36", fa, sheets(fa, 4, 25)));
        return new Result(counts, rows, List.copyOf(unmatched));
    }

    /** Rounded-up print sheets plus the wastage sheets; no wastage when there are no notebooks of this type. */
    public static int sheets(int notebooks, int perSheet, int wastage) {
        return notebooks > 0 ? (notebooks + perSheet - 1) / perSheet + wastage : 0;
    }

    private static String text(Map<String, Object> selections) {
        List<String> parts = new ArrayList<>();
        if (selections != null) {
            for (Object value : selections.values()) {
                if (value instanceof Map<?, ?> snapshot) parts.add(String.valueOf(snapshot.get("code")) + " " + snapshot.get("label"));
                else if (value != null) parts.add(String.valueOf(value));
            }
        }
        return String.join(" ", parts).toUpperCase(Locale.ROOT);
    }

    private static String label(Line line) {
        Map<String, Object> selections = line.selections() == null ? Map.of() : line.selections();
        Object size = selections.get("SIZE");
        if (size != null) return display(size);
        List<String> labels = selections.values().stream().map(TitleCardQuantities::display).filter(text -> !text.isBlank()).toList();
        return labels.isEmpty() ? "Line " + line.lineNo() : String.join(" / ", labels);
    }

    private static String display(Object value) {
        if (value instanceof Map<?, ?> snapshot) {
            Object label = snapshot.get("label");
            return label == null ? String.valueOf(snapshot.get("code")) : String.valueOf(label);
        }
        return value == null ? "" : String.valueOf(value);
    }
}
