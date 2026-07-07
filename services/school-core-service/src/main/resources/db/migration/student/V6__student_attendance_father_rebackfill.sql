-- Re-seeds tenant_school.outbox_events with an augmented student.upserted.v1 payload (adding
-- attendancePercent + fatherName) for every existing student row, so reporting.dim_student
-- (V18) backfills the two new columns for pre-existing students. Mirrors V5's guard: this
-- migration lives in the student schema's own Flyway history but runs after tenant_school
-- creates tenant_school.outbox_events (see V5's comment for the full ordering rationale).
DO $$
BEGIN
    IF to_regclass('tenant_school.outbox_events') IS NOT NULL THEN
        INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
        SELECT 'StudentUpserted:'||id, 'student.upserted.v1', 'Student', id::text, school_id,
               jsonb_build_object('id', id, 'schoolId', school_id, 'admissionNo', admission_no,
                                  'fullName', full_name, 'rollNo', roll_no, 'classId', class_id,
                                  'sectionId', section_id, 'parentContact', father_contact,
                                  'phone', phone, 'active', (deleted_at IS NULL),
                                  'attendancePercent', attendance_percent, 'fatherName', father_name)
        FROM student.students;
    END IF;
END $$;
