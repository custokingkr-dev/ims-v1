ALTER TABLE student.students ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE student.students ADD CONSTRAINT student_aggregate_version_positive CHECK (aggregate_version > 0);

CREATE FUNCTION student.bump_student_aggregate_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- The UPDATE's row lock serializes concurrent writers; callers cannot supply a lower version.
    NEW.aggregate_version := OLD.aggregate_version + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER student_aggregate_version_before_update
BEFORE UPDATE ON student.students FOR EACH ROW EXECUTE FUNCTION student.bump_student_aggregate_version();
