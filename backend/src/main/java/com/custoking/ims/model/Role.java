package com.custoking.ims.model;

/**
 * Application roles (stored as strings in app_users.role).
 *
 * SUPERADMIN – platform-level; manages schools, cannot access school ERP data.
 * ADMIN      – school-level; full read/write inside their school branch.
 * VIEWER     – school-level; read-only access inside their school branch.
 */
public enum Role {
    SUPERADMIN,
    ADMIN,
    VIEWER;

    public boolean isSchoolLevel() {
        return this == ADMIN || this == VIEWER;
    }

    public boolean canWrite() {
        return this == SUPERADMIN || this == ADMIN;
    }
}
