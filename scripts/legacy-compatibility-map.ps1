function Get-LegacyCompatibilityMappings {
    return @(
        @{ Domain = "identity"; PublicTable = "app_users"; TargetSchema = "identity"; TargetTable = "app_users" },
        @{ Domain = "identity"; PublicTable = "auth_sessions"; TargetSchema = "identity"; TargetTable = "auth_sessions" },
        @{ Domain = "identity"; PublicTable = "roles"; TargetSchema = "identity"; TargetTable = "roles" },
        @{ Domain = "identity"; PublicTable = "permissions"; TargetSchema = "identity"; TargetTable = "permissions" },
        @{ Domain = "identity"; PublicTable = "role_permissions"; TargetSchema = "identity"; TargetTable = "role_permissions" },
        @{ Domain = "identity"; PublicTable = "user_role_assignments"; TargetSchema = "identity"; TargetTable = "user_role_assignments" },
        @{ Domain = "identity"; PublicTable = "rbac_audit_log"; TargetSchema = "identity"; TargetTable = "rbac_audit_log" },

        @{ Domain = "tenant-school"; PublicTable = "academic_years"; TargetSchema = "tenant_school"; TargetTable = "academic_years" },
        @{ Domain = "tenant-school"; PublicTable = "schools"; TargetSchema = "tenant_school"; TargetTable = "schools" },
        @{ Domain = "tenant-school"; PublicTable = "school_classes"; TargetSchema = "tenant_school"; TargetTable = "school_classes" },
        @{ Domain = "tenant-school"; PublicTable = "school_sections"; TargetSchema = "tenant_school"; TargetTable = "school_sections" },
        @{ Domain = "tenant-school"; PublicTable = "staff_members"; TargetSchema = "tenant_school"; TargetTable = "staff_members" },
        @{ Domain = "tenant-school"; PublicTable = "zones"; TargetSchema = "tenant_school"; TargetTable = "zones" },
        @{ Domain = "tenant-school"; PublicTable = "zone_school_mappings"; TargetSchema = "tenant_school"; TargetTable = "zone_school_mappings" },
        @{ Domain = "tenant-school"; PublicTable = "zone_admin_assignments"; TargetSchema = "tenant_school"; TargetTable = "zone_admin_assignments" },
        @{ Domain = "tenant-school"; PublicTable = "school_module_entitlements"; TargetSchema = "tenant_school"; TargetTable = "school_module_entitlements" },

        @{ Domain = "student"; PublicTable = "students"; TargetSchema = "student"; TargetTable = "students" },
        @{ Domain = "student"; PublicTable = "import_batches"; TargetSchema = "student"; TargetTable = "import_batches" },
        @{ Domain = "student"; PublicTable = "import_rows"; TargetSchema = "student"; TargetTable = "import_rows" },
        @{ Domain = "student"; PublicTable = "student_review_campaigns"; TargetSchema = "student"; TargetTable = "student_review_campaigns" },
        @{ Domain = "student"; PublicTable = "student_review_items"; TargetSchema = "student"; TargetTable = "student_review_items" },

        @{ Domain = "attendance"; PublicTable = "attendance_daily"; TargetSchema = "attendance"; TargetTable = "attendance_daily" },
        @{ Domain = "attendance"; PublicTable = "attendance_student_records"; TargetSchema = "attendance"; TargetTable = "attendance_student_records" },

        @{ Domain = "fee"; PublicTable = "fee_bands"; TargetSchema = "fee"; TargetTable = "fee_bands" },
        @{ Domain = "fee"; PublicTable = "fee_items"; TargetSchema = "fee"; TargetTable = "fee_items" },
        @{ Domain = "fee"; PublicTable = "fee_assignments"; TargetSchema = "fee"; TargetTable = "fee_assignments" },
        @{ Domain = "fee"; PublicTable = "payment_records"; TargetSchema = "fee"; TargetTable = "payment_records" },

        @{ Domain = "catalog"; PublicTable = "catalog_items"; TargetSchema = "catalog"; TargetTable = "catalog_items" },
        @{ Domain = "catalog"; PublicTable = "supply_orders"; TargetSchema = "catalog"; TargetTable = "supply_orders" },
        @{ Domain = "catalog"; PublicTable = "annual_plan_entries"; TargetSchema = "catalog"; TargetTable = "annual_plan_entries" },
        @{ Domain = "catalog"; PublicTable = "catalog_orders"; TargetSchema = "catalog"; TargetTable = "catalog_orders" },
        @{ Domain = "catalog"; PublicTable = "annual_plan_items"; TargetSchema = "catalog"; TargetTable = "annual_plan_items" },

        @{ Domain = "workflow"; PublicTable = "workflow_definitions"; TargetSchema = "workflow"; TargetTable = "workflow_definitions" },
        @{ Domain = "workflow"; PublicTable = "workflow_steps"; TargetSchema = "workflow"; TargetTable = "workflow_steps" },
        @{ Domain = "workflow"; PublicTable = "workflow_instances"; TargetSchema = "workflow"; TargetTable = "workflow_instances" },
        @{ Domain = "workflow"; PublicTable = "workflow_actions"; TargetSchema = "workflow"; TargetTable = "workflow_actions" },

        @{ Domain = "firefighting"; PublicTable = "firefighting_requests"; TargetSchema = "firefighting"; TargetTable = "firefighting_requests" },
        @{ Domain = "firefighting"; PublicTable = "ff_quotations"; TargetSchema = "firefighting"; TargetTable = "ff_quotations" },

        @{ Domain = "reporting"; PublicTable = "command_center_actions"; TargetSchema = "reporting"; TargetTable = "command_center_actions" },
        @{ Domain = "reporting"; PublicTable = "command_center_feed"; TargetSchema = "reporting"; TargetTable = "command_center_feed" },
        @{ Domain = "reporting"; PublicTable = "academic_events"; TargetSchema = "reporting"; TargetTable = "academic_events" },
        @{ Domain = "reporting"; PublicTable = "event_student_contributions"; TargetSchema = "reporting"; TargetTable = "event_student_contributions" },

        @{ Domain = "notification"; PublicTable = "notification_broadcasts"; TargetSchema = "notification"; TargetTable = "notification_broadcasts" },
        @{ Domain = "notification"; PublicTable = "notification_delivery_logs"; TargetSchema = "notification"; TargetTable = "notification_delivery_logs" },
        @{ Domain = "notification"; PublicTable = "notification_logs"; TargetSchema = "notification"; TargetTable = "notification_logs" },

        @{ Domain = "billing"; PublicTable = "superadmin_invoices"; TargetSchema = "billing"; TargetTable = "superadmin_invoices" },
        @{ Domain = "billing"; PublicTable = "superadmin_order_seq"; TargetSchema = "billing"; TargetTable = "superadmin_order_seq" },

        @{ Domain = "audit"; PublicTable = "audit_log"; TargetSchema = "audit"; TargetTable = "audit_events" }
    )
}
