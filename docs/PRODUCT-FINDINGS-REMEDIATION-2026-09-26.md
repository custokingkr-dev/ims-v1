# Product findings remediation — 26 September 2026

This records implementation against [the product assessment](PRODUCT-DEEP-ANALYSIS-2026-09-26.md). The assessment remains a historical baseline. Changes are in the working tree, alongside preserved pre-existing user changes. They have not been deployed.

The [second implementation pass](PRODUCT-FOLLOWUP-IMPLEMENTATION-2026-09-26.md) supersedes the pending-code status below for password reset, shared quotas, private quotation files, broadcast dry-run dispatch, and API contract migration. External deployment/provider/acceptance gates are still distinguished from local implementation.

## Implemented findings

| Finding | Result | Evidence |
| --- | --- | --- |
| F1: duplicate logical payments | Collection requires a tenant-scoped idempotency key. Retries return the original payment and receipt; changing the request under an existing key returns conflict. The frontend preserves unresolved collections for safe retry. | PostgreSQL retry, simultaneous-request, payload-conflict and tenant-isolation tests; frontend recovery tests. |
| F2: historical year rewritten | Payment targets an explicit assignment/year; omitted selectors can only resolve the current year. Historical settlement preserves its assignment year and does not rewrite current enrollment status. | Historical/current-year integration cases. |
| F3: concurrency and receipt collisions | Transactional assignment locks and a conditional balance update prevent overpayment. A database sequence and reserved receipt namespace provide unique new receipt numbers. Existing duplicate receipt numbers remain unchanged and ambiguous number lookups are rejected. | Concurrent collection and migration tests, including a historical duplicate fixture. |
| F4: caller-controlled attribution | Payment attribution comes from the authenticated tenant context, including compatibility paths. | Spoofed-actor tests. |
| F5: truncated/mislabelled invoice statistics | SQL aggregates the entire dataset. Monthly issue counts use explicit bounds and a configured reporting timezone. The UI says Invoice analytics and distinguishes billed value from revenue or profit. | More-than-500 invoice, invalid legacy date, timezone-boundary and empty-dataset tests. |
| T1: broadcast claims | Backend capabilities describe permitted draft/approval actions and the actual dispatch restriction. Approval waits for the server; approved drafts are labelled not sent. | Capability, dispatch-policy and frontend failure/retry tests. |
| T2: suggestion acceptance presented as execution | Review opens the actual workflow. Acceptance is identified as a recorded decision. Unsupported AI/confidence claims are removed; Why this exposes available source/reason/freshness. Secondary dashboard information is collapsed. | Dashboard behavior tests; extracted broadcast component. |
| T3: fake quotation uploads | The form explicitly records document references. It no longer presents a selected filename as an uploaded document or asserts unimplemented automatic vendor evaluation. Confirmed requests/quotations survive subsequent-step retries. | Procurement partial-save and document-reference tests. |
| T4: unsupported login paths | Stub SSO, dead reset, placeholder contact, static status and unsupported security assertions are removed. Access help explains the administrator-assisted path. | Login unit tests and local browser inspection. |
| U1: task continuity | Validated panel URLs support links, refresh and browser history. User/school-scoped memory drafts retain admission, attendance and list filters during panel navigation. Dirty work receives an exit warning. | Navigation, entitlement, draft subscription and remount tests. |
| U2: admission partial success | A confirmed student ID survives photo failure and panel remount. Retry uploads only the photo; saved student fields are protected from accidental re-creation. | Admission/photo failure and refresh-failure tests. |
| U3: inconsistent accessibility | Shared dialog semantics, focus entry/trapping/restoration, Escape behavior, nested-dialog handling and control labels cover modals/drawers. Channel selection is semantic; student-review fetch failures are visible. | Dialog/Field tests and browser keyboard checks. |
| Zone placeholder | Zone admins can read their own zone and its active assigned schools. Cross-zone reads and writes remain prohibited; SELECT-only RLS and connection-scope reset provide database isolation. | Controller permission tests and PostgreSQL RLS tests including pooled-scope reuse. |
| Hidden navigation and drawer animation | Labels are visible by default, with the saved compact preference retained. Drawer progress no longer animates width. Active procurement labels use a darker existing color token. | Desktop/mobile navigation, contrast and overflow browser checks. |
| Stale access claims after logout/access removal | Gateway introspects every authenticated request. Identity checks the persisted access session, disabled-user state and current scope. Logout/reuse revokes access across the family; ordinary refresh rotation preserves valid concurrent access. | Gateway rejection/current-scope tests and real PostgreSQL family-revocation tests. |
| Fee report query growth | Installment/late-fee inputs are batched for report scopes instead of fetched per assignment. | Constant-query-count regression. |

## Operational implications

- Apply forward migrations `fee/V10__payment_integrity.sql` and `tenant_school/V28__zone_admin_reference_reads.sql` through the normal service release. Do not edit applied migrations or renumber historical receipts.
- The exact collection/recovery contract and historical receipt handling are documented in [Fee payment integrity](product/fee-payment-integrity.md).
- Release the fee backend and matching frontend together. Old clients without `idempotencyKey` will receive validation errors and must reload/update; accepting unkeyed writes would restore the duplicate-payment risk.
- Receipt numbers use `RCPT-V2-…`. A historic number shared by several records must be resolved using the payment ID and reconciled by an authorized accountant; this change does not guess which historical receipt is correct.
- Invoice reporting defaults to UTC; configure `billing.reporting-time-zone` (`BILLING_REPORTING_TIME_ZONE`) explicitly if the platform reports in another zone. Stored invoice dates remain calendar dates, with creation time used only for malformed/missing legacy dates.
- Authoritative authentication adds an identity/database round trip to each authenticated request. The gateway has a 10-second introspection deadline and fails closed on unavailability. Re-measure identity load and latency in development before promotion; the old throughput measurements do not establish the capacity of this revision.
- Admission/attendance drafts live in memory and are cleared when the authenticated workspace changes. A reload warns about unsaved work; it does not silently persist student records in browser storage.
- Unresolved payment recovery has different durability needs: retaining the original request/key is necessary after a lost response. Its stored payload is scoped to the user and school, excludes the display name, and is removed after confirmed completion. Original payment notes must remain unchanged while resolution is pending because they are part of the replay fingerprint.

## External acceptance and decisions still required

These are not represented as completed code fixes:

| Item | Current evidence / next concrete step |
| --- | --- |
| Branch protection | Live read-only checks on 26 September show no rulesets, protection endpoints returning 404, and the current GitHub principal having write but no admin permission. The existing apply script completed its dry-run and verified `summary`, `analyze (java-kotlin)` and `analyze (javascript-typescript)` from GitHub Actions app 15368 on commit `5500ed7d4bf4e10801ef1c8ab858f3cd61608e83`. A repository administrator must run `bash scripts/enable-branch-protection.sh --apply` after reviewing [the existing proposal](branch-protection-proposal.md). |
| Deployment | All eight production Cloud Run services reported Ready during the read-only check. This is health evidence for their existing revisions, not evidence that these local fixes are running. No cloud or GitHub writes were made. |
| Real-school acceptance | A named school, accountable support owner, test recipients/consent and acceptance date are still needed. Complete reconciled import, independent attendance, fee retry/receipt review, procurement evidence and recovery with that cohort. Local fixtures cannot substitute for school acceptance. |
| Communication delivery | General broadcast dispatch remains blocked by the existing policy-evidence requirement. Provider credentials alone do not satisfy recipient consent, category, dispatch and delivery evidence. No external messages were sent. |
| Private quotation file storage | The delivered scope is an honest document-reference workflow. Private upload, retention and authorized retrieval remain a separately designed capability; no file-storage success is implied. |
| Capacity and fleet quotas | The gateway limiter remains per replica. A shared school quota needs an agreed resource model and deployment design. Use the existing guarded `scripts/invoke-dev-load-certification.ps1 -Profile StaffWorkload` workflow with a reviewed development target, synthetic fixture and cost/logging estimates to measure this revision. No unbounded cloud load was launched. |
| Commercial/product decisions | Target segment, cohort, pricing, supplier cost/margin, queue ownership/escalation and willingness to pay require business evidence. The report's proposed adoption/cost-to-serve metrics are definitions for that work, not fabricated measurements. |
| Broader architecture | Generated API coverage and module decomposition remain incremental architecture work. This change addresses the identified correctness paths and concentrated dashboard/report behavior; it does not claim every compatibility endpoint is migrated or every repository is decomposed. |

## Verification

Verification logs are retained under ignored `artifacts/product-analysis-2026-09-26/`; fee-specific logs are under `artifacts/fee-integrity-tests.log`.

| Check | Final result |
| --- | --- |
| All five Java services, full service suites via Maven reactor subsets | **1,396 tests passed**, zero failures/errors/skips: billing 59, identity 127, operations 125, platform 272, school-core 813. PostgreSQL/Testcontainers ran locally. |
| Frontend Vitest, `npm.cmd test -- --maxWorkers=4` | **327 tests passed across 65 files.** One school-management test initially timed out during the unbounded parallel run; it passed in isolation and in the complete run with four workers. |
| Frontend production build | **Passed.** Existing large spreadsheet-chunk warnings remain; these are lazily loaded assets, not a measured initial-page transfer. |
| Standard gateway `npm.cmd test`, including contract freshness checks | **84 tests passed.** Generated route inventory is current at 411 controller mappings. |
| Existing browser regressions: auth, navigation drawer, responsive audit, superadmin portal audit | **57 tests passed.** An initial 4.43:1 active-nav contrast failure was fixed using the existing darker orange token; a pre-existing malformed regex in the user-added test was also repaired. Browser API fixtures are mocked; this does not certify live provider or full deployed-service behavior. |
| OpenAPI generated client | **Current**, covering the existing three identity operations. No claim of complete API coverage. |
| Runtime/service-authorization boundary audits | **Passed.** |
| Guarded duplicate infrastructure classes | **All seven remain consistent.** |
| Changed-file whitespace check | **Passed.** |
| Design detector | Two width-animation findings removed. One existing module-color accent warning remains a contextual design choice, not a verified usability defect. |
| Additional review | Independent source review found no further defects in the changed identity/gateway, billing aggregate or zone authorization paths. Local login/help disclosure inspected in the browser. |

No deployment, real payment, provider message, production load test or customer acceptance was performed. Pre-existing user edits were preserved; two small corrections to existing browser tests are noted above and in the diff.
