CREATE TABLE IF NOT EXISTS student.guardians (
    id VARCHAR(64) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(32),
    email VARCHAR(320),
    preferred_language VARCHAR(32),
    contact_verified_at TIMESTAMPTZ,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_guardian_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_guardians_school_name
    ON student.guardians (school_id, lower(full_name));

CREATE INDEX IF NOT EXISTS idx_guardians_school_phone
    ON student.guardians (school_id, phone)
    WHERE phone IS NOT NULL AND phone <> '';

CREATE TABLE IF NOT EXISTS student.student_guardians (
    id VARCHAR(64) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL REFERENCES student.students(id) ON DELETE CASCADE,
    guardian_id VARCHAR(64) NOT NULL REFERENCES student.guardians(id),
    relationship VARCHAR(32) NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    receives_notifications BOOLEAN NOT NULL DEFAULT TRUE,
    can_view_academic BOOLEAN NOT NULL DEFAULT TRUE,
    can_manage_fees BOOLEAN NOT NULL DEFAULT FALSE,
    pickup_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_student_guardian UNIQUE (student_id, guardian_id),
    CONSTRAINT ck_guardian_relationship CHECK (
        relationship IN ('FATHER', 'MOTHER', 'GUARDIAN', 'GRANDPARENT', 'SIBLING', 'OTHER')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_student_primary_guardian
    ON student.student_guardians (student_id)
    WHERE is_primary;

CREATE INDEX IF NOT EXISTS idx_student_guardians_school_student
    ON student.student_guardians (school_id, student_id);

CREATE INDEX IF NOT EXISTS idx_student_guardians_guardian
    ON student.student_guardians (guardian_id, student_id);

-- Consent is an immutable event ledger. Effective status is the latest event for a purpose.
CREATE TABLE IF NOT EXISTS student.student_consent_events (
    id VARCHAR(64) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL REFERENCES student.students(id) ON DELETE CASCADE,
    guardian_id VARCHAR(64) REFERENCES student.guardians(id),
    purpose VARCHAR(48) NOT NULL,
    status VARCHAR(24) NOT NULL,
    lawful_basis VARCHAR(32) NOT NULL DEFAULT 'CONSENT',
    notice_version VARCHAR(64) NOT NULL,
    evidence_source VARCHAR(32) NOT NULL,
    evidence_reference VARCHAR(512),
    notes TEXT,
    effective_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    recorded_by BIGINT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(128),
    CONSTRAINT ck_consent_purpose CHECK (purpose IN (
        'STUDENT_PHOTO', 'ID_CARD_PRODUCTION', 'SCHOOL_COMMUNICATIONS',
        'APAAR_REGISTRATION', 'DATA_CORRECTION'
    )),
    CONSTRAINT ck_consent_status CHECK (status IN ('PENDING', 'GRANTED', 'DENIED', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_consent_evidence_source CHECK (evidence_source IN (
        'SCHOOL_RECORD', 'SIGNED_FORM', 'GUARDIAN_PORTAL', 'EMAIL', 'SMS', 'WHATSAPP', 'OTHER'
    ))
);

CREATE INDEX IF NOT EXISTS idx_consent_student_purpose_latest
    ON student.student_consent_events (student_id, purpose, effective_at DESC, recorded_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_consent_school_idempotency
    ON student.student_consent_events (school_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Backfill legacy parent data without incorrectly merging same-name guardians that lack a phone number.
INSERT INTO student.guardians (id, school_id, full_name, phone, status, created_at, updated_at)
SELECT DISTINCT
    'legacy-' || md5(s.school_id::text || ':father:' || lower(trim(COALESCE(s.father_name, ''))) || ':' ||
        CASE WHEN trim(COALESCE(s.father_contact, '')) = '' THEN s.id::text ELSE regexp_replace(s.father_contact, '[^0-9]', '', 'g') END),
    s.school_id,
    COALESCE(NULLIF(trim(s.father_name), ''), 'Father / guardian'),
    NULLIF(trim(s.father_contact), ''),
    'ACTIVE',
    COALESCE(s.created_at, now()),
    COALESCE(s.updated_at, now())
FROM student.students s
WHERE trim(COALESCE(s.father_name, '')) <> '' OR trim(COALESCE(s.father_contact, '')) <> ''
ON CONFLICT (id) DO NOTHING;

INSERT INTO student.student_guardians (
    id, school_id, student_id, guardian_id, relationship, is_primary,
    receives_notifications, can_view_academic, can_manage_fees, created_at, updated_at
)
SELECT
    'legacy-link-' || md5(s.id::text || ':father'),
    s.school_id,
    s.id,
    'legacy-' || md5(s.school_id::text || ':father:' || lower(trim(COALESCE(s.father_name, ''))) || ':' ||
        CASE WHEN trim(COALESCE(s.father_contact, '')) = '' THEN s.id::text ELSE regexp_replace(s.father_contact, '[^0-9]', '', 'g') END),
    'FATHER', TRUE, TRUE, TRUE, TRUE,
    COALESCE(s.created_at, now()), COALESCE(s.updated_at, now())
FROM student.students s
WHERE trim(COALESCE(s.father_name, '')) <> '' OR trim(COALESCE(s.father_contact, '')) <> ''
ON CONFLICT (student_id, guardian_id) DO NOTHING;

INSERT INTO student.guardians (id, school_id, full_name, status, created_at, updated_at)
SELECT
    'legacy-' || md5(s.school_id::text || ':mother:' || lower(trim(s.mother_name)) || ':' || s.id::text),
    s.school_id, trim(s.mother_name), 'ACTIVE', COALESCE(s.created_at, now()), COALESCE(s.updated_at, now())
FROM student.students s
WHERE trim(COALESCE(s.mother_name, '')) <> ''
ON CONFLICT (id) DO NOTHING;

INSERT INTO student.student_guardians (
    id, school_id, student_id, guardian_id, relationship, is_primary,
    receives_notifications, can_view_academic, can_manage_fees, created_at, updated_at
)
SELECT
    'legacy-link-' || md5(s.id::text || ':mother'),
    s.school_id, s.id,
    'legacy-' || md5(s.school_id::text || ':mother:' || lower(trim(s.mother_name)) || ':' || s.id::text),
    'MOTHER', FALSE, FALSE, TRUE, FALSE,
    COALESCE(s.created_at, now()), COALESCE(s.updated_at, now())
FROM student.students s
WHERE trim(COALESCE(s.mother_name, '')) <> ''
ON CONFLICT (student_id, guardian_id) DO NOTHING;

ALTER TABLE student.guardians ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.guardians;
CREATE POLICY tenant_isolation ON student.guardians
  USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
         OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_guardians ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_guardians;
CREATE POLICY tenant_isolation ON student.student_guardians
  USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
         OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');

ALTER TABLE student.student_consent_events ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON student.student_consent_events;
CREATE POLICY tenant_isolation ON student.student_consent_events
  USING (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
         OR current_setting('app.bypass_rls', true) = 'on')
  WITH CHECK (school_id = nullif(current_setting('app.current_school_id', true), '')::bigint
              OR current_setting('app.bypass_rls', true) = 'on');
