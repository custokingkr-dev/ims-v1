# Product assessment follow-up implementation

This follows [the initial remediation](PRODUCT-FINDINGS-REMEDIATION-2026-09-26.md). It records the second implementation pass after the user said to proceed, selected any suitable dev school for acceptance, and chose password reset before SSO.

## Implemented work

| Area | Change | Operational limit |
| --- | --- | --- |
| Fleet abuse protection | Database-coordinated login, reset, user, school, import and export allowances; failed attempts count; 429/Retry-After survive the gateway. | Initial budgets require real dev load/cost certification. Body-only target schools use home-school/user accounting. |
| Password recovery | Real request/confirm flow, hashed expiring tokens, durable SMTP delivery intent, fenced retries, generic responses, single-use transaction and immediate credential-version invalidation. | Disabled until SMTP and always-running worker configuration are verified. SSO deferred by user. |
| Quotation evidence | Private PDF/JPEG/PNG storage, authenticated retrieval for requesters/reviewers, upload validation, immutable keys and durable orphan/replacement cleanup. | A configured private bucket and narrowly scoped operations-service IAM are needed. No cloud object was uploaded. |
| Broadcast workflow | Recipient preview, approval evidence, per-recipient queue/results, fresh policy checks, stable delivery keys and explicit dry-run outcomes. | Default off; live provider idempotency/delivery contract remains required. Dry run is not delivery. |
| API contracts | All 95 frontend compatibility call sites migrated; canonical fields, pagination, roster responses and authorization preserved. Ten generated clients cover 42 canonical operations. | Compatibility controllers remain available for external clients; static call-site coverage is not traffic telemetry. |
| Receipt migration | Duplicate backfill and sequence initialization explicitly include hidden schools under forced RLS, with transaction-local maintenance scope restored afterward. | Dev preflight has complete visibility: four payments, no duplicates; successful predeployment backup recorded. Post-migration checks remain. |
| Annual-plan confirmation | Exact reviewed current-year snapshot, trusted actor, immutable revision and transactional event. Duplicate confirmation returns the original revision; changed items require fresh review. | A recorded confirmation neither places orders nor sends notifications. |
| Purchase-request recovery | Required creation keys, payload conflict checks and persistent command receipts prevent duplicate requests/quotations after lost responses. Submission retries reconcile the persisted workflow status. | Older clients must send keys; lost responses must retain the original payload and key. |
| Release evidence | Aggregate-only fee migration preflight, dev service/database checks, selected synthetic cohort and local regression evidence. | Working-tree changes have not been deployed. Existing cloud revision health does not validate this revision. |

Detailed contracts: [password recovery and quotas](product/password-reset-and-shared-quotas.md), [private quotation files and recovery](product/private-quotation-files.md), [broadcast dispatch](product/broadcast-dispatch.md), [annual-plan confirmation](product/annual-plan-confirmation.md), [canonical API migration](product/canonical-api-migration-2026-09-26.md), and [fee payment integrity](product/fee-payment-integrity.md).

New forward migrations are identity `V8`, fee `V10`, tenant-school `V28`, catalog `V24`, firefighting `V12`/`V13`, and notification `V11`. They have been exercised in disposable local PostgreSQL instances and are not applied to the inspected dev revision. Retain normal Flyway ordering within each owning service; do not edit previously applied migrations.

## Verified dev context

The active non-production environment is `custoking-dev` in `asia-south2`. Stage is a configuration template rather than an active environment. All seven dev application services reported Ready on their existing revisions. The SQL instance was a zonal `db-f1-micro`, with 200 maximum connections and automated backup disabled; no scaling or load writes were made.

Authenticated gateway reads selected **Local Demo School, ID 1**, an active synthetic school, from 106 school records. The verification session was logged out. An initial 30-second cold-start request timed out; the subsequent authentication and school-directory reads succeeded. This is not a latency certification.

Dev identity has zero minimum instances and request-based CPU, so idle scheduled delivery cannot be assumed. SMTP recovery secrets were absent. GitHub access remains write-only without repository-admin rights, so branch protection still requires an administrator. Existing dev delivery and acceptance checks must not send test communications to arbitrary real contacts.

The reviewed receipt SQL ran read-only through the existing `ims-q-dev` diagnostic job. The first execution (`ims-q-dev-rhlzz`) conservatively reported incomplete visibility because its check overlooked inherited owner privileges. Subsequent metadata inspection confirmed inherited ownership and that the payment table does not force RLS. The corrected execution (`ims-q-dev-hk4n5`, 15:17 UTC) reports complete database visibility: four payments, no receipt/idempotency duplicate groups, no missing school/receipt values, and none of the V10 migration objects. No receipts, balances or schemas were changed. Windows truncates multiline CLI values, so the verified executions use a structured REST body. Cloud SQL predeployment backup `1790435759457` completed successfully at 15:17:20 UTC. Forced-RLS migration behavior remains covered by separate local fixtures.

All seven new migrations were also audited against dev's broad default grants. Six now revoke inherited defaults before granting only the operations needed by runtime code. Forty-two additional PostgreSQL tests passed as the actual restricted runtime role; see [migration runtime privileges](product/migration-runtime-privileges-2026-09-26.md).

## Release and acceptance gates

1. Review the combined application diff while preserving pre-existing portal edits; run the complete checks for the final revision.
2. Run `scripts/sql/fee-payment-integrity-preflight.sql` using an explicitly selected read-authorized database connection. It emits aggregate counts only and flags incomplete RLS visibility. Preserve historical duplicate receipt numbers and reconcile using payment IDs.
3. Release through the normal `dev` branch workflow with immutable image evidence. Apply the new service migrations before the matching frontend/gateway and reload old fee clients. Production promotion still requires the established environment gate and approved digests.
4. Configure private quotation storage and its cleanup execution, broadcast policy credentials/dry-run mode, and SMTP/worker readiness through the configuration reconciliation path. Do not switch on a live provider without its evidence contract.
5. Use synthetic records under dev school 1 for collection retry/receipt comparison, admission/photo retry, attendance, private quotation review, consent withdrawal and recovery. Assign a human acceptance/support owner for independent school use and escalation decisions.
6. Run the guarded dev capacity profile with current cost/logging estimates and stop thresholds before production expansion. Record real adoption/support/margin evidence instead of treating technical tests as customer outcomes.

## Verification

Logs and browser images are retained in ignored `artifacts/product-followup-2026-09-26/`; additional task-specific artifact paths are noted by their accompanying reports. [Local baselines and outcome measurement](product/local-baselines-and-outcome-measurement.md) documents reproducible authentication, spreadsheet and analytical SQL checks with their limits. Local authentication's isolated 80-request sample measured p50 25.65 ms and p95 49.71 ms at concurrency four; these figures exclude cloud and telemetry transport costs.

| Check | Final implementation evidence |
| --- | --- |
| Identity | Full suite: **147 passed**, zero failures/errors/skips, including the opt-in local authentication benchmark. Cached test contexts close before their disposable databases stop. |
| Operations | Full suite: **150 passed**, zero failures/errors/skips, including private files, required creation keys, concurrent replay and RLS. |
| Platform | Full suite: **288 passed**; the subsequent canonical reporting changes passed **24 focused tests**. |
| School-core | Full suite exercised **843 tests**, with 842 passing and one new pagination fixture error. The fixture was corrected; the final **45 focused tests passed**, including pagination, annual-plan RLS/confirmation and forced-RLS fee migration. This was a focused rerun, not a second full-suite run. |
| Frontend | Full suite: **372 passed across 70 files**. |
| Production frontend build | **Passed**, including TypeScript and the final urgent-request responsive correction. |
| Browser regressions | Final full run: **106 passed**, zero skips/flaky tests, across 15 files at four workers. Includes actual 320px panel visits, password reset, notebook flows, portal resilience, navigation, runtime and token checks. The user's `zz-p7` capture script and four PNGs were excluded and their hashes remained unchanged. |
| Gateway and contracts | **87 gateway tests** and **7 generator tests** passed. The generated inventory is fresh at 435 mappings; ten clients cover 42 canonical operations. The static browser scan reports zero compatibility calls. |
| SQL tools | Receipt preflight and product-outcome fixture tests passed, including RLS visibility rejection, preserved receipts, source-data immutability and explicit paise units. |
| Static checks | Runtime, authorization, schema, contract, data-boundary and default-schema audits passed; all seven guarded infrastructure classes remain consistent; changed-file whitespace check passed. One OpenAPI CI invariant passed locally; three routing checks require `pwsh` and were skipped on this Windows shell. |

The expanded mobile browser sweep now opens the navigation drawer and requires each panel to be visited; the old harness silently skipped failed clicks. It exposed a 62px urgent-request overflow at 320px. The form now wraps its action buttons and uses the existing grid columns for its title. The final coherent browser rerun passed all 106 checks. Browser APIs are mocked and the run uses Chromium; five mobile-header contrast checks in the navigation report remained incomplete because of overlap, so this is not full accessibility or deployed-service certification. The build retains existing warnings about large lazily loaded spreadsheet chunks; local spreadsheet measurements are documented separately, without claiming cloud performance certification.
