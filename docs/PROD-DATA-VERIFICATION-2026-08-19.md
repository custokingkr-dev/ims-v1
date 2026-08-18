# Production Data Verification — post-cutover, 2026-08-19

Full source-versus-destination comparison run after the cutover and the database rename, while the source
`custoking` database was still intact and frozen. Identical queries were issued to both sides.

## Result: nothing is missing

Across **all 107 relations, exactly one differs**, and that difference is correct behaviour rather than loss.

### Structure

| Dimension | Source | Destination | |
| --- | --- | --- | --- |
| relations | 107 | 107 | match |
| sequences, **including current values** | 37 | 37 | match (digest) |
| indexes | 339 | 339 | match (digest) |
| constraints | 243 | 243 | match (digest, names and types) |
| RLS policies | 66 | 66 | match (digest, including policy names) |
| routines / triggers / views / matviews | 36 / 0 / 0 / 0 | same | match |
| Flyway history | — | — | match (digest) |

Sequence *values* matching matters more than the count: a sequence restored without its current value
produces primary-key collisions on the next insert, and nothing would detect it until users hit errors.

### Data

| | Source | Destination |
| --- | --- | --- |
| students / enrollments / guardians | 1257 / 1329 / 875 | identical |
| app_users / schools / sections | 49 / 11 / 376 | identical |
| fee assignments / payments / **payment sum** | 3 / 2 / **112999** | identical |
| audit events | 0 | 0 |

### The one difference

`identity.auth_sessions`: source 621, destination 384.

| | unexpired | expired | total |
| --- | --- | --- | --- |
| source (frozen) | 381 | 240 | 621 |
| destination (live) | 383 | 1 | 384 |

**No live session was lost.** The destination holds *more* unexpired sessions than the source. The gap is
expired sessions pruned by the running application — cleanup that cannot execute against a frozen
database. Comparing raw totals here would have manufactured a false alarm; the comparison has to be
unexpired-against-unexpired.

## Object storage

| Check | Result |
| --- | --- |
| `students.photo_url` keys referenced | 881 |
| of those missing from destination | **0** |
| durable objects (`schools/`) each side | 1042 / 1042 |
| **CRC32C mismatches across all 1042** | **0** |
| all live object references (498 distinct, 856 rows) resolving | **0 missing** |

Every object-key-bearing column in the schema was enumerated rather than assumed:
`students.photo_url`, `photo_import_batches.workbook_object_key` (5),
`import_batches.original_file_object_path` (40), `photo_import_rows.final_photo_key` (811),
`prior_photo_key` (0), `source_object_key` (811, dangling by design),
`firefighting_requests.reference_file_url` (0), `ff_quotations.document_url` (0).

**Every live reference resolves under `schools/`.** The 11 objects deliberately not copied — 5 legacy
numeric-prefix photos and 6 top-level `student-imports/` workbooks — are referenced by nothing. The
earlier plan assumed import workbooks were referenced; measurement shows the live rows point at
`schools/...` paths instead.

## Method notes worth keeping

**A collided delimiter silently returned a stale answer.** Three different structural queries returned an
identical digest because `--update-env-vars` used `%` as its delimiter while the SQL contained `LIKE
'pg\_%'`. The env update failed, the job re-ran the *previous* query, and the output looked like three
passing checks. Identical results across queries that should differ is a harness fault, not a pass.

**Per-bucket counts inside a shell loop all read zero** — defect 12 again (`gcloud` consuming stdin in
loops). Counted individually the same buckets held 1175 and 1164 objects. A zero from a loop is not
evidence of an empty bucket.
