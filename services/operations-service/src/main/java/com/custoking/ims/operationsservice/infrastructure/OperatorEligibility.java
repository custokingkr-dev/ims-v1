package com.custoking.ims.operationsservice.infrastructure;

/** Whether a user may be given tracker work for a school and catalogue item. Implementations fail closed. */
public interface OperatorEligibility {
    /**
     * True only when identity confirms the user is an active operator for this school: school-wide, or scoped to this
     * catalogue item. Throws {@link IllegalStateException} when identity cannot be asked, so callers never assign on a
     * guess.
     */
    boolean eligible(long userId, long schoolId, String catalogCategory);
}
