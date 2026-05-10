package com.custoking.ims.model;

/**
 * Application roles (stored as strings in app_users.role).
 *
 * SUPERADMIN – platform-level; full access.
 * ZONE_ADMIN – zone-level; sees all schools in assigned zone.
 * ADMIN      – school-level; full access within school.
 * OPERATIONS – school-level; daily ops without finance.
 * ACCOUNTANT – school-level; finance and fee management.
 * TEACHER    – school-level; attendance and student access.
 * VIEWER     – school-level; read-only.
 */
public enum Role {
    SUPERADMIN,
    ZONE_ADMIN,
    ADMIN,
    OPERATIONS,
    ACCOUNTANT,
    TEACHER,
    VIEWER;

    public boolean isSchoolLevel() {
        return this == ADMIN || this == OPERATIONS || this == ACCOUNTANT
                || this == TEACHER || this == VIEWER;
    }

    public boolean canWrite() {
        return this == SUPERADMIN || this == ZONE_ADMIN || this == ADMIN || this == OPERATIONS || this == ACCOUNTANT;
    }
}
