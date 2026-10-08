# Read-only physical restore-target verifier

This source tool closes a connection-attestation gap in the earlier plan-only restore helper. It does not execute erasure replay, verify backup lineage, authorize restoration, or resume delivery. No Cloud SQL clone, control epoch, IAM grant, or verifier account was provisioned for this work.

The read-only inventory captured at 2026-10-07T17:31:21Z contained only `custoking-db-dev`; there was no existing nonce restore target. Live physical clone verification therefore remains unavailable. The local test results below must not be described as a live full restore.

## Connection boundary

`scripts/security/verify-dev-restore-target.py` accepts only project `custoking-dev`, region `asia-south2`, database `custoking_dev`, and target names matching `custoking-dev-security-restore-[14 UTC digits]-[8 lowercase hexadecimal digits]`. It refuses the primary source, public or mixed addresses, non-running instances, equal source/target addresses, and equal source/target CA fingerprints. Addresses come from authenticated `gcloud` Admin API reads, never caller-supplied connection evidence.

Only `GOOGLE_MANAGED_INTERNAL_CA` is supported. The instance description and independently listed CA inventory must agree on exactly one certificate. Shared CA, rotated/multiple CA, and unsupported configurations fail closed. PostgreSQL uses `sslmode=verify-ca` with only the exact clone CA in a temporary trust file. The source and target metadata/CA inventory are fetched again after the catalog query; changes invalidate the result. Google documents that verifying the CA also verifies instance identity in its unique per-instance CA mode; shared CA mode additionally requires hostname verification and is deliberately unsupported here. [Cloud SQL certificate authority modes](https://docs.cloud.google.com/sql/docs/postgres/authorize-ssl).

Physical TLS identity is separate from approved snapshot/source lineage. A matching nonce name and unique CA do not establish that the clone was restored from the approved source or point in time. The verifier always emits `sourceLineageVerified=false`, `restorationReady=false`, and `deliveryResume=false`. The Cloud SQL instance/operation records alone are not treated as an approved clone request or restore manifest. [Instance resource](https://docs.cloud.google.com/sql/docs/postgres/admin-api/rest/v1beta4/instances), [operation resource](https://docs.cloud.google.com/sql/docs/postgres/admin-api/rest/v1beta4/operations).

The connection explicitly disables GSSAPI encryption and requires TLS 1.2 or newer. This matters because libpq can otherwise prefer GSS encryption regardless of `sslmode`, bypassing the intended certificate-based identity check. Tests set inherited `PGGSSENCMODE=require` and still require correct-CA success and wrong-CA rejection. [PostgreSQL connection parameters](https://www.postgresql.org/docs/current/libpq-connect.html).

## Catalog-only credential and selected checks

The fixed login role is `ims_restore_verify`. An independently approved isolated-clone setup must provision its credential; this tool never creates roles and must never receive the primary owner password. The credential is accepted only through `IMS_RESTORE_VERIFY_PASSWORD`, stays in process memory, and is not included in command arguments, evidence, or diagnostics. Missing credentials or a missing role reject connection verification.

The role must have no superuser, bypass-RLS, replication, role/database creation, membership, relation/schema/function/database ownership, service-table column/DML privileges, service-schema creation, or database CREATE/TEMP authority. A clone setup must account for PostgreSQL's PUBLIC TEMP grant; the verifier rejects effective TEMP privilege, rather than changing the database. System-catalog access and CONNECT suffice. Privilege checks resolve relation OIDs through system catalogs, so business-schema USAGE or SELECT grants are unnecessary.

SQL is fixed: `BEGIN READ ONLY`, five bounded catalog queries, and `ROLLBACK`. The connection explicitly sets `search_path=pg_catalog`, preventing restored role/database defaults from selecting shadow catalog objects or privilege-check functions. There is no user SQL, business row access, or user-supplied student/school identifier. The selected checks are:

- Twelve migration-bound erasure, reservation, receipt, delivery-report/result, suppression and unverified-assertion trigger function bodies, expected table, row/before/after/event shape and payload-column restriction, enabled state, same-schema function identity, absence of a WHEN condition, and no security-definer or per-function setting override.
- The shared `reporting.safe_event_json` helper's source body, text-to-jsonb signature, immutable volatility, PL/pgSQL language, invoker privilege and absence of per-function settings or overloads.
- RLS enabled, FORCE enabled, and at least one policy on `student.students` and `student.erasure_journal_receipts`. This checks catalog state and policy presence, not the semantic equivalence of every tenant policy.
- `ims_school_core_rt` has no superuser, bypass-RLS, replication, role/database creation, membership, or relation/schema/function/database ownership; incarnation INSERT, receipt UPDATE/DELETE/TRUNCATE, and photo-cleanup DELETE/TRUNCATE are denied. The startup role guard does not reject INHERIT alone when there is no membership; this verifier follows that rule instead of inventing a new INHERIT requirement.
- V18's `generic_unknown_report_assertions` has FORCE RLS and a named tenant policy; effective UPDATE (including column grants), DELETE/TRUNCATE and INSERT of `assertion_kind`/`received_at` are denied for both `app_rt` and `ims_platform_rt`. Policy presence does not prove every policy's semantics or a full runtime privilege inventory.

V39 protects incarnation UPDATE with an immutable trigger; it does not promise a revoked UPDATE column privilege. The photo cleanup queue and platform tombstones are machine records whose current migrations do not enable RLS; this tool does not invent a FORCE requirement for them. The selected checks include specific provider-report fences but do not cover every service fence, Flyway histories/checksums/callbacks, restored inbox contents, or exhaustive erasure journal coverage.

Metadata results are capped at 2 MiB; certificate text is bounded and exactly one PEM certificate is accepted. The trigger query caps output at thirteen rows and requires exactly twelve selected rows, with bounded function text. The helper query rejects extra overloads. Metadata commands have at most 20 seconds each, catalog connection has a 20-second process deadline including TLS/authentication, and the overall workflow has a 150-second budget plus bounded process cleanup. Windows metadata children run inside a native kill-on-close Job Object attached before descendant creation; POSIX commands use a private process group. Errors are constant and omit provider/SQL stderr, host details, credentials and certificate contents.

Default execution reads metadata only. Explicit `--connect-read-only` requests the selected catalog checks; it is not restoration approval:

```text
python scripts/security/verify-dev-restore-target.py --target <approved exact nonce clone>
python scripts/security/verify-dev-restore-target.py --target <approved exact nonce clone> --connect-read-only
```

Do not run the second command until the target's independent isolation/lineage approval and its dedicated credential exist. Neither command provisions a clone or changes database/control state.

## Local verification

```text
python -O -m unittest scripts.tests.dev_restore_target_verifier_test scripts.tests.dev_restore_target_verifier_postgres_test scripts.tests.dev_erasure_coverage_test -v
```

The Docker fixture applies all twelve repository migration SQL chains transactionally under a non-superuser owner, in service/schema order, then uses a distinct catalog-only role and locally generated CA/server certificates. It runs actual TLS connections and rejects the wrong CA, disabled trigger, changed function, missing FORCE, unsafe terminal DELETE/incarnation INSERT grants, verifier business SELECT, and owner membership. A canonical-module spawn test accepts the PostgreSQL SSL request and observes the real child's TLS ClientHello before stalling; a sub-second whole-call deadline must terminate that child and close both parent pipe endpoints. The controlled metadata subprocess tests also exercise a real descendant holding the output pipe and the byte cap under optimized Python, so safety guards do not depend on `assert`.

These are local migration SQL and selected catalog/TLS tests. They do not run Flyway's history bookkeeping or grant callbacks, a Cloud SQL backup/PITR operation, GCS replay, application startup, multi-service delivery fencing, or a governed restore/cutover. Existing source/epoch coverage, legacy lineage, external erasure replay, backup/provider physical purge, and owner-approved retention/RPO/RTO remain separate acceptance requirements.
