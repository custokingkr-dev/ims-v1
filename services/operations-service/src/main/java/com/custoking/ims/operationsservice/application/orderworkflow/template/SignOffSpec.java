package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;

/**
 * A stage's sign-off (design D20): the QC reviewer sign-off, the consignment sign-off and the internal team approval.
 * Recording it is a superadmin action authenticated by the signed-in session; it records who and when, and becomes void
 * when the stage's data changes afterwards.
 *
 * @param label  the button label, for example "Record reviewer sign-off"
 * @param role   who may record it; always {@link Role#SUPERADMIN}
 * @param banner the amber banner shown until the sign-off exists, or null
 */
public record SignOffSpec(String label, Role role, String banner) {

    public SignOffSpec {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("A sign-off needs a label");
        if (role != Role.SUPERADMIN) throw new IllegalArgumentException("Only superadmin records sign-offs");
    }
}
