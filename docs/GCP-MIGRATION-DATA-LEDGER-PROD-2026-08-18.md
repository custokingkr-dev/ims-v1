# Production Data Ledger and Reconciliation — 2026-08-18

**Scope:** the complete production data inventory required by
`GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md`, measured live rather than estimated.
**Companion:** `GCP-SPLIT-PROJECT-MIGRATION-PLAN-2026-08-18.md`
**Method:** read-only. Cloud Storage was enumerated through the JSON API. PostgreSQL was read through a
temporary `postgres:16-alpine` Cloud Run job on Direct VPC egress — the pattern this project already uses
for `ims-q-dev` and `ims-gateway-smoke-sql-*` — with `SET default_transaction_read_only = on`, connecting
as `appuser` with the password from Secret Manager. **The job was deleted after the final pass and no
write of any kind was issued.**

School UUIDs and object keys are omitted here and held in restricted evidence.

---

## 1. Headline result

**The production database and the photo bucket are consistent. There are zero broken references.**
Three real defects exist, all pre-existing and none caused by migration planning. One of them, if not
understood in advance, would produce a **false NO-GO** at cutover.

| # | Finding | Severity |
| --- | --- | --- |
| D1 | `source_object_key` legitimately dangles by design — a naive DB↔GCS check reports hundreds of "missing objects" | **Must understand before cutover, or the integrity gate fails wrongly** |
| D2 | One student exists that was never projected into `reporting.dim_student` | Real, pre-existing projection gap |
| D3 | 17 superseded photo objects and 11 legacy-prefix objects are unreferenced | Cleanup, do not migrate |

---

## 2. Schema and Flyway state

13 schemas: `attendance`, `audit`, `billing`, `catalog`, `fee`, `firefighting`, `identity`,
`notification`, `public`, `reporting`, `student`, `tenant_school`, `workflow`.

Every schema carries a `flyway_schema_history` except `tenant_school`, which uses
`flyway_schema_history_tenant_school`. Applied counts against the repository at this commit:

| Schema | Applied | Repo `.sql` files | Delta |
| --- | --- | --- | --- |
| attendance | 9 | 8 | +1 |
| audit | 2 | 1 | +1 |
| billing | 7 | 6 | +1 |
| catalog | 8 | 7 | +1 |
| fee | 10 | 9 | +1 |
| firefighting | 12 | 11 | +1 |
| identity | 7 | 6 | +1 |
| notification | 11 | 10 | +1 |
| reporting | 29 | 28 | +1 |
| student | 20 | 19 | +1 |
| tenant_school | 27 | 26 | +1 |
| workflow | 6 | 5 | +1 |

**Every schema is exactly repository + 1**, the Flyway baseline row. The deployed schema state matches
the repository exactly, with no drift and no failed migrations. This is the release-ledger evidence the
runbook's §4 requires, and it passes.

---

## 3. Row counts (exact)

Selected; the full 66-relation table is in restricted evidence.

| Relation | Rows |
| --- | --- |
| `student.students` | 1,257 (across 7 schools) |
| `student.student_enrollments` | 1,329 |
| `student.import_rows` | 9,919 |
| `student.photo_import_sources` | 1,233 |
| `student.photo_import_rows` | 1,033 |
| `student.guardians` | 875 |
| `tenant_school.schools` | 11 |
| `tenant_school.school_sections` | 376 |
| `tenant_school.outbox_events` | 7,361 |
| `reporting.reporting_event_inbox` | 7,366 |
| `reporting.dim_student` | 1,256 |
| `reporting.dim_section` | 376 |
| `reporting.dim_school` | 11 |
| `identity.auth_sessions` | 574 |
| `identity.app_users` | 49 |

**Note on method:** `pg_stat_user_tables.n_live_tup` initially suggested `dim_section` was 379 against a
source of 376. Exact `count(*)` proved both are **376**. That apparent mismatch was statistics drift.
**Any integrity gate must use exact counts, never `n_live_tup`.**

### Reporting projection parity

| Source | Source rows | Projection | Projection rows | Verdict |
| --- | --- | --- | --- | --- |
| `tenant_school.schools` | 11 | `dim_school` | 11 | match |
| `tenant_school.school_sections` | 376 | `dim_section` | 376 | match |
| `attendance.attendance_daily` | 3 | `fact_attendance_daily` | 3 | match |
| `fee.fee_assignments` | 3 | `fact_fee_assignment` | 3 | match |
| `fee.payment_records` | 2 | `fact_payment` | 2 | match |
| `catalog.catalog_orders` | 1 | `fact_catalog_order` | 1 | match |
| `firefighting.firefighting_requests` | 1 | `fact_firefighting_request` | 1 | match |
| `student.student_review_items` | 1,796 | `fact_student_review_item` | 1,796 | match |
| `student.students` | **1,257** | `dim_student` | **1,256** | **D2 — off by one** |

**D2:** a targeted anti-join confirms `students_missing_from_dim = 1` and `dim_rows_with_no_student = 0`.
Exactly one student was never projected. Resolve or explain this **before** migration: carried across
unexplained, the same delta reappears in the destination ledger and cannot be distinguished from
migration-induced loss.

---

## 4. Durable event state — fully drained

| Outbox | Total | Pending | Dead-lettered |
| --- | --- | --- | --- |
| `tenant_school.outbox_events` | 7,361 | **0** | **0** |
| `firefighting.outbox_events` | 4 | **0** | **0** |
| `billing.outbox_events` | 0 | 0 | 0 |

`reporting.reporting_event_inbox`: 7,366 rows, **all `PROCESSED`**. No other status present.

There is **nothing in flight**. The runbook's "drain or bound durable events" gate is already satisfied in
steady state, which materially de-risks the cutover. Re-verify immediately before the freeze.

Minor note: 7,365 outbox rows against 7,366 inbox rows. Since every inbox row is `PROCESSED` and no outbox
row is pending, this is a historical artifact rather than a live discrepancy; record it so the destination
ledger's identical delta is not misread as migration loss.

---

## 5. Cloud Storage — `custoking-student-photos-prod`

1,175 objects, 68.91 MB. 1,124 `image/jpeg` and 51 `.xlsx`. 1,150 distinct CRC32C; 16 CRC values are
shared by 41 objects, which is expected (the same image genuinely uploaded for several students).

| Prefix | Objects | Disposition |
| --- | --- | --- |
| `schools/*/students` | 898 | 881 referenced by `students.photo_url`; **17 unreferenced (D3)** |
| `schools/*/student-imports` | 144 | import workbooks and staged photos; lifecycle-expiring in part |
| `temporary/photo-imports/` | 122 | **transient — do not migrate**; 14-day lifecycle |
| `student-imports/<numericId>/` | 6 | **legacy prefix (D3)** — unreferenced |
| `students/<numericId>/` | 5 | **legacy prefix (D3)** — unreferenced |

All 122 `temporary/` objects sit under `temporary/photo-imports/`, so the lifecycle rule covers them
completely; nothing accumulates outside it.

### 5.1 Database ↔ Storage reconciliation

`students.photo_url` distribution across 1,257 students: 881 current `schools/` keys, 376 null or empty,
**0 external URLs, 0 legacy-prefix keys.**

| Check | Result |
| --- | --- |
| DB keys with **no** GCS object (broken photos) | **0** |
| GCS objects under `schools/*/students` with no DB reference | **17** |

**Zero broken references.** Every referenced photo exists.

**D3, and a corrected hypothesis.** The 11 legacy objects use numeric school-ID prefixes
(`students/<numericId>/…`) from the layout that preceded the UUID scheme. No database row references them:
the earlier prefix migration completed on the database side, and
`scripts/migrate-school-storage-prefixes.ps1` was evidently run without `-DeleteLegacyObjects`. They
persist because `StudentPhotoStorage.deleteStoredPhoto` deliberately ignores any key not starting with
`schools/` and containing `/students/`, so the application cannot clean them up.

Worth recording precisely, because the read and delete paths disagree: the read path
(`readStoredPhoto`, and the signed-URL path) rejects only `http(s)://` and would happily serve a legacy
key. Only the **absence of database references** makes these objects dead. They are safe to delete, and
must not be carried into a fresh project.

I initially expected the 17 unreferenced photos to be retained rollback state held in
`photo_import_rows.prior_photo_key`. **That is wrong** — `prior_photo_key` is populated on **0 rows**. The
17 are genuinely unreferenced superseded photos, consistent with a photo being replaced (the key embeds
the image SHA-256, so a replacement writes a new key) and the old object never being removed. Three
students account for two each.

### 5.2 D1 — the finding that would cause a false NO-GO

Object-key columns and their populated counts:

| Column | Populated | Distinct |
| --- | --- | --- |
| `photo_import_rows.final_photo_key` | 811 | 811 |
| `photo_import_rows.source_object_key` | 811 | 811 |
| `photo_import_rows.prior_photo_key` | **0** | 0 |
| `photo_import_batches.workbook_object_key` | 5 | 5 |

`source_object_key` splits **689 under `schools/`** and **122 under `temporary/`**. But only 144 objects
exist under `schools/*/student-imports`, and the bucket's lifecycle rules delete objects after 14 days
under `temporary/photo-imports/` **and under three named `schools/<uuid>/student-imports/photo-import-…/`
prefixes**.

So `source_object_key` is a **transient reference that is designed to dangle** once its staged object
expires. A naive "every object key in the database must exist in the bucket" comparison at cutover will
report **hundreds of missing objects** and fail the integrity gate — wrongly.

**The integrity gate must therefore be scoped to durable references only:**

- **In scope (must match exactly):** `students.photo_url` — currently 881 keys, 0 missing.
- **Out of scope (expected to dangle):** `photo_import_rows.source_object_key`,
  and any key under `temporary/` or under a lifecycle-managed `student-imports/photo-import-…/` prefix.
- **Verify separately, not as a blocker:** `final_photo_key`, `workbook_object_key`.

---

## 6. Dev environment

`custoking-student-photos-dev`: 18 objects, 0.53 MB, all `image/jpeg`, 9 distinct CRC32C across 18 objects
(heavy reuse of the same fixture images). 12 under `schools/*/students`, and **6 under the legacy
`students/<numericId>/` prefix** — the same D3 pattern.

The dev **database could not be read**: `custoking-db-dev` is `STOPPED`, and Cloud SQL refuses even to
list databases or users in that state. Completing the dev ledger requires starting the instance, and
`gcp-cost-controls.yml` will stop it again on its `30 14 * * *` schedule. **Not done here** — it costs
money and needs the schedule suspended first.

---

## 7. Copy set for the production cutover

| Set | Objects | Action |
| --- | --- | --- |
| Photos referenced by `students.photo_url` | 881 | **Copy. Verify key, size and CRC32C exactly.** |
| Import workbooks (`workbook_object_key`) | 5 | Copy |
| Other `schools/*/student-imports` objects | ~139 | Copy if still present; do not fail on absence |
| `temporary/photo-imports/` | 122 | **Do not copy** — transient |
| Unreferenced superseded photos (D3) | 17 | **Do not copy** — delete after evidence capture |
| Legacy numeric-prefix objects (D3) | 11 | **Do not copy** — delete after evidence capture |

At 68.91 MB total, with a 146 MB database, the entire production cutover moves well under 250 MB.

---

## 8. Actions before cutover

1. **Resolve D2** — identify the unprojected student and either repair the projection or record why the
   delta is expected. Without this the destination ledger is ambiguous.
2. **Encode D1 into the integrity gate** — scope it to `students.photo_url` and explicitly exclude
   lifecycle-managed keys. This is the difference between a meaningful gate and a guaranteed false failure.
3. **Clean up D3** — capture evidence, then delete the 17 superseded and 11 legacy objects in the source,
   or explicitly exclude them from the copy set.
4. **Run this ledger for dev** once the instance can be started with the cost-control schedule suspended.
5. **Re-run every count in sections 3–5 immediately before the write freeze** and compare to the
   destination after import. These numbers are a baseline, not the cutover snapshot.
