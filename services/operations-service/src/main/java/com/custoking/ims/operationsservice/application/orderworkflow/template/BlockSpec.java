package com.custoking.ims.operationsservice.application.orderworkflow.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One card of a stage's work area (design §3.3, §4.3), rendered by the browser in template order. Only the parameters
 * the block type uses are set; the others are null.
 *
 * @param type            the card
 * @param title           the card title, or null for the block type's default
 * @param fields          {@code FIELDS}, {@code ORDER_PDF_LINK}, {@code LEGACY_DETAILS}: the stage fields the card shows, in
 *                        order; {@code INTRO_MESSAGE}: the one {@code TEXT} field the tick saves the message as sent into
 * @param checklist       {@code CHECKLIST}, {@code INTRO_MESSAGE}: the checklist key
 * @param save            {@code FIELDS}, {@code ORDER_PDF_LINK}: the card's save button label, e.g. "Save QC evidence"
 * @param categories      {@code VENDOR_DIRECTORY}, {@code VENDOR_SELECTION}, {@code VENDOR_GROUPS}: the vendor categories
 *                        shown; null means all (selection: every category; groups: every selected vendor)
 * @param suggestedByItem {@code VENDOR_SELECTION}: item code to the categories listed first and expanded
 */
public record BlockSpec(BlockType type, String title, List<String> fields, String checklist, String save, List<String> categories,
                        Map<String, List<String>> suggestedByItem) {

    public BlockSpec {
        if (type == null) throw new IllegalArgumentException("A block needs a type");
        fields = fields == null ? null : List.copyOf(fields);
        categories = categories == null ? null : List.copyOf(categories);
        if (suggestedByItem != null) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            suggestedByItem.forEach((item, codes) -> copy.put(item, List.copyOf(codes)));
            suggestedByItem = Collections.unmodifiableMap(copy);
        }
    }

    /** A block with no parameters. */
    public static BlockSpec of(BlockType type) { return new BlockSpec(type, null, null, null, null, null, null); }
}
