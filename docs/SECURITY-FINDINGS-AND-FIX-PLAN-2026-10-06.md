# Security findings and fix plan

Latest parallel work: [owner execution boundaries](security-remediation/owner-execution-boundaries-2026-10-09.md) fixes callback credential reuse, recovery evidence privileges and implicit dashboard publication. [Frozen local evidence](security-remediation/acceptance-owner-execution-local.json) records platform498/498 and school985 passed/one Windows skip out of986, independent reviews and current prior-image nested-JAR inventory. Exact new Linux CI/dev acceptance is pending at this checkpoint. Nine owner groups plus native applicability and snapshot custody remain open.


Latest dated follow-up: [recovery/provider findings and fix plan](security-remediation/remaining-security-completion-2026-10-08.md) and [native advisory matrix](security-remediation/remaining-round-native-advisory-research.md). Three source gaps are fixed and locally verified; new CI/dev acceptance and eleven owner-dependent action/finding groups remain required.


Current implementation, deployment and remaining acceptance criteria are tracked in [the security implementation ledger](security-remediation/IMPLEMENTATION-STATUS.md) and [the dev release report](security-remediation/dev-security-release.md). This document preserves the original 2026-10-06 assessment; its initial status is historical.

This is the consolidated remediation register for the IMS reviews dated 6 October 2026. It preserves findings from both the [architecture and security review](CODEBASE-ARCHITECTURE-SECURITY-REVIEW-2026-10-06.md) and the [attack assessment](CYBERSECURITY-ATTACK-ASSESSMENT-2026-10-06.md), including code weaknesses, conditional risks, reliability/privacy debt and verification gaps. Those reports retain detailed source references, research links and test evidence.

Reviewed commit: `df3f894a3c87951d2dc7dd78f8dda6791f01252e`, plus the working tree at review time. **All seven application services are now deployed to dev at `9509d20c`; final operational proofs and remaining acceptance criteria are recorded in the current ledger. Production was not changed. The [implementation and verification ledger](security-remediation/IMPLEMENTATION-STATUS.md) records current evidence and outstanding acceptance criteria. The original finding descriptions below are retained; source implementation alone does not certify deployed security.** Suggested owners are responsibilities, not assignments to named people. Priorities describe execution order, not scanner severity or proven production exploitability.

## Evidence and priorities

| Classification | Meaning |
| --- | --- |
| Reproduced | Actual implementation behavior observed in a controlled local experiment |
| Source | Missing control or behavior identified in reviewed code |
| Conditional | Impact depends on configuration, attacker capability or further reachability evidence |
| Verification | A control needs real database, browser, deployment or operational evidence |
| Debt | Maintainability or reliability issue affecting confidence or security boundaries |

P1 means the first remediation batch or deployment verification batch. P2 means the next hardening batch. P3 means follow-on structural maintenance. A failed deployment check that reveals public private-service access, cross-tenant access or privileged database runtime credentials should be escalated immediately rather than waiting for its planned batch.

Evidence already collected: 154 targeted tests in the architecture review and 182 additional targeted tests in the attack review passed. The image fetch experiment exceeded a configured 5,000 ms timeout, returning after 7,472 ms. These results do not establish live RLS, IAM, full E2E or resistance to all attacks. Standard generated-file checks had CRLF comparison failures; the direct gateway test run also used an installed dependency tree older than its lockfile.

## Findings register

### Authorization and identity

| ID | Priority and evidence | Finding and fix | Acceptance criterion | Suggested owner |
| --- | --- | --- | --- | --- |
| SEC-01 | P1 Source | Workflow approval ignores step `required_permission`/`required_role`. Enforce the active step's authority and business entity policy using the authenticated actor; explicitly define initiator self-approval policy. | A user with only `workflow:act` cannot approve a step requiring another permission. Every role/step combination is tested, with no row/event changes on denial. | Operations backend |
| SEC-02 | P1 Source | Workflow completion/cancellation lack prior-state restrictions; action reads/updates lack an explicit lock or version compare. Define a state machine, reject illegal terminal transitions and serialize or compare versions transactionally. | Pending or rejected instances cannot become completed through an unauthorized shortcut. Concurrent decisions produce one valid progression and consistent action history. | Operations backend |
| SEC-03 | P1 Source/Verification | Runtime DB guard checks username only and exists in only two RLS services. Add checks for SUPERUSER, BYPASSRLS, effective role memberships and ownership to every tenant-scoped service. Inspect actual policies and FORCE RLS requirements. | Startup rejects privileged runtime credentials; real PostgreSQL tests prove tenant separation and connection-context cleanup on success/error/reuse. | Backend and database |
| SEC-04 | P2 Conditional | Shared service-token possession plus trusted transport can confer broad caller authority; contextless permission helpers skip user checks. Model caller identity and allowed routes explicitly; distinguish peer capability from user authority. | Missing user context works only on approved machine routes for an authorized peer; other mutations deny access. Token naming/scope-string metadata is never treated as enforced privilege. | Gateway and backend |
| SEC-05 | P2 Source | No first-party privileged MFA/passkey flow found. Add phishing-resistant MFA and step-up for role changes, bulk export, permanent deletion and sensitive financial actions, with protected recovery/emergency access. | Privileged flows require verified factors; recovery cannot silently bypass the requirement; factor changes and emergency use are audited. | Identity and frontend |
| SEC-06 | P2 Source | JWT purpose/issuer/audience binding is incomplete and HMAC gives verifiers potential signing authority. Define allowed algorithms, token purpose, issuer/audience and key rotation; evaluate asymmetric signing where useful. | Wrong-purpose/issuer/audience/algorithm tokens are rejected; old/new key rotation works deliberately; authoritative session introspection remains mandatory. | Identity and gateway |
| SEC-07 | P2 Conditional | SameSite=None refresh cookies allow Origin-less requests. Define a browser-compatible Origin/Referer/Fetch Metadata or CSRF-proof policy, including legitimate nonbrowser callers. | Hostile, null and absent-origin cases have explicit expectations; rejected refresh/logout requests cannot change session state. | Gateway and identity |
| SEC-08 | P2 Conditional | Cross-tab refresh coordination is absent; strict reuse detection may revoke a legitimate session family during concurrent refresh. Reproduce in browsers before selecting browser locking or carefully bounded server handling. | Multiple tabs refresh without avoidable logout; genuine stolen-token reuse still revokes the family. Test logout/reset races across replicas. | Identity and frontend |
| SEC-09 | P2 Source/Debt | Transient refresh failure clears client auth state; memory-token comments overstate XSS protection. Distinguish temporary availability failure from confirmed session invalidity and correct the documentation. | A transient upstream outage has deliberate retry/recovery UX; invalid/revoked sessions still clear credentials; no durable access-token storage is introduced. | Frontend |

### Availability browser and dependencies

| ID | Priority and evidence | Finding and fix | Acceptance criterion | Suggested owner |
| --- | --- | --- | --- | --- |
| SEC-10 | P1 Reproduced/Source | Image body reads outlive request timeout; some early-exit streams are unclosed. Add an entire-body deadline, cancellation, guaranteed stream closure and bounded fetch concurrency. | A controlled slow-body server is stopped within the configured total deadline and small scheduling tolerance; redirects/rejections release resources; valid images still work. | School core |
| SEC-11 | P1 Source | Gateway proxy lacks consistent explicit deadlines/disconnect cancellation and ignores writable backpressure. Add operation-aware timeout budgets, abort propagation and drain-aware streaming. | Stalled upstream, slow client and abandoned export tests show bounded lifetime/memory and released upstream work. Preserve streaming exports and identity's existing introspection deadline. | Gateway |
| SEC-12 | P1 Source | Direct frontend HTML lacks CSP/framing/security headers. Configure headers on actual nginx documents and errors, including location inheritance; validate CSP in report-only mode before enforcing. | Login/dashboard/index/error responses expose intended policy; framing is blocked unless explicitly needed; allowed image/API origins work and unexpected script execution is blocked. | Frontend and deployment |
| SEC-13 | P1 Package evidence/Conditional | Lockfile audit flags grpc-js, tinypool/Vitest, source-map-js and brace-expansion graph entries. Upgrade via compatible dependency owners, align test tools and regenerate lockfiles. Assess reachable use separately from scanner severity. | Clean installs pass relevant tests and fresh dependency/image scans; remaining advisories have documented scope, mitigation and accountable disposition. See source reports for exact advisory links. | Frontend, gateway and build |
| SEC-14 | P1 Debt | Installed gateway SDK differs from declared/locked dependency version. Reproduce verification from an isolated clean lockfile install. | `npm ls` shows no relevant invalid dependency; standard and direct gateway checks run on the same locked graph used in CI/builds. | Gateway and build |
| SEC-15 | P2 Source/Conditional | Edge limiter uses syntactically valid bearer values before verification and the leftmost forwarded IP. Define trusted proxy hops and independent pre-auth limits to resist key churn. | Random bearer/IP-header churn cannot evade the pre-auth budget or force unbounded introspection; legitimate users behind shared networks remain supported. | Gateway and infrastructure |
| SEC-16 | P2 Conditional | Remote-image DNS checks retain a resolution-to-connect race; public HTTP(S) destinations and ports allow wider remote-fetch abuse. Apply an egress policy/proxy or connection-bound validated resolution, HTTPS/port restrictions where compatible and per-user/school quotas. | Controlled rebinding, redirect and alternate-IP tests cannot reach private/metadata destinations; the production private-address guard remains enabled. | School core and infrastructure |
| SEC-17 | P2 Source/Verification | Decoder concurrency, workbook parser limits and ingress/multipart limits differ. Align documented limits; cap decoding, decompressed entries/cells/size/time and aggregate memory. | Boundary-size legitimate imports work consistently; malformed/oversized/bomb fixtures fail early with bounded memory/CPU and clear errors. | School core and gateway |
| SEC-18 | P2 Conditional | PDF checks are not malware scanning/CDR; local root normalization does not by itself prevent symlink escape. Choose document handling safeguards appropriate to download/preview use and isolate local storage. | Harmless malicious-structure fixtures are rejected or quarantined by policy; no executable active content reaches unsafe preview paths; symlink/encoded-path tests cannot escape the storage root. | School core and operations |
| SEC-19 | P2 Conditional | CSV escaping differs across attendance and import-result exports. Centralize safe spreadsheet export rules, including control-character prefixes and delimiter/quote handling. | Every CSV/TSV export preserves ordinary text and prevents formula interpretation for the tested dangerous prefixes in supported spreadsheet consumers. | Backend export owners |
| SEC-20 | P2 Source/Conditional | Dashboard auth-off configuration is not visibly limited to local use; expensive monitoring queries need bounds. Reject auth-off in deployed profiles, restrict ingress, pin public callback URL and bound query cost. | Deployed configuration cannot start unprotected; hostile forwarded host cannot redirect callbacks; repeated queries have bounded resource cost. | Dashboard and infrastructure |
| SEC-21 | P2 Conditional | Dashboard replay/revocation state is per-instance; random fallback session secrets complicate replicas/revisions. Use a stable managed secret and shared durable state when multi-instance operation is required. | Logout/replay prevention works across instances and revisions; secret rotation has defined session behavior. | Dashboard and infrastructure |
| SEC-22 | P3 Source | Gateway/frontend runtime images lack explicit nonroot users. Use high ports, nonroot users and deliberate minimal writable directories. | Containers start and pass health checks without root; required nginx temporary/cache paths work; image/runtime scans confirm intended users. | Build and infrastructure |

### Privacy financial integrity and event consistency

| ID | Priority and evidence | Finding and fix | Acceptance criterion | Suggested owner |
| --- | --- | --- | --- | --- |
| SEC-23 | P1 Source | Signed student photos use one-year public immutable caching. Choose private short-lived/no-store behavior, adjust TTL and migrate existing object metadata; define capability revocation expectations. | New and existing objects return the intended headers; CDN/browser behavior is checked; deletion/access-removal expectations explicitly account for downloaded copies. | School core and storage |
| SEC-24 | P2 Source/Conditional | Legacy external/cleartext photo URLs bypass the intended storage/privacy path. Inventory, migrate or restrict legacy sources and avoid leaking sensitive referrers. | Supported photos use the documented allowed scheme/host/storage policy; migration exceptions are inventoried and time-bounded. | School core and frontend |
| SEC-25 | P1 Source, privileged scope | Billing payments lack the reviewed school-fee replay/concurrency controls and accept map-supplied amount/attribution/branch fields. Use validated DTOs, positive monetary invariants, server-derived attribution, scoped idempotency/fingerprints, constraints and invoice locking. | SUPERADMIN authorization remains required; same-key replay is stable, changed replay conflicts, competing payments preserve balances and invalid amounts are rejected. Define refunds/reversals separately. | Billing |
| SEC-26 | P2 Debt | Floating-point discount/share and legacy calculations, fixed GST and currency assumptions need explicit financial semantics. Standardize minor units/decimal rounding and configurable policy where required. | Boundary/rounding cases reconcile to receipts and reports without silently changing existing records; historical tax policy remains traceable. | Billing and school fees |
| SEC-27 | P2 Source | Client audit ingest binds identity but permits asserted action/entity/outcome. Separate client telemetry from authoritative server audit; add quotas and generate business audit in trusted mutation paths. | Client entries cannot impersonate authoritative successful actions; sensitive mutations have trustworthy actor, tenant, request and outcome records. | Platform and domain owners |
| SEC-28 | P2 Source | Student projection upserts can overwrite newer data after event reordering; projection and inbox completion can fail separately. Add aggregate versions and stale-write rejection while preserving deletion tombstones and idempotent processing. | Old/new reordered events, duplicates and crash/retry scenarios cannot regress current data, resurrect deleted students or double-apply effects. | Platform and event producers |
| SEC-29 | P2 Debt | Platform recipient resolution joins student-owned tables via an explicit baseline exception. Move contact/consent resolution to an owner API or suitable projection, then reduce grants. | Recipient scope/consent remain correct; foreign-schema read privilege and baseline exception are removed after successful migration. | Platform and school core |
| SEC-30 | P2 Source/Verification | Storage errors expose some exception details; retention spans photos/import originals/projections/backups/evidence. Return generic request-ID errors and define end-to-end retention/deletion. | Client errors reveal no internal object/bucket/stack details; privacy drill verifies every relevant store and documents backup retention/recovery behavior. | Domain owners and privacy operations |
| SEC-31 | P2 Conditional | Scheduled workers/readiness flags do not prove actual Cloud Run CPU allocation or recovery mail delivery; long relay calls hold DB connections/locks. Verify deployment scheduling/drains and bound relay batches/publish time. | Idle/scale-to-zero recovery and broadcast/outbox work meet defined latency; upstream stalls do not exhaust DB pools; delivery failure alerts are exercised. | Backend and infrastructure |
| SEC-32 | P2 Verification | Pool/instance/revision overlap and trace flush/sampling can create capacity/cost pressure. Budget total connections and work across all replicas, jobs and migrations. | Measured bounded-load tests stay below database connection/memory budgets; sampling and scaling values are justified and monitored. | Infrastructure and backend |

### Verification tooling and architecture maintenance

| ID | Priority and evidence | Finding and fix | Acceptance criterion | Suggested owner |
| --- | --- | --- | --- | --- |
| SEC-33 | P3 Reproduced/Debt | Generated route inventory/client checks fail on CRLF despite normalized content equality. Set explicit LF attributes for generated outputs or normalize comparisons deliberately. | Unmodified generated files pass on Windows/Linux; a genuine route/client change still fails the drift check. | Build and contracts |
| SEC-34 | P3 Debt | Broad map mutations, mixed read/command repositories, historical aliases and stale docs increase authorization reasoning cost; Maven convergence has explicit exclusions. Gradually type sensitive mutations, document route policy, reconcile feature/config state and reduce convergence exceptions. | Sensitive fields are explicitly writable/validated; aliases share authorization tests; docs match service ownership; removed dependency exclusions are backed by convergence checks. | Backend, frontend and build |

## Verification backlog

These are open assurance tasks, not asserted vulnerabilities. They preserve the unresolved attack families from the attack matrix. Record a vulnerability only when a check establishes a failing control; otherwise retain the evidence of the tested defense.

| ID | Priority | Scope and required evidence | Depends on or complements |
| --- | --- | --- | --- |
| VER-01 | P1 | Run the complete role/object/property matrix with two schools, restricted users, school admins, assigned/unassigned operators and SUPERADMIN. Cover every canonical/compatibility/diagnostic route, nested/bulk IDs, exports, notifications, files and direct backend URLs. Denials must have no DB/event/object side effects. | SEC-01–04, SEC-25, SEC-34 |
| VER-02 | P1 | Inspect live ingress, IAM invoker graph, runtime DB attributes/RLS policies, pool tenant reuse, bucket public access/ACLs and secret grants. Capture current configuration evidence without exposing secret values. | SEC-03–04, SEC-16, SEC-23 |
| VER-03 | P2 | Exercise credential stuffing, account enumeration, reset expiry/replay/parallel redemption, JWT malformed/wrong-purpose cases, logout/reset/role disablement and assignment revocation across replicas. Validate quotas and actual cookie attributes. | SEC-05–09, SEC-15 |
| VER-04 | P2 | Browser-test stored/reflected/DOM XSS candidates, CORS origin variations, CSRF forms/fetch, framing, callbacks and cache behavior on actual deployed documents. Include error pages and third-party components. | SEC-07, SEC-12, SEC-20, SEC-23–24 |
| VER-05 | P2 | Audit dynamic SQL identifiers/order/filter builders, command/template/expression sinks, XML/document parser settings and unsafe deserialization reachability. Run bounded benign malformed inputs; absence of an obvious sink is not closure. | SEC-13, SEC-17–19, SEC-34 |
| VER-06 | P2 | In isolated staging, test the real HTTP intermediary chain for parser desynchronization, duplicate headers, encoded paths and cache-key confusion. Verify gateway header stripping and internal path blocking through every entry point. | SEC-04, SEC-11–12 |
| VER-07 | P2 | Use controlled servers/files for DNS rebinding, redirect/address variants, slow bodies, polyglots, PDF active content, ZIP/XLSX bombs, CSV formulas and traversal/symlinks. Bound experiment CPU/memory/time. | SEC-10, SEC-16–19 |
| VER-08 | P1 | Run real PostgreSQL workflow/payment concurrency, replay and rollback tests. Verify illegal states, self-approval policy, overflow/negative input and event/audit consistency. | SEC-01–02, SEC-25–27 |
| VER-09 | P2 | Verify Pub/Sub issuer/audience/caller identity and publisher/subscriber grants, duplicate/different-ID old events, dead letters, retries and projection crash recovery. | SEC-04, SEC-28, SEC-31 |
| VER-10 | P2 | Measure expensive search/report/import/export costs, timeout/cancellation, slow-client buffering and introspection pressure. Inspect edge DoS controls, max instances, queue limits and billing alerts. | SEC-10–11, SEC-15, SEC-17, SEC-20, SEC-31–32 |
| VER-11 | P1 | Inspect current CI/WIF subject/repository/branch/environment restrictions, branch protections, protected deployment gates and runner permissions. Verify exact digest/SBOM/provenance/signature policy on the deployed artifact, plus fresh Java/OS/image scans. | SEC-13–14, SEC-22, SEC-34 |
| VER-12 | P2 | Exercise alerts for authentication abuse, privileged changes, exports, failed ingestion and infrastructure failures. Drill session/secret revocation, evidence preservation, isolated backups/PITR, destructive-insider permissions and timed restoration. Record agreed RPO/RTO and measured results. | SEC-05, SEC-21, SEC-27, SEC-30–32 |

## Implementation sequence and dependencies

### Batch one close concrete control gaps

Implement SEC-01 and SEC-02 together: authority checks without transition/concurrency enforcement leave related workflow abuse possible. Complete VER-08 with actual PostgreSQL before closure.

Implement SEC-10 and SEC-11 in separate bounded I/O changes, retaining the local slow-body reproduction as a regression fixture. Do not remove the private-address checks to simplify timeout tests. Use the existing test-only controlled destination mechanism.

Remediate SEC-13 and SEC-14 together using clean installations. Fix SEC-33 early if its generated-file failures prevent dependable standard verification; its P3 label does not require blocking the first batch on known tooling noise.

Implement SEC-12 and SEC-23 with deployment verification. Photo work must include existing metadata, not only new uploads. CSP must cover the execution host and required origins rather than just API headers.

Complete SEC-03, SEC-25 and VER-01/02/08/11 before claiming hardened tenant isolation, billing integrity or deployment boundaries. Preserve migration histories; add forward migrations and evaluate existing data before introducing constraints.

### Batch two strengthen identity privacy and consistency

Address SEC-04–09 and SEC-15 with an explicit caller/authentication policy. MFA enrollment/recovery and token changes need migration and compatibility planning. Do not weaken replay detection to hide a tab-race failure, cache authorization indefinitely, or bypass introspection for enriched JWTs.

Address SEC-16–21 with parser, egress, dashboard and resource safeguards. Validate reasonable limits with representative legitimate files, supported spreadsheets and realistic dashboard queries.

Address SEC-26–32 through domain-focused changes. Introduce event version fields in producers before relying on them in consumers; define old-event compatibility/backfill behavior. Replace the student-schema join before revoking its grants. Privacy deletion must include operational retention and backups as documented policy.

Run VER-03–10 and VER-12 against the resulting configuration, resolving new failures as findings rather than treating the checklist as automatically complete.

### Batch three reduce ongoing maintenance risk

Complete SEC-22, remaining SEC-33 and SEC-34. Introduce nonroot runtime layouts with compatible health checks and writable directories. Improve typed contracts, alias policy coverage and current feature documentation without broad unrelated rewrites.

Rerun the relevant checks when code, policies, versions or features change. Review new advisories and deployed configuration periodically; the October snapshot is evidence for that snapshot, not an enduring guarantee.

## Rollout and closure rules

Each change should identify its finding IDs, affected services and routes, migration/configuration dependencies, validation evidence and rollback path. For database constraints, cache metadata, event schemas, MFA and key rotation, document effects that a code rollback cannot undo. Prefer canary release of verified immutable digests using existing pipeline protections.

Preserve authoritative session introspection, gateway trusted-header stripping, tenant context cleanup, existing fee idempotency/locking, event inbox deduplication, deletion tombstones and signed-digest deployment verification. Fixes must not regress these established defenses.

An item can move from **Open** to **Implemented** only after the code/configuration change exists and its targeted checks pass. Move it to **Verified** after the required real environment evidence is recorded. **Closed** requires meeting the acceptance criterion and documenting residual limitations; risk acceptance is a separate explicit decision with an owner and review date.

Use this record for each item as work proceeds:

| Field | Required content |
| --- | --- |
| Finding ID and status | SEC/VER ID; Open, Implemented, Verified, Closed or explicitly Risk accepted |
| Owner and change | Responsible person/team; commit/PR and configuration or migration reference |
| Evidence | Test command/result, synthetic scenario and deployment revision where applicable |
| Security outcome | Expected and actual response, persisted state, events, object access and audit record |
| Rollback and residual risk | Concrete recovery steps, irreversible effects and remaining limits |
| Closure | Acceptance criterion satisfied; reviewer and verification date |

No estimated dates or individual assignments have been invented. Schedule batches after assigning owners and confirming the available staging/cloud access. The immediate deliverable is this saved, reviewable plan with all review findings and unresolved assurance tasks tracked.
