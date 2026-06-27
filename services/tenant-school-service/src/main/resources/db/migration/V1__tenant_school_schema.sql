CREATE SCHEMA IF NOT EXISTS tenant_school;

CREATE SEQUENCE IF NOT EXISTS tenant_school.seq_schools START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS tenant_school.academic_years (
    id     VARCHAR(255) NOT NULL,
    label  VARCHAR(255),
    active BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS tenant_school.schools (
    id                       BIGINT       NOT NULL DEFAULT nextval('tenant_school.seq_schools'),
    name                     VARCHAR(255) NOT NULL,
    short_code               VARCHAR(255) NOT NULL,
    city                     VARCHAR(255),
    state                    VARCHAR(255),
    contact_email            VARCHAR(255),
    contact_phone            VARCHAR(255),
    active                   BOOLEAN      NOT NULL,
    configured_class_count   INTEGER,
    configured_section_count INTEGER,
    created_at               TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_school_short_code UNIQUE (short_code)
);

ALTER SEQUENCE tenant_school.seq_schools OWNED BY tenant_school.schools.id;

CREATE TABLE IF NOT EXISTS tenant_school.school_classes (
    id         VARCHAR(255) NOT NULL,
    name       VARCHAR(255),
    sort_order INTEGER      NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS tenant_school.school_sections (
    id              VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    teacher_name    VARCHAR(255),
    active          BOOLEAN      NOT NULL,
    school_class_id VARCHAR(255) NOT NULL,
    school_id       BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_school_section_class FOREIGN KEY (school_class_id) REFERENCES tenant_school.school_classes (id),
    CONSTRAINT fk_tenant_school_section_school FOREIGN KEY (school_id) REFERENCES tenant_school.schools (id)
);

CREATE TABLE IF NOT EXISTS tenant_school.staff_members (
    id             BIGSERIAL    NOT NULL,
    name           VARCHAR(255),
    designation    VARCHAR(255),
    department     VARCHAR(255),
    monthly_salary BIGINT       NOT NULL,
    payroll_status VARCHAR(255),
    school_id      BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT fk_tenant_school_staff_school FOREIGN KEY (school_id) REFERENCES tenant_school.schools (id)
);

CREATE TABLE IF NOT EXISTS tenant_school.zones (
    id          BIGSERIAL    NOT NULL,
    name        VARCHAR(255) NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    city        VARCHAR(255),
    state       VARCHAR(255),
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_zone_name UNIQUE (name),
    CONSTRAINT uk_tenant_zone_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS tenant_school.zone_school_mappings (
    id        BIGSERIAL   NOT NULL,
    zone_id   BIGINT      NOT NULL,
    school_id BIGINT      NOT NULL,
    active    BOOLEAN     NOT NULL DEFAULT true,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    added_by  BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_zone_school UNIQUE (zone_id, school_id),
    CONSTRAINT fk_tenant_zsm_zone FOREIGN KEY (zone_id) REFERENCES tenant_school.zones (id) ON DELETE CASCADE,
    CONSTRAINT fk_tenant_zsm_school FOREIGN KEY (school_id) REFERENCES tenant_school.schools (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tenant_school.zone_admin_assignments (
    id          BIGSERIAL   NOT NULL,
    zone_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT true,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_by BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_zone_admin UNIQUE (zone_id, user_id),
    CONSTRAINT fk_tenant_zaa_zone FOREIGN KEY (zone_id) REFERENCES tenant_school.zones (id) ON DELETE CASCADE,
    CONSTRAINT fk_tenant_zaa_user FOREIGN KEY (user_id) REFERENCES identity.app_users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tenant_school.school_module_entitlements (
    id          BIGSERIAL PRIMARY KEY,
    school_id   BIGINT NOT NULL REFERENCES tenant_school.schools(id) ON DELETE CASCADE,
    module_code VARCHAR(50) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    plan        VARCHAR(50),
    start_date  DATE,
    end_date    DATE,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  BIGINT,
    CONSTRAINT uk_tenant_school_module UNIQUE (school_id, module_code),
    CONSTRAINT chk_tenant_module_dates CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);

CREATE INDEX IF NOT EXISTS idx_tenant_school_sections_school_id ON tenant_school.school_sections (school_id);
CREATE INDEX IF NOT EXISTS idx_tenant_school_sections_class_id ON tenant_school.school_sections (school_class_id);
CREATE INDEX IF NOT EXISTS idx_tenant_staff_members_school_id ON tenant_school.staff_members (school_id);
CREATE INDEX IF NOT EXISTS idx_tenant_zone_school_mappings_zone_id ON tenant_school.zone_school_mappings (zone_id);
CREATE INDEX IF NOT EXISTS idx_tenant_zone_school_mappings_school_id ON tenant_school.zone_school_mappings (school_id);
CREATE INDEX IF NOT EXISTS idx_tenant_zone_admin_assignments_user_id ON tenant_school.zone_admin_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_tenant_zone_admin_assignments_zone_id ON tenant_school.zone_admin_assignments (zone_id);
CREATE INDEX IF NOT EXISTS idx_tenant_sme_school ON tenant_school.school_module_entitlements (school_id);
CREATE INDEX IF NOT EXISTS idx_tenant_sme_school_code ON tenant_school.school_module_entitlements (school_id, module_code) WHERE enabled = TRUE;

DO $$
BEGIN
    IF to_regclass('public.academic_years') IS NOT NULL THEN
    INSERT INTO tenant_school.academic_years (id, label, active)
    SELECT id, label, active FROM public.academic_years
    ON CONFLICT (id) DO UPDATE SET label = EXCLUDED.label, active = EXCLUDED.active;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.schools') IS NOT NULL THEN
    INSERT INTO tenant_school.schools (
        id, name, short_code, city, state, contact_email, contact_phone, active,
        configured_class_count, configured_section_count, created_at
    )
    SELECT id, name, short_code, city, state, contact_email, contact_phone, active,
           configured_class_count, configured_section_count, created_at
    FROM public.schools
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        short_code = EXCLUDED.short_code,
        city = EXCLUDED.city,
        state = EXCLUDED.state,
        contact_email = EXCLUDED.contact_email,
        contact_phone = EXCLUDED.contact_phone,
        active = EXCLUDED.active,
        configured_class_count = EXCLUDED.configured_class_count,
        configured_section_count = EXCLUDED.configured_section_count,
        created_at = EXCLUDED.created_at;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.school_classes') IS NOT NULL THEN
    INSERT INTO tenant_school.school_classes (id, name, sort_order)
    SELECT id, name, sort_order FROM public.school_classes
    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, sort_order = EXCLUDED.sort_order;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.school_sections') IS NOT NULL THEN
    INSERT INTO tenant_school.school_sections (id, name, teacher_name, active, school_class_id, school_id)
    SELECT id, name, teacher_name, active, school_class_id, school_id FROM public.school_sections
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        teacher_name = EXCLUDED.teacher_name,
        active = EXCLUDED.active,
        school_class_id = EXCLUDED.school_class_id,
        school_id = EXCLUDED.school_id;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.staff_members') IS NOT NULL THEN
    INSERT INTO tenant_school.staff_members (id, name, designation, department, monthly_salary, payroll_status, school_id)
    SELECT id, name, designation, department, monthly_salary, payroll_status, school_id FROM public.staff_members
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        designation = EXCLUDED.designation,
        department = EXCLUDED.department,
        monthly_salary = EXCLUDED.monthly_salary,
        payroll_status = EXCLUDED.payroll_status,
        school_id = EXCLUDED.school_id;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.zones') IS NOT NULL THEN
    INSERT INTO tenant_school.zones (id, name, code, city, state, description, active, created_at, updated_at, created_by)
    SELECT id, name, code, city, state, description, active, created_at, updated_at, created_by FROM public.zones
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        code = EXCLUDED.code,
        city = EXCLUDED.city,
        state = EXCLUDED.state,
        description = EXCLUDED.description,
        active = EXCLUDED.active,
        created_at = EXCLUDED.created_at,
        updated_at = EXCLUDED.updated_at,
        created_by = EXCLUDED.created_by;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.zone_school_mappings') IS NOT NULL THEN
    INSERT INTO tenant_school.zone_school_mappings (id, zone_id, school_id, active, added_at, added_by)
    SELECT id, zone_id, school_id, active, added_at, added_by FROM public.zone_school_mappings
    ON CONFLICT (id) DO UPDATE SET
        zone_id = EXCLUDED.zone_id,
        school_id = EXCLUDED.school_id,
        active = EXCLUDED.active,
        added_at = EXCLUDED.added_at,
        added_by = EXCLUDED.added_by;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.zone_admin_assignments') IS NOT NULL THEN
    INSERT INTO tenant_school.zone_admin_assignments (id, zone_id, user_id, active, assigned_at, assigned_by)
    SELECT id, zone_id, user_id, active, assigned_at, assigned_by FROM public.zone_admin_assignments
    ON CONFLICT (id) DO UPDATE SET
        zone_id = EXCLUDED.zone_id,
        user_id = EXCLUDED.user_id,
        active = EXCLUDED.active,
        assigned_at = EXCLUDED.assigned_at,
        assigned_by = EXCLUDED.assigned_by;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.school_module_entitlements') IS NOT NULL THEN
    INSERT INTO tenant_school.school_module_entitlements (
        id, school_id, module_code, enabled, plan, start_date, end_date, notes, created_at, updated_at, created_by
    )
    SELECT id, school_id, module_code, enabled, plan, start_date, end_date, notes, created_at, updated_at, created_by
    FROM public.school_module_entitlements
    ON CONFLICT (id) DO UPDATE SET
        school_id = EXCLUDED.school_id,
        module_code = EXCLUDED.module_code,
        enabled = EXCLUDED.enabled,
        plan = EXCLUDED.plan,
        start_date = EXCLUDED.start_date,
        end_date = EXCLUDED.end_date,
        notes = EXCLUDED.notes,
        created_at = EXCLUDED.created_at,
        updated_at = EXCLUDED.updated_at,
        created_by = EXCLUDED.created_by;
    END IF;
END $$;

SELECT setval('tenant_school.seq_schools', COALESCE((SELECT MAX(id) FROM tenant_school.schools), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.staff_members', 'id'), COALESCE((SELECT MAX(id) FROM tenant_school.staff_members), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.zones', 'id'), COALESCE((SELECT MAX(id) FROM tenant_school.zones), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.zone_school_mappings', 'id'), COALESCE((SELECT MAX(id) FROM tenant_school.zone_school_mappings), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.zone_admin_assignments', 'id'), COALESCE((SELECT MAX(id) FROM tenant_school.zone_admin_assignments), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('tenant_school.school_module_entitlements', 'id'), COALESCE((SELECT MAX(id) FROM tenant_school.school_module_entitlements), 0) + 1, false);

CREATE OR REPLACE FUNCTION tenant_school.sync_school_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.schools') IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.schools (
        id, name, short_code, city, state, contact_email, contact_phone, active,
        configured_class_count, configured_section_count, created_at
    ) VALUES (
        NEW.id, NEW.name, NEW.short_code, NEW.city, NEW.state, NEW.contact_email, NEW.contact_phone, NEW.active,
        NEW.configured_class_count, NEW.configured_section_count, NEW.created_at
    )
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        short_code = EXCLUDED.short_code,
        city = EXCLUDED.city,
        state = EXCLUDED.state,
        contact_email = EXCLUDED.contact_email,
        contact_phone = EXCLUDED.contact_phone,
        active = EXCLUDED.active,
        configured_class_count = EXCLUDED.configured_class_count,
        configured_section_count = EXCLUDED.configured_section_count,
        created_at = EXCLUDED.created_at;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_school.sync_school_section_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.school_sections') IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.school_sections (id, name, teacher_name, active, school_class_id, school_id)
    VALUES (NEW.id, NEW.name, NEW.teacher_name, NEW.active, NEW.school_class_id, NEW.school_id)
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        teacher_name = EXCLUDED.teacher_name,
        active = EXCLUDED.active,
        school_class_id = EXCLUDED.school_class_id,
        school_id = EXCLUDED.school_id;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_school.sync_staff_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.staff_members') IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.staff_members (id, name, designation, department, monthly_salary, payroll_status, school_id)
    VALUES (NEW.id, NEW.name, NEW.designation, NEW.department, NEW.monthly_salary, NEW.payroll_status, NEW.school_id)
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        designation = EXCLUDED.designation,
        department = EXCLUDED.department,
        monthly_salary = EXCLUDED.monthly_salary,
        payroll_status = EXCLUDED.payroll_status,
        school_id = EXCLUDED.school_id;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_school.sync_zone_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.zones') IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.zones (id, name, code, city, state, description, active, created_at, updated_at, created_by)
    VALUES (NEW.id, NEW.name, NEW.code, NEW.city, NEW.state, NEW.description, NEW.active, NEW.created_at, NEW.updated_at, NEW.created_by)
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        code = EXCLUDED.code,
        city = EXCLUDED.city,
        state = EXCLUDED.state,
        description = EXCLUDED.description,
        active = EXCLUDED.active,
        created_at = EXCLUDED.created_at,
        updated_at = EXCLUDED.updated_at,
        created_by = EXCLUDED.created_by;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_school.sync_zone_school_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.zone_school_mappings') IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.zone_school_mappings (id, zone_id, school_id, active, added_at, added_by)
    VALUES (NEW.id, NEW.zone_id, NEW.school_id, NEW.active, NEW.added_at, NEW.added_by)
    ON CONFLICT (id) DO UPDATE SET
        zone_id = EXCLUDED.zone_id,
        school_id = EXCLUDED.school_id,
        active = EXCLUDED.active,
        added_at = EXCLUDED.added_at,
        added_by = EXCLUDED.added_by;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_school.delete_zone_school_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.zone_school_mappings') IS NULL THEN
        RETURN OLD;
    END IF;

    DELETE FROM public.zone_school_mappings WHERE id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_school.sync_module_entitlement_shadow()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF to_regclass('public.school_module_entitlements') IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.school_module_entitlements (
        id, school_id, module_code, enabled, plan, start_date, end_date, notes, created_at, updated_at, created_by
    ) VALUES (
        NEW.id, NEW.school_id, NEW.module_code, NEW.enabled, NEW.plan, NEW.start_date, NEW.end_date, NEW.notes, NEW.created_at, NEW.updated_at, NEW.created_by
    )
    ON CONFLICT (id) DO UPDATE SET
        school_id = EXCLUDED.school_id,
        module_code = EXCLUDED.module_code,
        enabled = EXCLUDED.enabled,
        plan = EXCLUDED.plan,
        start_date = EXCLUDED.start_date,
        end_date = EXCLUDED.end_date,
        notes = EXCLUDED.notes,
        created_at = EXCLUDED.created_at,
        updated_at = EXCLUDED.updated_at,
        created_by = EXCLUDED.created_by;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_tenant_school_shadow ON tenant_school.schools;
CREATE TRIGGER trg_tenant_school_shadow
AFTER INSERT OR UPDATE ON tenant_school.schools
FOR EACH ROW EXECUTE FUNCTION tenant_school.sync_school_shadow();

DROP TRIGGER IF EXISTS trg_tenant_school_section_shadow ON tenant_school.school_sections;
CREATE TRIGGER trg_tenant_school_section_shadow
AFTER INSERT OR UPDATE ON tenant_school.school_sections
FOR EACH ROW EXECUTE FUNCTION tenant_school.sync_school_section_shadow();

DROP TRIGGER IF EXISTS trg_tenant_staff_shadow ON tenant_school.staff_members;
CREATE TRIGGER trg_tenant_staff_shadow
AFTER INSERT OR UPDATE ON tenant_school.staff_members
FOR EACH ROW EXECUTE FUNCTION tenant_school.sync_staff_shadow();

DROP TRIGGER IF EXISTS trg_tenant_zone_shadow ON tenant_school.zones;
CREATE TRIGGER trg_tenant_zone_shadow
AFTER INSERT OR UPDATE ON tenant_school.zones
FOR EACH ROW EXECUTE FUNCTION tenant_school.sync_zone_shadow();

DROP TRIGGER IF EXISTS trg_tenant_zone_school_shadow ON tenant_school.zone_school_mappings;
CREATE TRIGGER trg_tenant_zone_school_shadow
AFTER INSERT OR UPDATE ON tenant_school.zone_school_mappings
FOR EACH ROW EXECUTE FUNCTION tenant_school.sync_zone_school_shadow();

DROP TRIGGER IF EXISTS trg_tenant_zone_school_shadow_delete ON tenant_school.zone_school_mappings;
CREATE TRIGGER trg_tenant_zone_school_shadow_delete
AFTER DELETE ON tenant_school.zone_school_mappings
FOR EACH ROW EXECUTE FUNCTION tenant_school.delete_zone_school_shadow();

DROP TRIGGER IF EXISTS trg_tenant_module_entitlement_shadow ON tenant_school.school_module_entitlements;
CREATE TRIGGER trg_tenant_module_entitlement_shadow
AFTER INSERT OR UPDATE ON tenant_school.school_module_entitlements
FOR EACH ROW EXECUTE FUNCTION tenant_school.sync_module_entitlement_shadow();
