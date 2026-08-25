-- Owner-operated, create-only repair for the exact plan produced by V25.
-- The function intentionally contains no transaction control: callers must open a
-- SERIALIZABLE transaction. It fails closed if any classifier or review writer is active.

CREATE TABLE student.guardian_safe_create_repair_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    contract_version VARCHAR(64) NOT NULL,
    contract_digest CHAR(64) NOT NULL,
    plan_sha256 CHAR(64) NOT NULL,
    expected_students INTEGER NOT NULL,
    expected_relationships INTEGER NOT NULL,
    expected_fields INTEGER NOT NULL,
    applied_students INTEGER NOT NULL,
    applied_relationships INTEGER NOT NULL,
    applied_fields INTEGER NOT NULL,
    approval_reference VARCHAR(255) NOT NULL,
    source_revision CHAR(40) NOT NULL,
    runner_payload_sha256 CHAR(64) NOT NULL,
    operator_job_name VARCHAR(63) NOT NULL,
    status VARCHAR(24) NOT NULL,
    executed_by TEXT NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_guardian_safe_create_repair_plan
        UNIQUE (contract_digest, plan_sha256),
    CONSTRAINT ck_guardian_safe_create_repair_digest
        CHECK (contract_digest ~ '^[0-9a-f]{64}$' AND plan_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_guardian_safe_create_repair_counts
        CHECK (
            expected_students > 0
            AND expected_relationships >= expected_students
            AND expected_fields >= expected_relationships
            AND applied_students = expected_students
            AND applied_relationships = expected_relationships
            AND applied_fields = expected_fields
        ),
    CONSTRAINT ck_guardian_safe_create_repair_status
        CHECK (status = 'COMPLETED'),
    CONSTRAINT ck_guardian_safe_create_repair_provenance CHECK (
        approval_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/#-]{6,254}$'
        AND source_revision ~ '^[0-9a-f]{40}$'
        AND runner_payload_sha256 ~ '^[0-9a-f]{64}$'
        AND operator_job_name ~ '^[a-z0-9][a-z0-9-]{0,62}$'
    )
);

CREATE TABLE student.guardian_safe_create_repair_actions (
    run_id VARCHAR(64) NOT NULL
        REFERENCES student.guardian_safe_create_repair_runs(run_id) ON DELETE RESTRICT,
    student_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    relationship VARCHAR(32) NOT NULL,
    guardian_id VARCHAR(64) NOT NULL,
    link_id VARCHAR(64) NOT NULL,
    field_count INTEGER NOT NULL,
    action_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, student_id, relationship),
    CONSTRAINT uq_guardian_safe_create_repair_guardian UNIQUE (guardian_id),
    CONSTRAINT uq_guardian_safe_create_repair_link UNIQUE (link_id),
    CONSTRAINT ck_guardian_safe_create_repair_relationship
        CHECK (relationship IN ('FATHER', 'MOTHER')),
    CONSTRAINT ck_guardian_safe_create_repair_action
        CHECK (field_count BETWEEN 1 AND 2 AND action_sha256 ~ '^[0-9a-f]{64}$')
);

ALTER TABLE student.guardian_safe_create_repair_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE student.guardian_safe_create_repair_actions ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE student.guardian_safe_create_repair_runs FROM PUBLIC;
REVOKE ALL ON TABLE student.guardian_safe_create_repair_actions FROM PUBLIC;

CREATE OR REPLACE FUNCTION student.execute_guardian_repair_v1(
    p_expected_plan_sha256 TEXT,
    p_expected_students INTEGER,
    p_expected_relationships INTEGER,
    p_expected_fields INTEGER,
    p_expected_contract_sha256 TEXT,
    p_approval_reference TEXT,
    p_source_revision TEXT,
    p_runner_payload_sha256 TEXT,
    p_operator_job_name TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY INVOKER
VOLATILE
PARALLEL UNSAFE
AS $$
DECLARE
    -- This is the last reviewed production evidence tuple. V25 intentionally changes
    -- the plan fingerprint, so this release is structurally inert until a later,
    -- separately reviewed migration replaces the plan pin with fresh evidence.
    c_approved_plan_sha256 CONSTANT TEXT :=
        'fe0425a615d15a1444cd8cbd9b3bbe64a5360a6b8a3a9f33e5b6110be7684492';
    c_approved_contract_sha256 CONSTANT TEXT :=
        'fa0ca25fd6c2f2e63f9040cebeb3899481415540ca3cc61a331624836012b641';
    c_approved_students CONSTANT INTEGER := 13;
    c_approved_relationships CONSTANT INTEGER := 14;
    c_approved_fields CONSTANT INTEGER := 15;
    c_approved_runner_payload_sha256 CONSTANT TEXT :=
        '6f3a742cf411d2a0829a40ddd580f894a095048a73e2f5095fea3118a114db21';
    v_contract_version TEXT;
    v_contract_digest TEXT;
    v_plan_sha256 TEXT;
    v_run_id TEXT;
    v_students INTEGER;
    v_relationships INTEGER;
    v_fields INTEGER;
    v_existing student.guardian_safe_create_repair_runs%ROWTYPE;
BEGIN
    IF current_setting('transaction_isolation') <> 'serializable' THEN
        RAISE EXCEPTION 'guardian repair requires a SERIALIZABLE transaction';
    END IF;
    IF p_expected_plan_sha256 IS NULL
       OR p_expected_plan_sha256 !~ '^[0-9a-f]{64}$'
       OR p_expected_contract_sha256 IS NULL
       OR p_expected_contract_sha256 !~ '^[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'guardian repair requires lowercase SHA-256 plan and contract digests';
    END IF;
    IF p_expected_students <= 0
       OR p_expected_relationships < p_expected_students
       OR p_expected_fields < p_expected_relationships THEN
        RAISE EXCEPTION 'guardian repair expected counts are invalid';
    END IF;
    IF p_expected_contract_sha256 <> c_approved_contract_sha256
       OR p_expected_plan_sha256 <> c_approved_plan_sha256
       OR p_expected_students <> c_approved_students
       OR p_expected_relationships <> c_approved_relationships
       OR p_expected_fields <> c_approved_fields THEN
        RAISE EXCEPTION 'guardian repair plan is not pinned by this reviewed release';
    END IF;
    IF p_runner_payload_sha256 IS DISTINCT FROM c_approved_runner_payload_sha256
       OR p_approval_reference IS NULL
       OR p_approval_reference !~ '^[A-Za-z0-9][A-Za-z0-9._:/#-]{6,254}$'
       OR p_source_revision IS NULL
       OR p_source_revision !~ '^[0-9a-f]{40}$'
       OR p_operator_job_name IS NULL
       OR p_operator_job_name !~ '^[a-z0-9][a-z0-9-]{0,62}$' THEN
        RAISE EXCEPTION 'guardian repair execution provenance is invalid';
    END IF;
    IF to_regclass('tenant_school.outbox_events') IS NULL THEN
        RAISE EXCEPTION 'guardian repair outbox is unavailable';
    END IF;

    -- The advisory lock serializes lock upgrades between repair sessions. SHARE locks
    -- reject concurrent normal DML (ROW EXCLUSIVE) while preserving ordinary reads.
    IF NOT pg_try_advisory_xact_lock(hashtextextended('student.guardian-repair-v1', 0)) THEN
        RAISE EXCEPTION 'another guardian repair transaction holds the execution lock';
    END IF;
    LOCK TABLE tenant_school.schools,
               student.students,
               student.guardians,
               student.student_guardians,
               student.student_consent_events,
               student.student_review_campaigns,
               student.student_review_items
        IN SHARE MODE NOWAIT;

    SELECT contract_version, contract_digest
    INTO v_contract_version, v_contract_digest
    FROM student.guardian_safe_create_contract_v1();

    IF v_contract_digest IS DISTINCT FROM p_expected_contract_sha256 THEN
        RAISE EXCEPTION 'guardian repair contract digest changed after approval';
    END IF;

    v_run_id := 'guardian-repair-v1-' || substr(encode(sha256(convert_to(
        p_expected_contract_sha256 || ':' || p_expected_plan_sha256,
        'UTF8'
    )), 'hex'), 1, 32);

    SELECT * INTO v_existing
    FROM student.guardian_safe_create_repair_runs
    WHERE run_id = v_run_id;

    IF FOUND THEN
        IF v_existing.contract_digest <> p_expected_contract_sha256
           OR v_existing.plan_sha256 <> p_expected_plan_sha256
           OR v_existing.expected_students <> p_expected_students
           OR v_existing.expected_relationships <> p_expected_relationships
           OR v_existing.expected_fields <> p_expected_fields
           OR v_existing.approval_reference <> p_approval_reference
           OR v_existing.source_revision <> p_source_revision
           OR v_existing.runner_payload_sha256 <> p_runner_payload_sha256
           OR v_existing.status <> 'COMPLETED' THEN
            RAISE EXCEPTION 'guardian repair replay does not match the completed ledger';
        END IF;
        RETURN jsonb_build_object(
            'status', 'ALREADY_COMPLETED',
            'runId', v_existing.run_id,
            'contractVersion', v_existing.contract_version,
            'contractDigest', v_existing.contract_digest,
            'planSha256', v_existing.plan_sha256,
            'students', v_existing.applied_students,
            'relationships', v_existing.applied_relationships,
            'fields', v_existing.applied_fields
        );
    END IF;

    CREATE TEMPORARY TABLE guardian_safe_create_repair_stage
        ON COMMIT DROP
        AS
        SELECT *
        FROM student.guardian_safe_create_plan_v1();

    SELECT
        count(*) FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')::integer,
        count(DISTINCT (student_id, relationship))
            FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')::integer,
        count(DISTINCT student_id)
            FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')::integer,
        encode(sha256(convert_to(COALESCE(
            jsonb_agg(fingerprint_record ORDER BY student_id, relationship, field_name)
                FILTER (WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT')::text,
            '[]'
        ), 'UTF8')), 'hex')
    INTO v_fields, v_relationships, v_students, v_plan_sha256
    FROM guardian_safe_create_repair_stage;

    IF v_plan_sha256 IS DISTINCT FROM p_expected_plan_sha256
       OR v_students IS DISTINCT FROM p_expected_students
       OR v_relationships IS DISTINCT FROM p_expected_relationships
       OR v_fields IS DISTINCT FROM p_expected_fields THEN
        RAISE EXCEPTION 'guardian repair plan changed after approval';
    END IF;

    INSERT INTO student.guardian_safe_create_repair_runs (
        run_id, contract_version, contract_digest, plan_sha256,
        expected_students, expected_relationships, expected_fields,
        applied_students, applied_relationships, applied_fields,
        approval_reference, source_revision, runner_payload_sha256, operator_job_name,
        status, executed_by
    ) VALUES (
        v_run_id, v_contract_version, v_contract_digest, v_plan_sha256,
        v_students, v_relationships, v_fields,
        v_students, v_relationships, v_fields,
        p_approval_reference, p_source_revision, p_runner_payload_sha256, p_operator_job_name,
        'COMPLETED', current_user
    );

    WITH relationships AS (
        SELECT
            student_id,
            school_id,
            relationship,
            target_guardian_id,
            target_link_id,
            max(legacy_value) FILTER (WHERE field_name = 'name') AS full_name,
            max(legacy_value) FILTER (WHERE field_name = 'contact') AS phone
        FROM guardian_safe_create_repair_stage
        WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT'
        GROUP BY student_id, school_id, relationship, target_guardian_id, target_link_id
    )
    INSERT INTO student.guardians (
        id, school_id, full_name, phone, email, preferred_language,
        contact_verified_at, status, created_by, updated_by, created_at, updated_at, version
    )
    SELECT
        target_guardian_id, school_id, full_name, phone, NULL, NULL,
        NULL, 'ACTIVE', NULL, NULL, transaction_timestamp(), transaction_timestamp(), 0
    FROM relationships;

    WITH relationships AS (
        SELECT DISTINCT
            student_id, school_id, relationship, target_guardian_id, target_link_id
        FROM guardian_safe_create_repair_stage
        WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT'
    )
    INSERT INTO student.student_guardians (
        id, school_id, student_id, guardian_id, relationship, is_primary,
        receives_notifications, can_view_academic, can_manage_fees, pickup_authorized,
        created_by, updated_by, created_at, updated_at, version
    )
    SELECT
        relationship_row.target_link_id,
        relationship_row.school_id,
        relationship_row.student_id,
        relationship_row.target_guardian_id,
        relationship_row.relationship,
        relationship_row.relationship = 'FATHER'
            OR NOT EXISTS (
                SELECT 1 FROM relationships father
                WHERE father.student_id = relationship_row.student_id
                  AND father.relationship = 'FATHER'
            ),
        false, false, false, false,
        NULL, NULL, transaction_timestamp(), transaction_timestamp(), 0
    FROM relationships relationship_row;

    INSERT INTO student.guardian_safe_create_repair_actions (
        run_id, student_id, school_id, relationship, guardian_id, link_id,
        field_count, action_sha256
    )
    SELECT
        v_run_id,
        student_id,
        school_id,
        relationship,
        target_guardian_id,
        target_link_id,
        count(*)::integer,
        encode(sha256(convert_to(
            jsonb_agg(fingerprint_record ORDER BY field_name)::text,
            'UTF8'
        )), 'hex')
    FROM guardian_safe_create_repair_stage
    WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT'
    GROUP BY student_id, school_id, relationship, target_guardian_id, target_link_id;

    INSERT INTO tenant_school.outbox_events (
        event_key, event_type, aggregate_type, aggregate_id, school_id, payload
    )
    SELECT
        v_run_id || ':guardian:' || target_guardian_id,
        'student.guardian.upserted.v1',
        'student',
        student_id::text,
        school_id,
        jsonb_build_object(
            'guardianId', target_guardian_id,
            'studentId', student_id,
            'relationship', relationship
        )
    FROM (
        SELECT DISTINCT student_id, school_id, relationship, target_guardian_id
        FROM guardian_safe_create_repair_stage
        WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT'
    ) relationship_row;

    WITH invalidated AS (
        UPDATE student.student_review_items item
        SET verified_full_name = false,
            verified_admission_no = false,
            verified_class_section = false,
            verified_roll_no = false,
            verified_father_name = false,
            verified_father_contact = false,
            verified_address = false,
            verified_blood_group = false,
            current_full_name = student_row.full_name,
            status = 'PENDING',
            correction_requested = false,
            correction_notes = NULL,
            suggested_full_name = NULL,
            completed_at = NULL,
            updated_at = now()
        FROM student.student_review_campaigns campaign,
             student.students student_row,
             (SELECT DISTINCT student_id
              FROM guardian_safe_create_repair_stage
              WHERE eligibility_bucket = 'SAFE_CREATE_UNLINKED_STUDENT') repaired
        WHERE item.campaign_id = campaign.id
          AND item.student_id = student_row.id
          AND item.student_id = repaired.student_id
          AND campaign.review_type = 'PROFILE_VERIFICATION'
          AND campaign.status = 'ACTIVE'
        RETURNING item.id, item.school_id, item.campaign_id, item.status
    )
    INSERT INTO tenant_school.outbox_events (
        event_key, event_type, aggregate_type, aggregate_id, school_id, payload
    )
    SELECT
        v_run_id || ':review:' || id,
        'student-review-item.upserted.v1',
        'StudentReviewItem',
        id,
        school_id,
        jsonb_build_object(
            'id', id,
            'schoolId', school_id,
            'campaignId', campaign_id,
            'status', status
        )
    FROM invalidated;

    RETURN jsonb_build_object(
        'status', 'COMPLETED',
        'runId', v_run_id,
        'contractVersion', v_contract_version,
        'contractDigest', v_contract_digest,
        'planSha256', v_plan_sha256,
        'students', v_students,
        'relationships', v_relationships,
        'fields', v_fields
    );
END
$$;

REVOKE ALL ON FUNCTION student.execute_guardian_repair_v1(
    TEXT, INTEGER, INTEGER, INTEGER, TEXT, TEXT, TEXT, TEXT, TEXT
)
    FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        REVOKE ALL ON TABLE student.guardian_safe_create_repair_runs FROM app_rt;
        REVOKE ALL ON TABLE student.guardian_safe_create_repair_actions FROM app_rt;
        REVOKE ALL ON FUNCTION student.execute_guardian_repair_v1(
            TEXT, INTEGER, INTEGER, INTEGER, TEXT, TEXT, TEXT, TEXT, TEXT
        )
            FROM app_rt;
    END IF;
END
$$;
