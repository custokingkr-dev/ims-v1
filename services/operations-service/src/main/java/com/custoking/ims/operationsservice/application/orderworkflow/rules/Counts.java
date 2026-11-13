package com.custoking.ims.operationsservice.application.orderworkflow.rules;

/**
 * Validation Count quantities (NB Step 11). "Ordered" is the quantity placed on the order; the other three are entered by
 * the operator and are optional (C9): a discrepancy note is required only when all three are entered and they do not
 * match.
 */
public record Counts(Long ordered, Long produced, Long accepted, Long dispatch) {

    /** Every count is entered and none is negative. */
    public boolean complete() {
        return ordered != null && produced != null && accepted != null && dispatch != null
                && ordered >= 0 && produced >= 0 && accepted >= 0 && dispatch >= 0;
    }

    /** Prototype rule: the counts match when accepted = dispatched and accepted <= produced <= ordered. */
    public boolean mismatch() {
        if (!complete()) throw new IllegalStateException("Counts are incomplete");
        return !(accepted.equals(dispatch) && accepted <= produced && produced <= ordered);
    }
}
