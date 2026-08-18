# Custoking Split-Project Migration Data-Integrity Plan

**Prepared:** 2026-08-14 IST
**Source:** mixed-environment project `custoking`
**Destinations:** `custoking-dev` and `custoking-prod`
**Runbook:** `GCP-SPLIT-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md`
**Status:** mandatory GO/NO-GO companion; contains no execution approval

This plan proves that each destination has the same intended database records, object content, schema,
durable-event state, and external Drive mappings as its stable source snapshot. Counts alone do not prove
integrity. Raw student data, object names, school UUIDs, folder IDs, credentials, and photos must not enter
Git or the general change ticket.

## 1. Current evidence and gaps

Read-only discovery on 13-14 August 2026 established:

- production uses PostgreSQL 16 on `custoking-db-prod`, with automated backups, PITR, and deletion
  protection; development uses PostgreSQL 16 on `custoking-db-dev`, with backups/PITR disabled;
- the existing recovery bucket contains old July exports, not a current cutover recovery point;
- source Cloud Storage inventory has checksums, but no freeze-time source-to-destination manifest exists;
- database student-photo references have not yet been fully reconciled to Cloud Storage and Google Drive;
- Drive is outside the GCP project and must be accessed by newly created destination identities;
- production reporting lacks a DLQ, and production notifications have a topic but no subscription; and
- as of 14 August, the operating account cannot see either approved destination project.
  **Superseded 18 August 2026:** both destination projects now exist and are `ACTIVE`, but they sit under a
  different organization than the source and are billed to an account the operating account cannot access.
  See `GCP-MIGRATION-PREFLIGHT-EVIDENCE-2026-08-18.md`.

Therefore migration is NO-GO until every section 9 gate has a named owner, timestamp, restricted evidence
reference, and independent verifier.

## 2. Environment isolation rules

Run every procedure separately for development and production. Never combine their exports, buckets,
manifests, secrets, or destination databases.

- development source data maps only to `custoking-dev`;
- production source data maps only to `custoking-prod`;
- destination databases/buckets begin empty or are reset using an approved, verified procedure before final
  import;
- source data is never deleted by a transfer job;
- source and destination publishers/consumers never process the same bounded event set concurrently; and
- production cutover begins only after the development copy, integrity comparison, and rollback rehearsal
  are accepted.

## 3. Snapshot and release contract

For each environment record in restricted evidence:

1. source project number, instance, database, bucket, UTC/IST snapshot boundary, operator, and verifier;
2. exact service image digests and source revision names;
3. ordered Flyway versions, descriptions, types, script names, checksums, success flags, and installed rank
   for every owned schema;
4. database backup/PITR point, stable-clone ID, export operation ID, export object size/CRC32C/generation;
5. source and destination object-manifest hashes; and
6. source/destination project, region, database version/flags, and application configuration version.

Do not compare a query taken from a writable source at a different time with an export. During rehearsal,
restore the selected source backup/PITR point to isolated clone A, compute its full ledger, export clone A,
import into an empty destination validation database, and compare those stable states. At final production
cutover, enforce the approved write freeze, drain or bound events, create the final stable export, and compare
the frozen source ledger to the destination before destination writes are allowed.

The migration itself must not silently upgrade schema or application code. Any schema/release change is a
separate reviewed deployment.

## 4. Database integrity ledger

All audit SQL uses `psql -v ON_ERROR_STOP=1`. A container or script exit code of zero after a failed query is
not evidence.

### 4.1 Structural ledger

Capture hashes or ordered metadata for:

- PostgreSQL version and required extensions;
- schemas, tables, columns, types, nullability, defaults, identity/sequence ownership;
- primary/unique/check/foreign-key constraints and validation state;
- indexes and validity/readiness flags;
- row-level-security enable/force state and policies;
- triggers, functions, views, materialized views, and privileges relevant to application roles; and
- Flyway history, including failed or checksum-mismatched entries.

Source/export and destination must match the approved release. Documented destination-only infrastructure
metadata is allowed; unexplained application-schema drift is NO-GO.

### 4.2 Table ledger

For every application table, compute on stable databases:

- exact row count;
- primary-key minimum/maximum where meaningful;
- maximum created/updated/occurred timestamp where present;
- deterministic SHA-256 of canonical row JSON ordered by the complete primary key; and
- schema hash from ordered column/type/nullability/default and constraint metadata.

Compute full-content fingerprints inside the database and return only digests. Never stream canonical rows
into logs or evidence. If a table has no stable key, define and review a deterministic order before execution.

### 4.3 Business invariants

Required result is zero unless a named owner records a pre-existing accepted exception before the snapshot:

- student school/class/section/academic-year references that do not match tenant-school ownership;
- duplicate `(school_id, admission_no)` or null/duplicate immutable `school_uid`;
- enrollment, guardian, consent, promotion, review, import, or photo-import references to missing parents;
- attendance student/register/school mismatches or daily aggregate inconsistencies;
- fee/payment student, band, assignment, or school mismatches;
- workflow, firefighting, notification, audit, identity, and reporting tenant references to missing schools;
- reporting rows retained for a permanently deleted student where the tombstone contract requires removal;
- tombstones with stale student dimensions/facts/contributions/notifications;
- invalid active role assignments, invalid indexes/unvalidated constraints, or sequences behind table maxima.

Also capture counts grouped by school ID and status for students, imports, attendance, fees/payments,
workflows, firefighting, reporting/notification inboxes, delivery attempts, and every outbox. Evidence may
contain IDs and counts, not names or student fields.

### 4.4 Durable events

For every outbox capture published/pending/dead-letter totals, oldest pending age, maximum attempts, stale
pending rows, and duplicate non-null event keys. For inboxes capture counts by status, stale processing,
retry-due failures, terminal/dead-letter state, oldest age, and maximum attempts.

Resolve or accept unexpected dead letters. Drain pending work to zero or record the exact bounded event IDs
as restricted hashes. After destination activation prove each bounded event was processed once or remains
safely retryable. Pub/Sub backlog is supporting evidence, not a substitute for database event ledgers.

## 5. Student photos and object integrity

### 5.1 Source manifest

Build the expected object-key set from active internal student photo URLs plus retained prior/final/source and
batch workbook keys required by policy. For each source live object record environment, logical key,
generation, size, CRC32C, content type, updated time, and retention class. Record relevant recoverable or
soft-deleted generations separately.

Mandatory source invariants:

1. every active internal student photo exists in the correct source environment bucket;
2. the path uses `schools/<school_uid>/students/<student_id>/photos/` and the UUID matches the database;
3. the current photo is not in a temporary/import lifecycle prefix;
4. no key resolves to the other environment's bucket; and
5. orphan candidates are reported but never deleted during migration.

### 5.2 Copy and destination comparison

Copy without source deletion. Storage Transfer Service can operate across projects but its agent or approved
user-managed identity must receive explicit read/write access. Use a least-privilege transfer identity and
record every temporary grant.

For each destination live object compare logical key, size, CRC32C, and content type. Record the new
destination generation independently: a copied object is a new object version, so source/destination
generation-number equality is neither required nor expected. Missing keys or size/checksum mismatches are
NO-GO. Preserve historical/soft-deleted source generations according to the approved retention/rollback
policy; do not silently discard them.

### 5.3 Cloud SQL to Google Drive

Without exposing names or identifiers in public evidence, verify:

- every school has one immutable non-null unique `school_uid`;
- database-managed Drive records and folder `appProperties` use that UUID and intended academic year/type;
- configured roots are accessible to the new environment runtime authorization and future operator;
- active batch folder/file IDs resolve and workbook/image size/checksum metadata matches the database; and
- OAuth consent/user-type restrictions permit the intended destination authorization flow.

Drive content is not copied by the GCP migration. A failed Drive canary is an access/integration failure; do
not restore Cloud SQL or Storage to address it.

## 6. Recovery and import rehearsal

For each environment:

1. create a fresh source backup/PITR point; development needs an on-demand backup because automatic recovery
   is disabled;
2. restore it to isolated clone A, compute the complete ledger, then export from clone A to the restricted
   recovery bucket using a timestamped non-overwriting object;
3. record backup, clone, export operation, size, CRC32C, generation, timestamps, and temporary IAM grants;
4. create the destination database with approved flags/users/roles and import the exact export;
5. compute the complete destination ledger and compare every structural/table/business/event result;
6. run exact approved application images in an isolated destination context and prove login/authorization,
   tenant isolation, school/student/photo reads, attendance/fee reads, and no external notification;
7. measure RPO, clone readiness, export/import, validation, and end-to-end RTO; and
8. retain recovery artifacts and source access through stabilization. Remove temporary clones/grants only
   after two-person evidence approval.

Run validation resources sequentially and stop/delete them promptly after evidence approval; do not enable HA
or minimum instances for a temporary rehearsal.

## 7. Final cutover comparison

### Before final export

- enforce the reviewed write freeze; stop imports, onboarding, writers, relays, Scheduler, and consumers;
- capture source database, object, Drive, BigQuery billing-history, secret-metadata, and durable-event ledgers;
- drain or exactly bound events and record the snapshot/export timestamps;
- confirm no source lifecycle action will delete an object during manifest/copy; and
- keep source resources and rollback identities intact.

### After import/copy, before destination writes

- compare the full destination database ledger to the stable source/export ledger;
- compare every destination live object by logical key, size, CRC32C, and content type;
- prove secret references resolve under each new service identity without exposing payloads;
- verify new billing export while preserving source-project history;
- verify Pub/Sub topology with consumers disabled, then enable only the destination event path;
- run tenant/student/photo reads and approved bounded transactions; and
- run Drive/OAuth, tracing, logging, monitoring, and primary/backup alert-delivery canaries.

### Stabilization

Re-run the complete ledger after queues settle. No orphan, invalid constraint/index, failed Flyway row,
missing photo, unexpected deletion, dead-letter increase, or unexplained count/fingerprint difference is
accepted. Keep source data, recovery artifacts, and rollback access until formal acceptance.

## 8. Failure handling

| Symptom | Classification | Required action |
| --- | --- | --- |
| destination data exists but access is denied | IAM/org policy/VPC/identity | keep source authoritative; repair narrow destination access and retest |
| source/export and destination DB ledger differ | copy/recovery integrity failure | NO-GO; preserve evidence, correct mechanism, repeat from stable snapshot |
| destination object missing or checksum/size differs | object integrity failure | NO-GO; recopy exact object; never bulk overwrite unexplained differences |
| database photo key has no source live/recoverable object | source integrity incident | freeze affected workflow; investigate audit/retention and approved recovery |
| Cloud Run cannot sign destination photo URLs | destination service-account/signBlob IAM | correct least-privilege identity binding and retest |
| Drive token/folder access fails | OAuth/sharing/identity integration | keep GCP data unchanged; repair authorization/sharing |
| destination count changes before activation | unplanned writer/consumer | stop destination processing; identify exact write/event window; repeat comparison |
| rows decrease/mutate without approved audit/event evidence | possible loss/corruption | freeze, preserve evidence, investigate backup/PITR; restore only with incident approval |
| Pub/Sub backlog is zero but outbox is stale | publisher/relay failure | diagnose relay; backlog metric does not close the event gate |

Rollback follows the split-project runbook: stop destination processing, preserve its write window, and route
back to the intact source. Never discard destination-only accepted writes or replace source blindly.

## 9. Mandatory GO/NO-GO gates

- [ ] Both destination projects, billing, policies, quotas, operators, and cross-project permissions exist.
- [ ] Fresh dev/prod backup IDs and logical export size/checksum/generation are recorded.
- [ ] Rehearsal exports import independently and complete database ledgers match.
- [ ] Final source/export and destination structural/table fingerprints match exactly.
- [ ] Flyway histories/checksums match the approved environment release; no failed row exists.
- [ ] Business orphan/mismatch queries have zero unaccepted failures.
- [ ] Outbox/inbox/Pub/Sub event set is drained or exactly bounded and single-consumer ownership is proven.
- [ ] Source/recoverable object manifests exist and every destination live object matches key/size/CRC32C.
- [ ] Every active internal photo reference resolves to the correct destination object and school UUID path.
- [ ] Retention/lifecycle treatment of historical and soft-deleted objects is approved and evidenced.
- [ ] Drive mappings/app properties and destination OAuth access pass a non-mutating canary.
- [ ] Billing history is retained and the new per-project export is verified.
- [ ] Every deployed secret reference resolves under its destination service identity without payload leakage.
- [ ] Login, tenant isolation, student detail/photo, and approved bounded transaction tests pass.
- [ ] Source rollback routing/data retention, destination-write reconciliation authority, RPO/RTO, and artifact
  retention are approved.

Any unchecked item is NO-GO. Schedule pressure is not a data-integrity exception.

## 10. Evidence handling and cost

- Store ledgers, object manifests, IAM details, folder IDs, UUIDs, private IPs, billing IDs, and restore metadata
  only in the approved restricted evidence location.
- Hash each evidence file with SHA-256 and produce a signed/controlled manifest.
- Never store secret payloads, passwords, OAuth tokens, student fields, photos, or raw exports in Git.
- Label every file with environment, source snapshot/export time, tool/script commit, operator, and verifier.
- Use minimum-sized sequential validation resources; record gross cost; stop/delete them after approval.
- Preserve recovery artifacts through the stabilization window; cleanup is a separate witnessed action.

## 11. Official references

- [Cloud SQL PostgreSQL export/import](https://cloud.google.com/sql/docs/postgres/import-export/import-export-sql)
- [Cloud SQL PostgreSQL restore](https://cloud.google.com/sql/docs/postgres/backup-recovery/restore)
- [Storage Transfer Service cross-project transfers](https://cloud.google.com/storage-transfer/docs/create-transfers)
- [Cloud Storage checksum validation](https://cloud.google.com/storage/docs/data-validation)
- [Artifact Registry cross-project image copy](https://cloud.google.com/artifact-registry/docs/docker/copy-images)
- [Workload Identity Federation for deployment pipelines](https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines)
- [Pub/Sub replay, snapshots, and retention](https://cloud.google.com/pubsub/docs/replay-overview)
- [Cloud Billing export to BigQuery](https://cloud.google.com/billing/docs/how-to/export-data-bigquery-setup)
