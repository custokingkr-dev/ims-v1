# Superseded: In-Place GCP Move Data-Integrity Analysis

> **DO NOT EXECUTE THIS PLAN.** It assumes the existing `custoking` project is moved in place. The approved
> design now copies development into `custoking-dev` and production into `custoking-prod`. The authoritative
> data procedure is `GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md`; it supersedes all comparison,
> cutover, rollback, and GO/NO-GO guidance below.

**Planned cutover:** Sunday, 16 August 2026 (IST)
**Evidence timestamp:** 13 August 2026
**Status:** Required companion to `GCP-PROJECT-ACCOUNT-MIGRATION-RUNBOOK-2026-08-16.md`; not execution approval
**Scope:** Production and development data, recoverability, cross-system consistency, and proof that an in-place organization move did not lose or corrupt data

## 1. Data-safety conclusion

An in-place Resource Manager move is the lowest-data-risk option. Google documents it as a metadata operation: the project ID and number, databases, buckets, and project-owned resources stay in place and remain online. The main cutover risk is therefore loss of **access** caused by destination IAM, organization policy, quota, OAuth, or service-account restrictions—not a Google-managed copy that could omit rows or objects.

That property does not remove the need for recovery and integrity evidence. The current state is **not yet sufficient to certify “no data issue”**:

1. Production has good Cloud SQL automated-backup/PITR protection, but development does not.
2. Existing restore drills prove that Cloud SQL can restore a clone. They do not validate row counts, row digests, cross-schema invariants, student-photo references, or application reads from the restored data.
3. The recovery bucket has only two old database exports from 23 July 2026. They are not a current Sunday recovery point.
4. Cloud Storage has a complete planning-time aggregate, but a restricted freeze-time manifest and post-move comparison have not been produced.
5. The database-to-GCS student-photo reference set has not been reconciled.
6. Google Drive is external to the GCP project. Folder ownership, app properties, checksums, and OAuth behavior have not been verified under the destination operating identity.
7. Production has a notification Pub/Sub topic without a subscription. No deployed producer was found pointing at that topic and the delivery provider is currently `logging`, so active loss is not asserted; nevertheless, publishing to that topic would not be recoverable with the present retention configuration.
8. The exact destination organization/folder, destination billing account, and authorized destination administrators are still unknown. The source export policy is still a hard blocker.

The Sunday change is a **NO-GO** until the mandatory gates in section 9 are evidenced. A backup file existing is not a pass; it must be restorable and its restored data must satisfy the same non-PII integrity ledger as the source.

## 2. Verified data-surface inventory

The values below came from read-only live API/CLI discovery on 13 August 2026. They are a planning baseline, not the final freeze manifest.

### 2.1 Cloud SQL

| Environment | Instance | State/version | Tier/storage | Recovery state | Latest successful backup at discovery |
|---|---|---|---|---|---|
| Production | `custoking-db-prod` | RUNNABLE, PostgreSQL 16, `asia-south2-c` | `db-g1-small`, 10 GB SSD, automatic storage growth | Automated backups enabled, PITR enabled, 14 retained backups, deletion protection enabled | 12 August 2026 21:14:25 UTC; 16 successful backup runs were visible |
| Development | `custoking-db-dev` | RUNNABLE, PostgreSQL 16, `asia-south2-b` | `db-f1-micro`, 15 GB SSD, automatic storage growth | Automated backups disabled, PITR disabled, deletion protection enabled; one old on-demand backup remains | 23 July 2026 21:46:41 UTC |

The production database was approximately 153 MB used at the latest telemetry sample. Development was approximately 1.64 GB used at the latest sample; a prior load-test peak reached approximately 6.13 GB. These figures are capacity signals, not row-integrity evidence.

Existing recovery evidence:

- Production drill on 5 August restored a PITR clone to RUNNABLE in about nine minutes and produced a 2,771,531-byte validation export.
- Development drill on 11 August restored a PITR clone to RUNNABLE in 539.49 seconds and produced a 65,248,345-byte full synthetic-data export. The artifact explicitly records `dataRowsValidated=false`.
- The production workflow deliberately used schema-only export validation. Neither artifact records business-table counts, content digests, referential checks, or application login/read checks against the clone.

Therefore both artifacts are useful recovery-mechanism evidence but cannot close the data-validation gate.

### 2.2 Cloud Storage

All 21 buckets were enumerated using the Storage API, including live and noncurrent object generations. At the discovery timestamp:

- 3,210 live objects.
- 3,289 total object generations.
- 2,804,295,328 bytes across enumerated generations.
- Zero enumerated generations missing a CRC32C value.
- No bucket had a locked retention policy.
- The Terraform-state bucket was the only versioned bucket in the critical set: 2 live objects, 81 generations, and 79 noncurrent generations.
- The critical buckets use uniform bucket-level access and enforced public-access prevention.

Critical planning-time baselines:

| Bucket | Live objects | Generations | Bytes | Protection/configuration note |
|---|---:|---:|---:|---|
| `custoking-student-photos-prod` | 1,767 | 1,767 | 2,362,058,740 | Versioning disabled; seven-day soft delete; public access prevention enforced |
| `custoking-student-photos-dev` | 22 | 22 | 10,300,841 | Versioning disabled; seven-day soft delete; public access prevention enforced |
| `custoking-db-snapshots` | 2 | 2 | 269,352 | Both objects are old 23 July SQL exports; seven-day soft delete |
| `custoking-terraform-state` | 2 | 81 | 10,913,417 | Versioning enabled; seven-day soft delete |

The production photo-bucket live objects classified by path as:

| Class | Objects | Bytes | Interpretation |
|---|---:|---:|---|
| Student-photo-path objects | 898 | 35,818,297 | Path shape alone does not prove an active DB reference; reconcile every object/key |
| Student-import evidence | 736 | 2,307,522,233 | Some batch prefixes are intentionally lifecycle-managed |
| Temporary photo-import evidence | 122 | 18,467,618 | Lifecycle-managed temporary input |
| Other | 11 | 250,592 | Must be classified in restricted evidence before acceptance |

The production bucket has one 14-day Delete rule covering `temporary/photo-imports/` plus three batch-specific import prefixes. At discovery it matched 816 objects and 2,324,632,691 bytes; none was already 14 days old. The dev rule matched four objects and 9,742,323 bytes; none was already age-eligible. Lifecycle deletions can occur independently of the project move, so the post-move comparator must identify them by rule and timestamp rather than report every permitted expiry as migration loss.

Soft-deleted generations are a separate recovery surface and must be manifested separately:

- Production photo bucket: 2 soft-deleted objects, 241,251 bytes.
- Development photo bucket: 9 soft-deleted objects, 42,507 bytes.

Do not commit object names, Drive identifiers, school UUIDs, or manifests to Git. They may expose student or school information.

### 2.3 Secret Manager and deployed references

- 44 secrets, 63 total versions, 48 enabled versions, 6 disabled versions, and 9 destroyed versions.
- Every secret had at least one enabled version at discovery.
- 107 Cloud Run service/job secret references point to 43 distinct existing secrets.
- All 107 deployed references use `latest`; no missing secret reference was found.
- All 107 reference pairs were authorized through project- or secret-level IAM without reading a secret payload.
- Secrets use automatic replication; no CMEK, rotation schedule, expiry, or version aliases were found.

This is a clean configuration baseline. Repeat the same metadata/IAM resolution after the move. Never export payloads to the evidence package.

### 2.4 BigQuery billing history

The US dataset `billing_export` is not empty:

| Table | Rows at discovery | Logical bytes | Last modified shown by BigQuery |
|---|---:|---:|---|
| `cloud_pricing_export` | 1,918,820 | 1,273,553,627 | 11 August 2026 21:47:11 UTC |
| Detailed resource usage table | 171,932 | 131,684,278 | 13 August 2026 13:32:26 UTC |
| Standard usage table | 72,174 | 49,034,561 | 13 August 2026 14:22:37 UTC |

The pricing transfer configuration is owned by the current personal identity and explicitly names the current billing account. A billing-account switch does not retarget that configuration. Historical tables must remain untouched; the destination billing administrator must configure the new account's standard, detailed, and pricing exports and verify new table ingestion. Google states that previously exported data is not automatically backfilled to a new export location.

### 2.5 Pub/Sub durable event state

- 6 topics, 5 subscriptions, no snapshots, and no schemas.
- All five subscription backlogs and oldest-unacked-age metrics were zero throughout the sampled six-hour window.
- No topic has topic-level message retention.
- No subscription retains acknowledged messages.
- The production reporting subscription has no dead-letter topic.
- `ims-notifications-events-v1-prod` has no subscription. No live service outbox configuration was found publishing to it; school-core, billing, and operations currently publish to the reporting topic, and notification delivery is configured to `logging`.

Zero backlog is favorable at the discovery instant, but it is not a replay guarantee. Database outboxes/inboxes are the durable source-of-truth path and must be reconciled. If the product intends to publish notification events in production, create and validate the production push subscription/DLQ through the existing reviewed script before declaring the notification path production-ready. Do not add retention or replay messages during the migration without a separate duplicate-delivery review.

### 2.6 External and operational data surfaces

| Surface | Where it lives | Migration behavior | Required proof |
|---|---|---|---|
| Google Drive school/photo-import folders | Google Drive, outside the GCP resource hierarchy | Does not move with the project | Destination operator/runtime can list both roots; DB `school_uid` mappings equal Drive app properties; intended folder/file counts and checksums match |
| OAuth client/refresh authorization | GCP OAuth configuration plus external Google identity grants | Internal-user behavior can change after an organization move | Record consent-screen user type/scopes; run a non-mutating Drive canary under the post-move operating model |
| MSG91 | External account | Does not move | Ownership/recovery/sender/dry-run state attested and tested without exposing credentials |
| Cloud Logging/compliance logs | Project-owned Logging buckets | Move with project | Sinks, exclusions, buckets, retention, views, and new log arrival match |
| GitHub actions/artifacts/configuration | GitHub, outside GCP | Does not move | Repository/environment access, WIF authentication, workflow protections, and required retained evidence remain available |
| Local school photo/Excel exports | Operator workstation | Does not move | Explicit owner, approved retention, access control, and deletion schedule; never use as cloud recovery evidence |

## 3. Release and database-schema contract

The current production source baseline contains these maximum Flyway versions:

| Service/schema | Production source | Development source |
|---|---:|---:|
| Identity | V5 | V5 |
| Billing | V6 | V6 |
| Workflow | V5 | V5 |
| Firefighting | V11 | V11 |
| Attendance | V8 | V8 |
| Catalog | V7 | V7 |
| Fee | V9 | V9 |
| Student | V17 | V18 |
| Tenant school | V26 | V26 |
| Reporting | V26 | V28 |
| Notification | V10 | V10 |
| Audit | V1 | V1 |

Production services are primarily on source commit `3b47abe41fed`, with platform on `4482ff2a588c`; both contain student V17/reporting V26. Development's verified fix baseline is `08125d2f`, containing student V18/reporting V28. Cloud Run revisions use immutable Artifact Registry digests.

Before freeze, export every schema's `flyway_schema_history` and prove:

- no `success = false` row;
- no missing or out-of-order version relative to that environment's approved image;
- applied checksum equals the migration file in the recorded source commit;
- there is no migration applied to the database that is absent from the approved release;
- schema history is captured before any production promotion decision and recaptured if production is promoted.

Startup logs confirm Flyway runs, and dev diagnostic evidence confirms student V18/reporting V27/V28 behavior. Logs are not a substitute for the database history export. One diagnostic execution also logged SQL errors for a nonexistent `published` column yet exited zero, so all migration-audit SQL must run with `psql -v ON_ERROR_STOP=1` and fail the job on any statement error.

## 4. Database integrity ledger

Generate the same machine-readable, non-PII ledger from each live database, its isolated restored copy, and the post-move live database. Keep the detailed output in restricted evidence. No student names, contacts, addresses, object keys, tokens, or row payloads may appear in the ledger.

### 4.1 Structural invariants

Record and compare:

1. Database name, PostgreSQL version, database size, recovery state, and current UTC timestamp.
2. Schema, table, sequence, materialized-view, extension, and function inventories.
3. Each schema's Flyway version, description, checksum, installation timestamp, and success state.
4. Tables with unvalidated foreign/check constraints: required result is zero unless explicitly accepted.
5. Indexes with `indisvalid = false` or `indisready = false`: required result is zero.
6. Primary-key and unique-constraint definitions.
7. Sequence current values compared with the maximum owned key; no sequence may be behind its table.
8. RLS enabled/forced state, tenant policies, table owners, and runtime grants for all tenant-bearing tables.
9. User/role names and role attributes without password hashes or secret material.
10. Unexpected tables in `public`, especially legacy copies that were supposed to be decommissioned.

### 4.2 Table fingerprints

For every application table, capture:

- exact row count;
- primary-key minimum/maximum when meaningful;
- maximum `created_at`, `updated_at`, `occurred_at`, or equivalent timestamp where present;
- a deterministic SHA-256 fingerprint computed on the isolated restored database, ordered by the full primary key and based on canonical row JSON;
- a schema hash derived from ordered column/type/nullability/default metadata and constraints.

Do not stream canonical row JSON out of the database. Compute and return only the digest. Full-content fingerprints can be expensive and should run on the isolated restore, not the production instance. On live production, use counts, key bounds, schema hashes, and targeted business invariants during the cutover window.

Do not claim that a separately timed query against a writable live database represents the SQL-export snapshot. For an exact comparison without a write freeze, restore the fresh Cloud SQL backup/PITR point to isolated clone A, compute the full ledger there, export clone A, import that export into an empty validation database on the isolated instance, and compare the two stable ledgers. The live source is then tied to the recorded backup/PITR time, and the logical export path is tested without racing production writes. Post-move live counts can legitimately advance because an in-place move leaves traffic online; compare that state using monotonic keys/timestamps, the audit/event window, and the cutover timestamp.

If the business requires byte-for-byte before/after equality, it must approve and enforce a short application write freeze. No general maintenance/read-only switch was found in the codebase, so the runbook must not claim an enforced write freeze unless a separately tested mechanism exists.

### 4.3 Cross-schema business invariants

Required result is zero for each orphan/mismatch query unless a named owner documents an accepted legacy exception:

- `student.students.school_id` absent from `tenant_school.schools`.
- Active students whose `class_id`, `section_id`, or `academic_year_id` does not belong to their school/academic-year structure.
- Duplicate `(school_id, admission_no)` or null/duplicate `school_uid` values.
- `student.student_enrollments`, guardians, consent events, promotion items, review items, import rows, or photo-import rows referencing a missing student/batch/campaign.
- Attendance rows referencing a missing student/daily register, carrying a different school than the student, or disagreeing with the daily present/absent/late/leave/total aggregates.
- Fee assignments/payments referencing a missing student, band, assignment, or carrying a different school from the student.
- Catalog, workflow, firefighting, notification, audit, identity role-assignment, and reporting rows carrying a school ID absent from `tenant_school.schools` where that row type requires a live school.
- Reporting dimensions/facts for a terminally deleted student in an environment that has the V28 tombstone contract.
- Tombstones whose corresponding stale `dim_student`, fee/payment fact, contribution, or notification row still exists.
- Active identity role assignments whose user/role is missing or expired assignment is incorrectly active.
- Foreign keys or checks marked not validated, invalid indexes, and sequences behind table maxima.

Counts must also be grouped by school ID and by status for students, import jobs, attendance, fees/payments, workflows, firefighting, reporting inbox, notification inbox/delivery, and each outbox. Only school IDs and counts belong in evidence; names and student fields do not.

### 4.4 Durable event invariants

For `tenant_school.outbox_events`, `billing.outbox_events`, and `firefighting.outbox_events`, capture:

- total rows;
- published, pending, and dead-lettered counts;
- oldest pending age;
- maximum attempts;
- pending rows older than the documented relay SLO;
- duplicate non-null event keys.

For reporting and notification inboxes, capture counts by status, stale `PROCESSING`, retry-due `FAILED`, terminal/dead-letter state, oldest age, and maximum attempt count.

Before T0, all unexpected dead-letter rows must be resolved or accepted. Pending rows must be drained to zero or a precisely bounded count with event IDs hashed in restricted evidence. Immediately after the move, prove those same events were processed once or remain safely retryable. Pub/Sub's zero-backlog metric is supporting evidence, not the primary ledger.

## 5. Student-photo and Drive reconciliation

Student photos span Cloud SQL, Cloud Storage, and Google Drive. They require a three-way check.

### 5.1 Cloud SQL to Cloud Storage

Build the restricted set of expected object keys from:

- active `student.students.photo_url` values that are internal object keys rather than external HTTP(S) URLs;
- retained `prior_photo_key`, `final_photo_key`, `source_object_key`, and batch `workbook_object_key` fields according to the retention policy;
- active photo-import batch/source evidence.

For each expected key, compare bucket, existence, generation, size, CRC32C, content type, and updated time. Mandatory rules:

1. Every active student's internal `photo_url` exists in the correct environment bucket.
2. Its path begins `schools/<school_uid>/students/<student_id>/photos/` and the school UUID equals the database school's immutable `school_uid`.
3. The current student-photo object is not inside a lifecycle-managed temporary/import prefix.
4. No database key resolves to the other environment's bucket.
5. Every missing expected object is a NO-GO until restored or explicitly proven to be an external/legacy URL.
6. Unreferenced student-photo objects are reported as orphan candidates but are not deleted during migration.

The post-move GCS comparison must include live and soft-deleted generations. Compare `(bucket, object name, generation, size, CRC32C)`. A missing pre-move generation is acceptable only if audit evidence identifies an authorized deletion or an already-matching lifecycle rule; otherwise it is a data-integrity incident.

### 5.2 Cloud SQL to Google Drive

Without exposing folder/file names in public evidence, verify:

- every school has one immutable non-null unique `school_uid`;
- managed Drive folder records have the same school ID/UUID and intended academic year;
- each configured root is accessible by the production/dev runtime authorization and the future operator;
- Drive folder `appProperties` identify the same school UUID and folder type as the database mapping;
- active batches' folder/file IDs resolve, and the workbook/image size/checksum metadata matches database source metadata;
- no destination organization policy or Internal OAuth-user restriction prevents refresh-token or reauthorization behavior;
- the canary is list/metadata-only unless an explicitly approved non-production write/delete test is used.

Drive data is not copied by Resource Manager. A failed Drive canary is an integration-access failure; do not restore Cloud SQL or GCS to address it.

## 6. Recovery procedure that closes the current gap

### 6.1 Freeze-time recovery points

1. Confirm production's latest automated backup is successful and PITR coverage includes the planned cutover time.
2. Create a fresh on-demand production backup close to cutover and retain its ID.
3. Create a fresh on-demand development backup because automated backup/PITR is disabled.
4. Restore each fresh backup/PITR point to isolated clone A, compute its integrity ledger, then create the logical export from that stable clone into the approved restricted recovery bucket. Record object generation, size, CRC32C, creation time, export operation ID, database, source backup/PITR point, and clone instance.
5. Do not overwrite the 23 July exports. Use timestamped, immutable object names and enforce restricted IAM/public-access prevention.
6. Record the Cloud SQL service-agent bucket IAM grant added for export and remove temporary grants only after validation.

### 6.2 Independent restore validation

For each environment:

1. Restore the fresh backup/PITR point to a new isolated clone A. Never target a live database.
2. Deny public access; use private networking and least-privilege temporary IAM.
3. Run the structural ledger, table counts, schema hashes, full table fingerprints, cross-schema invariants, and photo-key extraction on clone A.
4. Export clone A, import that exact export into an empty validation database on the isolated instance, and run the same ledger there. Counts, schema hashes, and full fingerprints must match clone A exactly.
5. Start the approved application images against the restored database in an isolated validation context, or run an equivalent authenticated read-only application validator. Prove login/authorization, tenant isolation, school listing, representative student listing/detail, fee/attendance reads, and photo-key lookup without sending notifications.
6. Capture only non-PII results and hashes. Do not export restored production rows to a workstation.
7. Record RPO (backup/PITR timestamp to cutover), clone readiness time, export/import time, validation time, and cleanup results.
8. Delete the temporary instance and validation objects only after two operators confirm evidence. Preserve the recovery artifacts through the stabilization window.

Use the shortest approved validation window and one isolated instance per environment sequentially where practical; do not enable HA or keep minimum compute running for temporary validation. The data sizes observed are small enough that a controlled sequential rehearsal avoids a long-lived parallel stack. Capture the actual gross cost and delete temporary clones only after evidence approval.

Google recommends exports as additional protection. The full export/import path must be tested because a backup-restore test alone does not prove that the separately retained logical export is usable.

## 7. Cutover comparison

### Before T0

- Record project ID/number, database instance IDs/states, database sizes, latest backup/PITR state, and the complete restricted data ledger.
- Record all critical GCS live and soft-deleted generation manifests and their manifest SHA-256 hashes.
- Record BigQuery table schemas/counts/bytes/last-modified values.
- Drain/reconcile database outboxes and inboxes; record Pub/Sub backlog metrics.
- Record Drive metadata canary results.
- Record the exact UTC and IST cutover timestamps and the last approved application/deployment change.

### Immediately after the organization move

- Do not restore or copy anything merely because access fails.
- Prove both databases remain RUNNABLE with the same instance IDs, IP configuration, databases, users, and sizes.
- Run the lightweight live ledger and business invariants.
- Re-list every critical bucket/generation and compare with lifecycle/audit-aware rules.
- Resolve all deployed secret references again without reading payloads.
- Verify BigQuery historical tables remain readable.
- Verify Pub/Sub topology/backlog and process the bounded pre-move event set.
- Run production and dev application/tenant/student/photo reads and an approved non-production write transaction.
- Run Drive/OAuth and observability canaries.

### During stabilization

- Run the full post-move data ledger again after asynchronous queues settle.
- Verify no orphan count, invalid constraint/index, failed Flyway row, missing photo, or dead-letter count increased unexpectedly.
- Verify new billing-export rows appear under the intended target account/table strategy; retain old-account history.
- Keep recovery artifacts and source rollback authorization until formal acceptance.

## 8. Failure classification

| Symptom | Likely class | Required action |
|---|---|---|
| Database/bucket exists but access is denied | Destination IAM/org policy/VPC-SC/identity | Correct the narrow policy or reverse the project move; do not restore data |
| Cloud Run cannot sign photo URLs | Service-account token-creator or IAM policy restriction | Repair self-impersonation/signBlob permission and retest |
| GCS manifest difference is an age-eligible object under a recorded lifecycle prefix | Expected lifecycle activity | Correlate timestamps and audit log; record as expected, not data loss |
| Active DB photo key has no live object and no recoverable soft-deleted generation | Data-integrity incident | Freeze affected workflow; recover the exact generation if available; do not bulk overwrite |
| Drive token/folder access fails | External OAuth/Drive ownership or Internal-user change | Keep GCP data unchanged; repair external authorization/sharing |
| DB row ledger differs between source snapshot and restored export | Recovery artifact invalid/inconsistent | NO-GO; produce a fresh export and repeat validation |
| Post-move live counts advance with corresponding audit/outbox records | Expected live writes | Reconcile the time window; no rollback solely for count increase |
| Rows decrease or mutate without an authorized audit/event/lifecycle record | Possible data loss/corruption | Freeze changes, preserve evidence, investigate PITR/soft delete; restore only with incident approval |
| Pub/Sub backlog is zero but outbox rows are old/unpublished | Publisher/relay failure | Diagnose relay; Pub/Sub metric does not close the event gate |

## 9. Mandatory GO/NO-GO gates

Every item requires a named owner, timestamp, restricted evidence reference, and independent verifier:

- [ ] Exact destination organization/folder, destination billing account, and destination administrators are verified.
- [ ] Source export and destination import policies are effective; official Move Analysis has no unresolved blocker.
- [ ] Fresh prod and dev backup IDs and logical export generations/checksums are recorded.
- [ ] Both fresh logical exports are restored independently and the source/restored integrity ledgers match.
- [ ] All Flyway histories/checksums match the approved environment release, with no failed row.
- [ ] Structural and cross-schema orphan/mismatch queries have zero unaccepted failures.
- [ ] Database outbox/inbox and Pub/Sub bounded-event reconciliation passes.
- [ ] Complete live and soft-deleted GCS manifests exist; all enumerated generations have CRC32C.
- [ ] Every active internal student-photo reference resolves to the correct object and school UUID path.
- [ ] Lifecycle-managed objects and intended retention are explicitly accepted; no unexpected missing generation exists.
- [ ] Drive roots, folder mappings, app properties, and OAuth behavior pass a non-mutating destination-model canary.
- [ ] BigQuery historical dataset/table counts are recorded and the target-account export plan is approved.
- [ ] All 107 deployed secret references resolve under the intended post-move policies, without reading payloads.
- [ ] Production notification topic without a subscription is either proven intentionally inactive and accepted, or the reviewed production subscription/DLQ setup is completed and tested separately.
- [ ] Application login, tenant isolation, student detail/photo reads, and approved non-production transaction pass before and after.
- [ ] Rollback identities, reverse-move policies, data-recovery authority, RPO/RTO, and stabilization retention are approved.

Any unchecked mandatory item means **NO-GO**. Schedule pressure is not a data-integrity exception.

## 10. Evidence handling

- Store database ledgers, object manifests, IAM details, folder IDs, school UUIDs, billing identifiers, private IPs, and restore metadata only in the approved restricted evidence location.
- Hash every evidence file with SHA-256 and produce one signed/controlled manifest.
- Never store secret payloads, database passwords, OAuth refresh tokens, student names/contacts/addresses, downloaded photos, or raw row exports in Git or the change ticket.
- Use `psql -v ON_ERROR_STOP=1` and make every audit job fail on a SQL error. Container exit zero after a failed query is not evidence.
- Give evidence files an environment, source snapshot/export timestamp, cutover timestamp, tool/script commit, operator, and verifier.
- Preserve recovery artifacts through the approved stabilization window; cleanup is a separate, witnessed action.

## 11. Official references

- [Resource Manager project migration](https://docs.cloud.google.com/resource-manager/docs/project-migration)
- [Analyze a project move](https://docs.cloud.google.com/resource-manager/docs/analyze-move)
- [Perform a project migration](https://docs.cloud.google.com/resource-manager/docs/perform-migration)
- [Handle migration special cases](https://docs.cloud.google.com/resource-manager/docs/handle-special-cases)
- [Cloud SQL for PostgreSQL restore overview](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/restore)
- [Cloud SQL for PostgreSQL best practices](https://docs.cloud.google.com/sql/docs/postgres/best-practices)
- [Cloud Storage checksum validation](https://docs.cloud.google.com/storage/docs/data-validation)
- [Pub/Sub replay, snapshots, and retention](https://docs.cloud.google.com/pubsub/docs/replay-overview)
- [Set up Cloud Billing export to BigQuery](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-setup)
