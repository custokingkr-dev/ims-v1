-- Create student-level attendance records table
-- Enables per-student Present/Absent marking instead of aggregate counts only

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

  -- Foreign keys
  CONSTRAINT fk_attendance_student_records_daily
    FOREIGN KEY (attendance_daily_id) REFERENCES attendance_daily(id) ON DELETE CASCADE,
  CONSTRAINT fk_attendance_student_records_student
    FOREIGN KEY (student_id) REFERENCES students(id) ON DELETE CASCADE,
  CONSTRAINT fk_attendance_student_records_school
    FOREIGN KEY (school_id) REFERENCES schools(id) ON DELETE CASCADE,
  CONSTRAINT fk_attendance_student_records_academic_year
    FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
  CONSTRAINT fk_attendance_student_records_class
    FOREIGN KEY (class_id) REFERENCES school_classes(id),
  CONSTRAINT fk_attendance_student_records_section
    FOREIGN KEY (section_id) REFERENCES school_sections(id) ON DELETE CASCADE,

  -- Uniqueness constraints
  CONSTRAINT uk_attendance_student_daily_student
    UNIQUE (attendance_daily_id, student_id),
  CONSTRAINT uk_attendance_student_date_year
    UNIQUE (student_id, attendance_date, academic_year_id)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_attendance_student_records_school_date
  ON attendance_student_records(school_id, attendance_date);

CREATE INDEX IF NOT EXISTS idx_attendance_student_records_section_date
  ON attendance_student_records(section_id, attendance_date);

CREATE INDEX IF NOT EXISTS idx_attendance_student_records_daily
  ON attendance_student_records(attendance_daily_id);

CREATE INDEX IF NOT EXISTS idx_attendance_student_records_student_date
  ON attendance_student_records(student_id, attendance_date);

CREATE INDEX IF NOT EXISTS idx_attendance_student_records_academic_year
  ON attendance_student_records(academic_year_id, attendance_date);
