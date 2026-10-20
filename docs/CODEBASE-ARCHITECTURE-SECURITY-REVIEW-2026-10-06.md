# Custoking IMS architecture features and security review

Current implementation, deployment and remaining acceptance criteria are tracked in [the security implementation ledger](security-remediation/IMPLEMENTATION-STATUS.md) and [the dev release report](security-remediation/dev-security-release.md). This document preserves the original 2026-10-06 assessment; its initial status is historical.

The consolidated [findings and fix plan](SECURITY-FINDINGS-AND-FIX-PLAN-2026-10-06.md) tracks remediation and verification work from this review and the subsequent attack assessment.

Custoking IMS implements a substantial school ERP and procurement platform. Its strongest architectural controls are authoritative session introspection, application scope checks backed by PostgreSQL row security, durable event publication, and promotion of signed immutable container digests. The highest priority improvements are dependency remediation, security headers on the actual frontend document, sensitive photo cache policy, bounded gateway streaming, and stronger database privilege checks.

This review covers the working tree at commit `df3f894a3c87951d2dc7dd78f8dda6791f01252e` on 6 October 2026. Source inspection and targeted tests establish the behavior described below. Production IAM, current deployments, database privileges, provider delivery, and recovery settings require fresh operational evidence; dated repository records do not establish their present state. This is a detailed architectural and security assessment, not a claim that every line or every endpoint has been penetration tested. Existing user changes were preserved. No production resources or application implementation were changed.

## System structure

The system has seven primary deployable applications: a React SPA, a Node gateway, and five Java services. There are twelve logical domain routes at the gateway, but these are consolidated into five backend processes. The separate `tools/live-dashboard` application provides an operational dashboard and has its own authentication boundary.

```text
Browser
  -> frontend nginx -> gateway -> identity introspection
                              -> owning private Java service -> PostgreSQL
                                                           -> private object storage

Domain transaction -> domain row plus outbox row
Outbox relay -> Pub/Sub -> platform durable inbox -> reporting projections
                                               -> notification processing

Cloud Scheduler -> private maintenance endpoints -> relay and drain work
```

The frontend also supports a configured API base URL, so a deployment can call the gateway directly. Serving the SPA through the gateway and serving it directly from frontend nginx have different response-header behavior today.

| Application | Responsibility | Persistence boundary |
| --- | --- | --- |
| Frontend | Login, recovery, workspace, role-specific panels, school and zone management | In-memory access token; localStorage session hint |
| Gateway | Route selection, user introspection, trusted header propagation, service credentials, Cloud Run authentication, CORS, payload and request limits | Bounded local rate buckets and cached Cloud Run ID tokens |
| Identity | Users, password authentication, sessions, recovery, RBAC, scoped assignments, shared quotas | `identity` |
| School core | Tenant setup, students, staff, timetable, attendance, school fees, catalog and supply ordering, photographs | `tenant_school`, `student`, `attendance`, `fee`, `catalog` |
| Operations | Workflow definitions and instances, urgent procurement, quotations and quotation documents | `workflow`, `firefighting` |
| Platform | Workspace/reporting projections, command center, academic events and contributions, notification orchestration, audit | `reporting`, `notification`, `audit` |
| Billing | Platform/customer invoices, billing payments, invoice and order sequences | `billing` |

The stack declared in source is Java 25, Spring Boot 4.1.1, PostgreSQL/JDBC and JPA, Node 24, React 18, React Router 7.18.3, TypeScript, Vite, Axios, Vitest and Playwright. Some documentation still says React Router 6 and describes older service boundaries. The root Maven parent centralizes dependency baselines and convergence checks, with six explicitly excluded artifacts representing known convergence debt.

### Why these boundaries exist

Keeping school operations together allows related student, attendance, fee and catalog workflows to share transactions within one process. Separating identity makes authentication and permission state authoritative. Separating platform reporting enables read models without making every dashboard query a distributed request. Billing and operations have distinct business responsibilities and release paths.

The repository's compatibility controllers and diagnostic aliases preserve existing browser contracts while domain ownership evolves. This reduces migration disruption but increases authorization and contract maintenance work. Consolidation also reduces the number of deployed processes compared with the historical twelve-service design. This cost/complexity rationale is an architectural inference; the source proves the consolidated topology.

Physical deployment separation does not imply fully independent databases: schemas share PostgreSQL infrastructure, runtime credentials are commonly named `app_rt`, and platform still contains an explicitly baselined join to `student.students`.

## Implemented feature map

The endpoint inventory generator discovers 44 controllers and 436 method/path mappings across five services: 338 canonical, 90 compatibility, and eight internal mappings. It also discovers 70 gateway route entries and twelve diagnostic aliases. These counts describe annotated mappings, not 436 separate business features. The parser classifies by file location; an internal path outside `api/internal` can therefore count as canonical.

The full endpoint/source lookup is [the generated inventory](../services/api-gateway/api-route-inventory.json). Payload contracts and typed browser operations are under [OpenAPI contracts](../contracts/openapi) and [generated clients](../frontend/src/generated). The generator reports no remaining frontend compatibility calls under its supported detection rules; that is not proof that every handwritten URL has been eliminated.

| Feature | Implemented behavior | Main source areas |
| --- | --- | --- |
| Authentication | Login, access sessions, rotating refresh cookies, family revocation, disabled-user and credential-version checks | Identity `IdentityAuthService`, `JwtService`, `AuthController`; frontend `AuthContext`, `services/api.ts` |
| Recovery | Capability endpoint, queued email reset delivery, random one-use tokens, password reset and session invalidation | Identity `PasswordResetService`, `PasswordResetRepository`, `PasswordResetController` |
| Users and RBAC | Roles, permissions, school/zone assignments, user provisioning, operator school assignments | Identity controllers and RBAC repositories |
| School onboarding | School and zone management, class/section setup, academic year, staff, module entitlements, tenant localization | School-core tenant controllers/repositories; SchoolManagementPage and ZoneManagementPage |
| Workspace | Role-specific navigation, school/zone/admin views, dashboard drawers, metrics and actions | UnifiedWorkspacePage, workspace configuration, platform reporting |
| Students | Create/update/list records, class roster, imports, guardian normalization and consent evidence, review campaigns and permanent deletion | Student controllers/repositories and frontend student panels |
| Photographs | Upload/normalize portraits, private GCS storage, signed display URLs, Google Drive folder and mapping workflow, resumable import review | StudentPhotoStorage; `photoimport`; PhotoImportPanel |
| Student exports | Assigned-school export context, streamed ZIP containing workbook/photos, progress and audit records | StudentExportController, StudentExportService, archive writer/repository |
| Attendance | Daily/section register workflows, submit actions, absentee selection, notification queue, reports and student history | Attendance controllers/repository, absentee delivery package, attendance panels |
| Timetable | Staff/class scheduling and timetable views/studio | School-core timetable sources; TimetableModule and TimetableStudioPanel |
| School fees | Structures, bands, items, installments, discounts, assignments, payments, receipts, reminders and reports | FeeReadRepository, fee controllers, fee workspace |
| Supply procurement | Catalog, dynamic product forms, assets, school orders, quoting/approval, annual planning, vendor settlement actions | Catalog controllers/services/repositories and frontend catalog/order/planning panels |
| Urgent procurement | Requests, quotations, workflow/approval and order paths, document storage | Operations workflow/firefighting controllers and repositories |
| Platform billing | Customers, invoices, invoice exports, recorded billing payments, revenue views | BillingInvoiceService/Repository and superadmin panels |
| Reporting | Domain event facts/dimensions, workspace projections, command-center feed and action views | Platform projector package and reporting repositories |
| Notifications | Inbox and retries, sender profiles, WhatsApp onboarding, broadcasts, guarded live email transport, provider reports | Platform notification/broadcast workers, controllers and provider adapters |
| Audit | Audit ingest/read, RBAC/auth evidence, export and operational evidence | Identity and platform audit repositories; domain export audit |

### Feature availability depends on configuration

Implemented code does not mean a feature is enabled for a school or deployment. School module entitlements gate modules. Catalog product forms default off. Photo upload needs a configured bucket; Drive imports additionally need credentials, shared folders and explicit enablement. Generic notification delivery defaults to logging with MSG91 dry-run. Broadcast dispatch defaults off and live broadcasts have additional channel, school, destination, sender and template controls. Absentee delivery defaults to dry-run.

Password recovery is also conditional: the mailer must be enabled and the worker-ready flag must be true. The service throws during construction when email recovery is enabled without this readiness flag. The flag is a declared readiness assertion, not a measurement of actual allocated CPU/minimum instances.

The current notification implementation is newer than the broad August architecture descriptions: a separate guarded live broadcast email path exists alongside the generic dry-run inbox. Treat these as separate delivery modes.

## Browser and request architecture

`App.tsx` exposes `/login`, `/reset-password`, `/dashboard`, `/schools`, `/zones`, and redirects unknown paths. Major pages are lazy-loaded. The dashboard is a unified workspace whose panels depend on role, module and permission state. These browser checks help navigation; backend checks are the authorization boundary.

The Axios transport is shared with generated clients, defaults to `/api/v1`, sends credentials, attaches the in-memory bearer token, and uses a 30-second timeout. Canonical login receives 60 seconds for cold starts. A non-auth 401 triggers one shared refresh promise per concurrent burst, followed by one retry. Failed refresh clears the token/session hint; the interceptor redirects to login. A transient refresh outage currently becomes loss of client auth state as well as an invalid-session failure.

`AuthContext` stores only the boolean `custoking_isLoggedIn` in localStorage. On reload, it attempts refresh only when that hint exists. The actual access credential is module memory. This reduces durable token exposure, but JavaScript running through XSS can still access memory or issue authenticated requests. The comment claiming memory storage prevents XSS theft is too strong.

For a protected API request:

1. Gateway parses the path, applies response security headers and CORS handling, then checks local request limits and body size.
2. Explicit route ordering chooses the logical domain. Specific roster/provisioning/dashboard action routes precede broader groups.
3. Any route rewriting to `/api/v1/internal` or `/api/v1/pubsub` is rejected before user authentication. Diagnostic aliases cannot bypass this rule.
4. Bearer parsing rejects malformed/oversized credentials. Optional local HMAC verification rejects invalid signatures/expiry and refresh tokens.
5. Every authenticated request still goes through identity introspection. Embedded JWT permissions and operator schools are not authoritative.
6. Identity verifies the persisted access-token digest/session, user status and credential version, then resolves current permissions and school assignments. Shared quotas are charged with the canonical method/path and an authorized school hint.
7. Gateway removes client-supplied authenticated/service-token headers, stamps the current principal, injects the destination's shared token, and obtains Cloud Run IAM authentication when appropriate.
8. The backend validates the service token, applies permission/module/school scope, and executes its operation under request tenant context.

Identity introspection has a ten-second deadline. Invalid credentials produce 401; identity availability failures propagate to a gateway upstream error instead of pretending that the token expired. This favors prompt revocation and fail-closed authorization, but places identity and its database on the availability/latency path of essentially every protected API call.

The gateway overwrites outbound `Authorization` with the Google ID token for `.run.app` upstreams. Domain services rely on stamped user context rather than the user's JWT. This is coherent with the trust model but makes the gateway a particularly privileged component. Google documents service-specific IAM identities and audience-bound ID tokens for this transport boundary. [Cloud Run service authentication](https://docs.cloud.google.com/run/docs/authenticating/service-to-service)

## Identity and session mechanics

Passwords use bcrypt cost 12. JWTs contain subject, unique ID, issue time, expiry, role/user/school/zone claims, permissions, claim version, and operator schools. Signing is selected by the JJWT library from the HMAC key; the gateway accepts HS256 and HS512. Describing all tokens as necessarily HS512 is therefore inaccurate when the configuration only requires a 32-character minimum secret.

Access TTL defaults to 15 minutes; refresh TTL defaults to seven days. Session rows persist SHA-256 digests of both credentials, family ID, status, user and credential version. Refresh locks the presented session row, retires it as ROTATED, and creates a successor in the same family. Reusing a retired token revokes the entire family and records evidence. `noRollbackFor = ResponseStatusException.class` ensures the revocation commits even though reuse returns an error. Ordinary rotation preserves previously issued access tokens until expiry; logout/reuse revokes them through family status.

Refresh cookies are HttpOnly, scoped to `/api/v1/auth`, and deployed templates configure Secure plus SameSite=None. Gateway rejects disallowed Origin values on refresh/logout. CORS response filtering alone would not prevent cookie-auth side effects; the explicit request rejection is significant. Origin-less requests remain permitted, so a defined Referer/Fetch Metadata fallback would strengthen the policy. [OWASP CSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)

Recovery uses 32 random bytes encoded as a 43-character base64url token, durable delivery intent, bounded worker processing, and uniform public behavior independent of SMTP delivery timing. Confirmation requires at least twelve Unicode code points and at most 72 UTF-8 bytes. Quotas include fleet and per-account login/reset controls and per-user/per-school read/write/import/export limits. These shared quotas materially improve the older per-replica gateway limiter.

Cross-tab refresh coordination is absent from the inspected browser transport. Two tabs can present the same cookie concurrently; strict reuse detection may revoke their common family even for a legitimate race. Review the existing locking/reuse semantics with a multi-tab browser test before choosing browser locks or a narrowly designed server grace mechanism.

## Tenant and database architecture

`TenantContextFilter` reads gateway-stamped identity and permission headers into thread-local context and clears it in a finally block. `TenantScope` constrains school users to their authenticated school, operators to assigned schools for supported platform operations, zone readers to their own zone, and superadmins to intentionally broader scope.

`TenantAwareDataSource` wraps the pool and sets tenant GUCs on every connection checkout. School-core resets school, bypass, operator-school and zone context. Values are parameterized. Session-level settings cover autocommit operations as well as transactions; checkout reset prevents a previous borrower's context leaking into the next request. Operator/projector widening uses transaction-local settings where appropriate. Failed setting closes the borrowed connection.

PostgreSQL RLS is an additional backstop on tenant tables, with exceptions for contextless inbox/feed infrastructure. Platform projection writers explicitly enable transaction-local bypass through `ProjectorRls`, which automatically ends with the transaction. Normal RLS cannot protect against arbitrary SQL executed under the application role if that SQL can also set the bypass GUC. The protection assumes trusted application code and correctly constrained SQL execution.

`RuntimeDbRoleGuard` exists in school-core and platform and rejects a non-`app_rt` username under the prod profile. It currently checks the role name rather than `rolsuper`, `rolbypassrls`, table ownership and memberships. Billing and operations have tenant-aware data sources but no equivalent guard class in the inspected source. PostgreSQL explicitly distinguishes ordinary roles, table owners, superusers and BYPASSRLS roles. [PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)

JDBC queries generally bind data values and validate interpolated schema/table identifiers. Hibernate uses `ddl-auto: validate`, open-in-view is disabled, and domain Flyway configuration handles multiple schema histories. The standard Spring Flyway switch is disabled because custom domain migration wiring is present; it does not mean no migrations run. Separate Flyway credentials support DDL outside runtime privileges. Historical migrations should be preserved and new changes should use forward migrations.

The schema-boundary baseline explicitly permits platform to access `student`. `ReportingCommandRepository` joins `student.students` when resolving academic-event contribution reminder targets. This is current architecture debt, not an unknown boundary violation. Replace it with an owning-service consent/recipient policy API or an appropriate projection, then reduce grants and remove the baseline exception.

### Fee collection mechanics

`FeeReadRepository` is also a command repository despite its name. Fee payment recording requires a bounded idempotency key, resolves the student's school, validates tenant scope, derives a request fingerprint, and takes a transaction advisory lock keyed by school/payment key. A matching replay returns the original result; a changed replay conflicts. It locks the assignment row, validates the balance, allocates a receipt sequence and updates state within a transaction. The unique index provides a final deduplication guard. These controls address duplicate clicks and concurrent collection more directly than a browser button lock.

School fee amounts use integer minor units in the reviewed payment path. Discount/share percentages and some legacy billing calculations use floating-point values; billing includes a fixed 12 percent GST calculation. The internationalization gap is therefore partly business semantics, not just currency formatting. Standardize currency units, tax policy and decimal rounding explicitly.

Platform billing payments are a separate path. The inspected billing payment insert/status refresh has no analogous idempotency key or visible invoice-row lock. Apply the fee collection invariant there as well and verify concurrent/replayed payment handling against PostgreSQL before calling billing reconciliation complete.

## Event architecture and consistency

School-core, operations and billing have transactional outbox writers and relays. The domain write and event row belong to the same database transaction. The relay claims unpublished eligible rows with `FOR UPDATE SKIP LOCKED`, publishes before marking success, increments attempts, backs off, and dead-letters repeated failures. Publish-before-mark preserves durability when a process dies between external publication and local update, at the cost of possible duplicate delivery.

The canonical envelope carries namespaced event ID, event key/type/version, aggregate identity, occurrence time, school, payload and trace context. Namespacing IDs such as `school-core:<id>` prevents collisions between service-local sequences.

Platform authenticates Pub/Sub ingress, persists an inbox keyed by event ID, and projects facts/dimensions using event-type-specific projectors. Duplicate event IDs do not produce a second inbox event. Failures retry and eventually become dead letters. Student deletion uses durable tombstones and a transaction advisory lock so a delayed upsert cannot resurrect permanently deleted projections.

Deletion protection does not solve every ordering problem: `upsertStudent` overwrites the dimension on conflict without comparing a source version or source occurrence time. Distinct old/new upsert events arriving out of order can regress names/contact/class data. Add an aggregate sequence/version and reject stale upserts; timestamps alone require tie/clock semantics. Verify reordered and duplicate events independently.

Projection upserts and inbox completion are separate repository transactions in the inspected processor. Repeated processing must therefore remain harmless across partial failure. Event-ID uniqueness and aggregate-version ordering are complementary controls, not interchangeable guarantees.

Cloud Run request-based CPU motivates request-driven private relay/drain endpoints and request-boundary trace flushing. Background `@Scheduled` methods alone cannot guarantee timely work when an instance is idle or at zero. Recovery email and broadcast workers require actual worker allocation appropriate to their readiness settings. Long external publishes inside outbox transactions also occupy connections/locks, so relay batch/time limits matter.

## Files photographs and sensitive data

Student photos are decoded, EXIF-oriented, resized without destructive cropping, and re-encoded to JPEG. Inputs have byte/type checks and a 40-million-pixel ceiling. Content-addressed keys prevent stale-photo overwrite ambiguity. Signed V4 URLs are produced through IAM signing rather than committed private keys.

The photo cache directive is currently `public, max-age=31536000, immutable`. Private bucket IAM remains private; that directive does not make the bucket public. However, a response already obtained through a signed URL can remain in caches well beyond URL expiry. Signed URLs are bearer capabilities usable by anyone who possesses them during validity. Prefer a documented short private cache policy or no-store for minor photographs, and choose TTL based on the intended access/deletion behavior. Existing object metadata must also be updated. [Cloud Storage signed URLs](https://docs.cloud.google.com/storage/docs/access-control/signed-urls), [Cloud Storage caching](https://cloud.google.com/storage/docs/caching)

Original import evidence uses private/no-store metadata. Temporary import evidence has a lifecycle prefix. Legacy HTTP(S) photo URLs are still returned as-is, which allows externally hosted images and possibly cleartext links. Constrain or migrate these where student privacy and deployment CSP require it.

Photo-import workbooks support XLSX/XLS/CSV/TSV, cap parsed rows at 1,000, and use POI ZIP inflate-ratio protection. The parser allows 10 MiB, but ordinary nginx/gateway/multipart defaults are tighter; align the supported user limit across all layers. Formula evaluation and decoder memory costs deserve hostile-input tests. Forty million decoded pixels can require roughly 160 MB for one four-byte pixel buffer before additional resize buffers, so byte limits alone are insufficient at high concurrency. [OWASP upload guidance](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)

The export path checks operator/superadmin role, `student:export`, and authorized school scope, limits concurrent exports with a per-instance semaphore, streams the archive, and records progress/completion. Export responses use no-store. Recipient consent policy and repository privacy/erasure drill tooling are meaningful implemented controls, but end-to-end retention must include photos, import originals, reporting payloads, backups and operational evidence.

Storage failures currently embed exception class/message in some HTTP error reasons. Keep user errors generic and bind detailed internal diagnostics to request ID, especially when infrastructure errors can reveal bucket/object details.

## Deployment and operational architecture

The source describes branch-owned deployment: dev changes build/reuse images and verify them; main promotes approved digests through Cloud Deploy canaries. Release workflow code includes BuildKit provenance, SBOM attestations, cosign signing/verification, exact-digest vulnerability gates, deployment evidence and rollback paths. Actions and base images are pinned. This is materially stronger than tag-only promotion.

CI includes Java/frontend/gateway tests, duplicate-class checks, service and contract audits, CodeQL, Gitleaks, privacy drills and HIGH/CRITICAL image scans. The daily container scan includes the operational dashboard. Six shared infrastructure abstractions plus the role guard are protected by duplicate-class drift checks, so copying common code has an explicit consistency gate.

Java runtime images use a non-root application user. Gateway and frontend Dockerfiles have no explicit non-root runtime user. Gateway does not need privileged ports in Cloud Run if configured on 8080. Frontend can use an unprivileged nginx layout with writable directories deliberately configured.

Cloud Run manifests use parameter placeholders and defaults. Rendered deployment configuration and live revision values, not placeholder examples, must establish project, service-account, scaling, audience and secret settings. Secret Manager references, WIF governance, observability Terraform and recovery runbooks are present. Current live branch protection, bucket policies, database backups/PITR, HA and subscription DLQs were not queried during this review.

Small database pools and maximum instance settings control cost/connection pressure. Capacity must be evaluated as the sum of every pool across instances, overlapping revisions, migrations, jobs and operator sessions. Trace flushing is adapted to request-based CPU but adds request-boundary work; set sampling from measured operational needs rather than assuming 100 percent sampling is free.

## Prioritized security and reliability improvements

Priority indicates proposed implementation order. A source weakness is not automatically a proven production exploit.

| Priority | Finding | Concrete improvement and acceptance criterion |
| --- | --- | --- |
| P1 | Lockfiles contain currently flagged vulnerable dependencies | Update compatible dependency branches; align Vitest/coverage and worker tooling; reproduce npm audits from clean installs and pass image scans. Avoid a blanket major override without compatibility tests. |
| P1 | Direct frontend HTML lacks gateway CSP and framing/security headers | Set headers at nginx on document/error responses and account for nginx location inheritance. Verify `/dashboard`, `/index.html`, error responses and configured image/API origins. |
| P1 | Signed minor-photo responses use one-year public caching | Choose private short-lived/no-store policy, migrate existing object metadata, and verify response headers and deletion/revocation expectations. |
| P1 | Gateway fetch lacks an explicit overall upstream deadline, ignores `res.write` backpressure and does not explicitly cancel on disconnect | Use operation-aware AbortController deadlines, cancel request/response streams on disconnect and wait for drain. Test stalled upstreams, slow downloads and abandoned exports. |
| P1 | Database guard only checks username and only exists in two RLS services | Verify superuser/BYPASSRLS/membership/ownership state at startup in every RLS service. Introduce service-specific runtime grants where practical; reject privileged runtime state. |
| P1 | Billing payments lack the reviewed fee payment replay/concurrency invariants | Add school-scoped keys/fingerprints, unique constraints and invoice locking; prove replay and competing payments cannot duplicate collection or lose balance updates. |
| P2 | No first-party MFA/passkey flow found for privileged IMS roles | Add privileged-user MFA and step-up for role changes, bulk export and permanent deletion, with secure recovery and audited emergency access. |
| P2 | Gateway edge limiter keys any syntactically valid bearer before verification and trusts the leftmost forwarded IP | Use trusted-hop IP resolution and independent unauthenticated limits. Reject attacker key churn before expensive work. Preserve current fleet/account/user quotas. |
| P2 | Shared token holders can assert user headers; permission-if-authenticated helpers intentionally skip when no user exists | Require explicit caller identity and route policy for contextless operations; distinguish peer capability from user permission. Do not infer write restriction from a shared token's `read` name or a scope-string argument alone. |
| P2 | JWTs omit issuer/audience and rely on shared HMAC secrets | Define token purpose, allowed algorithms, issuer/audience and rotation. Consider asymmetric keys to reduce verifier signing authority while retaining authoritative sessions. |
| P2 | SameSite=None cookie flows allow Origin-less requests | Define browser Origin/Referer/Fetch Metadata fallback or a CSRF proof; test allowed same-origin, configured cross-origin, null and absent Origin flows. |
| P2 | Student projection upserts can overwrite newer values when events reorder | Add source aggregate versions and stale-event rejection, preserving existing deletion tombstones. |
| P2 | Platform reminder target resolution still reads student-owned tables | Move recipient/contact/consent resolution to the owner, then revoke foreign-schema reads and retire baseline debt. |
| P2 | Upload decoder concurrency and workbook/user-facing limits differ | Bound simultaneous decoding, align limits, constrain rows/cells/uncompressed workbook size and test expensive/corrupt inputs. |
| P2 | Per-instance dashboard replay/revocation maps and generated fallback session secrets complicate replica consistency | Configure a stable managed secret and durable replay/revocation storage if multi-instance/revision operation is required; verify logout across replicas. |
| P3 | Gateway/frontend run without an explicit non-root user | Use high ports, minimal writable paths and non-root runtime users; test startup and health checks. |
| P3 | Generated checks compare raw LF output with CRLF working-tree files | Apply explicit LF attributes to generated outputs or normalize comparisons. Test generation checks on Windows and Linux. |
| P3 | Broad Map payloads, mixed command/read repositories and historical compatibility increase reasoning cost | Convert security-sensitive mutations to validated DTOs, separate command responsibilities gradually, and maintain endpoint-to-policy tests. |

JWT purpose/algorithm/issuer/audience hardening follows [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725.html). Resource limits, object scope and privileged business operations align with [OWASP API Security Top 10](https://api-security.owasp.org/editions/2023/en/0x11-t10/). CSP must be delivered on the executing document, not merely API responses; see [OWASP CSP guidance](https://cheatsheetseries.owasp.org/cheatsheets/Content_Security_Policy_Cheat_Sheet.html).

### Dependency audit results

Current npm registry audits of repository lockfiles returned the following on 6 October 2026. Counts are vulnerable package entries, including transitive/effect entries, not independent exploitable defects.

| Graph | Audit result | Exposure and remediation |
| --- | --- | --- |
| Gateway | One high entry, `@grpc/grpc-js` | Lockfile has 1.14.4. Advisory fixes include 1.14.5 on that branch. It concerns server certificate authentication in specific configurations; inspected gateway tracing is not a public gRPC server, so presence does not prove exploitability. Update the graph and rescan. |
| Frontend full | Six entries: two critical, two high, two moderate | Critical entries are `tinypool` and dependent Vitest classification; high entries include brace-expansion and source-map-js. Review CI/developer exposure as well as any browser bundling. |
| Frontend excluding dev | One high entry, nested `brace-expansion` | Nodes occur under archiver-utils/readdir-glob/rimraf/zip-stream from the workbook/archive graph. A production dependency classification alone does not establish that a vulnerable function reaches the browser bundle. |

Primary advisory details: [gRPC authentication](https://github.com/advisories/GHSA-m9gg-hp2v-232j), [Tinypool worker options](https://github.com/advisories/GHSA-85c8-ppgw-ccpr), [source-map-js resource consumption](https://github.com/advisories/GHSA-68fv-2mgg-jv7q), and [brace-expansion recursion](https://github.com/advisories/GHSA-qhr7-859c-m2p7). The Tinypool advisory depends on a prototype-pollution gadget context; a critical scanner classification does not mean every test command is remotely exploitable.

The installed gateway `node_modules` is older than the declared/locked OpenTelemetry SDK: `npm ls` reports SDK 0.221.0 invalid against the declared ^0.222.0, while the lockfile specifies 0.222.0. Local gateway test results therefore apply to the current installed tree and should be repeated after an isolated clean install before a release decision. Java artifacts and OS packages were not freshly vulnerability-scanned locally.

## Verification evidence

| Check | Result |
| --- | --- |
| Gateway direct tests | 88 passed using `node --test server.test.js api-contract.test.js` |
| Frontend transport/auth/permission tests | 37 passed across four files |
| Identity JWT/session rotation/quota unit tests | 23 passed: JWT six, rotation fifteen, abuse protection two |
| Operational dashboard auth/server tests | Six passed |
| Runtime Java/public-schema boundary audit | Passed |
| Service authorization boundary audit | Passed |
| Runtime schema dependency baseline audit | Passed, including the explicitly permitted platform/student dependency |
| Duplicate-class drift | All seven guarded class families consistent |
| Standard gateway `npm test` | Failed at raw-byte route inventory comparison before direct tests ran |
| Generated TypeScript client check | Failed at raw-byte broadcast client comparison |
| Fresh generation compared in memory | Route inventory and broadcast client equal after CRLF-to-LF normalization; no route additions/removals or changed endpoint line numbers |
| Database boundary audit | Could not run because `custoking-postgres` is not running |
| npm vulnerability audits | Findings above; audit exit status is expected to be nonzero when vulnerabilities exist |

Total targeted automated tests passed: 154. No new tests were added. No local stack was started or production mutation performed. RLS integration tests, full application E2E, provider delivery, image scans and live cloud governance remain separate verification tasks.

## Recommended execution sequence

First remediate the dependency graph in an isolated clean install, add frontend document headers, and correct photo cache metadata. These changes have clear verification paths and directly affect the build/runtime privacy boundary. Fix generated newline checks in the same maintenance window so Windows verification is dependable.

Then bound gateway proxy execution and streaming, extend database privilege guards, and give platform billing the same replay/concurrency controls as school fee collection. Test with the real local PostgreSQL stack, including pooled-connection reuse, transaction failures and competing payments.

Next strengthen privileged authentication, caller-specific service authorization, JWT purpose/rotation, browser refresh coordination, and event ordering. Reduce the platform/student database exception as part of recipient consent ownership. Keep existing fail-closed introspection, durable tombstones and exact-digest release verification intact.

Finally reconcile current-state docs with source and fresh deployment evidence. Record actual deployed projects, IAM identities, role attributes, subscription retries/DLQs, worker allocation, backup recovery, module flags and provider modes. Each feature should have separate status for implemented, enabled and operationally verified.

## Source navigation

- [Gateway request and proxy implementation](../services/api-gateway/server.js)
- [Gateway tracing adaptation](../services/api-gateway/tracing.js)
- [Frontend routes](../frontend/src/App.tsx), [transport](../frontend/src/services/api.ts), [auth state](../frontend/src/contexts/AuthContext.tsx), [navigation and modules](../frontend/src/pages/workspace/config.ts)
- [Frontend nginx](../frontend/nginx.conf)
- [Identity session service](../services/identity-service/src/main/java/com/custoking/ims/identityservice/application/IdentityAuthService.java)
- [JWT implementation](../services/identity-service/src/main/java/com/custoking/ims/identityservice/security/JwtService.java), [shared abuse controls](../services/identity-service/src/main/java/com/custoking/ims/identityservice/application/AuthAbuseProtection.java)
- [School tenant scope](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/security/TenantScope.java), [connection context](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/security/TenantAwareDataSource.java), [runtime role guard](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/security/RuntimeDbRoleGuard.java)
- [School fee commands and queries](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/FeeReadRepository.java)
- [Photo storage](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/infrastructure/StudentPhotoStorage.java), [workbook parser](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/photoimport/PhotoImportWorkbookParser.java), [student export](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/studentexport/StudentExportService.java)
- [Outbox relay](../services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/outbox/OutboxRelay.java)
- [Inbox processing](../services/platform-service/src/main/java/com/custoking/ims/platformservice/application/ReportingEventInboxProcessor.java), [student dimensions](../services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/DimensionProjectionRepository.java)
- [Internal caller verification](../services/platform-service/src/main/java/com/custoking/ims/platformservice/security/InternalCallerAuthenticator.java), [Google ID verification](../services/platform-service/src/main/java/com/custoking/ims/platformservice/security/GoogleIdentityTokenVerifier.java)
- [Billing persistence](../services/billing-service/src/main/java/com/custoking/ims/billingservice/persistence/BillingInvoiceRepository.java)
- [Dashboard authentication](../tools/live-dashboard/auth.mjs)
- [Schema dependency debt](RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json), [internal authorization rules](INTERNAL-SERVICE-AUTHORIZATION.md)
- [PR checks](../.github/workflows/ci-pr.yml), [release workflow](../.github/workflows/build-release.yml), [container scans](../.github/workflows/security-scan.yml)
