# Security implementation and verification ledger

The requested multi-agent implementation covers all 34 findings and 12 assurance tasks. This ledger separates implemented source, controlled tests and live acceptance. **The plan is not yet fully closed or deployed.** Production has not been changed.

## Latest source, CI and partial dev release

PR [314](https://github.com/custokingkr-dev/ims-v1/pull/314) merged to dev at `bb59dc2f`. Final [CI run 37516333730](https://github.com/custokingkr-dev/ims-v1/actions/runs/37516333730) and [CodeQL run 37516332173](https://github.com/custokingkr-dev/ims-v1/actions/runs/37516332173) passed; both CodeQL language analyses passed and the reviewed open-alert count was zero. This is source and scan evidence, not proof that every live acceptance criterion is closed.

The explicit [dev release 37517387391](https://github.com/custokingkr-dev/ims-v1/actions/runs/37517387391) passed all seven service release suites and immutable image digest scans. Identity, frontend and gateway rolled out. The release stopped at school startup; school, operations, platform and billing cutover remain pending. School's dedicated runtime role has safe attributes and no privileged memberships or ownership. Its startup guard rejects two existing owner-only student repair tables with enabled/FORCE RLS and no policies. A forward migration preserving explicit denial is being prepared; no guard bypass or broad credential rollback is authorized.

Actual anonymous live frontend HTTP/browser checks passed, including deployed security headers: `tmp/live-frontend-security-smoke-corrected.json`. That evidence does not establish runtime UID or authenticated user permissions. Final signed transport/carrier, real user matrix, WebAuthn enrollment, Scheduler delivery and remaining service rollout checks remain open.

## Findings

| ID | Current status and evidence |
| --- | --- |
| SEC-01 | Implemented: active step permission+role and independent approver; real PostgreSQL denials/races tested. |
| SEC-02 | Implemented: locks, expected versions and legal state transitions; real PostgreSQL tests. |
| SEC-03 | Implemented guards/FORCE migrations; five live isolated roles prepared and inspected; identity cutover completed, school fails closed on owner-only zero-policy repair tables, remaining service cutover pending. |
| SEC-04 | Implemented exact signed machine capabilities and principal carriers; adversarial paths tested; live caller drill pending. |
| SEC-05 | Implemented WebAuthn UV, session-bound step-up and independent dual-admin recovery; real PostgreSQL/browser verification. Real dev enrollment pending. |
| SEC-06 | Implemented bound/purpose-pinned HS256 and rotation; tests passed. Old unbound tokens require relogin. |
| SEC-07 | Implemented explicit cookie request provenance in gateway and identity; local/browser tests passed. |
| SEC-08 | Implemented Web Locks and logout epochs; genuine two-tab browser proof. Unsupported-browser limitation documented. |
| SEC-09 | Implemented recoverable transient session restore and accurate memory-token threat model; tests passed. |
| SEC-10 | Implemented complete image response deadline; controlled HTTP tests passed. |
| SEC-11 | Implemented proxy whole-body deadlines, cancellation and backpressure; gateway tests passed. |
| SEC-12 | Implemented enforced CSP and real built-container browser/header proof; anonymous live frontend security headers/browser checks passed. |
| SEC-13 | Updated dependency owners/lockfiles/Jackson/runtime patches; npm audits zero. All seven immutable dev release image scans passed in run 37517387391; future changes require fresh scans. |
| SEC-14 | Clean gateway dependency install and lock alignment; final CI 90 tests passed; the complete follow-up gateway suite passed 99 tests. |
| SEC-15 | Implemented preauth budgets, explicit proxy trust and bounded verified-user quota groups; real loopback two-user/revocation tests passed. Protected edge/shared-IP live design pending domain. |
| SEC-16 | Implemented connect-bound DNS validation, redirects/HTTPS443 and private-address rejection; bounded fixtures passed. External egress policy remains separately reviewed. |
| SEC-17 | Implemented multipart, workbook expansion/cells, decoding concurrency and body limits; boundary fixtures passed. |
| SEC-18 | Implemented recursive PDF active-content rejection, root confinement and browser download behavior; Windows symlink fixture was skipped locally; actual Linux CI catalog storage suite passed 8/8 with the symlink case executed. This is not antivirus/CDR. |
| SEC-19 | Implemented centralized safe CSV text; dangerous prefixes covered. |
| SEC-20 | Implemented deployed dashboard auth-off refusal, pinned origin and query limits; local/container tests. Live dashboard deployment pending authorized environment. |
| SEC-21 | Implemented managed shared Firestore replay/revocation state and stable secret requirements; Terraform validated. Live cross-instance/TTL proof pending. |
| SEC-22 | Implemented and tested nonroot gateway/frontend port8080; immutable release image gates passed; anonymous live HTTP cannot prove process UID, and remaining runtime rollout is pending. |
| SEC-23 | Implemented private photo caches/short signed TTL; actual18-object dev metadata correction. Old caches cannot be retrospectively revoked. |
| SEC-24 | Implemented legacy URL restrictions and reviewed GCS handling; compatibility fixtures passed. |
| SEC-25 | Implemented typed scoped idempotent locked billing payments; actual PostgreSQL financial/race tests passed. |
| SEC-26 | Implemented exact decimal arithmetic and explicit historical tax metadata; financial fixtures passed. |
| SEC-27 | Implemented authoritative audit provenance and durable actor quotas; PostgreSQL tests passed. |
| SEC-28 | Implemented transactional inbox/version ordering/digest/tombstones and deletion fences; PostgreSQL reorder/erasure tests passed. |
| SEC-29 | Implemented owner-owned student projection, removed foreign SQL and added live owner fee-recipient consent/contact capability; PostgreSQL tests passed. |
| SEC-30 | Implemented safe errors and terminal erasure fences/redaction; retention schedules, provider/backup privacy drill and operational policy remain pending. |
| SEC-31 | Implemented bounded relay futures/batch/dispatch and authenticated reset drain; source tests passed. Policy HTTP remains bounded within short row-lock transaction; external mail/scale-zero proof pending. |
| SEC-32 | Implemented explicit pool/scaling budgets and overlap/migration reserve;198/200 admitted budget. Local bounded pool probe passed; real workload load certificate pending. |
| SEC-33 | Implemented CRLF-safe generated drift checks and LF attributes; checks passed. |
| SEC-34 | Implemented typed financial/workflow mutations and contract convergence; contract schemas/wrappers and strict version parsing verified; current UI uses independent FF approval routes. |

## Verification tasks

| ID | Current evidence and remaining criteria |
| --- | --- |
| VER-01 | Controlled role/tenant/object tests implemented; actual two-school live browser matrix pending. |
| VER-02 | Dev IAM/ingress/bucket/role metadata inspected; five roles prepared, ownerless migrations implemented; identity cutover completed, school/other Java cutovers and shared-role retirement pending. Signed caller checks remain required. |
| VER-03 | JWT/session/CSRF/passkey/recovery tests passed; live reset delivery and session revocation drill pending. |
| VER-04 | Real Chromium two-tab, WebAuthn and built nginx CSP/preview/header tests passed; anonymous deployed frontend header/browser smoke passed; authenticated live user/passkey acceptance remains pending. |
| VER-05 | Prepared SQL/identifier analysis and bounded parser cases passed; broader injection surface review tracked in source reports. |
| VER-06 | Controlled request parsing/header tests available; four actual loopback HTTP/1.1 ambiguous framing cases rejected400 before application dispatch. Edge/HTTP2/upstream parser assurance remains pending. No live network stress. |
| VER-07 | Controlled slow body/DNS/redirect/hostile PDF/workbook/CSV tests passed. actual Linux CI symlink/storage suite passed 8/8 without skips. |
| VER-08 | Real PostgreSQL workflow/payment races, idempotency, outbox rollback and RLS tests passed. |
| VER-09 | Signed machine caller/source event replay/reordering/tombstone tests passed; actual PubSub restart/caller graph drill pending. |
| VER-10 | Admission budget and bounded local PostgreSQL pool probe passed; deployed load/SLO validation remains pending. |
| VER-11 | WIF policy reviewed, release/image gates preserved. Branch protection requires repository admin; final CI/CodeQL passed, reviewed open CodeQL alerts zero, and all seven immutable image release scans passed. Source changes after that release need new image evidence. |
| VER-12 | Dev backups/PITR/TLS enabled and fresh backup successful. Isolated schema-only PITR recovered all12 schemas in about12.6 minutes; exact temporary job and clone deletion verified; agreed RPO/RTO and operational alert/revocation exercise pending. |

## Evidence

- [Identity/platform](identity-platform.md)
- [School/files](school-files.md)
- [Workflow/billing](workflow-billing.md)
- [Migration isolation](migration-process.md)
- [Capacity/delivery](capacity-and-delivery.md)
- [Gateway/runtime](gateway-and-runtime.md)
- [Browser](browser.md), [container evidence](browser-evidence.json)
- [Dashboard](dashboard.md)
- [Actual prepared dev role proof](dev-runtime-role-proof.json)
- [Actual dev photo cache proof](dev-photo-cache-proof.json)
- [Actual isolated dev PITR recovery and cleanup](dev-pitr.md)

Final deployment fixture batch passed 46 tests; a further secret transport deadline test passed in the nine-test revocation suite. All24 offline architecture/deployment stages passed after service-wide Java scaling caps were added. Root applied those five dev service-wide caps to2 before application cutover; existing instances may persist temporarily, so this does not certify transition capacity. Final CI, CodeQL and all seven immutable release image scans passed. Application rollout is partial as recorded above; remaining live acceptance and cutovers are still required.

## Final CI test counts

Counts below come from final successful matrix job logs, selecting each service's final aggregate once. Java totals were independently checked against unique test class counts; shared reports and reactor totals were not added again. Evidence: `tmp/ci-37516333730-final-identity-platform-operations-billing-gateway.json` and `tmp/ci-37516333730-final-school-frontend.json`.

| Suite | Final CI tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| Identity | 183 | 182 | 1 |
| Platform | 380 | 380 | 0 |
| Operations | 195 | 195 | 0 |
| Billing | 95 | 95 | 0 |
| School | 892 | 892 | 0 |
| Gateway | 90 | 90 | 0 |
| Frontend unit | 431 | 431 | 0 |
| Frontend browser | 116 | 116 | 0 |

All listed final CI suites have zero failures/errors. Java alone totals **1,745 tests: 1,744 passed and one intentional opt-in benchmark skip**. Gateway's later complete follow-up suite passed **99 tests**; it is separate evidence and is not added to the 90-test CI count. The Linux school catalog storage subset passed **8/8**, including actual symlink enforcement, and is already included in school's total.

## Final release sequence

1. Require source/contract/security tests and fresh image/advisory gates.
2. Reconcile migration IAM and exact dev deployment targets.
3. Execute all owner migrations using the same immutable images in isolated jobs.
4. Cut over all seven services, confirm health and real authorization behavior.
5. Remove owner/shared database secret access from runtime identities; inspect dedicated roles and FORCE policies after migration.
6. Verify authenticated Scheduler, recovery, privacy and alerts. Owner-controlled repository/edge settings remain open until evidenced.

Rollback must preserve authorization, MFA, dedicated roles and tombstones; do not restore broad credentials as a shortcut.
