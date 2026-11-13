package com.custoking.ims.operationsservice.application.orderworkflow.template;

/**
 * The closed set of completion rules (design D19). A stage's {@code Complete} is refused until every gate it declares
 * passes.
 * <ul>
 *   <li>{@code FIELDS_PRESENT}: the listed fields are saved.</li>
 *   <li>{@code ALL_CHECKED}: every item of the checklist is ticked, except the listed ones.</li>
 *   <li>{@code CHOICE_IN}: the choice field holds one of the listed values.</li>
 *   <li>{@code DESIGN_FILES_PRESENT}: at least one design file exists.</li>
 *   <li>{@code VENDORS_SELECTED}: each listed category, or any category, has a selected vendor.</li>
 *   <li>{@code HEAD_APPROVED}: an approval exists and it binds the CDR link that is saved now.</li>
 *   <li>{@code SIGNED_OFF}: a sign-off exists and nothing changed since.</li>
 *   <li>{@code COUNTS_CONSISTENT}: when every count is entered and they do not match, a discrepancy note is present.</li>
 * </ul>
 */
public enum GateType {
    FIELDS_PRESENT, ALL_CHECKED, CHOICE_IN, DESIGN_FILES_PRESENT, VENDORS_SELECTED, HEAD_APPROVED, SIGNED_OFF, COUNTS_CONSISTENT
}
