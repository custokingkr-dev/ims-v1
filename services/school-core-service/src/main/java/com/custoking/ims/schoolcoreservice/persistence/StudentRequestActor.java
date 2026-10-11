package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.security.TenantContext;

/** Reads the current authenticated actor; never accepts a body-supplied identity. */
final class StudentRequestActor {

    private StudentRequestActor() {}

    static Long actorId() {
        return TenantContext.get().userId();
    }
}
