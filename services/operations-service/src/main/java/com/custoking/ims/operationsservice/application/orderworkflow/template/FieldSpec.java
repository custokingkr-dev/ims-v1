package com.custoking.ims.operationsservice.application.orderworkflow.template;

import java.util.List;
import java.util.Optional;

/**
 * One typed field of a stage (design §3.3). The loader fills in each kind's limits, so a served spec always shows the
 * limit the server enforces.
 *
 * @param kind        the field kind
 * @param label       the label on screen (verbatim from the prototype where it has one)
 * @param placeholder the input's placeholder, or null
 * @param min         {@code WHOLE}/{@code NUMBER}: the smallest value; null for every other kind
 * @param max         {@code TEXT}: characters; {@code WORDS}: words; {@code URL}: characters; {@code WHOLE}/{@code NUMBER}:
 *                    the largest value; null for {@code DATE} and {@code CHOICE}
 * @param choices     {@code CHOICE}: {@code [value, label]} pairs in display order; null for every other kind
 * @param legacy      a field an earlier version of the stage saved: accepted on save and kept, but only shown read-only
 *                    under "Earlier details" (design D41)
 */
public record FieldSpec(FieldKind kind, String label, String placeholder, Long min, Long max, List<List<String>> choices,
                        boolean legacy) {

    public FieldSpec {
        if (kind == null) throw new IllegalArgumentException("A field needs a kind");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("A field needs a label");
        choices = choices == null ? null : choices.stream().map(List::copyOf).toList();
    }

    /** The stored values of a {@code CHOICE} field, in display order; empty for every other kind. */
    public List<String> choiceValues() {
        return choices == null ? List.of() : choices.stream().map(pair -> pair.get(0)).toList();
    }

    /** The label of a {@code CHOICE} value, if it is one of the choices. */
    public Optional<String> choiceLabel(String value) {
        if (choices == null) return Optional.empty();
        return choices.stream().filter(pair -> pair.get(0).equals(value)).map(pair -> pair.get(1)).findFirst();
    }
}
