# Fresh precommit-journal dev acceptance preparation

Prepared 2026-10-07; **not executed against dev**. No cloud resources, IAM grants,
provider calls, or fixtures were created by preparation. Repository search found no
references to the reserved IDs; that is not a live collision result.

The Python helper `scripts/security/prepare-dev-precommit-journal-acceptance.py`
provides default offline preparation plus explicit `--apply-dev --mode seed|verify|cleanup`.
The separate browser runner is `scripts/security/run-dev-journal-delete.mjs`.

Scope is school **990008101**, SCHOOL_ADMIN **990008201**, student **990008301**.
Use one fresh 12-character lowercase hex nonce. Marker is
`SEC-JOURNAL-DEV-20261007-<nonce>`; admission number appends `-STUDENT`.
Class/year/section IDs are marker strings ending `-CLASS`, `-YEAR`, `-SECTION`
(their schema keys are strings, not the initially proposed numeric IDs).
No old 990007 students, erased identities or tombstones are reused.

## Reviewed plan and release boundary

Plan JSON requires `project=custoking-dev`, `region=asia-south2`, `nonce`, exact
`marker`, `ids={schoolId:990008101,userId:990008201,studentId:990008301}`, and
`sourceSha` containing the exact successful new release's 40-character source SHA.
`services` is a map of these exact Cloud Run names to `{revision,image}`:

- `custoking-school-core-service-dev`
- `custoking-identity-service-dev`
- `custoking-frontend-dev`
- `custoking-api-gateway-dev`

Images must be their corresponding exact dev Artifact Registry repositories with
`@sha256:` digests. Extra journal environment keys are rejected before any readback. Include exactly seven journal environment variables in
`journalEnvironment`: `STUDENT_ERASURE_JOURNAL_ENABLED`, `...PROJECT_ID`,
`...BUCKET`, `...SOURCE_LINEAGE_ID`, `...RESTORE_EPOCH`, `...EPOCH_GENERATION`,
`...EPOCH_SHA256`. Enabled must be `true`, project `custoking-dev`, bucket
`custoking-dev-erasure-journal`; control generation and hash must be pinned.

All apply modes require `--release-evidence FILE --release-evidence-sha256 HASH`.
This is an operator-reviewed immutable successful release readback using the
existing `sourceHeadSha/status/conclusion/services` proof schema, not an arbitrary
self-attestation from the browser. The helper verifies exact source/revisions/images
against this pinned file, then independently reads Cloud Run services and revisions:
Ready, latest created=latest ready=expected, exactly 100% untagged traffic, exact image,
exact journal configuration, and dedicated `ims_school_core_rt`. Root supplies the
reviewed file/hash after deployment. A local file hash alone is not GitHub provenance.

## Execution sequence after root releases the deployment hold

1. Generate/review the exact plan. Default `--mode prepare --plan PLAN --out NEWFILE`
   makes no cloud calls and emits only the read-only collision SQL.
2. Run `--apply-dev --mode seed --plan PLAN --release-evidence RELEASE
   --release-evidence-sha256 SHA --out NEW-SEED-PROOF`. Read-only owner collision
   preflight includes source/account/school, SQL receipt, photo queue, projection,
   tombstone and marker parents. The seed repeats collision checks in a transaction
   with bounded statement/lock timeouts and an advisory lock. Only one STUDENTS
   entitlement and one isolated non-contact student are inserted. No ON CONFLICT
   or account reuse. Password is random, bcrypt rounds 12; SQL contains only its hash.
3. The private file is `tmp/dev-journal-<nonce>-private.json`, ignored by Git. It
   supplies the root browser's normal login, fresh software CTAP2 enrollment/assertion,
   and confirmed application DELETE. It includes original server-generated incarnation,
   expected source SHA and guarded-ready marker. Tokens remain browser memory only.
   Run the browser helper with `--apply-dev --private-file tmp/dev-journal-...-private.json`.
4. Run Python `--apply-dev --mode verify` with the same plan/release flags and
   `--browser-proof tmp/dev-journal-browser-proof.json`. It requires the bound actual
   application 200 DELETE proof, then reads SQL receipt and checks source/child absence,
   original incarnation, and exact runtime ACL denials: incarnation INSERT, receipt
   DELETE and photo-queue DELETE all false. It reads the exact GCS object generation,
   capped at 4096 bytes, recomputes canonical intent ID, Java-compatible operation UUID,
   object path and content hash, and matches all SQL receipt fields. Tombstone presence
   is recorded as an observation, not required or claimed if asynchronous work is pending.
5. Run `--apply-dev --mode cleanup`. Exact owner checks and zero source rows are
   mandatory. Actor is disabled and sessions/assignments revoked before marker parent
   removal. Fresh normal login must return 401. Receipts, tombstones, account disable
   record, passkey/security audits and external intents are retained. Private credentials
   are removed only after successful cleanup and their absence is verified.

Every mode needs a fresh output path (no overwrite). Owner job transport is reused
from `final-live-fixture-acceptance.py`: fixed private dev SQL endpoint/database,
immutable PostgreSQL image, migration SA, referenced owner secret, one task,
retry zero, 60-second job deadline; exact nonce job deletion plus independent NOT_FOUND
check occurs in finally. The existing job cleanup aggregate is
`tmp/security-final-owned-jobs-cleanup.json`; curate only this run's newly added names.
Cloud commands have finite deadlines and disk-spooled response ceilings; failures
withhold CLI output. An ambiguous seed preserves private recovery information.
Do not rerun seed with another nonce: fixed IDs intentionally block reuse.

## Verification performed during preparation and remaining scope

`python -O -m unittest scripts.tests.dev_precommit_journal_acceptance_test
scripts.tests.dev_precommit_journal_acceptance_postgres_test -v`: **12 tests passed**
(11 offline controls; 1 real PostgreSQL 16 minimal schema transaction rehearsal).
The PostgreSQL test verifies generated seed SQL, collision rejection, disable/revoke,
parent cleanup and retained receipt. It does not run application migrations, browser
ceremonies, GCS, actual runtime IAM or application deletion. Its owned local container
was independently absent after completion. Node syntax check passed for root's runner.
No `assert` security guards are used; Python optimization cannot disable admission.

This first live scope uploads no photo and seeds no guardians/contact destinations.
It does **not** prove generation-pinned physical photo deletion, provider erasure,
backup purge, broker delivery, hardware-backed authenticators or full restored-source
reconciliation. It can prove one application's successful precommit journal + SQL
receipt after the real deployment only when the described live checks actually run.
Do not label this preparation as dev acceptance. Do not auto-resume delivery or delete
immutable intent/receipt/tombstone/audit records during cleanup.
