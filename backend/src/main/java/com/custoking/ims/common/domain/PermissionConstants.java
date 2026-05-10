package com.custoking.ims.common.domain;

/**
 * Centralised Spring Security SpEL expressions for @PreAuthorize.
 * All role names match the Role enum: SUPERADMIN, ZONE_ADMIN, ADMIN,
 * OPERATIONS, ACCOUNTANT, TEACHER, VIEWER.
 * Add new constants here; never hard-code role strings in controllers.
 */
public final class PermissionConstants {

    // ── Broad access tiers ────────────────────────────────────────────────────
    public static final String SUPERADMIN_ACCESS =
            "hasRole('SUPERADMIN')";

    public static final String ZONE_OR_SUPER =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN')";

    public static final String ADMIN_ACCESS =
            "hasAnyRole('SUPERADMIN','ADMIN')";

    /** Any authenticated school-level or above user. */
    public static final String WORKSPACE_ACCESS =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT','TEACHER','VIEWER')";

    /** Can make write-level school ops (ADMIN + OPERATIONS). */
    public static final String WRITE_ACCESS =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";

    // ── School management ─────────────────────────────────────────────────────
    public static final String SCHOOL_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN')";
    public static final String SCHOOL_CREATE =
            "hasRole('SUPERADMIN')";
    public static final String SCHOOL_UPDATE =
            "hasRole('SUPERADMIN')";
    public static final String SCHOOL_MANAGE_ADMIN =
            "hasRole('SUPERADMIN')";
    public static final String SCHOOL_MANAGE_OPERATIONS =
            "hasRole('SUPERADMIN')";

    // ── Zone management ───────────────────────────────────────────────────────
    public static final String ZONE_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN')";
    public static final String ZONE_CREATE =
            "hasRole('SUPERADMIN')";
    public static final String ZONE_UPDATE =
            "hasRole('SUPERADMIN')";
    public static final String ZONE_ASSIGN_SCHOOL =
            "hasRole('SUPERADMIN')";
    public static final String ZONE_ASSIGN_ADMIN =
            "hasRole('SUPERADMIN')";

    // ── Student management ────────────────────────────────────────────────────
    public static final String STUDENT_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT','TEACHER','VIEWER')";
    public static final String STUDENT_CREATE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String STUDENT_UPDATE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String STUDENT_DELETE =
            "hasRole('SUPERADMIN')";
    public static final String STUDENT_IMPORT =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String STUDENT_EXPORT =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT')";

    // ── Attendance ────────────────────────────────────────────────────────────
    public static final String ATTENDANCE_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT','TEACHER','VIEWER')";
    public static final String ATTENDANCE_MANAGE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS','TEACHER')";

    // ── Fee management — OPERATIONS excluded from configure/collect ───────────
    public static final String FEE_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT','VIEWER')";
    public static final String FEE_CONFIGURE =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";
    public static final String FEE_COLLECT =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";
    public static final String FEE_UPDATE =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";
    public static final String FEE_EXPORT =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT')";

    // ── Supply / catalog orders ───────────────────────────────────────────────
    public static final String ORDER_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT','VIEWER')";
    public static final String ORDER_CREATE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String ORDER_UPDATE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String ORDER_APPROVE =
            "hasRole('SUPERADMIN')";
    public static final String ORDER_FULFILL =
            "hasRole('SUPERADMIN')";

    // ── Firefighting requests ─────────────────────────────────────────────────
    public static final String FIREFIGHTING_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT','VIEWER')";
    public static final String FIREFIGHTING_CREATE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String FIREFIGHTING_UPDATE =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS')";
    public static final String FIREFIGHTING_APPROVE =
            "hasAnyRole('SUPERADMIN','ADMIN')";
    public static final String FIREFIGHTING_FULFILL =
            "hasRole('SUPERADMIN')";

    // ── Payments ──────────────────────────────────────────────────────────────
    public static final String PAYMENT_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT','VIEWER')";
    public static final String PAYMENT_CREATE =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";
    public static final String PAYMENT_REVERSE =
            "hasAnyRole('SUPERADMIN','ADMIN')";

    // ── Invoices ──────────────────────────────────────────────────────────────
    public static final String INVOICE_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT','VIEWER')";
    public static final String INVOICE_CREATE =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";
    public static final String INVOICE_CANCEL =
            "hasAnyRole('SUPERADMIN','ADMIN')";

    // ── Customers / parents ───────────────────────────────────────────────────
    public static final String CUSTOMER_READ =
            "hasAnyRole('SUPERADMIN','ADMIN','OPERATIONS','ACCOUNTANT')";
    public static final String CUSTOMER_CREATE =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";
    public static final String CUSTOMER_UPDATE =
            "hasAnyRole('SUPERADMIN','ADMIN','ACCOUNTANT')";

    // ── Reports ───────────────────────────────────────────────────────────────
    public static final String REPORT_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT','TEACHER','VIEWER')";
    public static final String REPORT_EXPORT =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT')";

    // ── Audit logs ────────────────────────────────────────────────────────────
    public static final String AUDIT_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','ACCOUNTANT')";

    // ── User management ───────────────────────────────────────────────────────
    public static final String USER_READ =
            "hasRole('SUPERADMIN')";
    public static final String USER_CREATE =
            "hasRole('SUPERADMIN')";
    public static final String USER_UPDATE =
            "hasRole('SUPERADMIN')";
    public static final String USER_DISABLE =
            "hasRole('SUPERADMIN')";
    public static final String USER_RESET_PASSWORD =
            "hasRole('SUPERADMIN')";
    public static final String USER_MANAGE =
            "hasRole('SUPERADMIN')";

    // ── Workflow ──────────────────────────────────────────────────────────────
    public static final String WORKFLOW_READ =
            "hasAnyRole('SUPERADMIN','ZONE_ADMIN','ADMIN','OPERATIONS','ACCOUNTANT')";
    public static final String WORKFLOW_APPROVE =
            "hasAnyRole('SUPERADMIN','ADMIN')";
    public static final String WORKFLOW_ADMIN =
            "hasRole('SUPERADMIN')";

    private PermissionConstants() {}
}
