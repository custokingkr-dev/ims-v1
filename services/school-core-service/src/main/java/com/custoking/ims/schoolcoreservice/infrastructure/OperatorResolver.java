package com.custoking.ims.schoolcoreservice.infrastructure;

/** Chooses the operator who receives a new order's steps; identity owns the answer. */
public interface OperatorResolver {

    /** {@code userId} is set only for ITEM_SPECIFIC and SCHOOL_WIDE; AMBIGUOUS and NONE leave the order unassigned. */
    record Resolution(String outcome, Long userId) { }

    /** Throws {@link IllegalStateException} when identity cannot be asked. */
    Resolution resolve(long schoolId, String catalogCategory);
}
