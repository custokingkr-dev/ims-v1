package com.custoking.ims.operationsservice.infrastructure;

import java.util.Collection;
import java.util.Map;

/** Staff display names, which identity owns: the tracker shows who is assigned a step and who wrote a note. */
public interface StaffDirectory {
    /** The most ids one call may ask about. */
    int MAX_IDS = 200;

    /**
     * Full names of the ids identity knows as staff: active users holding an OPERATIONS or SUPERADMIN assignment. Any
     * other id is absent from the answer. Throws {@link IllegalArgumentException} for more than {@link #MAX_IDS} ids and
     * {@link IllegalStateException} when identity cannot be asked.
     */
    Map<Long, String> fullNames(Collection<Long> userIds);
}
