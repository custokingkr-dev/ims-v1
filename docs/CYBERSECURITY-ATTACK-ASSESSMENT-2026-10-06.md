# IMS cybersecurity attack assessment

Current implementation, deployment and remaining acceptance criteria are tracked in [the security implementation ledger](security-remediation/IMPLEMENTATION-STATUS.md) and [the dev release report](security-remediation/dev-security-release.md). This document preserves the original 2026-10-06 assessment; its initial status is historical.

The consolidated [findings and fix plan](SECURITY-FINDINGS-AND-FIX-PLAN-2026-10-06.md) tracks remediation and verification work from both reviews.

The review found meaningful protections against unauthorized access, alongside weaknesses that should be addressed before treating the platform as hardened. The most important newly identified issue is workflow authorization: a tenant user with the general workflow action permission can reach actions that do not enforce the configured approval step authority or completion prerequisites. A separate local experiment reproduced an image download exceeding its configured request timeout.

This assessment covers the working tree at commit `df3f894a3c87951d2dc7dd78f8dda6791f01252e` on 6 October 2026. It combines source inspection, existing security tests, a controlled localhost experiment, dependency audit results, and current primary security references. It complements the [architecture and feature review](CODEBASE-ARCHITECTURE-SECURITY-REVIEW-2026-10-06.md). No production attack traffic was sent and no application implementation was changed.

No review can establish protection against every possible attack. This document maps the relevant attack families, identifies what the available evidence establishes, and specifies the remaining verification work. Passing mocked controller tests does not prove deployed IAM or database row security. An absence of an obvious injection sink does not prove every parser and dependency is safe.

## Scope and evidence

The system includes the React frontend, Node API gateway, identity, school core, operations, platform and billing services, PostgreSQL schemas, object storage, Pub/Sub consumers, operational dashboard, and build and deployment configuration. Its architecture is explained in the companion review rather than repeated here.

Evidence labels used below have specific meanings:

| Label | Meaning |
| --- | --- |
| Reproduced | Observed in a controlled local experiment against the actual implementation |
| Source finding | A missing control or unsafe behavior is identifiable in the reviewed code; deployed exploitation has not been demonstrated |
| Defense observed | A relevant control exists, sometimes supported by targeted tests; this is not universal attack resistance |
| Runtime check | Requires the real browser, database, network topology, cloud configuration or concurrent requests |
| Conditional | Impact depends on a stated prerequisite such as a stolen privileged credential |

An additional **182 targeted tests passed** during this attack-focused review. The earlier architecture review ran another 154 targeted tests. These are selected suites, not the full repository test suite or a live penetration test.

| Expanded test group | Tests passed | Main coverage |
| --- | ---: | --- |
| Identity | 54 | Provisioning authorization, tenant scope, RBAC, user directory and password reset |
| School core | 58 | Student, attendance, fee and catalog scope; tenant helpers; image fetching and storage |
| Platform | 36 | Audit, notification and reporting scope; internal caller and Pub/Sub authentication |
| Operations | 23 | Workflow tenant scope, urgent procurement scope and quotation storage |
| Billing | 11 | Billing tenant checks and tenant helpers |

The existing route-contract precheck has a Windows newline issue described in the companion review. Direct targeted gateway tests passed; a successful direct test run should not be presented as a successful standard gateway test command. Dependency drift between installed gateway modules and its lockfile is also recorded there.

## Threat model

The important adversaries are an unauthenticated internet visitor, a malicious user within a school, a valid user targeting another school, a compromised school administrator, an operator abusing assigned access, a compromised SUPERADMIN, and an attacker controlling a dependency, deployment identity or backend process.

The protected assets include student and staff personal data, photographs, attendance, fee balances and receipts, procurement approvals, quotations, identity sessions, school assignments, audit records, service credentials and deployment authority. Availability and cloud spending are assets too: a user can cause damage without reading data or executing commands.

The main trust boundaries are browser to gateway, gateway to private services, user identity to tenant context, application to PostgreSQL, application to user-supplied remote URLs, file parser to untrusted documents, signed URL to object storage, Pub/Sub delivery to event processing, and CI identity to production deployment.

## Priority findings

### Workflow actions do not enforce approval step authority

**Priority high. Source finding.** [WorkflowReadController](../services/operations-service/src/main/java/com/custoking/ims/operationsservice/api/WorkflowReadController.java) checks `workflow:act` for creation, submission, approval, rejection, cancellation and completion. It correctly derives the initiator and action actor from the authenticated tenant context rather than trusting actor identifiers supplied by the client.

However, [WorkflowReadRepository](../services/operations-service/src/main/java/com/custoking/ims/operationsservice/persistence/WorkflowReadRepository.java) loads step definitions containing `required_permission` and `required_role`, while `approve` does not enforce those fields. It checks only that the instance is `IN_PROGRESS`, records the action, and advances or approves the workflow. The seeded urgent-procurement workflow includes steps requiring distinct business permissions. The default identity RBAC seed grants `workflow:act` to ADMIN and SCHOOL_ADMIN as well as SUPERADMIN.

`complete` records an action and sets `COMPLETED` without requiring an approved state. `cancel` also lacks a prior-state restriction. Consequently, the endpoint logic permits completion without the approval sequence and alteration of terminal states. Tenant row security can constrain which school's rows are reachable; it does not enforce who may perform each approval within that school.

The instance read and update also lack an explicit row lock or version compare-and-set in these paths. Concurrent actions need database-backed testing for duplicated decisions or inconsistent progression.

**Improvement:** enforce the current step's authority using the authenticated actor; define allowed state transitions; lock or version-check the instance within the action transaction; reject actions on terminal instances; bind workflow actions to the corresponding business entity authority. Decide explicitly whether initiators may approve their own requests. Add tests where a SCHOOL_ADMIN with `workflow:act` but without the step permission tries approval, completes a pending instance, reopens a rejected instance, and races another approver.

The code gap is established; its end-to-end effect on procurement fulfillment has not been demonstrated against a running database. The workflow tenant tests do not establish step authority or transition correctness.

### Image body reads can exceed the configured timeout

**Priority high for availability. Reproduced locally.** [ImageUrlFetcher](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/ImageUrlFetcher.java) uses five-second connection and request limits, but obtains an `InputStream` and reads the body separately. A slow peer can therefore hold the read beyond the request timeout. Some redirect and rejection branches also do not close the received body stream.

A localhost server sent image response headers and two bytes, delayed seven seconds, then sent two more bytes. Running the actual compiled fetcher produced:

```text
configuredRequestTimeoutMs=5000 elapsedMs=7472 acceptedBytes=4
```

The probe used the existing test-only loopback allowance through reflection. This does **not** demonstrate a bypass of production private-address blocking. Four bytes are not a valid photograph; the experiment establishes the duration of the fetch stage before image validation.

**Improvement:** enforce a deadline covering the entire response body, cancel outstanding I/O when it expires, close every response stream, limit simultaneous remote fetches, and bound requests per authenticated user and school. A byte limit alone does not limit waiting time.

### Gateway upstream work lacks explicit bounded lifetime

**Priority high for availability. Source finding.** The gateway proxy does not consistently attach an explicit upstream deadline and cancellation signal, and its streaming loop does not honor `res.write()` backpressure. A slow upstream or disconnected/slow client can retain work or increase buffering. Existing ingress limits and rate buckets are useful but do not address all of these resource lifetimes.

**Improvement:** establish operation-specific deadlines, propagate disconnect cancellation, await writable drain, bound concurrent expensive operations, and test slow upstreams and clients with small controlled concurrency. Include identity introspection in the availability budget because protected requests depend on it.

### Frontend documents lack a browser security policy

**Priority medium. Source finding.** The frontend nginx configuration does not establish CSP or framing restrictions on the directly served SPA document. Gateway response headers cannot protect a document served separately by frontend nginx. React escaping and the absence of an obvious dynamic HTML sink are helpful, but they do not replace document policy.

**Improvement:** deliver and verify CSP, `frame-ancestors`, `X-Content-Type-Options` and an appropriate referrer policy on the actual deployed HTML response. Introduce CSP in report-only mode while validating required assets, then enforce it. Verify login, error pages and dashboard documents too.

### Sensitive photographs have long public cache lifetimes

**Priority medium. Source finding.** [StudentPhotoStorage](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java) uses private storage and signed URLs, but photo metadata includes `public, max-age=31536000, immutable`. Private bucket access and signed URL expiration do not remove copies already cached or downloaded. Removing a user's role also does not revoke a previously issued signed capability immediately.

**Improvement:** use a privacy-appropriate cache policy, short capability lifetimes and a documented replacement/deletion strategy. For resources requiring immediate access revocation, consider an authenticated delivery path. Verify bucket/CDN behavior rather than inferring it from metadata alone.

### Billing payment integrity differs from school fee payment integrity

**Priority medium, privileged scope. Source finding.** [BillingInvoiceRepository](../services/billing-service/src/main/java/com/custoking/ims/billingservice/persistence/BillingInvoiceRepository.java) accepts map-supplied payment amount, attribution and branch fields, inserts a payment and recalculates status without the school-fee path's visible idempotency and balance concurrency controls. Positive-amount validation is not visible in this method. The compatibility controller requires SUPERADMIN, so this is not evidence that an ordinary school user can modify billing payments.

**Improvement:** define payment invariants, validated typed inputs, server-derived attribution, idempotency and transactional locking consistently. Verify database constraints and retries in a real database; distinguish reversals from negative arbitrary payments.

### Client-submitted audit events need explicit provenance

**Priority medium. Source finding.** The audit ingest endpoint binds non-superadmin identity and school fields to the authenticated context, preventing simple attribution to another user. It still accepts caller-asserted action, entity and outcome fields. Such entries cannot by themselves establish that a business action actually occurred.

**Improvement:** distinguish client telemetry from server-generated authoritative business audit records, prevent client entries from asserting authoritative success, apply ingest quotas, and generate sensitive action audit records within the trusted action path. Verify gateway reachability and intended permissions for every canonical and compatibility ingest path.

### Dependency advisories require remediation and reachability assessment

The earlier lockfile audit returned one high gateway entry and six frontend entries, including two critical classifications; the frontend production-only graph retained one high entry. These counts are package/advisory classifications, not confirmed remotely exploitable application vulnerabilities.

The gateway `@grpc/grpc-js` advisory concerns server client-certificate/auth-context behavior; this gateway is an HTTP application using gRPC-related telemetry dependencies, so direct public exploitability was not established. The tinypool issue is a prototype-pollution gadget requiring an upstream pollution condition and relevant options use. Source-map and brace-expansion advisories also need caller reachability and input analysis. See the [grpc advisory](https://github.com/advisories/GHSA-m9gg-hp2v-232j), [tinypool advisory](https://github.com/advisories/GHSA-85c8-ppgw-ccpr), [source-map advisory](https://github.com/advisories/GHSA-68fv-2mgg-jv7q) and [brace-expansion advisory](https://github.com/advisories/GHSA-qhr7-859c-m2p7).

**Improvement:** upgrade through compatible dependency owners, regenerate lockfiles, perform a clean installation, repeat audits and relevant tests, and inspect the actual deployed image/SBOM. Keep development-tool exposure separate from browser bundle exposure.

## Attack coverage matrix

Each row describes a relevant family, the current evidence and the next verification. Multiple variants belong to each family; the table is an inventory, not a claim of exhaustive execution.

### Identity and unauthorized access

| Attack family | Current evidence | Remaining verification or improvement |
| --- | --- | --- |
| Password guessing and credential stuffing | Defense observed: bcrypt and authentication quotas | Distributed attempts, account/IP combinations, lockout abuse and alerting |
| Phishing and privileged account takeover | Source gap: no application MFA found | Add phishing-resistant MFA/step-up for privileged actions and recovery |
| Username/email enumeration | Some reset tests passed | Compare status, messages, timing and quotas across login/reset/directory |
| Reset-token theft and replay | Reset tests passed | Expiry, replay, changed email, parallel redemption and cross-replica behavior |
| JWT forgery and algorithm confusion | Defense observed: validation and authoritative introspection | Wrong algorithm/key, malformed claims, expired token, actual deployed configuration |
| JWT cross-application confusion | Source concern: issuer/audience binding absent | Bind intended issuer/audience where token reuse boundaries require it |
| Refresh replay and session fixation | Defense observed: rotation, family revocation and database locking | Concurrent refresh/logout, multiple tabs, actual cookie domain/path and replicas |
| Stolen access token | Defense observed: persisted session checks each protected request | Confirm revocation latency under failures and changed role/school assignment |
| Horizontal IDOR/BOLA | Tenant helper tests passed across domains | Two-school object matrix using real RLS and all endpoint aliases |
| Vertical privilege escalation | Identity provisioning/RBAC tests passed | Every role against every privileged action, including indirect changes |
| Unauthorized property updates/mass assignment | Typed DTOs in many routes; maps remain in compatibility paths | Attempt changes to owner, role, school, balance, status and attribution |
| Forged trusted identity/service headers | Gateway stripping defenses and tests observed | Full deployed proxy chain, duplicate headers and every backend entry point |
| Internal endpoint exposure | Gateway blocks internal/PubSub paths after rewriting | Direct Cloud Run URL, IAM invoker policy, encoded and legacy path variants |
| Operator assignment abuse | Identity introspection uses current assignments | Assignment removal, object IDs outside assignment, bulk/export endpoints |
| Service credential theft/lateral movement | Conditional: IAM plus shared service tokens are trust boundaries | Minimize invoker graph, rotate secrets, scope each service's DB and storage rights |
| Workflow approval and state bypass | Source finding described above | Enforce step authority and state machine; database-backed concurrent tests |

### Browser HTTP and injection

| Attack family | Current evidence | Remaining verification or improvement |
| --- | --- | --- |
| Stored/reflected/DOM XSS | React escaping; no obvious dynamic dangerous HTML/eval sink found in reviewed application source | Stored fields, errors, filenames, URL parameters, dashboard and third-party components |
| Clickjacking | Source gap: frontend framing policy absent | Actual HTML response policy and embedded-page verification |
| CSRF and login/logout CSRF | Origin rejection on refresh/logout when disallowed Origin is present; deployed refresh cookie uses SameSite=None | Missing/null Origin, simple form requests, cookie-only endpoints, deployment topology |
| CORS origin confusion | Gateway allowlist logic and tests observed | Credentials, wildcard configurations, suffix/port variants and hostile browser origin |
| Open redirect/host-header poisoning | Dashboard callback handling depends partly on deployment URL configuration | Verify fixed public URL, forwarded headers and registered OAuth redirects |
| HTTP request smuggling/desynchronization | Runtime check | Test actual load balancer/nginx/Node/backend parser chain in isolated staging |
| Cache poisoning/deception | Runtime check; sensitive photo policy finding | CDN cache keys, authenticated response caching, query/path/header variations |
| SQL injection | Predominantly bound JDBC parameters observed | Audit every dynamic SQL identifier/order/filter builder and exercise input paths |
| Command/template/expression injection | No obvious process-execution or expression sink found in inspected main Java code | Parser and dependency paths, future rendering features, deployment scripts |
| XXE and unsafe deserialization | Runtime/dependency check | Malformed XML-containing documents, parser options and dependency advisories |
| CSV/spreadsheet formula injection | Attendance CSV escapes dangerous leading characters; import-result helper differs | Control characters and all export consumers; verify actual spreadsheet interpretation |
| Path traversal and arbitrary file retrieval | Storage key validation and normalized-root checks observed | Encoded paths, alternate separators, symlinks where local storage is used |

### Files outbound requests and availability

| Attack family | Current evidence | Remaining verification or improvement |
| --- | --- | --- |
| SSRF to private services or cloud metadata | Defense observed: private address checks and revalidation per redirect | DNS rebinding race, alternate address encodings, IPv6 and egress policy |
| Remote URL abuse outside private networks | HTTP and HTTPS public destinations permitted | Restrict schemes/ports where possible, quotas, destination policy and egress proxy |
| Slow remote response exhaustion | Reproduced image read exceeds configured timeout | Whole-body deadline, cancellation and concurrency caps |
| Upload polyglots and MIME spoofing | Magic/decode checks, image re-encoding and size/pixel limits observed | Mismatched extensions/content, malformed formats and parser edge cases |
| Malicious PDF and embedded content | Page/encryption/JavaScript checks observed | Antivirus/CDR where warranted, embedded files/actions and preview behavior |
| ZIP/XLSX decompression and image bombs | Some file/pixel limits observed | Decompressed-size/entry limits, memory ceilings and parser timeouts |
| Signed URL theft and permission-after-issue changes | Conditional capability exposure; photo cache finding | TTL, cache policy, URL leakage and revocation requirements |
| API resource exhaustion/expensive searches | Rate/payload controls exist | Per-operation cost, bounded pagination, DB timeouts, bulk/reporting quotas |
| Slow clients and abandoned requests | Gateway lifetime/backpressure source finding | Controlled stream tests and worker/memory measurements |
| Distributed DoS and cloud bill exhaustion | Deployment runtime check | Edge controls, maximum instances, budgets, queue limits and fail-safe behavior |
| Dashboard denial of service/information exposure | Authentication normally enabled; disabling flag is not visibly restricted to local deployment | Forbid deployed auth-off configuration, restrict ingress, quota expensive monitoring queries |

### Business integrity infrastructure and recovery

| Attack family | Current evidence | Remaining verification or improvement |
| --- | --- | --- |
| Payment replay and concurrent double collection | School fee path has idempotency, locking and balance checks; billing differs | Real concurrent retries, request fingerprint changes, negative/overflow values |
| Illegal state transitions and self-approval | Workflow source finding | Entity-specific state invariants, separation of duties and race testing |
| Cross-tenant notification/export leakage | Scope tests passed | Actual recipient joins, bulk exports and operator assignment transitions |
| Event spoofing and unauthorized push | Internal/PubSub authentication tests passed | Deployed audience/issuer/service identity, topic publisher/subscriber grants |
| Event replay and stale projection overwrite | Durable inbox dedupe and deletion tombstones observed; upsert ordering remains a concern | Duplicate delivery plus different-ID old events; version-aware projection writes |
| Audit poisoning and forensic evasion | Audit attribution bound; client assertion concern | Trusted event provenance, retention, restricted deletion and actionable alerts |
| Database RLS bypass | Policy/configuration intent observed | Runtime role not owner/SUPERUSER/BYPASSRLS; FORCE RLS and pooled-context isolation |
| Broad database lateral access | Shared runtime-role design deserves scrutiny | Per-service schema privileges, cross-schema joins, migration role separation |
| Cloud bucket/secret exposure | Private storage intent observed | Current IAM, public access prevention, ACLs, logs, signed capability issuance |
| Dependency or container compromise | Advisories found; scanning/SBOM/provenance controls observed | Clean graph, deployed artifact, runtime reachability and nonroot processes |
| CI/deployment takeover | Pinned actions/images and signed digest promotion observed | Actual WIF trust restrictions, branch protections, environment gates and runner exposure |
| Ransomware/destructive insider | Operational verification required | Restricted delete authority, isolated backups and timed restoration drill |
| Monitoring blind spots and incident response failure | Audit/monitoring components exist | Test real alert delivery, session/secret revocation, evidence preservation and recovery |

## Why the existing protections matter

The gateway's authoritative identity lookup is a strong design choice: a valid JWT alone does not preserve access after a session or assignment is revoked. Its removal of caller-supplied trusted headers protects the boundary between public requests and backend tenant context. These protections depend on private backends remaining inaccessible through alternative entry points.

Application permission checks and database row security address different problems. Permissions limit actions; tenant predicates limit rows. A school administrator can still abuse an action within their own school's rows when a business authorization check is missing, as the workflow finding demonstrates. OWASP recommends validating authorization for each request and protected resource, with denial as the default. [OWASP authorization guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html).

The outbox/inbox design reduces message-loss and duplicate-processing risks. It does not automatically establish event authenticity, ordered delivery or correctness under stale updates. Likewise, validating a file's type and size reduces parser risk but does not establish that its contents are harmless. [OWASP file upload guidance](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html).

## Controlled verification still required

Use an isolated staging environment with synthetic data, two schools, distinct school administrators, a restricted user, an assigned operator and a SUPERADMIN. The database must use the same runtime role and policies as deployment. Establish the expected permission and ownership rule for each route before executing tests.

1. Enumerate canonical, compatibility and diagnostic routes from the checked-in inventory. Test relevant verbs, encoded path variants and direct backend entry points. Confirm denials prevent side effects, rather than merely returning an error after a write.
2. For each object operation, substitute another user's and another school's identifier in the path, query, body, nested objects and bulk lists. Include files, exports, action history and recipient lists.
3. Exercise every role and important property change. Repeat after logout, password reset, user disablement, permission removal and operator unassignment. Verify old access and refresh tokens across replicas.
4. Verify workflow transitions, step authority, self-approval policy and parallel actions against persisted state. Verify payments with the same key, changed payload and concurrent balance updates.
5. Test hostile browser origins and document response headers on the actual frontend host. Include credentialed fetch, form submission, framing and pages returned by error paths.
6. Verify SSRF and file handling using controlled servers and harmless malformed fixtures. Apply strict execution limits; stop if resource usage exceeds the agreed small test budget.
7. Inspect deployed IAM, ingress, SQL role attributes, bucket permissions, secret access and WIF conditions. Repository intent does not prove deployed policy. Cloud Run ingress and IAM are separate controls and both need inspection. [Google Cloud ingress documentation](https://docs.cloud.google.com/run/docs/securing/ingress).
8. Run small, bounded concurrency experiments for timeouts, cancellation, worker usage and retries. Request smuggling requires the real intermediary chain because inconsistent HTTP interpretation is the defining condition. [MITRE CWE-444](https://cwe.mitre.org/data/definitions/444.html).
9. Trigger representative alerts and perform a synthetic recovery drill. Measure recovery time and recovered data, rather than accepting that backups merely exist.

An authorization case is complete only when the response, database state, emitted events, object access and audit record agree with the expected denial or success. Capture identity/role, route, object owner, expected result, actual result and repeatable evidence.

## Remediation order

**First:** close workflow step and state gaps; bound image body reads and gateway upstream/stream lifetimes; remediate advisory-affected dependencies with clean installations. These address concrete code weaknesses and verified vulnerable package entries.

**Next:** establish browser document policy, correct sensitive photo cache behavior, unify billing payment invariants, separate client telemetry from authoritative audit, and require MFA for privileged access. Verify database runtime attributes and deployment entry points in parallel because a mistake there can undermine application controls.

**Then:** make the route/role/object matrix repeatable in staging; enforce business transition and concurrent-write regressions; strengthen per-service least privilege, event ordering and expensive-operation budgets. Verify pipeline trust conditions rather than relying only on pinned actions. [GitHub secure use guidance](https://docs.github.com/en/actions/reference/security/secure-use).

**Operationally:** exercise alerts, incident response and restoration. Phishing-resistant MFA, restricted privileges and isolated recoverable backups reduce the impact of stolen credentials and destructive compromise. [CISA ransomware guidance](https://www.cisa.gov/stopransomware/ransomware-guide).

## Research basis

The attack inventory combines application verification requirements, web-testing categories, API risks and operational threats. OWASP Top 10 is a risk taxonomy, not a complete test plan. ASVS Level 2 is a reasonable proposed baseline for this multi-tenant application, with selected stronger requirements for SUPERADMIN, financial actions and deployment identities; this review does not certify ASVS compliance.

| Primary reference | How it informs this assessment |
| --- | --- |
| [OWASP ASVS 5.0.0](https://owasp.org/projects/asvs) | Verification requirements and measurable security controls |
| [OWASP WSTG 4.2](https://wstg.owasp.org/v4.2/4-Web_Application_Security_Testing/) | Identity, authentication, authorization, session, input, configuration and business testing |
| [OWASP Top 10 2025](https://top10.owasp.org/2025/en/) | Risk categories including access control, configuration, supply chain and exceptional conditions |
| [OWASP API Security Top 10 2023](https://api-security.owasp.org/editions/2023/en/0x11-t10/) | Object/function/property authorization, resource consumption and API inventory |
| [OWASP business logic guidance](https://cheatsheetseries.owasp.org/cheatsheets/Business_Logic_Security_Cheat_Sheet.html) | Transition rules, abuse cases and concurrency invariants |
| [OWASP mass assignment guidance](https://cheatsheetseries.owasp.org/cheatsheets/Mass_Assignment_Cheat_Sheet.html) | Explicit writable properties and protected attributes |
| [OWASP SSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html) | Destination validation, redirects and network defenses |
| [OWASP CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) | Cookie-authenticated cross-origin request verification |
| [OWASP CSV injection](https://community.owasp.org/attacks/CSV_Injection) | Spreadsheet interpretation of exported untrusted values |

The findings concern the reviewed snapshot. Deployment changes, newly discovered advisories and new features require renewed assessment.
