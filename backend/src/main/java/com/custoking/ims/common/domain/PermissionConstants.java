package com.custoking.ims.common.domain;

public final class PermissionConstants {

    // Generic access levels
    public static final String SUPERADMIN_ACCESS = "hasRole('SUPERADMIN')";
    public static final String ADMIN_ACCESS = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ADMIN')";
    public static final String WORKSPACE_ACCESS = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ADMIN','STAFF','ACCOUNTANT','APPROVER','VIEWER')";
    public static final String WRITE_ACCESS = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ADMIN')";

    // School Management
    public static final String SCHOOL_CREATE = "hasRole('SUPERADMIN')";
    public static final String SCHOOL_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN')";
    public static final String SCHOOL_UPDATE = "hasRole('SUPERADMIN')";
    public static final String SCHOOL_ADMIN_MANAGE = "hasRole('SUPERADMIN')";

    // Student Management
    public static final String STUDENT_CREATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN')";
    public static final String STUDENT_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','STAFF','ACCOUNTANT','APPROVER')";
    public static final String STUDENT_UPDATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','STAFF')";
    public static final String STUDENT_DELETE = "hasRole('SUPERADMIN')";

    // Fee Management
    public static final String FEE_STRUCTURE_MANAGE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT')";
    public static final String FEE_COLLECTION = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT')";
    public static final String FEE_REPORTS = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT')";

    // Attendance
    public static final String ATTENDANCE_MANAGE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','STAFF','APPROVER')";
    public static final String ATTENDANCE_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','STAFF','ACCOUNTANT','APPROVER')";

    // Catalog/Supply Orders
    public static final String ORDER_CREATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN')";
    public static final String ORDER_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";
    public static final String ORDER_UPDATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";
    public static final String ORDER_APPROVE = "hasAnyRole('SUPERADMIN','APPROVER')";

    // Firefighting Requests
    public static final String FIREFIGHTING_CREATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN')";
    public static final String FIREFIGHTING_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";
    public static final String FIREFIGHTING_UPDATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";
    public static final String FIREFIGHTING_APPROVE = "hasAnyRole('SUPERADMIN','APPROVER')";
    public static final String FIREFIGHTING_FULFILL = "hasRole('SUPERADMIN')";

    // Payments and Invoices
    public static final String PAYMENT_CREATE = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT')";
    public static final String PAYMENT_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";
    public static final String INVOICE_CREATE = "hasAnyRole('SUPERADMIN','ACCOUNTANT')";
    public static final String INVOICE_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";

    // Audit
    public static final String AUDIT_READ = "hasAnyRole('SUPERADMIN','SCHOOL_ADMIN','ACCOUNTANT','APPROVER')";

    // User Management
    public static final String USER_MANAGE = "hasRole('SUPERADMIN')";

    private PermissionConstants() {
        // Utility class
    }
}