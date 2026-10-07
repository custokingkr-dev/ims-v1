# Security implementation and verification ledger

The requested multi-agent implementation covers all 34 findings and 12 assurance tasks. This ledger separates implemented source, controlled tests and live acceptance. **All seven application services are deployed to dev; the security plan still has open live acceptance criteria.** Production has not been changed.

## Remaining-work follow-up ? 2026-10-07

Additional review found and fixed unbounded Google Drive metadata/pagination and SMS template variables that could overwrite validated routing/correlation. The full local school suite ran899 tests:898 passed, one Windows symlink privilege skip, zero failures/errors. The focused platform suites passed18 tests. These application follow-ups require mandatory CI and dev deployment; the previous full seven-service release remains the live baseline until that completes. [Drive/DNS scope](outbound-upload-followup.md), [SMS and fresh configuration scope](acceptance-remaining-dev-config.md), [local counts](acceptance-remaining-local-tests.json).

The six existing dashboard security identities were imported into the established dev state; unrelated managed state was independently unchanged. No infrastructure apply or web deployment occurred. The targeted plan proposes no creation/deletion/replacement and only reviewed display/description drift. Three Terraform mock runs and13 helper tests passed, including four actual local PostgreSQL replay/export checks. The expired Firestore fixture still returned200; asynchronous physical cleanup remains unobserved. [State adoption](acceptance-dashboard-state-adoption.md).

The new external-journal toolkit generates bounded, reviewed dev-only export, immutable storage and isolated replay plans; it executes no cloud/SQL operations or delivery resume. Precommit erasure durability, durable photo cleanup, operational journal provisioning and full restore fencing remain unimplemented. Generic live MSG91 inbox delivery also remains blocked pending current-owner consent and uncertain-outcome handling. These are explicit unfinished code/contract work, not merely missing credentials. [Journal design and limits](operational-erasure-journal.md), [updated remaining fix plan](acceptance-owner-actions.md).

## Current source and dev acceptance ? 2026-10-07

[PR 319](https://github.com/custokingkr-dev/ims-v1/pull/319) merged the final application changes into dev as `f901c5f0631d9fcff617374c8e569401c1af84d6`. [Implementation CI 37604084992](https://github.com/custokingkr-dev/ims-v1/actions/runs/37604084992) passed all mandatory gates, including Windows helpers, six dev-edge Terraform tests and both changed Java suites. [CodeQL 37604084653](https://github.com/custokingkr-dev/ims-v1/actions/runs/37604084653) passed both languages; the reviewed PR 319 merge ref has zero open alerts. That is a scoped result, not a repository-wide historical alert count. [Exact implementation CI evidence](acceptance-implementation-ci.json).

The [full dev release 37614977208](https://github.com/custokingkr-dev/ims-v1/actions/runs/37614977208) succeeded: all seven service suites, five owner migrations, seven serial rollouts, signing/approval steps and readiness checks passed. Independent readback verified seven exact Ready revisions/digests with100% traffic and absence of all five owner jobs. Image gates used two fresh scans and five verified exact cached verdicts within24hours. Fresh frontend header/CSP/browser and runtime secret/ancestor readback passed. [Final release report](acceptance-final-dev-release.md). The successful earlier source `9509d20c4413dafd1196eaf9815f83cf4d3d2fb8` and release 37524985447 remain historical baseline evidence in [the earlier release report](dev-security-release.md); they are not represented as the latest source.

Broadcast owner-policy HTTP now executes outside database transactions and row locks, followed by short exact-row claims and full binding/evidence-expiry validation. The full platform suite passed 385 tests, including real PostgreSQL slow-policy/ABA/competing-outcome checks. Actual provider submission remains after committed durable reservation. [Lock boundary implementation](policy-http-lock-boundary.md).

Actual dev acceptance passed independently school-bound logins/session revocation (8 checks), object/carrier denials (6), software CTAP2 passkey enrollment/assertion/replay/step-up (22), independent dual-admin recovery (19), refresh concurrency/reuse fencing (10), and isolated managed signing-key rotation. The rotation clone never changed main identity signing configuration and its resources were independently removed. Real Firestore cross-instance atomic claim/revocation and the actual dashboard auth wrapper passed. Physical TTL deletion was still unobserved at the later readback; expiry/revocation enforcement does not rely on TTL cleanup. Dashboard web OAuth deployment remains open. [Identity and dashboard evidence](acceptance-identity-dashboard.md).

Actual broker delivery, duplicate suppression, isolated dead-letter routing and repaired replay passed. After the new platform revision, persisted dedupe/tombstones suppressed a duplicate and late upsert. Actual same-origin software passkeys authorized20 synthetic student DELETEs; all20 outbox records published and processed, all20 student tombstones retained, and no source/reporting student rows remained. The actual uploaded photo current/pinned live generation is unavailable; its recovery copy follows unchanged seven-day soft-delete retention, with no physical purge claim. Three ordinary actors were disabled with password and old-token401; parent fixtures removed,20student+2broker tombstones/audits retained and exact jobs absent. Initial harness errors/rollback are retained. [Final live evidence](acceptance-final-live.md), [eight exact local private-file absences](acceptance-local-private-cleanup.json).

The bounded two-school read probe passed 240 requests at two requests per second, p95 422 ms. Documented memory Usage component, CPU and connection guards passed; this does not certify peak workload or the 198/200 modeled pool budget. Synthetic privacy/rollback/after-commit cleanup/restore-replay tests passed. The isolated dev PITR recovered all 12 schemas and exact two fixture markers, with job and clone absence verified. Its cloud clone became ready in about 14 minutes 21 seconds, but operator interruption delayed validation to about 101 minutes 46 seconds; this does not establish a passing 30-minute usable-application RTO. Recovery targets and retention remain unapproved. [Capacity/privacy scope](acceptance-capacity-privacy.md), [actual marker PITR](acceptance-pitr-marker.md).

The dev edge module validates HTTPS/TLS/routing and WAF/rate controls with six mock-provider tests; it is not applied. Owned DNS, budget and tested ingress/auth-origin cutover remain necessary. All four bounded current-ingress HTTP/2 streams were observed: the normal control succeeded and malformed streams reset, but one reset used INTERNAL_ERROR instead of the strict PROTOCOL_ERROR expectation. The initial strict-conformance result remains failed; no exploit is established. [Edge preparation](../../deploy/gcp/application-edge/README.md), [HTTP/2 observations](acceptance-http2-parser.md).

The dev registry now has additive KEEP protection and28 verified tags for18 reviewed baseline/current index/runtime digests, with existing cleanup semantics preserved. This is configured retention, not manual-deletion immutability. [Image protection](acceptance-registry-rollback-protection.md).

The live Scheduler failure metric now includes the fifth identity job, with exact filter readback and unchanged descriptor. Actual alert/provider recipient receipt remains open. A concrete read-only inventory identifies 198 obsolete unsafe dev revisions while retaining current/secure/unknown rollback revisions; no revisions have been deleted because deletion is irreversible and explicit approval is pending. [Metric correction](acceptance-scheduler-metric.md), [deletion review](dev-obsolete-revision-dry-run.md).

Historical successful boundary evidence includes 15 signed caller checks, 12 anonymous denials, immutable configured image Users (not observed process UID), 11 legacy runtime secret membership removals, app_rt NOLOGIN/zero sessions, and independent catalog validation of five dedicated roles and 79 forced existing RLS relations. No production target was changed. These historical results and the new exact-release results retain their separate dates/scopes.

## Findings

| ID | Current status and evidence |
| --- | --- |
| SEC-01 | Implemented: active step permission+role and independent approver; real PostgreSQL denials/races tested. |
| SEC-02 | Implemented: locks, expected versions and legal state transitions; real PostgreSQL tests. |
| SEC-03 | Implemented and deployed dedicated runtime roles, owner-only migrations and FORCE guards; 79 existing owned RLS relations independently inspected. Legacy runtime owner/shared secret access removed, app_rt NOLOGIN/zero sessions, baseline migration ACLs retained. Actual two-school actor/object and VIEWER matrix now executed; this is a bounded fixture matrix, not every possible permission combination. |
| SEC-04 | Implemented exact signed machine capabilities and principal carriers; actual signed transport negatives plus live forged branch/actor/carrier and foreign-object denials passed. |
| SEC-05 | Implemented UV, session-bound step-up and independent dual-admin recovery. Actual dev software CTAP2 enrollment/assertion/replay/expiry and recovery passed; physical hardware interoperability remains open. |
| SEC-06 | Implemented purpose/audience-bound HS256 and session fences; isolated managed clone rotation accepted overlap and rejected retired keys. Main signing secret unchanged; old unbound tokens require relogin. |
| SEC-07 | Implemented explicit cookie request provenance in gateway and identity; local/browser tests passed. |
| SEC-08 | Implemented Web Locks and logout epochs; genuine two-tab browser proof. Unsupported-browser limitation documented. |
| SEC-09 | Implemented recoverable transient session restore and accurate memory-token threat model; tests passed. |
| SEC-10 | Implemented complete image response deadline; controlled HTTP tests passed. |
| SEC-11 | Implemented proxy whole-body deadlines, cancellation and backpressure; gateway tests passed. |
| SEC-12 | Implemented enforced CSP and real built-container browser/header proof; anonymous live frontend security headers/browser checks passed. |
| SEC-13 | Dependency/lock/runtime updates implemented; npm audits zero and digest-bound Trivy release gates passed. Final release evidence distinguishes fresh scans from exact cached verdicts; no universal vulnerability-free claim. |
| SEC-14 | Clean gateway dependency install and lock alignment; latest full CI passed 99 tests; initial PR 314 checkpoint passed 90 tests. |
| SEC-15 | Implemented preauth budgets, explicit proxy trust and bounded verified-user quota groups; real loopback two-user/revocation tests passed. Protected edge/shared-IP live design pending domain. |
| SEC-16 | Implemented connect-bound DNS validation, redirects/HTTPS443 and private-address rejection; bounded fixtures passed. External egress policy remains separately reviewed. |
| SEC-17 | Implemented multipart, workbook expansion/cells, decoding concurrency and body limits; boundary fixtures passed. |
| SEC-18 | Implemented recursive PDF active-content rejection, root confinement and browser download behavior; Windows symlink fixture was skipped locally; actual Linux CI catalog storage suite passed 8/8 with the symlink case executed. This is not antivirus/CDR. |
| SEC-19 | Implemented centralized safe CSV text; dangerous prefixes covered. |
| SEC-20 | Dashboard auth-off refusal, pinned origin and query limits implemented. Actual wrapper replay/logout/expiry checks against Firestore passed; real OAuth web deployment remains open. |
| SEC-21 | Managed named Firestore, exact conditional get/create IAM, stable managed secret and cross-instance replay/revocation passed. TTL ACTIVE, physical expired-document deletion not yet observed; exact Terraform state adoption passed, while web OAuth deployment remains open. |
| SEC-22 | Implemented and tested nonroot gateway/frontend port8080; immutable release image gates passed; anonymous live HTTP cannot prove process UID, and all seven runtime rollouts passed; exact deployed image hashes verify gateway User=node/frontend User=nginx; observed process UID is not claimed. |
| SEC-23 | Implemented private photo caches/short signed TTL; actual18-object dev metadata correction. Old caches cannot be retrospectively revoked. |
| SEC-24 | Implemented legacy URL restrictions and reviewed GCS handling; compatibility fixtures passed. |
| SEC-25 | Implemented typed scoped idempotent locked billing payments; actual PostgreSQL financial/race tests passed. |
| SEC-26 | Implemented exact decimal arithmetic and explicit historical tax metadata; financial fixtures passed. |
| SEC-27 | Implemented authoritative audit provenance and durable actor quotas; PostgreSQL tests passed. |
| SEC-28 | Transactional inbox, versions/digests, tombstones and terminal deletion fences implemented. Actual broker duplicate/DLQ/repaired replay and new-revision persistence passed;20 actual student DELETEs converged to20 processed deletion events/tombstones and zero source/projections. |
| SEC-29 | Implemented owner-owned student projection, removed foreign SQL and added live owner fee-recipient consent/contact capability; PostgreSQL tests passed. |
| SEC-30 | Safe errors and terminal erasure redaction/fences implemented; synthetic restore replay prevents resurrection/provider calls. Operational durable external erasure journal, approved retention, provider deletion and full recovery acceptance remain open. |
| SEC-31 | Bounded relays and authenticated drain/reset implemented. Broadcast owner-policy HTTP is now outside transactions/locks with exact short claims and expiry/binding revalidation. Generic inbox delivery provider I/O remains in its existing transaction. Five Scheduler targets returned2xx; metric covers all five; actual SMTP/provider/incident receipt remains open. |
| SEC-32 | Explicit pool/scaling and migration/overlap budgets implemented. Bounded live240 reads passed with documented memory Usage/CPU/connection guards. This does not certify peak SLOs, actual global reserve or physical old-instance drain; irreversible198-revision cleanup awaits approval. |
| SEC-33 | Implemented CRLF-safe generated drift checks and LF attributes; checks passed. |
| SEC-34 | Implemented typed financial/workflow mutations and contract convergence; contract schemas/wrappers and strict version parsing verified; current UI uses independent FF approval routes. |

## Verification tasks

| ID | Current evidence and remaining criteria |
| --- | --- |
| VER-01 | Controlled authorization tests plus actual two-school SCHOOL_ADMIN own/foreign lists and objects, forged carriers, session revocation and VIEWER read/write/admin denials executed. Scope is reserved synthetic fixtures. |
| VER-02 | Dedicated roles/runtime secret revocation/FORCE catalog and signed caller boundary evidence passed; live actor/tenant/object controls now executed. Production or exhaustive role matrices are not claimed. |
| VER-03 | Actual dev passkey enrollment/assertion/replay/expiry, independent recovery, refresh race/reuse fencing, session revocation and isolated managed key rotation passed. Real delivery/reset receipt and physical keys remain open. |
| VER-04 | Full Chromium regression suite, live frontend headers and actual dev browser CTAP2 passkey/recovery checks passed. Software authenticators do not establish physical hardware interoperability. |
| VER-05 | Prepared SQL/identifier analysis and bounded parser cases passed; broader injection surface review tracked in source reports. |
| VER-06 | Four loopback HTTP/1.1 ambiguous cases rejected400 before application dispatch. Four bounded current-ingress HTTP/2 controls observed; malformed streams rejected but one reset code failed strict conformance. Future owned-edge/upstream assurance remains open; no stress or exploit claim. |
| VER-07 | Controlled slow body/DNS/redirect/hostile PDF/workbook/CSV tests passed. actual Linux CI symlink/storage suite passed 8/8 without skips. |
| VER-08 | Real PostgreSQL workflow/payment races, idempotency, outbox rollback and RLS tests passed. |
| VER-09 | Actual reserved-topic delivery/duplicate suppression, isolated DLQ and repaired replay passed. Post-new-revision persisted tombstone/inbox proof passed; no in-flight crash/full backlog or email/SMS provider receipt claim. |
| VER-10 | Exact runtime scale metadata and local pool fixtures verified. Live240 reads passed p95 422ms and documented memory Usage guard. Peak/global budget, agreed SLO and physical drain remain unproved; read-only198-revision deletion review prepared. |
| VER-11 | Pinned WIF/release/image gates and mandatory CI/CodeQL passed. Repository enforcement readback requires administrator access; scoped CodeQL count is not repository historical assurance. New exact seven-service application release passed; evidence is tracked above. |
| VER-12 | Private encrypted source backups/PITR remain enabled. Actual isolated recovery verified12 schemas+2marker checksums and independent resource absence. Operator interruption prevents a30minRTOpass. Approved RPO/RTO, full application recovery, external erasure journal and alert recipient exercise remain open. |

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

## Final release test counts

These totals come from the completed service-test jobs of release37614977208, each final aggregate counted once. All have zero failures/errors.

| Suite | Tests | Passed | Skipped |
| --- | ---: | ---: | ---: |
| Identity |183|182|1|
| Platform |385|385|0|
| Operations |195|195|0|
| Billing |95|95|0|
| School |893|893|0|
| Gateway |99|99|0|
| Frontend unit |431|431|0|
| Frontend browser |116|116|0|

Java totals **1,751 tests:1,750 passed and one intentional opt-in benchmark skip**. The full release suite totals2,397 tests,2,396 passed and one skip. Thirty-one additional controlled acceptance-helper tests passed locally, and the updated PITR helper parses under PowerShell5. These are separate from the application totals. The follow-up evidence/helper changes require mandatory remote CI before merging.

## Remaining acceptance owners and actions

[The concrete remaining fix/acceptance plan](acceptance-owner-actions.md) lists the next steps and completion evidence for owned domain/edge, repository administrator protections, dashboard OAuth, provider/incident receipt, physical security keys, approved retention and recovery, representative capacity/drain, irreversible revision cleanup and upstream reset-code conformance. Passing source and dev tests does not close these externally dependent criteria. No owner decision or risk acceptance is inferred from silence.

## Final release sequence

1. Require source/contract/security tests and fresh image/advisory gates.
2. Reconcile migration IAM and exact dev deployment targets.
3. Execute all owner migrations using the same immutable images in isolated jobs.
4. Cut over all seven services, confirm health and real authorization behavior.
5. Remove owner/shared database secret access from runtime identities; inspect dedicated roles and FORCE policies after migration.
6. Verify authenticated Scheduler, recovery, privacy and alerts. Owner-controlled repository/edge settings remain open until evidenced.

Rollback must preserve authorization, MFA, dedicated roles and tombstones; do not restore broad credentials as a shortcut.
