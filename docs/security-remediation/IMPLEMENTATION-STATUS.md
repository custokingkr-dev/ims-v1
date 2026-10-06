# Security implementation and verification ledger

The requested multi-agent implementation covers all34 findings and12 assurance tasks. This ledger separates implemented source, controlled tests and live acceptance. **The plan is not yet fully closed or deployed.** Production has not been changed.

## Findings

| ID | Current status and evidence |
| --- | --- |
| SEC-01 | Implemented: active step permission+role and independent approver; real PostgreSQL denials/races tested. |
| SEC-02 | Implemented: locks, expected versions and legal state transitions; real PostgreSQL tests. |
| SEC-03 | Implemented guards/FORCE migrations; five live isolated roles prepared and inspected; deployment/cutover pending. |
| SEC-04 | Implemented exact signed machine capabilities and principal carriers; adversarial paths tested; live caller drill pending. |
| SEC-05 | Implemented WebAuthn UV, session-bound step-up and independent dual-admin recovery; real PostgreSQL/browser verification. Real dev enrollment pending. |
| SEC-06 | Implemented bound/purpose-pinned HS256 and rotation; tests passed. Old unbound tokens require relogin. |
| SEC-07 | Implemented explicit cookie request provenance in gateway and identity; local/browser tests passed. |
| SEC-08 | Implemented Web Locks and logout epochs; genuine two-tab browser proof. Unsupported-browser limitation documented. |
| SEC-09 | Implemented recoverable transient session restore and accurate memory-token threat model; tests passed. |
| SEC-10 | Implemented complete image response deadline; controlled HTTP tests passed. |
| SEC-11 | Implemented proxy whole-body deadlines, cancellation and backpressure; gateway tests passed. |
| SEC-12 | Implemented enforced CSP and real built-container browser/header proof; live headers pending. |
| SEC-13 | Updated dependency owners/lockfiles/Jackson/runtime patches; npm audits zero. Fresh complete image scan gate pending. |
| SEC-14 | Clean gateway dependency install and lock alignment; standard94 tests passed. |
| SEC-15 | Implemented preauth budgets, explicit proxy trust and bounded verified-user quota groups; real loopback two-user/revocation tests passed. Protected edge/shared-IP live design pending domain. |
| SEC-16 | Implemented connect-bound DNS validation, redirects/HTTPS443 and private-address rejection; bounded fixtures passed. External egress policy remains separately reviewed. |
| SEC-17 | Implemented multipart, workbook expansion/cells, decoding concurrency and body limits; boundary fixtures passed. |
| SEC-18 | Implemented recursive PDF active-content rejection, root confinement and browser download behavior; Windows symlink test skipped, Linux CI pending. This is not antivirus/CDR. |
| SEC-19 | Implemented centralized safe CSV text; dangerous prefixes covered. |
| SEC-20 | Implemented deployed dashboard auth-off refusal, pinned origin and query limits; local/container tests. Live dashboard deployment pending authorized environment. |
| SEC-21 | Implemented managed shared Firestore replay/revocation state and stable secret requirements; Terraform validated. Live cross-instance/TTL proof pending. |
| SEC-22 | Implemented and tested nonroot gateway/frontend port8080; image/runtime release checks pending. |
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
| VER-02 | Dev IAM/ingress/bucket/role metadata inspected; five roles prepared, ownerless migrations implemented; runtime cutover/owner secret revocation pending. |
| VER-03 | JWT/session/CSRF/passkey/recovery tests passed; live reset delivery and session revocation drill pending. |
| VER-04 | Real Chromium two-tab, WebAuthn and built nginx CSP/preview/header tests passed; final deployed browser checks pending. |
| VER-05 | Prepared SQL/identifier analysis and bounded parser cases passed; broader injection surface review tracked in source reports. |
| VER-06 | Controlled request parsing/header tests available; four actual loopback HTTP/1.1 ambiguous framing cases rejected400 before application dispatch. Edge/HTTP2/upstream parser assurance remains pending. No live network stress. |
| VER-07 | Controlled slow body/DNS/redirect/hostile PDF/workbook/CSV tests passed. Windows symlink skip requires Linux evidence. |
| VER-08 | Real PostgreSQL workflow/payment races, idempotency, outbox rollback and RLS tests passed. |
| VER-09 | Signed machine caller/source event replay/reordering/tombstone tests passed; actual PubSub restart/caller graph drill pending. |
| VER-10 | Admission budget and bounded local PostgreSQL pool probe passed; deployed load/SLO validation remains pending. |
| VER-11 | WIF policy reviewed, release/image gates preserved. Branch protection requires repository admin; fresh signed/SBOM image scan evidence pending release. |
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

Final deployment fixture batch passed 46 tests; a further secret transport deadline test passed in the nine-test revocation suite. All24 offline architecture/deployment stages passed after service-wide Java scaling caps were added. Root applied those five dev service-wide caps to2 before application cutover; existing instances may persist temporarily, so this does not certify transition capacity. The final immutable image scans, CodeQL checks and application rollout remain required.

## Final release sequence

1. Require source/contract/security tests and fresh image/advisory gates.
2. Reconcile migration IAM and exact dev deployment targets.
3. Execute all owner migrations using the same immutable images in isolated jobs.
4. Cut over all seven services, confirm health and real authorization behavior.
5. Remove owner/shared database secret access from runtime identities; inspect dedicated roles and FORCE policies after migration.
6. Verify authenticated Scheduler, recovery, privacy and alerts. Owner-controlled repository/edge settings remain open until evidenced.

Rollback must preserve authorization, MFA, dedicated roles and tombstones; do not restore broad credentials as a shortcut.
