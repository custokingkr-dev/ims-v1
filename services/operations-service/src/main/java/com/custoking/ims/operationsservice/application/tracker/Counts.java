package com.custoking.ims.operationsservice.application.tracker;

/**
 * Step 11 quantities. "Ordered" is the count entered at order placement; the other three are typed by the operator.
 */
public record Counts(Integer ordered, Integer produced, Integer accepted, Integer dispatch) {

    public boolean complete() {
        return ordered != null && produced != null && accepted != null && dispatch != null
                && ordered >= 0 && produced >= 0 && accepted >= 0 && dispatch >= 0;
    }

    /** Prototype rule: a match needs accepted = dispatch and accepted <= produced <= ordered. */
    public boolean mismatch() {
        if (!complete()) throw new IllegalStateException("Counts are incomplete");
        return !(accepted.equals(dispatch) && accepted <= produced && produced <= ordered);
    }
}
