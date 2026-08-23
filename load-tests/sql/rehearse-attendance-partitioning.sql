\set ON_ERROR_STOP on
\if :{?rehearsal_rows}
\else
\set rehearsal_rows 1000000
\endif

\timing on
DROP SCHEMA IF EXISTS attendance_rehearsal CASCADE;
CREATE SCHEMA attendance_rehearsal;

CREATE TABLE attendance_rehearsal.attendance_daily (
    id VARCHAR(255) PRIMARY KEY,
    attendance_date DATE NOT NULL,
    school_id BIGINT NOT NULL
);

CREATE TABLE attendance_rehearsal.records_source (
    id VARCHAR(255) NOT NULL,
    attendance_daily_id VARCHAR(255) NOT NULL,
    student_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    remarks TEXT,
    recorded_by BIGINT,
    recorded_at TIMESTAMPTZ,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ
);

WITH daily_rows AS (
    SELECT d,
           DATE '2024-01-01' + ((d - 1) % 1096)::integer AS attendance_date
    FROM generate_series(1, CEIL(:rehearsal_rows::numeric / 1000)::integer) d
)
INSERT INTO attendance_rehearsal.attendance_daily(id, attendance_date, school_id)
SELECT 'daily-' || d, attendance_date, 900000000 + ((d - 1) % 100)
FROM daily_rows;

INSERT INTO attendance_rehearsal.records_source(
    id, attendance_daily_id, student_id, school_id, attendance_date, academic_year_id,
    class_id, section_id, status, recorded_by, recorded_at)
SELECT 'record-' || g,
       'daily-' || (((g - 1) / 1000) + 1),
       -- Student identifiers are globally unique in the application. Keep a stable 1,000-student
       -- cohort per synthetic school so repeated attendance dates exercise realistic uniqueness.
       (((((g - 1) / 1000) % 100) * 100000) + ((g - 1) % 1000) + 1)::bigint,
       900000000 + ((((g - 1) / 1000)) % 100),
       DATE '2024-01-01' + ((((g - 1) / 1000)) % 1096)::integer,
       CASE
         WHEN (DATE '2024-01-01' + ((((g - 1) / 1000)) % 1096)::integer) < DATE '2025-04-01'
           THEN '2024-25'
         WHEN (DATE '2024-01-01' + ((((g - 1) / 1000)) % 1096)::integer) < DATE '2026-04-01'
           THEN '2025-26'
         ELSE '2026-27'
       END,
       'class-' || ((((g - 1) / 1000)) % 12),
       'section-' || ((g - 1) / 1000),
       CASE (g % 4) WHEN 0 THEN 'ABSENT' WHEN 1 THEN 'PRESENT' WHEN 2 THEN 'LATE' ELSE 'LEAVE' END,
       1,
       now()
FROM generate_series(1, :rehearsal_rows) g;

-- Model the existing source only after the synthetic heap load. A real migration starts from an
-- already-constrained table; maintaining three B-trees row-by-row while generating test data adds
-- hours without exercising migration behavior.
ALTER TABLE attendance_rehearsal.records_source
    ADD CONSTRAINT records_source_pk PRIMARY KEY (id),
    ADD CONSTRAINT records_source_daily_fk FOREIGN KEY (attendance_daily_id)
        REFERENCES attendance_rehearsal.attendance_daily(id) ON DELETE CASCADE,
    ADD CONSTRAINT records_source_daily_student_uk UNIQUE (attendance_daily_id, student_id),
    ADD CONSTRAINT records_source_student_date_year_uk
        UNIQUE (student_id, attendance_date, academic_year_id),
    ADD CONSTRAINT records_source_status_check
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

-- Global identity registry preserves the two uniqueness guarantees that PostgreSQL cannot enforce
-- directly across range partitions unless attendance_date is part of each unique key.
CREATE TABLE attendance_rehearsal.record_identity_registry (
    id VARCHAR(255) NOT NULL,
    attendance_daily_id VARCHAR(255) NOT NULL,
    student_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL
);

INSERT INTO attendance_rehearsal.record_identity_registry(
    id, attendance_daily_id, student_id, attendance_date)
SELECT id, attendance_daily_id, student_id, attendance_date
FROM attendance_rehearsal.records_source;

ALTER TABLE attendance_rehearsal.record_identity_registry
    ADD CONSTRAINT record_identity_registry_pk PRIMARY KEY (id),
    ADD CONSTRAINT record_identity_daily_student_uk UNIQUE (attendance_daily_id, student_id);

CREATE TABLE attendance_rehearsal.records_partitioned (
    id VARCHAR(255) NOT NULL,
    attendance_daily_id VARCHAR(255) NOT NULL,
    student_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    remarks TEXT,
    recorded_by BIGINT,
    recorded_at TIMESTAMPTZ,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ
) PARTITION BY RANGE (attendance_date);

CREATE TABLE attendance_rehearsal.records_y2024 PARTITION OF attendance_rehearsal.records_partitioned
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE attendance_rehearsal.records_y2025 PARTITION OF attendance_rehearsal.records_partitioned
    FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE attendance_rehearsal.records_y2026 PARTITION OF attendance_rehearsal.records_partitioned
    FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE attendance_rehearsal.records_default PARTITION OF attendance_rehearsal.records_partitioned DEFAULT;

INSERT INTO attendance_rehearsal.records_partitioned
SELECT * FROM attendance_rehearsal.records_source;

-- Build and validate final semantics after the set-based copy, then install triggers for ongoing
-- writes. This is the production-grade ordering: historical rows do not pay per-row trigger/index
-- maintenance, while every row is still validated before cutover.
ALTER TABLE attendance_rehearsal.records_partitioned
    ADD CONSTRAINT records_partitioned_pk PRIMARY KEY (id, attendance_date),
    ADD CONSTRAINT records_partitioned_daily_fk FOREIGN KEY (attendance_daily_id)
        REFERENCES attendance_rehearsal.attendance_daily(id) ON DELETE CASCADE,
    ADD CONSTRAINT records_partitioned_daily_student_uk
        UNIQUE (attendance_daily_id, student_id, attendance_date),
    ADD CONSTRAINT records_partitioned_student_date_year_uk
        UNIQUE (student_id, attendance_date, academic_year_id),
    ADD CONSTRAINT records_partitioned_status_check
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

CREATE INDEX records_partitioned_school_date_idx
    ON attendance_rehearsal.records_partitioned(school_id, attendance_date);
CREATE INDEX records_partitioned_section_date_idx
    ON attendance_rehearsal.records_partitioned(section_id, attendance_date);
CREATE INDEX records_partitioned_daily_idx
    ON attendance_rehearsal.records_partitioned(attendance_daily_id);
CREATE INDEX records_partitioned_student_date_idx
    ON attendance_rehearsal.records_partitioned(student_id, attendance_date);
CREATE INDEX records_partitioned_year_date_idx
    ON attendance_rehearsal.records_partitioned(academic_year_id, attendance_date);

CREATE FUNCTION attendance_rehearsal.register_record_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO attendance_rehearsal.record_identity_registry(
        id, attendance_daily_id, student_id, attendance_date)
    VALUES (NEW.id, NEW.attendance_daily_id, NEW.student_id, NEW.attendance_date);
    RETURN NEW;
END;
$$;

CREATE FUNCTION attendance_rehearsal.unregister_record_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM attendance_rehearsal.record_identity_registry WHERE id = OLD.id;
    RETURN OLD;
END;
$$;

CREATE FUNCTION attendance_rehearsal.update_record_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE attendance_rehearsal.record_identity_registry
    SET id = NEW.id,
        attendance_daily_id = NEW.attendance_daily_id,
        student_id = NEW.student_id,
        attendance_date = NEW.attendance_date
    WHERE id = OLD.id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER records_partitioned_register_identity
    BEFORE INSERT ON attendance_rehearsal.records_partitioned
    FOR EACH ROW EXECUTE FUNCTION attendance_rehearsal.register_record_identity();
CREATE TRIGGER records_partitioned_unregister_identity
    AFTER DELETE ON attendance_rehearsal.records_partitioned
    FOR EACH ROW EXECUTE FUNCTION attendance_rehearsal.unregister_record_identity();
CREATE TRIGGER records_partitioned_update_identity
    BEFORE UPDATE OF id, attendance_daily_id, student_id, attendance_date
    ON attendance_rehearsal.records_partitioned
    FOR EACH ROW EXECUTE FUNCTION attendance_rehearsal.update_record_identity();

ALTER TABLE attendance_rehearsal.records_partitioned ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON attendance_rehearsal.records_partitioned
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
           OR current_setting('app.bypass_rls', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on');

ANALYZE attendance_rehearsal.records_partitioned;

DO $$
BEGIN
    BEGIN
        INSERT INTO attendance_rehearsal.records_partitioned
        SELECT id, attendance_daily_id, student_id + 10000000, school_id,
               attendance_date + 1, academic_year_id, class_id, section_id,
               status, remarks, recorded_by, recorded_at, updated_by, updated_at
        FROM attendance_rehearsal.records_source LIMIT 1;
        RAISE EXCEPTION 'global id uniqueness was not enforced';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    BEGIN
        UPDATE attendance_rehearsal.records_partitioned target
        SET attendance_daily_id = duplicate.attendance_daily_id,
            student_id = duplicate.student_id
        FROM attendance_rehearsal.records_source duplicate
        WHERE target.id = 'record-1' AND duplicate.id = 'record-2';
        RAISE EXCEPTION 'global daily/student uniqueness was not enforced on update';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO attendance_rehearsal.records_partitioned
        SELECT 'duplicate-daily-student', attendance_daily_id, student_id, school_id,
               attendance_date + 1, academic_year_id, class_id, section_id,
               status, remarks, recorded_by, recorded_at, updated_by, updated_at
        FROM attendance_rehearsal.records_source LIMIT 1;
        RAISE EXCEPTION 'global daily/student uniqueness was not enforced';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO attendance_rehearsal.records_partitioned(
            id, attendance_daily_id, student_id, school_id, attendance_date,
            academic_year_id, class_id, section_id, status)
        VALUES ('bad-fk', 'missing-daily', 99999999, 900000000, DATE '2026-08-01',
                '2026-27', 'class', 'section', 'PRESENT');
        RAISE EXCEPTION 'attendance_daily FK was not enforced';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;

    BEGIN
        INSERT INTO attendance_rehearsal.records_partitioned(
            id, attendance_daily_id, student_id, school_id, attendance_date,
            academic_year_id, class_id, section_id, status)
        SELECT 'bad-status', id, 99999998, school_id, attendance_date,
               '2026-27', 'class', 'section', 'INVALID'
        FROM attendance_rehearsal.attendance_daily LIMIT 1;
        RAISE EXCEPTION 'status check was not enforced';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END;
$$;

CREATE ROLE attendance_rehearsal_app NOLOGIN;
GRANT USAGE ON SCHEMA attendance_rehearsal TO attendance_rehearsal_app;
GRANT SELECT ON attendance_rehearsal.records_partitioned TO attendance_rehearsal_app;
SET ROLE attendance_rehearsal_app;
SELECT set_config('app.current_school_id', '900000000', false);
SELECT set_config('app.bypass_rls', 'off', false);
DO $$
DECLARE visible_schools bigint;
        visible_rows bigint;
BEGIN
    SELECT count(DISTINCT school_id) INTO visible_schools
    FROM attendance_rehearsal.records_partitioned;
    IF visible_schools <> 1 THEN
        RAISE EXCEPTION 'RLS exposed % schools instead of one', visible_schools;
    END IF;

    PERFORM set_config('app.current_school_id', '1', false);
    SELECT count(*) INTO visible_rows FROM attendance_rehearsal.records_partitioned;
    IF visible_rows <> 0 THEN
        RAISE EXCEPTION 'RLS exposed % rows to an unknown tenant', visible_rows;
    END IF;

    PERFORM set_config('app.bypass_rls', 'on', false);
    SELECT count(*) INTO visible_rows FROM attendance_rehearsal.records_partitioned;
    IF visible_rows = 0 THEN
        RAISE EXCEPTION 'RLS bypass did not expose rows to the controlled maintenance role';
    END IF;
END;
$$;
RESET ROLE;

CREATE TEMP TABLE attendance_rehearsal_pruning_plan(plan jsonb);
DO $$
DECLARE plan_row record;
BEGIN
    FOR plan_row IN EXECUTE $plan$
        EXPLAIN (FORMAT JSON)
        SELECT count(*) FROM attendance_rehearsal.records_partitioned
        WHERE attendance_date >= DATE '2026-01-01' AND attendance_date < DATE '2027-01-01'
    $plan$
    LOOP
        INSERT INTO attendance_rehearsal_pruning_plan(plan)
        VALUES (plan_row."QUERY PLAN"::jsonb);
    END LOOP;
END;
$$;

DO $$
DECLARE plan_text text;
BEGIN
    SELECT plan::text INTO STRICT plan_text FROM attendance_rehearsal_pruning_plan;
    IF plan_text NOT LIKE '%records_y2026%' OR
       plan_text LIKE '%records_y2024%' OR plan_text LIKE '%records_y2025%' OR
       plan_text LIKE '%records_default%' THEN
        RAISE EXCEPTION 'partition pruning failed: %', plan_text;
    END IF;
END;
$$;

-- Freeze all forward-migration evidence before releasing the target. The later rollback build must
-- not require target indexes and rollback indexes to coexist on a capacity-constrained workstation.
CREATE TABLE attendance_rehearsal.target_evidence AS
SELECT
    count(*) AS partitioned_rows,
    sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
        school_id, attendance_date, academic_year_id, class_id, section_id, status), 0)::numeric)
      AS partitioned_checksum,
    (SELECT sum(pg_total_relation_size(relid))
     FROM pg_partition_tree('attendance_rehearsal.records_partitioned')) AS partitioned_bytes,
    pg_total_relation_size('attendance_rehearsal.record_identity_registry') AS registry_bytes,
    (SELECT count(*) FROM attendance_rehearsal.record_identity_registry) AS registry_rows,
    (SELECT count(*) FROM pg_inherits
     WHERE inhparent = 'attendance_rehearsal.records_partitioned'::regclass) AS partitions,
    (SELECT count(*) FROM pg_indexes
     WHERE schemaname = 'attendance_rehearsal' AND tablename LIKE 'records_y%') AS child_indexes,
    to_regclass('attendance_rehearsal.records_default') IS NOT NULL AS default_partition_present
FROM attendance_rehearsal.records_partitioned;

-- Persist immutable source evidence, then release the synthetic source before constructing the
-- rollback copy. The production source remains external and untouched; keeping three full local
-- copies simultaneously adds no correctness coverage and can exceed a developer workstation disk.
CREATE TABLE attendance_rehearsal.source_evidence AS
SELECT count(*) AS source_rows,
       sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
           school_id, attendance_date, academic_year_id, class_id, section_id, status), 0)::numeric)
         AS source_checksum,
       pg_total_relation_size('attendance_rehearsal.records_source') AS source_bytes
FROM attendance_rehearsal.records_source;
DROP TABLE attendance_rehearsal.records_source;

-- Forward semantics and registry parity are frozen in target_evidence. The target is now read-only
-- for rollback, so release the global registry before the heap copy to preserve an 8 GiB host floor.
DROP TABLE attendance_rehearsal.record_identity_registry;

-- Reconstruct the original unpartitioned shape as the rollback rehearsal.
CREATE TABLE attendance_rehearsal.records_rollback (
    id VARCHAR(255) NOT NULL,
    attendance_daily_id VARCHAR(255) NOT NULL,
    student_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    remarks TEXT,
    recorded_by BIGINT,
    recorded_at TIMESTAMPTZ,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ
);
INSERT INTO attendance_rehearsal.records_rollback
SELECT * FROM attendance_rehearsal.records_partitioned;

CREATE TABLE attendance_rehearsal.rollback_heap_evidence AS
SELECT pg_total_relation_size('attendance_rehearsal.records_rollback') AS rollback_heap_bytes;

-- The complete forward target has already passed uniqueness/FK/check/RLS/pruning and checksum gates.
-- Release it before building rollback indexes, matching an operator-approved
-- rollback cutover while keeping peak local storage bounded.
DROP TABLE attendance_rehearsal.records_partitioned CASCADE;

ALTER TABLE attendance_rehearsal.records_rollback
    ADD CONSTRAINT records_rollback_pk PRIMARY KEY (id),
    ADD CONSTRAINT records_rollback_daily_fk FOREIGN KEY (attendance_daily_id)
        REFERENCES attendance_rehearsal.attendance_daily(id) ON DELETE CASCADE,
    ADD CONSTRAINT records_rollback_daily_student_uk UNIQUE (attendance_daily_id, student_id),
    ADD CONSTRAINT records_rollback_student_date_year_uk
        UNIQUE (student_id, attendance_date, academic_year_id),
    ADD CONSTRAINT records_rollback_status_check
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'LEAVE'));

WITH checks AS (
    SELECT
      (SELECT source_rows FROM attendance_rehearsal.source_evidence) AS source_rows,
      (SELECT partitioned_rows FROM attendance_rehearsal.target_evidence) AS partitioned_rows,
      (SELECT count(*) FROM attendance_rehearsal.records_rollback) AS rollback_rows,
      (SELECT source_checksum FROM attendance_rehearsal.source_evidence) AS source_checksum,
      (SELECT partitioned_checksum FROM attendance_rehearsal.target_evidence) AS partitioned_checksum,
      (SELECT sum(hashtextextended(concat_ws('|', id, attendance_daily_id, student_id,
               school_id, attendance_date, academic_year_id, class_id, section_id, status), 0)::numeric)
       FROM attendance_rehearsal.records_rollback) AS rollback_checksum,
      (SELECT partitions FROM attendance_rehearsal.target_evidence) AS partitions,
      (SELECT child_indexes FROM attendance_rehearsal.target_evidence) AS child_indexes,
      pg_database_size(current_database()) AS database_bytes,
      (SELECT source_bytes FROM attendance_rehearsal.source_evidence) AS source_bytes,
      (SELECT partitioned_bytes FROM attendance_rehearsal.target_evidence) AS partitioned_bytes,
      (SELECT registry_bytes FROM attendance_rehearsal.target_evidence) AS registry_bytes,
      pg_total_relation_size('attendance_rehearsal.records_rollback') AS rollback_bytes,
      (SELECT rollback_heap_bytes FROM attendance_rehearsal.rollback_heap_evidence) AS rollback_heap_bytes,
      (SELECT default_partition_present FROM attendance_rehearsal.target_evidence)
        AS default_partition_present,
      (SELECT registry_rows FROM attendance_rehearsal.target_evidence) AS registry_rows
)
SELECT 'IMS_ATTENDANCE_PARTITION_REHEARSAL|' || json_build_object(
    'sourceRows', source_rows,
    'partitionedRows', partitioned_rows,
    'rollbackRows', rollback_rows,
    'rowCountsMatch', source_rows = partitioned_rows AND source_rows = rollback_rows,
    'forwardChecksumMatches', source_checksum = partitioned_checksum,
    'rollbackChecksumMatches', source_checksum = rollback_checksum,
    'partitions', partitions,
    'childIndexes', child_indexes,
    'databaseBytes', database_bytes,
    'sourceBytes', source_bytes,
    'partitionedBytes', partitioned_bytes,
    'identityRegistryBytes', registry_bytes,
    'rollbackBytes', rollback_bytes,
    'rollbackHeapBytes', rollback_heap_bytes,
    'peakCoexistenceBytes', partitioned_bytes + rollback_heap_bytes,
    'globalIdentityRegistryRows', registry_rows,
    'uniqueSemanticsPassed', true,
    'foreignKeyPassed', true,
    'checkConstraintPassed', true,
    'rlsPassed', true,
    'rlsBypassPassed', true,
    'partitionPruningPassed', true,
    'defaultPartitionPresent', default_partition_present,
    'rollbackPassed', source_rows = rollback_rows AND source_checksum = rollback_checksum
)::text
FROM checks;
