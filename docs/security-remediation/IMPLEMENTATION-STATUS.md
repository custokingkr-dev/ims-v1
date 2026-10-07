# Security implementation and verification ledger

The requested multi-agent implementation covers all 34 findings and 12 assurance tasks. This ledger separates implemented source, controlled tests and live acceptance. **All seven application services are deployed to dev; the security plan still has open live acceptance criteria.** Production has not been changed.

## Latest source, CI and successful dev release

### Additional acceptance work on 2026-10-07

The follow-up implementation moves broadcast owner-policy HTTP outside database transactions and revalidates exact PostgreSQL row versions before approval, queueing or reservation. The local full platform suite passed 385 tests, with zero failures/errors/skips. This code is awaiting its new dev release; the application release below remains the deployed baseline. See [the lock-boundary change and verification](policy-http-lock-boundary.md).

Actual dev synthetic Pub/Sub delivery, duplicate suppression, isolated dead-letter routing and repaired replay passed; marker projections were erased and their two tombstones retained. Fresh-process persistence will be checked after the next platform deployment. This did not send email or SMS. See [broker evidence](dev-synthetic-pubsub-delivery.md).

The bounded live two-school read probe passed 240 requests with p95 422 ms, using the documented database memory Usage component guard and unchanged CPU/connection guardrails. Synthetic export, erasure, rollback, after-commit photo cleanup and restored tombstone replay tests passed. The new PITR drill checks only two reserved fixture markers on an isolated clone. These are bounded acceptance checks, not peak-load certification or an approved retention/recovery policy. See [capacity and privacy scope](acceptance-capacity-privacy.md).

The separate [dev edge module](../../deploy/gcp/application-edge/README.md) validates HTTPS routing, TLS, WAF and flood controls with six mock-provider tests. It has not been applied: an owned DNS hostname, budget review and tested ingress/WebAuthn origin cutover are required. The Windows helper job and the new Terraform job now participate in the mandatory CI summary gate. This paragraph records implementation and local checks; remote CI, image scans, the new release and final cleanup will be recorded after they finish.

PR 314 implemented the security changes; PR 316 added the owner-only repair-table deny policies and PR 317 corrected full-release orchestration. The latest merged dev commit is `9509d20c4413dafd1196eaf9815f83cf4d3d2fb8`. [CI 37523104488](https://github.com/custokingkr-dev/ims-v1/actions/runs/37523104488) passed the complete application suites. [Orchestration CI 37524381259](https://github.com/custokingkr-dev/ims-v1/actions/runs/37524381259) passed and correctly skipped unchanged application suites. [CodeQL 37524380978](https://github.com/custokingkr-dev/ims-v1/actions/runs/37524380978) passed both languages; the reviewed PR 317 ref has zero open alerts, which is not a repository-wide historical alert count.

[Final dev release 37524985447](https://github.com/custokingkr-dev/ims-v1/actions/runs/37524985447) succeeded for all seven services: all release test suites, five isolated owner migration jobs, seven serial Cloud Deploy rollouts and seven immutable image approvals passed. School V38 installed explicit denial policies on the two owner-only repair tables without weakening the startup guard. All seven digest-bound scan verdicts passed: one new school image scan plus six matching cached passing verdicts within the 24-hour policy. Those six scans were not rerun. Every final revision was ready, matched its runtime image digest and served 100% traffic; gateway health was UP and frontend HTTP returned 200. Private Java HTTP statuses were not probed by that release artifact.

The first release stopped safely at school startup; that partial attempt and two operator-canceled attempts remain in the historical evidence. Final signed transport/application caller rejection passed 15 checks and anonymous authorization passed 12 checks. Fresh anonymous final frontend header/browser checks passed with exact revision/image linkage. Exact image hashes verify gateway User=node and frontend User=nginx; this proves configured image User, not observed Cloud Run process UID. Actual secret access revocation passed: 11 memberships removed, all seven current revisions unchanged, two IAM ancestor policies checked, migration owner and identity signer access retained, no secret values read. Shared `app_rt` LOGIN is disabled and sessions are zero; its owner-only retirement job was independently confirmed absent. Legacy ACLs remain for migration callbacks. Independent final catalog checks passed for all five roles (identity has zero RLS tables and uses application authorization): no foreign-table CRUD, 79 existing RLS-enabled owned-schema relations forced with policies, exact repair-table denials and app_rt NOLOGIN. Seven exact ready revisions and scale caps were verified. All five authenticated Scheduler targets returned 2xx; provider receipt, actual load/SLOs, old-instance drain and real tenant/passkey/broker/privacy exercises remain open. These probes do not establish real tenant user authorization or WebAuthn enrollment. See [the release report](dev-security-release.md) and [sanitized release gates](dev-release-gates.json).

## Findings

| ID | Current status and evidence |
| --- | --- |
| SEC-01 | Implemented: active step permission+role and independent approver; real PostgreSQL denials/races tested. |
| SEC-02 | Implemented: locks, expected versions and legal state transitions; real PostgreSQL tests. |
| SEC-03 | Implemented guards/FORCE migrations; five live isolated roles prepared and inspected; all five Java dedicated runtime cutovers and owner migrations completed; school V38 preserves explicit owner-only denial. Runtime owner/shared secret access was revoked and gateway signing authority removed; shared app_rt LOGIN disabled/zero sessions verified; retained baseline ACLs remain, independent final catalog passed all five roles and 79 existing RLS-enabled owned-schema relations forced with policies; real tenant-row matrix remains open. |
| SEC-04 | Implemented exact signed machine capabilities and principal carriers; adversarial paths tested; actual signed transport/carrier/machine rejection checks passed; real tenant user matrix remains pending. |
| SEC-05 | Implemented WebAuthn UV, session-bound step-up and independent dual-admin recovery; real PostgreSQL/browser verification. Real dev enrollment pending. |
| SEC-06 | Implemented bound/purpose-pinned HS256 and rotation; tests passed. Old unbound tokens require relogin. |
| SEC-07 | Implemented explicit cookie request provenance in gateway and identity; local/browser tests passed. |
| SEC-08 | Implemented Web Locks and logout epochs; genuine two-tab browser proof. Unsupported-browser limitation documented. |
| SEC-09 | Implemented recoverable transient session restore and accurate memory-token threat model; tests passed. |
| SEC-10 | Implemented complete image response deadline; controlled HTTP tests passed. |
| SEC-11 | Implemented proxy whole-body deadlines, cancellation and backpressure; gateway tests passed. |
| SEC-12 | Implemented enforced CSP and real built-container browser/header proof; anonymous live frontend security headers/browser checks passed. |
| SEC-13 | Updated dependency owners/lockfiles/Jackson/runtime patches; npm audits zero. Final run 37524985447 passed seven digest-bound verdicts: one fresh school scan and six verified cached verdicts; future changes require current digest-bound evidence. |
| SEC-14 | Clean gateway dependency install and lock alignment; latest full CI passed 99 tests; initial PR 314 checkpoint passed 90 tests. |
| SEC-15 | Implemented preauth budgets, explicit proxy trust and bounded verified-user quota groups; real loopback two-user/revocation tests passed. Protected edge/shared-IP live design pending domain. |
| SEC-16 | Implemented connect-bound DNS validation, redirects/HTTPS443 and private-address rejection; bounded fixtures passed. External egress policy remains separately reviewed. |
| SEC-17 | Implemented multipart, workbook expansion/cells, decoding concurrency and body limits; boundary fixtures passed. |
| SEC-18 | Implemented recursive PDF active-content rejection, root confinement and browser download behavior; Windows symlink fixture was skipped locally; actual Linux CI catalog storage suite passed 8/8 with the symlink case executed. This is not antivirus/CDR. |
| SEC-19 | Implemented centralized safe CSV text; dangerous prefixes covered. |
| SEC-20 | Implemented deployed dashboard auth-off refusal, pinned origin and query limits; local/container tests. Live dashboard deployment pending authorized environment. |
| SEC-21 | Implemented managed shared Firestore replay/revocation state and stable secret requirements; Terraform validated. Live cross-instance/TTL proof pending. |
| SEC-22 | Implemented and tested nonroot gateway/frontend port8080; immutable release image gates passed; anonymous live HTTP cannot prove process UID, and all seven runtime rollouts passed; exact deployed image hashes verify gateway User=node/frontend User=nginx; observed process UID is not claimed. |
| SEC-23 | Implemented private photo caches/short signed TTL; actual18-object dev metadata correction. Old caches cannot be retrospectively revoked. |
| SEC-24 | Implemented legacy URL restrictions and reviewed GCS handling; compatibility fixtures passed. |
| SEC-25 | Implemented typed scoped idempotent locked billing payments; actual PostgreSQL financial/race tests passed. |
| SEC-26 | Implemented exact decimal arithmetic and explicit historical tax metadata; financial fixtures passed. |
| SEC-27 | Implemented authoritative audit provenance and durable actor quotas; PostgreSQL tests passed. |
| SEC-28 | Implemented transactional inbox/version ordering/digest/tombstones and deletion fences; PostgreSQL reorder/erasure tests passed. |
| SEC-29 | Implemented owner-owned student projection, removed foreign SQL and added live owner fee-recipient consent/contact capability; PostgreSQL tests passed. |
| SEC-30 | Implemented safe errors and terminal erasure fences/redaction; retention schedules, provider/backup privacy drill and operational policy remain pending. |
| SEC-31 | Implemented bounded relay futures/batch/dispatch and authenticated reset drain; source tests passed. Policy HTTP remains bounded within short row-lock transaction; all five Scheduler jobs enabled and authenticated targets returned 2xx; external SMTP/provider receipt and scale-zero workload proof remain open. |
| SEC-32 | Implemented explicit pool/scaling budgets and overlap/migration reserve;source 198/200 budget model and local bounded pool probe implemented; seven exact ready revisions/scale caps verified. Read-only metrics do not certify the full model, old-instance drain or real workload/SLOs; memory gauge discrepancy requires review. |
| SEC-33 | Implemented CRLF-safe generated drift checks and LF attributes; checks passed. |
| SEC-34 | Implemented typed financial/workflow mutations and contract convergence; contract schemas/wrappers and strict version parsing verified; current UI uses independent FF approval routes. |

## Verification tasks

| ID | Current evidence and remaining criteria |
| --- | --- |
| VER-01 | Controlled role/tenant/object tests implemented; actual two-school live browser matrix pending. |
| VER-02 | Dev IAM/ingress/bucket/role metadata inspected; five roles prepared, ownerless migrations implemented; all Java dedicated runtime cutovers completed; owner/shared secret access revocation passed with 11 removals and unchanged seven revisions; shared app_rt LOGIN disabled and zero sessions verified; independent final five-role/RLS catalog readback passed; real tenant-row matrix remains open. Actual signed transport/caller boundary checks passed; real tenant user/object tests remain open. |
| VER-03 | JWT/session/CSRF/passkey/recovery tests passed; live reset delivery and session revocation drill pending. |
| VER-04 | Real Chromium two-tab, WebAuthn and built nginx CSP/preview/header tests passed; anonymous deployed frontend header/browser smoke passed; authenticated live user/passkey acceptance remains pending. |
| VER-05 | Prepared SQL/identifier analysis and bounded parser cases passed; broader injection surface review tracked in source reports. |
| VER-06 | Controlled request parsing/header tests available; four actual loopback HTTP/1.1 ambiguous framing cases rejected400 before application dispatch. Edge/HTTP2/upstream parser assurance remains pending. No live network stress. |
| VER-07 | Controlled slow body/DNS/redirect/hostile PDF/workbook/CSV tests passed. actual Linux CI symlink/storage suite passed 8/8 without skips. |
| VER-08 | Real PostgreSQL workflow/payment races, idempotency, outbox rollback and RLS tests passed. |
| VER-09 | Signed machine caller/source event replay/reordering/tombstone tests passed; four topics/four subscriptions have zero metadata/IAM configuration findings; actual broker delivery/restart/replay drill remains pending. |
| VER-10 | Configured seven-service caps/readiness and bounded local PostgreSQL pool probe verified; old-instance drain UNKNOWN, source 198 model unproven by sampled live metrics, deployed load/SLO and conflicting memory gauge review remain pending. |
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
- [Initial prepared dev role proof](dev-runtime-role-proof.json)
- [Final five-role/RLS catalog](dev-final-runtime-role-catalog.json)
- [Final capacity metadata and explicit limits](dev-final-capacity.json)
- [Final Scheduler configuration](dev-final-scheduler-config.json), [request history](dev-final-scheduler-delivery.json)
- [Operational corrections](dev-operational-corrections.md)
- [Actual dev photo cache proof](dev-photo-cache-proof.json)
- [Actual isolated dev PITR recovery and cleanup](dev-pitr.md)

The original implementation checkpoint passed 46 deployment fixtures; the later nine-test revocation suite included a transport-deadline regression. All 24 offline architecture/deployment stages passed. Five Java service-wide dev scaling caps were set to two before cutover. Existing instances may persist temporarily, so this does not certify transition capacity. Application CI, CodeQL and all seven digest-bound image vulnerability verdicts passed; six final verdicts reused verified cached scans. All seven dev application rollouts completed. The remaining acceptance criteria are listed below.

## Final CI test counts

Counts below come from final successful follow-up CI 37523104488 matrix job logs, selecting each service's final aggregate once. Java totals were independently checked against unique test class counts; shared reports and reactor totals were not added again. Evidence: `dev-release-gates.json` follow-up CI aggregates.

| Suite | Final CI tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| Identity | 183 | 182 | 1 |
| Platform | 380 | 380 | 0 |
| Operations | 195 | 195 | 0 |
| Billing | 95 | 95 | 0 |
| School | 893 | 893 | 0 |
| Gateway | 99 | 99 | 0 |
| Frontend unit | 431 | 431 | 0 |
| Frontend browser | 116 | 116 | 0 |

All listed final CI suites have zero failures/errors. Java alone totals **1,746 tests: 1,745 passed and one intentional opt-in benchmark skip**. The final follow-up gateway suite passed **99 tests**; the earlier 90-test checkpoint is preserved only as historical evidence. The Linux school catalog storage subset passed **8/8**, including actual symlink enforcement, and is already included in school's total.

## Remaining acceptance owners and actions

All feasible dev deployment, metadata, role/secret cutover and request-level boundary steps in this session are evidenced. The following criteria require dedicated fixtures, external systems or explicit owner decisions; they are not risk acceptance or a claim of complete attack coverage.

| Owner | Open acceptance action |
| --- | --- |
| Product/security administrators | Supply isolated two-school user fixtures; execute real role/object authorization matrix and privileged WebAuthn enrollment/recovery. |
| Identity/security operators | Rehearse managed key rotation, session revocation and independently audited emergency recovery without MFA downgrade. |
| Messaging/provider operators | Verify SMTP/provider receipt and alerts with controlled queue records; test idle/scale-zero durable delivery and broker restart/replay/DLQ handling. Authenticated Scheduler request 2xx is already evidenced. |
| Platform/database operations | Certify real workload SLOs and pool reserve, establish old-instance drain, and investigate conflicting database memory gauges. Read-only sampled metrics are insufficient. |
| Data/privacy owners | Approve retention periods and run multi-store erasure/provider/backup restore plus deletion replay; establish measured RPO/RTO. Schema-only PITR is evidenced separately. |
| Cloud/repository owners | Complete dashboard shared-state TTL/cross-instance checks, protected edge/custom-domain design and administrator-owned repository/production controls. |

## Final release sequence

1. Require source/contract/security tests and fresh image/advisory gates.
2. Reconcile migration IAM and exact dev deployment targets.
3. Execute all owner migrations using the same immutable images in isolated jobs.
4. Cut over all seven services, confirm health and real authorization behavior.
5. Remove owner/shared database secret access from runtime identities; inspect dedicated roles and FORCE policies after migration.
6. Verify authenticated Scheduler, recovery, privacy and alerts. Owner-controlled repository/edge settings remain open until evidenced.

Rollback must preserve authorization, MFA, dedicated roles and tombstones; do not restore broad credentials as a shortcut.
