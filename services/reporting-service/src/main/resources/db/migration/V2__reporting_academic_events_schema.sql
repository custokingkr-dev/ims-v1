CREATE TABLE IF NOT EXISTS academic_events (
    id VARCHAR(36) PRIMARY KEY,
    school_id BIGINT NOT NULL REFERENCES public.schools(id),
    academic_year_id VARCHAR(36) REFERENCES public.academic_years(id),
    title VARCHAR(200) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_date DATE,
    total_budget BIGINT NOT NULL DEFAULT 0,
    school_contribution BIGINT NOT NULL DEFAULT 0,
    student_contribution_target BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT REFERENCES public.app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS event_student_contributions (
    id VARCHAR(36) PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL REFERENCES academic_events(id),
    student_id BIGINT NOT NULL REFERENCES public.students(id),
    school_id BIGINT NOT NULL REFERENCES public.schools(id),
    expected_amount BIGINT NOT NULL DEFAULT 0,
    paid_amount BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_reminder_sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reporting_event_student UNIQUE (event_id, student_id)
);

CREATE INDEX IF NOT EXISTS idx_reporting_academic_events_school ON academic_events(school_id);
CREATE INDEX IF NOT EXISTS idx_reporting_event_contributions_event ON event_student_contributions(event_id);
CREATE INDEX IF NOT EXISTS idx_reporting_event_contributions_school ON event_student_contributions(school_id);

INSERT INTO academic_events
    (id, school_id, academic_year_id, title, event_type, event_date,
     total_budget, school_contribution, student_contribution_target,
     status, created_by, created_at, updated_at)
SELECT id, school_id, academic_year_id, title, event_type, event_date,
       total_budget, school_contribution, student_contribution_target,
       status, created_by, created_at, updated_at
FROM public.academic_events
ON CONFLICT (id) DO NOTHING;

INSERT INTO event_student_contributions
    (id, event_id, student_id, school_id, expected_amount, paid_amount,
     status, last_reminder_sent_at, created_at, updated_at)
SELECT id, event_id, student_id, school_id, expected_amount, paid_amount,
       status, last_reminder_sent_at, created_at, updated_at
FROM public.event_student_contributions
ON CONFLICT (id) DO NOTHING;
