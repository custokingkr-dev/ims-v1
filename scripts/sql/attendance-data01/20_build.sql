\set ON_ERROR_STOP on

-- Single-shot by design. A failure rolls this transaction back completely and leaves phase FROZEN,
-- so the same file can be retried after the failure is corrected.
BEGIN;
SET LOCAL statement_timeout = '0';
SET LOCAL lock_timeout = '10s';

DO $data01$
DECLARE phase_now text;
BEGIN
    IF current_setting('app.data01_maintenance_approved', true) IS DISTINCT FROM 'DATA-01' THEN
        RAISE EXCEPTION 'DATA-01 maintenance approval marker is absent';
    END IF;
    SELECT phase INTO phase_now
      FROM attendance.attendance_student_records_data01_control
     WHERE singleton;
    IF phase_now IS DISTINCT FROM 'FROZEN' THEN
        RAISE EXCEPTION '20_build.sql requires FROZEN phase, found %', phase_now;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                    WHERE tgrelid = 'attendance.attendance_student_records'::regclass
                      AND tgname = 'data01_source_write_freeze'
                      AND NOT tgisinternal) THEN
        RAISE EXCEPTION 'Source write-freeze trigger is absent';
    END IF;
    IF to_regclass('attendance.attendance_student_records_data01') IS NOT NULL
       OR to_regclass('attendance.attendance_student_record_identity') IS NOT NULL THEN
        RAISE EXCEPTION 'Build targets already exist; inspect unexpected out-of-band objects';
    END IF;
END
$data01$;

CREATE TABLE attendance.attendance_student_record_identity (
    id varchar(255) PRIMARY KEY,
    attendance_daily_id varchar(255) NOT NULL,
    student_id bigint NOT NULL,
    attendance_date date NOT NULL,
    academic_year_id varchar(255) NOT NULL,
    CONSTRAINT attendance_student_record_identity_daily_student_uk
        UNIQUE (attendance_daily_id, student_id),
    CONSTRAINT attendance_student_record_identity_student_date_year_uk
        UNIQUE (student_id, attendance_date, academic_year_id)
);

INSERT INTO attendance.attendance_student_record_identity(
    id, attendance_daily_id, student_id, attendance_date, academic_year_id
)
SELECT id, attendance_daily_id, student_id, attendance_date, academic_year_id
FROM attendance.attendance_student_records;

CREATE TABLE attendance.attendance_student_records_data01 (
    id varchar(255) NOT NULL,
    attendance_daily_id varchar(255) NOT NULL,
    student_id bigint NOT NULL,
    school_id bigint NOT NULL,
    attendance_date date NOT NULL,
    academic_year_id varchar(255) NOT NULL,
    class_id varchar(255) NOT NULL,
    section_id varchar(255) NOT NULL,
    status varchar(20) NOT NULL,
    remarks text,
    recorded_by bigint,
    recorded_at timestamptz,
    updated_by bigint,
    updated_at timestamptz
) PARTITION BY RANGE (attendance_date);

DO $data01$
DECLARE
    first_year integer;
    last_year integer;
    partition_year integer;
BEGIN
    SELECT coalesce(extract(year FROM min(attendance_date))::integer,
                    extract(year FROM current_date)::integer),
           greatest(coalesce(extract(year FROM max(attendance_date))::integer,
                             extract(year FROM current_date)::integer),
                    extract(year FROM current_date)::integer) + 2
      INTO first_year, last_year
      FROM attendance.attendance_student_records;

    FOR partition_year IN first_year..last_year LOOP
        EXECUTE format(
            'CREATE TABLE attendance.attendance_student_records_y%s PARTITION OF attendance.attendance_student_records_data01 FOR VALUES FROM (%L) TO (%L)',
            partition_year,
            make_date(partition_year, 1, 1),
            make_date(partition_year + 1, 1, 1));
    END LOOP;
END
$data01$;

CREATE TABLE attendance.attendance_student_records_default
    PARTITION OF attendance.attendance_student_records_data01 DEFAULT;

INSERT INTO attendance.attendance_student_records_data01
SELECT * FROM attendance.attendance_student_records;

ALTER TABLE attendance.attendance_student_records_data01
    ADD CONSTRAINT attendance_student_records_data01_pk
        PRIMARY KEY (id, attendance_date),
    ADD CONSTRAINT attendance_student_records_data01_daily_fk
        FOREIGN KEY (attendance_daily_id)
        REFERENCES attendance.attendance_daily(id) ON DELETE CASCADE,
    ADD CONSTRAINT attendance_student_records_data01_daily_student_uk
        UNIQUE (attendance_daily_id, student_id, attendance_date),
    ADD CONSTRAINT attendance_student_records_data01_student_date_year_uk
        UNIQUE (student_id, attendance_date, academic_year_id),
    ADD CONSTRAINT attendance_student_records_data01_status_check
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

CREATE INDEX idx_asr_data01_school_date
    ON attendance.attendance_student_records_data01(school_id, attendance_date);
CREATE INDEX idx_asr_data01_section_date
    ON attendance.attendance_student_records_data01(section_id, attendance_date);
CREATE INDEX idx_asr_data01_daily
    ON attendance.attendance_student_records_data01(attendance_daily_id);
CREATE INDEX idx_asr_data01_student_date
    ON attendance.attendance_student_records_data01(student_id, attendance_date);
CREATE INDEX idx_asr_data01_academic_year
    ON attendance.attendance_student_records_data01(academic_year_id, attendance_date);

ALTER TABLE attendance.attendance_student_records_data01 ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attendance.attendance_student_records_data01
  USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
         OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

-- Preserve explicit grants on the parent. PostgreSQL checks parent permissions when the parent is
-- addressed, so child partitions do not need separately broadened grants.
DO $data01$
DECLARE grant_row record;
BEGIN
    FOR grant_row IN
        SELECT grantee, string_agg(privilege_type, ', ' ORDER BY privilege_type) AS privileges
          FROM information_schema.table_privileges
         WHERE table_schema = 'attendance'
           AND table_name = 'attendance_student_records'
           AND grantee <> current_user
         GROUP BY grantee
    LOOP
        EXECUTE format('GRANT %s ON attendance.attendance_student_records_data01 TO %s',
            grant_row.privileges,
            CASE WHEN grant_row.grantee = 'PUBLIC' THEN 'PUBLIC'
                 ELSE quote_ident(grant_row.grantee) END);
    END LOOP;
END
$data01$;

CREATE FUNCTION attendance.enforce_student_record_identity_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
DECLARE
    accepted_id varchar(255);
    existing_identity attendance.attendance_student_record_identity%ROWTYPE;
BEGIN
    SELECT * INTO existing_identity
      FROM attendance.attendance_student_record_identity
     WHERE id = NEW.id;
    IF FOUND THEN
        IF existing_identity.attendance_daily_id IS DISTINCT FROM NEW.attendance_daily_id
           OR existing_identity.student_id IS DISTINCT FROM NEW.student_id
           OR existing_identity.attendance_date IS DISTINCT FROM NEW.attendance_date
           OR existing_identity.academic_year_id IS DISTINCT FROM NEW.academic_year_id THEN
            RAISE EXCEPTION USING ERRCODE = '23505',
                MESSAGE = 'duplicate attendance student record identity';
        END IF;
        RETURN NEW;
    END IF;

    -- AttendanceReadRepository supplies a fresh UUID on every save and uses this exact key as its
    -- ON CONFLICT target. Leave the existing registry row untouched; PostgreSQL will route the
    -- candidate to DO UPDATE, whose update trigger maintains the existing identity row.
    SELECT * INTO existing_identity
      FROM attendance.attendance_student_record_identity
     WHERE student_id = NEW.student_id
       AND attendance_date = NEW.attendance_date
       AND academic_year_id = NEW.academic_year_id;
    IF FOUND THEN
        IF NOT EXISTS (
            SELECT 1 FROM attendance.attendance_student_records existing
             WHERE existing.id = existing_identity.id
               AND existing.attendance_date = existing_identity.attendance_date
        ) THEN
            RAISE EXCEPTION USING ERRCODE = '23503',
                MESSAGE = 'attendance identity registry is inconsistent';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO existing_identity
      FROM attendance.attendance_student_record_identity
     WHERE attendance_daily_id = NEW.attendance_daily_id
       AND student_id = NEW.student_id;
    IF FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23505',
            MESSAGE = 'duplicate attendance daily/student identity';
    END IF;

    INSERT INTO attendance.attendance_student_record_identity(
        id, attendance_daily_id, student_id, attendance_date, academic_year_id
    ) VALUES (
        NEW.id, NEW.attendance_daily_id, NEW.student_id, NEW.attendance_date, NEW.academic_year_id
    )
    ON CONFLICT (id) DO UPDATE
       SET attendance_daily_id = EXCLUDED.attendance_daily_id
     WHERE attendance.attendance_student_record_identity.attendance_daily_id = EXCLUDED.attendance_daily_id
       AND attendance.attendance_student_record_identity.student_id = EXCLUDED.student_id
       AND attendance.attendance_student_record_identity.attendance_date = EXCLUDED.attendance_date
       AND attendance.attendance_student_record_identity.academic_year_id = EXCLUDED.academic_year_id
    RETURNING id INTO accepted_id;
    IF accepted_id IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '23505',
            MESSAGE = 'duplicate attendance student record identity';
    END IF;
    RETURN NEW;
END
$function$;

CREATE FUNCTION attendance.enforce_student_record_identity_update()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.attendance_date IS DISTINCT FROM OLD.attendance_date THEN
        RAISE EXCEPTION USING ERRCODE = '55000',
            MESSAGE = 'DATA-01 partition identity columns id and attendance_date are immutable';
    END IF;
    UPDATE attendance.attendance_student_record_identity
       SET attendance_daily_id = NEW.attendance_daily_id,
           student_id = NEW.student_id,
           attendance_date = NEW.attendance_date,
           academic_year_id = NEW.academic_year_id
     WHERE id = OLD.id
       AND attendance_daily_id = OLD.attendance_daily_id
       AND student_id = OLD.student_id
       AND attendance_date = OLD.attendance_date
       AND academic_year_id = OLD.academic_year_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503',
            MESSAGE = 'attendance identity registry is inconsistent';
    END IF;
    RETURN NEW;
END
$function$;

CREATE FUNCTION attendance.enforce_student_record_identity_delete()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $function$
BEGIN
    DELETE FROM attendance.attendance_student_record_identity WHERE id = OLD.id;
    IF NOT FOUND THEN
        RAISE EXCEPTION USING ERRCODE = '23503',
            MESSAGE = 'attendance identity registry is inconsistent';
    END IF;
    RETURN OLD;
END
$function$;

REVOKE ALL ON FUNCTION attendance.enforce_student_record_identity_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION attendance.enforce_student_record_identity_update() FROM PUBLIC;
REVOKE ALL ON FUNCTION attendance.enforce_student_record_identity_delete() FROM PUBLIC;

UPDATE attendance.attendance_student_records_data01_control
   SET phase = 'BUILT'
 WHERE singleton;

COMMIT;

SELECT 'IMS_DATA01_BUILD|' || json_build_object(
    'phase', phase,
    'sourceRows', source_rows_at_freeze,
    'targetRows', (SELECT count(*) FROM attendance.attendance_student_records_data01),
    'registryRows', (SELECT count(*) FROM attendance.attendance_student_record_identity),
    'partitions', (SELECT count(*) - 1 FROM pg_partition_tree(
        'attendance.attendance_student_records_data01'::regclass))
)::text
FROM attendance.attendance_student_records_data01_control
WHERE singleton;
