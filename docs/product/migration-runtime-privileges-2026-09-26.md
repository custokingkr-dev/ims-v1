# Runtime privileges for the September product migrations

Reviewed all seven new migrations before their first deployment. Six now explicitly define runtime access; tenant-school V28 adds policies to existing reference tables and creates no object requiring a new grant.

Migration-owner defaults are not a reliable contract: a runtime import can omit them, while the dev database's owner defaults grant broad DML to `app_rt`. Each new object now revokes PUBLIC and `app_rt` privileges before granting its required operations. These changes belong to the unapplied migration files; do not alter these files after deployment.

| Migration | Effective new runtime access |
| --- | --- |
| Identity V8 | SELECT, INSERT, UPDATE, DELETE on quotas, reset tokens and delivery intents. DELETE supports bounded expiry cleanup; no TRUNCATE. |
| Fee V10 | USAGE and SELECT on the new receipt sequence. Runtime can allocate numbers, but cannot use `setval` to rewind it. |
| Tenant-school V28 | Existing table grants unchanged; adds zone-scoped read policies only. |
| Catalog V24 | SELECT and INSERT on annual-plan confirmations; no UPDATE, DELETE or TRUNCATE, including when tenant bypass is enabled. |
| Notification V11 | SELECT and INSERT on recipient rows; UPDATE only on delivery-progress columns. Approved recipient/event identity cannot be rewritten or deleted by runtime. |
| Firefighting V12 | SELECT and INSERT on document reservations; UPDATE only on status, upload time and cleanup progress. Object identity and retained tombstones cannot be rewritten or deleted by runtime. |
| Firefighting V13 | SELECT and INSERT on creation replay receipts; no UPDATE, DELETE or TRUNCATE. |

Existing `fee.payment_records` DML is deliberately unchanged. `StudentReadRepository.deleteStudent` removes financial child records in its guarded deletion flow. Restricting that existing table would change a separate established workflow. This report therefore does not claim all historical payment rows are database-immutable.

The append-only repositories use plain SELECT plus INSERT (and `ON CONFLICT DO NOTHING` for procurement replay), so they need no UPDATE privilege for row locks. Queue and document processing retain column-level UPDATE privileges; real PostgreSQL tests exercise their `FOR UPDATE` / `SKIP LOCKED` operations.

## Verification

42 targeted tests passed, zero failures, errors or skips, on local PostgreSQL 16 containers:

| Module | Test classes | Passed |
| --- | --- | ---: |
| Identity | PasswordResetRuntimePrivilegesIntegrationTest; PasswordResetAndQuotaIntegrationTest | 12 |
| School-core | AnnualPlanConfirmationRepositoryIntegrationTest; FeePaymentMigrationIntegrationTest | 9 |
| Platform | BroadcastDispatchRepositoryIntegrationTest | 4 |
| Operations | FirefightingCreationReplayIntegrationTest; QuotationDocumentIntegrationTest | 17 |

Runtime fixtures connect as `app_rt` with NOSUPERUSER, NOBYPASSRLS and NOINHERIT. Tests cover both absent and broad default grants, including PUBLIC defaults in the identity fixture. They execute real authorized operations and check denied operations using PostgreSQL SQLSTATE `42501`, rather than mistaking tenant filtering for privilege enforcement. Existing replay races, RLS boundaries, cleanup recovery and transaction rollback tests remain in the selected classes.

Final logs are `artifacts/product-dev-release-2026-09-26/privileges-{identity,school-core,platform,operations}-final.log`. Initial logs capture test-only corrections: Java generic assertion inference and checking the underlying SQLSTATE rather than Spring's wrapper message. All final selected classes passed after those corrections. These tests do not replace deployed migration/readiness verification; cloud operations remain the release owner's responsibility.
