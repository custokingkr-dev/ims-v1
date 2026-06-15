/**
 * Frontend permission-code constants.
 *
 * These strings mirror the permission codes in the backend
 * {@code PermissionConstants.java} and the {@code permissions} table.
 *
 * RULES:
 * - Frontend permission checks are UI-visibility guards only.
 * - The backend enforces all authorization; these constants just hide
 *   inaccessible UI elements — never assume frontend checks are a security boundary.
 * - Do NOT use role strings like "ADMIN" for authorization. Use these codes.
 * - When adding a new permission, add it here AND in {@code PermissionConstants.java}
 *   AND seed it in the next Flyway migration.
 */

// ── Platform access ────────────────────────────────────────────────────────
export const PLATFORM_ADMIN       = 'platform:admin';
export const WORKSPACE_ACCESS     = 'workspace:access';

// ── School management ──────────────────────────────────────────────────────
export const SCHOOL_READ          = 'school:read';
export const SCHOOL_CREATE        = 'school:create';
export const SCHOOL_UPDATE        = 'school:update';
export const SCHOOL_ADMIN_MANAGE  = 'school:admin_manage';
export const SCHOOL_SUSPEND       = 'school:suspend';

// ── Zone management ────────────────────────────────────────────────────────
export const ZONE_READ            = 'zone:read';
export const ZONE_MANAGE          = 'zone:manage';
export const ZONE_ASSIGN_SCHOOL   = 'zone:assign_school';

// ── Student management ─────────────────────────────────────────────────────
export const STUDENT_READ         = 'student:read';
export const STUDENT_CREATE       = 'student:create';
export const STUDENT_UPDATE       = 'student:update';
export const STUDENT_DELETE       = 'student:delete';
export const STUDENT_IMPORT       = 'student:import';

// ── Attendance ─────────────────────────────────────────────────────────────
export const ATTENDANCE_READ      = 'attendance:read';
export const ATTENDANCE_MANAGE    = 'attendance:manage';

// ── Fee management ─────────────────────────────────────────────────────────
export const FEE_READ             = 'fee:read';
export const FEE_COLLECT          = 'fee:collect';
export const FEE_REVERSE          = 'fee:reverse';
export const FEE_ASSIGN           = 'fee:assign';
export const FEE_STRUCTURE_READ   = 'fee_structure:read';
export const FEE_STRUCTURE_MANAGE = 'fee_structure:manage';

// ── Supply / catalog orders ────────────────────────────────────────────────
export const ORDER_READ           = 'order:read';
export const ORDER_CREATE         = 'order:create';
export const ORDER_UPDATE         = 'order:update';
export const ORDER_APPROVE        = 'order:approve';
export const ORDER_FULFILL        = 'order:fulfill';
export const ORDER_REJECT         = 'order:reject';

// ── Firefighting requests ──────────────────────────────────────────────────
export const FIREFIGHTING_READ    = 'firefighting:read';
export const FIREFIGHTING_CREATE  = 'firefighting:create';
export const FIREFIGHTING_UPDATE  = 'firefighting:update';
export const FIREFIGHTING_APPROVE = 'firefighting:approve';
export const FIREFIGHTING_FULFILL = 'firefighting:fulfill';

// ── Payments ───────────────────────────────────────────────────────────────
export const PAYMENT_READ         = 'payment:read';
export const PAYMENT_CREATE       = 'payment:create';
export const PAYMENT_RECONCILE    = 'payment:reconcile';

// ── Invoices ───────────────────────────────────────────────────────────────
export const INVOICE_READ         = 'invoice:read';
export const INVOICE_CREATE       = 'invoice:create';
export const INVOICE_CANCEL       = 'invoice:cancel';

// ── Customers ─────────────────────────────────────────────────────────────
export const CUSTOMER_READ        = 'customer:read';
export const CUSTOMER_CREATE      = 'customer:create';

// ── Reports ────────────────────────────────────────────────────────────────
export const REPORT_READ          = 'report:read';

// ── Audit logs ─────────────────────────────────────────────────────────────
export const AUDIT_READ           = 'audit:read';

// ── User management ────────────────────────────────────────────────────────
export const USER_MANAGE          = 'user:manage';
export const USER_READ            = 'user:read';
export const USER_CREATE          = 'user:create';
export const USER_UPDATE          = 'user:update';
export const USER_DISABLE         = 'user:disable';
export const USER_RESET_PASSWORD  = 'user:reset_password';

// ── Timetable ─────────────────────────────────────────────────────────────
export const TIMETABLE_READ       = 'timetable:read';
export const TIMETABLE_MANAGE     = 'timetable:manage';

// ── Staff ─────────────────────────────────────────────────────────────────
export const STAFF_READ           = 'staff:read';
export const STAFF_MANAGE         = 'staff:manage';

// ── Annual plan ────────────────────────────────────────────────────────────
export const PLAN_READ            = 'plan:read';
export const PLAN_MANAGE          = 'plan:manage';

// ── Workflow ───────────────────────────────────────────────────────────────
export const WORKFLOW_READ        = 'workflow:read';
export const WORKFLOW_ACT         = 'workflow:act';

// ── RBAC management ────────────────────────────────────────────────────────
export const ROLE_READ            = 'role:read';
export const ROLE_CREATE          = 'role:create';
export const ROLE_UPDATE          = 'role:update';
export const ROLE_ASSIGN          = 'role:assign';
export const ROLE_REVOKE          = 'role:revoke';
export const PERMISSION_READ      = 'permission:read';

// ── Notifications ─────────────────────────────────────────────────────────
export const NOTIFICATION_READ    = 'notification:read';
export const NOTIFICATION_SEND    = 'notification:send';
