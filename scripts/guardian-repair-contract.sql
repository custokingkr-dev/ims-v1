-- Capability-only entry point for the reviewed guardian repair implementation.
-- The database function is installed separately through the normal reviewed release path.
-- This contract must remain a single invocation: the runner pins its canonical SHA-256.
SELECT student.execute_guardian_repair_v1(
    :'expected_plan_sha256',
    :expected_students::integer,
    :expected_relationships::integer,
    :expected_fields::integer,
    :'expected_contract_digest',
    :'approval_reference',
    :'source_revision',
    :'runner_payload_sha256',
    :'operator_job_name'
);
