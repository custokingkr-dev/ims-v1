package com.custoking.ims.common.domain;

/**
 * Centralised Spring Security SpEL expressions for @PreAuthorize.
 * All expressions delegate to RbacService which checks the RBAC tables
 * (roles → role_permissions → permissions). Role names are NOT hard-coded here.
 *
 * Permission codes map to rows in the "permissions" table seeded by V112/V118.
 * To grant a new role access to an endpoint, update role_permissions in the DB
 * — no code change required.
 */
public final class PermissionConstants {

    // ── Broad access tiers ────────────────────────────────────────────────────
    /** Any request that must come from a platform-level administrator. */
    public static final String SUPERADMIN_ACCESS =
            "@rbacService.hasPermission(authentication, 'platform:admin')";

    /** SUPERADMIN or ZONE_ADMIN — zone-scoped administrative access. */
    public static final String ZONE_OR_SUPER =
            "@rbacService.hasPermission(authentication, 'zone:read')";

    /** SUPERADMIN or ADMIN — school-level administrative access. */
    public static final String ADMIN_ACCESS =
            "@rbacService.hasPermission(authentication, 'school:admin_manage')";

    /** Any user with an active role that grants workspace access. */
    public static final String WORKSPACE_ACCESS =
            "@rbacService.hasPermission(authentication, 'workspace:access')";

    // ── School management ─────────────────────────────────────────────────────
    public static final String SCHOOL_READ =
            "@rbacService.hasPermission(authentication, 'school:read')";
    public static final String SCHOOL_CREATE =
            "@rbacService.hasPermission(authentication, 'school:create')";
    public static final String SCHOOL_UPDATE =
            "@rbacService.hasPermission(authentication, 'school:update')";
    public static final String SCHOOL_MANAGE_ADMIN =
            "@rbacService.hasPermission(authentication, 'school:admin_manage')";
    public static final String SCHOOL_MANAGE_OPERATIONS =
            "@rbacService.hasPermission(authentication, 'school:admin_manage')";

    // ── Zone management ───────────────────────────────────────────────────────
    public static final String ZONE_READ =
            "@rbacService.hasPermission(authentication, 'zone:read')";
    public static final String ZONE_CREATE =
            "@rbacService.hasPermission(authentication, 'zone:manage')";
    public static final String ZONE_UPDATE =
            "@rbacService.hasPermission(authentication, 'zone:manage')";
    public static final String ZONE_ASSIGN_SCHOOL =
            "@rbacService.hasPermission(authentication, 'zone:assign_school')";
    public static final String ZONE_ASSIGN_ADMIN =
            "@rbacService.hasPermission(authentication, 'zone:manage')";

    // ── Student management ────────────────────────────────────────────────────
    public static final String STUDENT_READ =
            "@rbacService.hasPermission(authentication, 'student:read')";
    public static final String STUDENT_CREATE =
            "@rbacService.hasPermission(authentication, 'student:create')";
    public static final String STUDENT_UPDATE =
            "@rbacService.hasPermission(authentication, 'student:update')";
    public static final String STUDENT_DELETE =
            "@rbacService.hasPermission(authentication, 'student:delete')";
    public static final String STUDENT_IMPORT =
            "@rbacService.hasPermission(authentication, 'student:import')";
    public static final String STUDENT_EXPORT =
            "@rbacService.hasPermission(authentication, 'student:read')";

    // ── Attendance ────────────────────────────────────────────────────────────
    public static final String ATTENDANCE_READ =
            "@rbacService.hasPermission(authentication, 'attendance:read')";
    public static final String ATTENDANCE_MANAGE =
            "@rbacService.hasPermission(authentication, 'attendance:manage')";

    // ── Fee management ────────────────────────────────────────────────────────
    public static final String FEE_READ =
            "@rbacService.hasPermission(authentication, 'fee:read')";
    public static final String FEE_CONFIGURE =
            "@rbacService.hasPermission(authentication, 'fee_structure:manage')";
    public static final String FEE_COLLECT =
            "@rbacService.hasPermission(authentication, 'fee:collect')";
    public static final String FEE_UPDATE =
            "@rbacService.hasPermission(authentication, 'fee_structure:manage')";
    public static final String FEE_EXPORT =
            "@rbacService.hasPermission(authentication, 'fee:read')";

    // ── Supply / catalog orders ───────────────────────────────────────────────
    public static final String ORDER_READ =
            "@rbacService.hasPermission(authentication, 'order:read')";
    public static final String ORDER_CREATE =
            "@rbacService.hasPermission(authentication, 'order:create')";
    public static final String ORDER_UPDATE =
            "@rbacService.hasPermission(authentication, 'order:update')";
    public static final String ORDER_APPROVE =
            "@rbacService.hasPermission(authentication, 'order:approve')";
    public static final String ORDER_FULFILL =
            "@rbacService.hasPermission(authentication, 'order:fulfill')";

    // ── Firefighting requests ─────────────────────────────────────────────────
    public static final String FIREFIGHTING_READ =
            "@rbacService.hasPermission(authentication, 'firefighting:read')";
    public static final String FIREFIGHTING_CREATE =
            "@rbacService.hasPermission(authentication, 'firefighting:create')";
    public static final String FIREFIGHTING_UPDATE =
            "@rbacService.hasPermission(authentication, 'firefighting:update')";
    public static final String FIREFIGHTING_APPROVE =
            "@rbacService.hasPermission(authentication, 'firefighting:approve')";
    public static final String FIREFIGHTING_FULFILL =
            "@rbacService.hasPermission(authentication, 'firefighting:fulfill')";

    // ── Payments ──────────────────────────────────────────────────────────────
    public static final String PAYMENT_READ =
            "@rbacService.hasPermission(authentication, 'payment:read')";
    public static final String PAYMENT_CREATE =
            "@rbacService.hasPermission(authentication, 'payment:create')";
    public static final String PAYMENT_REVERSE =
            "@rbacService.hasPermission(authentication, 'fee:reverse')";

    // ── Invoices ──────────────────────────────────────────────────────────────
    public static final String INVOICE_READ =
            "@rbacService.hasPermission(authentication, 'invoice:read')";
    public static final String INVOICE_CREATE =
            "@rbacService.hasPermission(authentication, 'invoice:create')";
    public static final String INVOICE_CANCEL =
            "@rbacService.hasPermission(authentication, 'invoice:cancel')";

    // ── Customers / parents ───────────────────────────────────────────────────
    public static final String CUSTOMER_READ =
            "@rbacService.hasPermission(authentication, 'customer:read')";
    public static final String CUSTOMER_CREATE =
            "@rbacService.hasPermission(authentication, 'customer:create')";
    public static final String CUSTOMER_UPDATE =
            "@rbacService.hasPermission(authentication, 'customer:create')";

    // ── Reports ───────────────────────────────────────────────────────────────
    public static final String REPORT_READ =
            "@rbacService.hasPermission(authentication, 'report:read')";
    public static final String REPORT_EXPORT =
            "@rbacService.hasPermission(authentication, 'report:read')";

    // ── Audit logs ────────────────────────────────────────────────────────────
    public static final String AUDIT_READ =
            "@rbacService.hasPermission(authentication, 'audit:read')";

    // ── User management ───────────────────────────────────────────────────────
    public static final String USER_READ =
            "@rbacService.hasPermission(authentication, 'user:read')";
    public static final String USER_CREATE =
            "@rbacService.hasPermission(authentication, 'user:create')";
    public static final String USER_UPDATE =
            "@rbacService.hasPermission(authentication, 'user:update')";
    public static final String USER_DISABLE =
            "@rbacService.hasPermission(authentication, 'user:disable')";
    public static final String USER_RESET_PASSWORD =
            "@rbacService.hasPermission(authentication, 'user:reset_password')";
    public static final String USER_MANAGE =
            "@rbacService.hasPermission(authentication, 'user:read')";

    // ── Workflow ──────────────────────────────────────────────────────────────
    public static final String WORKFLOW_READ =
            "@rbacService.hasPermission(authentication, 'workflow:read')";
    public static final String WORKFLOW_APPROVE =
            "@rbacService.hasPermission(authentication, 'workflow:act')";
    public static final String WORKFLOW_ADMIN =
            "@rbacService.hasPermission(authentication, 'workflow:act')";

    // ── RBAC management ───────────────────────────────────────────────────────
    public static final String ROLE_READ =
            "@rbacService.hasPermission(authentication, 'role:read')";
    public static final String ROLE_CREATE =
            "@rbacService.hasPermission(authentication, 'role:create')";
    public static final String ROLE_UPDATE =
            "@rbacService.hasPermission(authentication, 'role:update')";
    public static final String ROLE_ASSIGN =
            "@rbacService.hasPermission(authentication, 'role:assign')";
    public static final String ROLE_REVOKE =
            "@rbacService.hasPermission(authentication, 'role:revoke')";
    public static final String ROLE_DISABLE =
            "@rbacService.hasPermission(authentication, 'role:disable')";
    public static final String PERMISSION_READ =
            "@rbacService.hasPermission(authentication, 'permission:read')";
    public static final String PERMISSION_ASSIGN =
            "@rbacService.hasPermission(authentication, 'permission:assign')";
    public static final String PERMISSION_REVOKE =
            "@rbacService.hasPermission(authentication, 'permission:revoke')";

    // ── Notifications ─────────────────────────────────────────────────────────
    public static final String NOTIFICATION_READ =
            "@rbacService.hasPermission(authentication, 'notification:read')";
    public static final String NOTIFICATION_SEND =
            "@rbacService.hasPermission(authentication, 'notification:send')";

    // ── System / platform operations ──────────────────────────────────────────
    public static final String SYSTEM_ACTUATOR =
            "@rbacService.hasPermission(authentication, 'system:actuator')";

    private PermissionConstants() {}
}
