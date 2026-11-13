package com.custoking.ims.operationsservice.application.orderworkflow.template;

import java.util.List;

/**
 * One completion rule of a stage (design D19). Only the parameters of its {@link GateType} are set; the others are null.
 *
 * @param type       the rule
 * @param fields     {@code FIELDS_PRESENT}: the fields that must be saved; {@code COUNTS_CONSISTENT}: the produced,
 *                   accepted and dispatched count fields, in that order
 * @param field      {@code CHOICE_IN}: the choice field; {@code COUNTS_CONSISTENT}: the discrepancy note field
 * @param values     {@code CHOICE_IN}: the accepted values
 * @param checklist  {@code ALL_CHECKED}: the checklist key
 * @param except     {@code ALL_CHECKED}: zero-based items not required (for example the sign-off item)
 * @param categories {@code VENDORS_SELECTED}: each listed category needs a vendor; null means any one category
 */
public record GateSpec(GateType type, List<String> fields, String field, List<String> values, String checklist,
                       List<Integer> except, List<String> categories) {

    public GateSpec {
        if (type == null) throw new IllegalArgumentException("A gate needs a type");
        fields = fields == null ? null : List.copyOf(fields);
        values = values == null ? null : List.copyOf(values);
        except = except == null ? null : List.copyOf(except);
        categories = categories == null ? null : List.copyOf(categories);
    }

    /** A gate with no parameters ({@code DESIGN_FILES_PRESENT}, {@code HEAD_APPROVED}, {@code SIGNED_OFF}). */
    public static GateSpec of(GateType type) { return new GateSpec(type, null, null, null, null, null, null); }

    public static GateSpec fieldsPresent(List<String> fields) {
        return new GateSpec(GateType.FIELDS_PRESENT, fields, null, null, null, null, null);
    }

    public static GateSpec allChecked(String checklist, List<Integer> except) {
        return new GateSpec(GateType.ALL_CHECKED, null, null, null, checklist, except, null);
    }

    public static GateSpec choiceIn(String field, List<String> values) {
        return new GateSpec(GateType.CHOICE_IN, null, field, values, null, null, null);
    }

    public static GateSpec vendorsSelected(List<String> categories) {
        return new GateSpec(GateType.VENDORS_SELECTED, null, null, null, null, null, categories);
    }

    public static GateSpec countsConsistent(List<String> countFields, String noteField) {
        return new GateSpec(GateType.COUNTS_CONSISTENT, countFields, noteField, null, null, null, null);
    }
}
