CREATE TABLE IF NOT EXISTS attendance_daily (
    id               VARCHAR(255) NOT NULL,
    attendance_date  DATE,
    total_enrolled   INTEGER      NOT NULL,
    present_count    INTEGER      NOT NULL,
    absent_count     INTEGER      NOT NULL,
    recorded_by      BIGINT,
    recorded_at      TIMESTAMPTZ,
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    locked           BOOLEAN      NOT NULL,
    school_class_id  VARCHAR(255) NOT NULL,
    section_id       VARCHAR(255) NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_attendance_day_section_year UNIQUE (attendance_date, section_id, academic_year_id),
    CONSTRAINT fk_attendance_class FOREIGN KEY (school_class_id) REFERENCES public.school_classes (id),
    CONSTRAINT fk_attendance_section FOREIGN KEY (section_id) REFERENCES public.school_sections (id),
    CONSTRAINT fk_attendance_year FOREIGN KEY (academic_year_id) REFERENCES public.academic_years (id)
);

CREATE TABLE IF NOT EXISTS attendance_student_records (
    id VARCHAR(255) PRIMARY KEY,
    attendance_daily_id VARCHAR(255) NOT NULL,
    student_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    academic_year_id VARCHAR(255) NOT NULL,
    class_id VARCHAR(255) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PRESENT', 'ABSENT')),
    remarks TEXT,
    recorded_by BIGINT,
    recorded_at TIMESTAMPTZ,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_attendance_student_records_daily
        FOREIGN KEY (attendance_daily_id) REFERENCES attendance_daily(id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_student_records_student
        FOREIGN KEY (student_id) REFERENCES public.students(id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_student_records_school
        FOREIGN KEY (school_id) REFERENCES public.schools(id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_student_records_academic_year
        FOREIGN KEY (academic_year_id) REFERENCES public.academic_years(id),
    CONSTRAINT fk_attendance_student_records_class
        FOREIGN KEY (class_id) REFERENCES public.school_classes(id),
    CONSTRAINT fk_attendance_student_records_section
        FOREIGN KEY (section_id) REFERENCES public.school_sections(id) ON DELETE CASCADE,
    CONSTRAINT uk_attendance_student_daily_student UNIQUE (attendance_daily_id, student_id),
    CONSTRAINT uk_attendance_student_date_year UNIQUE (student_id, attendance_date, academic_year_id)
);

CREATE INDEX IF NOT EXISTS idx_attendance_daily_section_year ON attendance_daily (section_id, academic_year_id);
CREATE INDEX IF NOT EXISTS idx_attendance_daily_date ON attendance_daily (attendance_date);
CREATE INDEX IF NOT EXISTS idx_attendance_student_records_school_date ON attendance_student_records(school_id, attendance_date);
CREATE INDEX IF NOT EXISTS idx_attendance_student_records_section_date ON attendance_student_records(section_id, attendance_date);
CREATE INDEX IF NOT EXISTS idx_attendance_student_records_daily ON attendance_student_records(attendance_daily_id);
CREATE INDEX IF NOT EXISTS idx_attendance_student_records_student_date ON attendance_student_records(student_id, attendance_date);
CREATE INDEX IF NOT EXISTS idx_attendance_student_records_academic_year ON attendance_student_records(academic_year_id, attendance_date);

INSERT INTO attendance_daily
    (id, attendance_date, total_enrolled, present_count, absent_count,
     recorded_by, recorded_at, updated_by, updated_at, locked,
     school_class_id, section_id, academic_year_id)
SELECT id, attendance_date, total_enrolled, present_count, absent_count,
       recorded_by, recorded_at, updated_by, updated_at, locked,
       school_class_id, section_id, academic_year_id
FROM public.attendance_daily
ON CONFLICT (id) DO NOTHING;

INSERT INTO attendance_student_records
    (id, attendance_daily_id, student_id, school_id, attendance_date,
     academic_year_id, class_id, section_id, status, remarks,
     recorded_by, recorded_at, updated_by, updated_at)
SELECT id, attendance_daily_id, student_id, school_id, attendance_date,
       academic_year_id, class_id, section_id, status, remarks,
       recorded_by, recorded_at, updated_by, updated_at
FROM public.attendance_student_records
ON CONFLICT (id) DO NOTHING;
