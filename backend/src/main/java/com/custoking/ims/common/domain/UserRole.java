package com.custoking.ims.common.domain;

public enum UserRole {
    SUPERADMIN,
    SCHOOL_ADMIN,
    OPERATIONS,
    STAFF,
    ACCOUNTANT,
    APPROVER;

    public String getAuthority() {
        return "ROLE_" + name();
    }
}