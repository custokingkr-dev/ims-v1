-- Repin the owner-only guardian safe-create capability to the exact aggregate
-- production evidence captured after V25/V26 were deployed. The contract and
-- reviewed counts are unchanged; only the contract-aware safe-plan fingerprint
-- is replaced. pg_get_functiondef keeps the executable body byte-for-byte
-- equivalent apart from the single reviewed digest and fails closed on drift.

DO $$
DECLARE
    c_previous_plan_sha256 CONSTANT TEXT :=
        'fe0425a615d15a1444cd8cbd9b3bbe64a5360a6b8a3a9f33e5b6110be7684492';
    c_reviewed_plan_sha256 CONSTANT TEXT :=
        '05743ca971b91f82879e13383258bfed3f3c131d6130ab4d5bf1996da6f8e6e1';
    v_definition TEXT;
    v_occurrences INTEGER;
BEGIN
    SELECT pg_get_functiondef(
        'student.execute_guardian_repair_v1(text,integer,integer,integer,text,text,text,text,text)'
            ::regprocedure
    )
    INTO v_definition;

    v_occurrences := (
        length(v_definition) - length(replace(v_definition, c_previous_plan_sha256, ''))
    ) / length(c_previous_plan_sha256);
    IF v_occurrences <> 1 THEN
        RAISE EXCEPTION
            'guardian repair definition drifted: expected exactly one previous plan pin, found %',
            v_occurrences;
    END IF;
    IF position(c_reviewed_plan_sha256 IN v_definition) > 0 THEN
        RAISE EXCEPTION 'guardian repair definition already contains the reviewed plan pin';
    END IF;

    v_definition := replace(
        v_definition,
        c_previous_plan_sha256,
        c_reviewed_plan_sha256
    );
    EXECUTE v_definition;

    SELECT pg_get_functiondef(
        'student.execute_guardian_repair_v1(text,integer,integer,integer,text,text,text,text,text)'
            ::regprocedure
    )
    INTO v_definition;
    IF position(c_previous_plan_sha256 IN v_definition) > 0
       OR position(c_reviewed_plan_sha256 IN v_definition) = 0 THEN
        RAISE EXCEPTION 'guardian repair reviewed plan pin was not applied exactly';
    END IF;
END
$$;
