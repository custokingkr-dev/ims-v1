package com.custoking.ims.operationsservice.application.orderworkflow.template;

import java.util.List;
import java.util.Set;

/**
 * A stage checklist (design §3.3, D17). Saved data stores the ticked items as zero-based indexes, so the item order is
 * part of the stored meaning and never changes for an existing checklist.
 *
 * @param items       the item labels, verbatim
 * @param signOffItem the zero-based index of the item that only the stage's sign-off ticks (NB Step 7 item 8 is 7), or
 *                    null; a data save can never tick it
 * @param source      where the wording comes from: {@code prototype}, {@code sop}, {@code note} or {@code ours}
 */
public record ChecklistSpec(List<String> items, Integer signOffItem, String source) {

    /** The allowed {@code source} values. */
    public static final Set<String> SOURCES = Set.of("prototype", "sop", "note", "ours");

    public ChecklistSpec {
        items = items == null ? List.of() : List.copyOf(items);
        if (items.isEmpty()) throw new IllegalArgumentException("A checklist needs at least one item");
        if (signOffItem != null && (signOffItem < 0 || signOffItem >= items.size())) {
            throw new IllegalArgumentException("A checklist's sign-off item must be one of its items");
        }
        if (source == null || !SOURCES.contains(source)) throw new IllegalArgumentException("A checklist needs a source in " + SOURCES);
    }

    public int size() { return items.size(); }
}
