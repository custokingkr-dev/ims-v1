# Precommit student erasure contract

The source now supports an explicitly enabled **development** erasure journal. After the existing authenticated DELETE authorization, fresh step-up and admission confirmation, it verifies the externally controlled restore epoch and synchronously creates/verifies an immutable deletion intent **before** removing any source or child row. Production defaults to the existing behavior until separately authorized. Source implementation and local tests do not establish live deployment, complete historical journal coverage, physical object purge or restoration-ready status.

## Request and failure behavior

Successful DELETE retains its existing 200 response and `deleted=true, permanent=true` body after the source transaction commits. An enabled but unavailable/misconfigured journal returns 503 before source erasure. Wrong confirmation still fails before any external intent. The source transaction has a 45-second deadline; the complete journal claim has a 25-second deadline and two bounded worker slots, with single-attempt bounded SDK transport. Transport details, credentials and object content are not included in errors.

An external intent is an **irreversible authorized erasure request**. SQL rollback, a lost HTTP response or process failure does not cancel it. A retry of the same student incarnation in the same source/restore epoch derives the same SHA256 intent ID and operation UUID; it must verify the existing exact canonical body and generation. Creation uses `ifGenerationMatch=0`; neither a 412 nor an uncertain creation response alone counts as success. The source records the verified receipt and enriches the existing `student.deleted.v1` outbox payload in the same transaction as erasure. Durable photo cleanup uses the same operation UUID.

Consequently, 503 may mean the source is unchanged while an external intent already exists. Owner reconciliation must conservatively finish that authorized request. The external record survives rollback of SQL outbox/receipt evidence and closes the successful SQL commit → external mirror gap. Requests issued before this feature was enabled remain outside its prospective guarantee.

## Exact development configuration

| Environment variable | Required value when enabled |
| --- | --- |
| `STUDENT_ERASURE_JOURNAL_ENABLED` | `true` only for the explicitly configured dev rollout |
| `STUDENT_ERASURE_JOURNAL_PROJECT_ID` | `custoking-dev` |
| `STUDENT_ERASURE_JOURNAL_BUCKET` | `custoking-dev-erasure-journal` |
| `STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID` | Owner-held `[a-z0-9][a-z0-9-]{7,63}` identifier; a canonical lowercase UUID fits |
| `STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH` | Owner-held canonical lowercase UUID |
| `STUDENT_ERASURE_JOURNAL_EPOCH_GENERATION` | Positive generation of the reviewed current control object |
| `STUDENT_ERASURE_JOURNAL_EPOCH_SHA256` | SHA256 of its exact canonical bytes |

The logical source identity is fixed to `custoking-db-dev`, database `custoking_dev`. Enabled source deletion, reconciliation and owner delivery policy independently check JDBC `current_database()` equals `custoking_dev` before any external journal operation. The physical instance, lineage and clone connection still require governed deployment/operator evidence; SQL database-name equality cannot prove a GCP instance or detect an unannounced snapshot restore.

The fixed control object is `control/<lineage>/current.json`. Its body has exactly these fields, sorted by key, compact ASCII JSON and one final LF:

```json
{"database":"custoking_dev","project":"custoking-dev","restoreEpoch":"<canonical UUID>","schemaVersion":1,"sourceInstance":"custoking-db-dev","sourceLineageId":"<lineage>","state":"ACTIVE"}
```

`RECONCILING` replaces `ACTIVE` for the controlled replay capability. Normal DELETE claims and owner delivery checks require ACTIVE; replay requires RECONCILING. The source checks latest control metadata against its configured generation and verifies generation-pinned content/hash before and after each journal operation. A changed generation, wrong state, wrong schema/body or failed read stops the operation. The control owner must use generation preconditions when replacing the pointer. Freeze/drain remains required because pointer changes cannot revoke a request already in flight atomically with a SQL commit.

Intent objects are `intents/<lineage>/<original-epoch>/<intentId>.json`. The identity hash binds project, logical source instance/database, lineage, original restore epoch, school ID, student ID and immutable student incarnation. The canonical body additionally contains `kind=student.erasure-intent.v1`, that intent ID and its deterministic operation UUID. It contains no admission string, name, phone or email. Identifiers are necessary erasure provenance and should receive the journal's restricted access/retention policy.

The application writer needs create/get on the intent prefix and get-only on the control prefix. It has no list/update/delete journal capability. The separately authorized control/restore operator owns coverage enumeration, pointer transitions and recovery approval. An application precondition does not replace IAM enforcement. No retention horizon or irreversible retention lock is invented by this source contract.

## Incarnation and delivery fences

Student migration V39 assigns an immutable `erasure_incarnation` UUID; application create/update requests do not accept that field. It converts an existing runtime table-level student INSERT grant into grants for the existing ordinary columns, excluding `erasure_incarnation`, and removes any explicit INSERT grant on that incarnation column. A normal runtime insert receives the database UUID default. A direct runtime statement specifying an incarnation is denied even when another tenant's terminal receipt is hidden by RLS. The dedicated-role migration callback copies those column grants without restoring table-level INSERT. Future writable student columns require an explicit reviewed column grant. The immutable update trigger still rejects changing an existing incarnation; owner-controlled restore/replay requires separate exact binding.

The committed receipt table has tenant RLS/FORCE RLS, no PUBLIC privileges, SELECT/INSERT runtime privileges and no student/school foreign key. V39 removes broad bootstrap default grants before applying this append-only grant baseline; the dedicated callback copies it. Its reinsertion trigger rejects an erased incarnation visible to the current database role/tenant, without a global SECURITY DEFINER lookup. The runtime incarnation-column restriction supplies the cross-tenant reuse barrier. A receipt is terminal local evidence, not an external restore barrier or a durable pending-request queue.

With the feature enabled, production Spring guardian-policy evaluators first check the source database and resolve the exact current school/student incarnation, verify ACTIVE control, and look up its deterministic current-epoch intent. **Only confirmed GCS 404 permits evaluation to proceed.** Presence, malformed metadata/body, permission denial, transport error or changed control denies without returning a destination or ALLOW evidence. Complete fence lookup is bounded to four seconds. Broadcast recipient resolution, typed absentee owner revalidation and dispatch-time policy share this fence. Legacy manual test constructors and feature-disabled production paths perform no additional journal/SQL lookup.

Current-epoch lookup does not discover arbitrary older epochs. A new ACTIVE epoch requires complete replay of every applicable older external intent, including claims whose source transaction rolled back. Freshly restored consent alone is insufficient authority to send. The system does not claim automatic snapshot detection or automatic completeness from SQL IDs/timestamps.

## Controlled source reconciliation

`StudentReadRepository.reconcileErasure(IntentReference)` is an application capability with **no new REST endpoint**. It checks the source database and verifies an exact object/generation/SHA256, full canonical intent body and same source lineage under the current RECONCILING head. It locks the restored student and requires exact school/incarnation equality before reusing the complete existing source/child erasure, enriched deletion outbox and durable photo queue transaction. Repeated already-absent replay succeeds only when its matching committed receipt proves that source erasure. An unproven absent target or a reused student ID returns 409 without manufacturing a global numeric-ID tombstone.

A recovery predating V39 cannot prove incarnation equality from a newly backfilled UUID. It stays quarantined unless separately approved legacy ownership evidence supplies a safe mapping. Source capability success is not a platform tombstone replay, all-intent coverage certificate, object/provider deletion receipt or delivery-resume decision. Recovery operators must prove the physical isolated clone, freeze all new writes/erasures and delivery, replay full source/platform/object fences, protect against rewound identifier sequences, establish complete externally held coverage, then approve a new epoch/cutover. That cross-service operation is separate from this local implementation.

## Validation scope

The journal unit tests exercise deterministic retries, acceptance with a lost response, unequal conflict, epoch changes, incarnation reuse, non-dev configuration rejection, complete-call deadline, confirmed-404-only delivery and pinned RECONCILING replay. SDK adapter tests exercise generation-zero create, generation-pinned reads, size ceilings and 403 versus 404 distinction. Exact passing runs, suites and source hashes are in the [sanitized local proof](acceptance-precommit-erasure-local.json).

Nine source integration cases use **all four actual owning migration chains** (`tenant_school`, `student`, `fee`, `attendance`) in local PostgreSQL 16 and the real repository transaction. The fixture provisions actual baseline/dedicated runtime roles and the real owner's broad default DML grants before migration. Eight cases cover confirmation/journal failure, external intent surviving rollback, receipt/outbox/photo-queue commit on retry, epoch-change rollback, incarnation immutability, current-consent delivery suppression after an external-only intent, source reconciliation and reuse/absence rejection. The additional dedicated-role case executes the actual application `createStudent`, proves a supplied JSON incarnation is ignored in favor of a fresh database UUID, and verifies SQLSTATE 42501 for a raw retired-UUID insert under another tenant. It executes the actual dedicated ACL callback twice and checks column-level INSERT and append-only receipt privileges survive.

A tenth case independently connects to database `postgres` with only the preliminary read columns and proves the database guard stops deletion, replay and delivery before any external read/write. The tests make zero cloud/provider calls. The full source-chain fixture differs from the intentionally minimal SQL fixture of the separate [export/replay planning toolkit](operational-erasure-journal.md); neither is a live cross-service Cloud SQL recovery test.
