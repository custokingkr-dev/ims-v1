-- Tenant isolation performance indexes on school-scoped tables
-- All wrapped in DO $$ to suppress errors when index already exists

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'students' AND indexname = 'idx_students_school_id') THEN
        CREATE INDEX idx_students_school_id ON students (school_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'school_sections' AND indexname = 'idx_school_sections_school_id') THEN
        CREATE INDEX idx_school_sections_school_id ON school_sections (school_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'staff_members' AND indexname = 'idx_staff_members_school_id') THEN
        CREATE INDEX idx_staff_members_school_id ON staff_members (school_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'annual_plan_items' AND indexname = 'idx_annual_plan_items_school_id') THEN
        CREATE INDEX idx_annual_plan_items_school_id ON annual_plan_items (school_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'app_users' AND indexname = 'idx_app_users_role_branch_id') THEN
        CREATE INDEX idx_app_users_role_branch_id ON app_users (role, branch_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'app_users' AND indexname = 'idx_app_users_zone_id') THEN
        CREATE INDEX idx_app_users_zone_id ON app_users (zone_id);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'firefighting_requests' AND indexname = 'idx_ff_requests_school_id_status') THEN
        CREATE INDEX idx_ff_requests_school_id_status ON firefighting_requests (school_id, status);
    END IF;
END $$;
