-- A student incarnation survives later snapshots and cannot be changed or reused after erasure.
-- Recovery predating this migration requires quarantine and approved legacy ownership evidence.
ALTER TABLE student.students ADD COLUMN erasure_incarnation uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX student_erasure_incarnation_unique ON student.students(erasure_incarnation);

-- No source FK: terminal evidence must survive removal of its student and school parent.
CREATE TABLE student.erasure_journal_receipts (
    intent_id char(64) PRIMARY KEY CHECK (intent_id ~ '^[a-f0-9]{64}$'),
    operation_id uuid NOT NULL UNIQUE,
    student_id bigint NOT NULL CHECK (student_id > 0),
    school_id bigint NOT NULL CHECK (school_id > 0),
    student_incarnation uuid NOT NULL,
    source_lineage_id varchar(64) NOT NULL CHECK (source_lineage_id ~ '^[a-z0-9][a-z0-9-]{7,63}$'),
    restore_epoch uuid NOT NULL,
    journal_object varchar(512) NOT NULL,
    journal_generation bigint NOT NULL CHECK (journal_generation > 0),
    journal_sha256 char(64) NOT NULL CHECK (journal_sha256 ~ '^[a-f0-9]{64}$'),
    committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (student_incarnation, restore_epoch)
);
ALTER TABLE student.erasure_journal_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE student.erasure_journal_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON student.erasure_journal_receipts
    USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
           OR current_setting('app.bypass_rls', true) = 'on')
    WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
                OR current_setting('app.bypass_rls', true) = 'on');
REVOKE ALL ON student.erasure_journal_receipts FROM PUBLIC;
DO $$ DECLARE attribute record;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        -- Bootstrap owner defaults can grant all DML to new tables. Terminal receipts are
        -- append-only; afterMigrate mirrors these precise privileges to the dedicated role.
        REVOKE ALL ON student.erasure_journal_receipts FROM app_rt;
        GRANT SELECT, INSERT ON student.erasure_journal_receipts TO app_rt;

        -- A runtime INSERT must receive a fresh database-generated incarnation. Preserve
        -- existing INSERT columns while excluding this owner-controlled identity field.
        IF has_table_privilege('app_rt', 'student.students', 'INSERT') THEN
            REVOKE INSERT ON student.students FROM app_rt;
            FOR attribute IN SELECT attname FROM pg_attribute
                WHERE attrelid='student.students'::regclass AND attnum > 0
                  AND NOT attisdropped AND attname <> 'erasure_incarnation'
            LOOP
                EXECUTE format('GRANT INSERT (%I) ON student.students TO app_rt', attribute.attname);
            END LOOP;
        END IF;
        REVOKE INSERT (erasure_incarnation) ON student.students FROM app_rt;
    END IF;
END $$;

CREATE FUNCTION student.require_immutable_erasure_incarnation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.erasure_incarnation IS DISTINCT FROM OLD.erasure_incarnation THEN
        RAISE EXCEPTION 'Student erasure incarnation cannot change';
    END IF;
    IF TG_OP = 'INSERT' AND EXISTS (SELECT 1 FROM student.erasure_journal_receipts r
               WHERE r.student_incarnation = NEW.erasure_incarnation) THEN
        RAISE EXCEPTION 'Erased student incarnation cannot be reused';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER student_immutable_erasure_incarnation BEFORE INSERT OR UPDATE ON student.students
    FOR EACH ROW EXECUTE FUNCTION student.require_immutable_erasure_incarnation();
