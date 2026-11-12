package com.custoking.ims.schoolcoreservice.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A complete, valid order form for each structured product category, built from the forms the catalog migrations seed:
 * V9 notebooks, V12 belts and bill books, V14 flex and fliers, V20 ties, V21 certificates, V22 report cards, V25 trophies,
 * V33 the bill book details, V34 brochures, flute boards and standees, and V35 the order-level file links. Every typed value
 * is text, exactly as the order screen sends it; counts are numbers.
 */
final class ProductOrderFixtures {
    /** The categories whose lines carry typed values (a measurement, a free-text size, a GSM) that are stored apart from selections. */
    static final List<String> TYPED = List.of("FLEX", "FLIERS", "BROCHURES", "FLUTE_BOARDS", "STANDEES", "BILLBOOKS");

    private ProductOrderFixtures() { }

    static Map<String, Object> orderData(String category) {
        return switch (category) {
            case "BELTS" -> form(Map.of(), line(10, "BELT_TYPE", "CLOTH", "BUCKLE_TYPE", "BRONZE", "LENGTH", "L70"));
            case "BILLBOOKS" -> form(Map.of("DESIGN_FILE_LINK", "https://drive.google.com/file/d/bill-books"),
                    line(50, "SIZE", "A5", "PAGES", "P100", "PERFORATION", "YES", "SEQUENCE", "WHITE_UP", "SLIPS_PER_PAGE", "S2",
                            "PAGE_COPIES", "2", "COLOR_FIRST_PAGE", "White", "COLOR_FIRST_COPY_PAGE", "Pink",
                            "COLOR_SECOND_COPY_PAGE", "Yellow", "PRINT_COLOR_FIRST_PAGE", "Blue",
                            "PRINT_COLOR_FIRST_COPY_PAGE", "Blue", "PRINT_COLOR_SECOND_COPY_PAGE", "Blue",
                            "NUMBERING_REQUIRED", "YES", "NUMBERING_START", "1001", "NUMBERING_BASED_ON", "SLIPS"));
            case "BROCHURES" -> form(Map.of(), line(500, "HEIGHT_AFTER_FOLDING", "8.5 in", "WIDTH_AFTER_FOLDING", "11 in",
                    "BROCHURE_TYPE", "Tri-fold", "FOLD_COUNT", "2", "INNER_PAPER_GSM", "130"));
            case "CERTIFICATES" -> form(Map.of(), line(20, "CATEGORY", "GENERIC", "SIZE", "A4", "GSM", "GSM_300"));
            case "FLEX" -> form(Map.of("DESIGN_FILE_LINK", "https://drive.google.com/file/d/flex"),
                    line(2, "FLEX_TYPE", "STAR", "LENGTH_FT", "6", "BREADTH_FT", "4.5"));
            case "FLIERS" -> form(Map.of(), line(3000, "SIZE", "A4", "GSM", "130"));
            case "FLUTE_BOARDS" -> form(Map.of(), line(4, "HEIGHT_FT", "3", "WIDTH_FT", "2.25"));
            case "REPORT_CARDS" -> form(Map.of(), line(50, "SIZE", "A4", "INNER_PAGES", "P8", "FOLDING", "YES"));
            case "STANDEES" -> form(Map.of(), line(3, "STANDEE_TYPE", "Roll-up", "HEIGHT_FT", "6", "WIDTH_FT", "2.5",
                    "FLEX_TYPE", "Star flex"));
            case "TIES" -> form(Map.of(), line(40, "TIE_TYPE", "SATIN", "LENGTH", "LEN_12"));
            case "TROPHIES" -> form(Map.of(), line(3, "VARIANT", "T_WM001_B"));
            case "NOTEBOOKS" -> Map.of("orderSelections", Map.of("CUSTOMIZATION", "NON_CUSTOMIZED"), "lines", List.of(
                    Map.of("selections", Map.of("SIZE", "LONG", "RULING", "SINGLE_RULE"), "bookCount", 25, "pageCount", 198)));
            default -> throw new IllegalArgumentException("No order form fixture for " + category);
        };
    }

    /** What the typed fields of {@link #orderData}'s line are stored as: numbers for INTEGER and DECIMAL fields, text otherwise. */
    static Map<String, Object> storedAttributes(String category) {
        return switch (category) {
            case "BILLBOOKS" -> pairs("PAGE_COPIES", 2, "COLOR_FIRST_PAGE", "White", "COLOR_FIRST_COPY_PAGE", "Pink",
                    "COLOR_SECOND_COPY_PAGE", "Yellow", "PRINT_COLOR_FIRST_PAGE", "Blue", "PRINT_COLOR_FIRST_COPY_PAGE", "Blue",
                    "PRINT_COLOR_SECOND_COPY_PAGE", "Blue", "NUMBERING_START", 1001);
            case "BROCHURES" -> pairs("HEIGHT_AFTER_FOLDING", "8.5 in", "WIDTH_AFTER_FOLDING", "11 in", "BROCHURE_TYPE", "Tri-fold",
                    "INNER_PAPER_GSM", "130", "FOLD_COUNT", "2");
            case "FLEX" -> pairs("LENGTH_FT", 6, "BREADTH_FT", 4.5);
            case "FLIERS" -> pairs("SIZE", "A4", "GSM", 130);
            case "FLUTE_BOARDS" -> pairs("HEIGHT_FT", 3, "WIDTH_FT", 2.25);
            case "STANDEES" -> pairs("STANDEE_TYPE", "Roll-up", "HEIGHT_FT", 6, "WIDTH_FT", 2.5, "FLEX_TYPE", "Star flex");
            default -> Map.of();
        };
    }

    private static Map<String, Object> form(Map<String, Object> orderSelections, Map<String, Object> line) {
        return Map.of("orderSelections", orderSelections, "lines", List.of(line));
    }

    private static Map<String, Object> line(int count, Object... selections) {
        return Map.of("selections", pairs(selections), "bookCount", count, "pageCount", 1);
    }

    private static Map<String, Object> pairs(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
