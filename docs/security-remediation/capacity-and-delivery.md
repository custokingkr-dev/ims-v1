# Capacity, delivery and direct-boundary evidence

Date: 2026-10-06. Owner: workflow/billing implementation agent for source review, overlap audit, local measurement and read-only direct smoke. Root agent owns manifest and live infrastructure actions. No cloud load, message delivery, IAM mutation or database production changes were performed by this agent.

## Connection ceilings (SEC-32)

The old connection audit counted only one running revision. The old dev target allowed four instances of each domain: school-core pool 20 (80 connections), four other domain pools 5 (80), total 160. Two overlapping revisions allow 320 runtime connections before migration and job clients. Counting startup migration pools adds 136, jobs 10 and the 40 maintenance/operator reserve: **506 configured simultaneous connections**, above the assumed database limit 200.

The root agent's revised manifests explicitly set domain instance ceilings to two, school-core runtime pool to eight, and the other four runtime pools to three. The overlap audit now counts:

| Service | Instances / revision | Runtime pool | Two-revision runtime ceiling | Migration pools per starting instance | Per-instance migration ceiling | Two starting instances |
| --- | ---: | ---: | ---: | --- | ---: | ---: |
| Identity | 2 | 3 | 12 | Single-schema Flyway lock + migration connection | 2 | 4 |
| School core | 2 | 8 | 32 | Five Hikari pools, each maximum 3 | 15 | 30 |
| Operations | 2 | 3 | 12 | Two Hikari pools, each maximum 3 | 6 | 12 |
| Platform | 2 | 3 | 12 | Three Hikari pools, each maximum 3 | 9 | 18 |
| Billing | 2 | 3 | 12 | Single-schema Flyway lock + migration connection | 2 | 4 |
| Total | | | **80** | | | **68** |

Steady runtime ceiling is 40. Conservative overlap/startup/job ceiling is `80 + 68 + 10 = 158`. Adding the separate 40 reserve gives **198 / 200**, leaving two nonreserved connection slots. The reserve is genuine operational headroom, not authorization to grow application pools. Live `max_connections`, overlapping old revisions with old pool sizes, owner migration clients, temporary jobs and other services must still be counted before rollout. In particular, a revision with the old school pool 20 does not become pool eight until that revision has drained. These calculations are source ceilings and cannot certify live utilization or autoscaler behavior.

Custom Flyway schemas run sequentially, but their datasource objects are distinct. `minimumIdle=0` and `idleTimeout=10000` do not close a pool immediately; recently used schema pools can coexist until housekeeping drains them. The calculation counts all configured pools rather than assuming only the actively migrating schema has connections. Single-schema two-connection Flyway behavior is an explicit conservative assumption that must be checked if that datasource configuration changes.

`scripts/audit-db-connection-budget.ps1` now takes revision overlap, startup instances, jobs and operator reserve separately; it fails oversubscription and emits each contribution. `ConcurrentStartupInstancesPerService=1` is valid only if deployment actually enforces that startup bound. Its default counts each service's configured maximum. Five fixture scenarios prove old dev fails, the revised conservative shape passes, all five school migration pools are counted, added jobs can fail the budget, and an explicitly enforced startup bound is modeled separately. A genuine missing/changed migration configuration fails rather than silently assuming zero.

## Local bounded measurement (VER-10 / SEC-32)

`BoundedPoolLoadIntegrationTest` uses an isolated PostgreSQL 16 container. Thirty-two benign SELECTs run on eight workers against a real Hikari maximum three pool, with one-second connection and query limits. Observed physical pool peak: **three**. Measured elapsed time: **595 ms**, request p95 **381 ms**. A controlled `pg_sleep(5)` query cancelled after **1,006 ms** and the pool returned to zero active connections; subsequent query and `pg_stat_activity` confirmed reuse and at most three physical application connections. Observed heap delta was 9,107,344 bytes. This is a local measurement for this fixture, not a stable per-request allocation estimate or a Cloud Run memory/performance certification. No business data or secrets were used.

Machine-readable observations are saved in [capacity-local-evidence.json](capacity-local-evidence.json). The actual test and fixture scripts are:

```powershell
.\mvnw.cmd -f services/operations-service/pom.xml test '-Dtest=BoundedPoolLoadIntegrationTest' '-Dstyle.color=never'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/tests/db-connection-budget-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/audit-db-connection-budget.ps1 -Environment dev
```

Cloud certification remains required at the new pool sizes: stop growth at measured Cloud SQL CPU >=80%, memory >=90%, connections >=140 (the existing dev load tool thresholds), any new authorization failure, rising waiting pool requests or sustained service error rate. Use `invoke-dev-load-certification.ps1` and `read-dev-capacity-evidence.ps1` with an isolated authorized fixture and exact deployed revisions. Retain request latency, database connections/CPU/memory, container memory, replica counts and revision overlap. Do not extrapolate the old 10,000-user/load evidence to changed pools.

## Memory, queues and trace budget

All checked-in Cloud Run services use concurrency 80 and one CPU. Identity, operations, platform and billing each have 768Mi; school core has 2Gi; frontend/gateway have 512Mi. Java runtime `MaxRAMPercentage=75` yields approximately 576Mi maximum heap plus 192Mi for native allocations in the 768Mi containers, and 1,536Mi heap plus 512Mi native in school core. Native usage includes threads, direct buffers, TLS, JIT/metaspace and client libraries, so these remainder values are budgets rather than measured spare memory.

At two domain instances, domain memory ceilings total 10Gi per revision, before frontend/gateway and any overlap. A service concurrency 80 with pool three can have far more waiting HTTP requests than active database consumers. The source Hikari acquisition timeout remains 30 seconds. Gateway and service operation deadlines and admission controls must align so failed/cancelled requests do not occupy queue/multipart/parser memory until the acquisition timeout. Peak import/export/multipart memory requires the actual bounded workload measurements; the SQL-only local fixture does not exercise it. Trace sampling deploy parameters are 0.05, whereas application defaults are 1.0; the live deployment must prove the parameter is rendered on every revision and relay request flushes have a bounded deadline.

Cloud Run maximum settings can be exceeded briefly and apply at service or revision scope; account for revision overlap rather than treating a source maxScale as an absolute database safety barrier. [Google maximum instance documentation](https://docs.cloud.google.com/run/docs/configuring/max-instances). Use bounded pools and explicit connection accounting, as recommended by [Cloud SQL connection guidance](https://docs.cloud.google.com/sql/docs/postgres/manage-connections).

## Request-driven delivery (SEC-31)

Source manifests have request-based CPU allocation (`cpu-throttling=true`) and minScale zero. A Java `@Scheduled` method alone therefore does not prove idle delivery. [Google CPU allocation guidance](https://docs.cloud.google.com/run/docs/configuring/billing-settings) explains that request-based CPU is available while requests are being processed.

`configure-async-relay-scheduler.ps1` provides a cost-conscious request-driven pattern: minute HTTP POST, dedicated `ims-async-scheduler-{env}` service account, service-scoped invoker grants, exact service URL as OIDC audience, and explicit verification of observed 2xx Cloud Run request logs. School/operations/billing target `/api/v1/internal/outbox/relay`; platform targets `/api/v1/internal/async/drain`. Retry settings and delivery verification remain enabled. The private-workflow dev provisioning script currently selects operations and platform only; source existence does not establish the school/billing live jobs.

Root should capture live scheduler jobs for all four domains, exact URI/audience/identity, last attempt state, dedicated invoker IAM graph, and real bounded idle-to-request drain latency. Test one harmless fixture event per relevant domain after scale-to-zero; verify consumer result, duplicate handling and backlog/dead-letter alert delivery. Keep broadcasting in DRY_RUN/OFF unless its separately verified sender and recipient policy is active. Pub/Sub source runtime provisioning grants reporting-topic publisher only to school/operations/billing identities; push OIDC scripts restrict target audience and caller, with subscription/DLQ retry configuration. Actual topic/project grants and shared service-agent permissions still need VER-09 evidence.

School/billing/operations publisher waits default five seconds with hard ten-second cap, cancellation on timeout/interrupt; each production publisher receives the remaining ten-second relay dispatch budget, batch maximum ten. Database selection and updates are additional latency outside this dispatch budget. Payment/action writes and outbox retry/publish-before-mark behavior stay transactional. The future may have been accepted remotely before cancellation: at-least-once deduplication remains mandatory.

Password reset delivery currently requires an always-allocated worker and minimum running instance when enabled. Source defaults disable reset, and manifest does not activate SMTP/worker-ready. Do not set `IDENTITY_PASSWORD_RESET_WORKER_READY=true` on an idle request-only service merely to satisfy startup. A legitimate rollout needs either a verified always-CPU/minimum-instance worker or an authenticated request-driven durable drain design, then actual mail delivery/retry and idle/scale-to-zero evidence. SMTP socket timeouts alone do not establish delivery. This agent did not enable reset or send mail.

## Direct backend boundary smoke (VER-01)

The previous direct smoke manufactured user ID zero/SUPERADMIN and performed PUT followed by DELETE on REPORTS entitlement, potentially changing an existing school's configuration. The new template is entirely read-only. First, it proves IAM + peer token without user context cannot call catalog annual-plan or tenant module user routes (expects 401/403). Optional positive reads require explicitly configured positive dedicated user and school IDs; context is SCHOOL_ADMIN with only `order:read,school:read`, and no all-school discovery. No response data/PII is printed. No synthetic SUPERADMIN, mutation or entitlement deletion remains.

`new-direct-service-smoke-job.ps1 -SmokeUserId <dedicated-id> -SmokeSchoolId <school-id>` enables the positive reads. Without that actor, the job reports **boundary-only** evidence rather than pretending functional reads succeeded. The chosen dedicated actor/school must be verified by the release operator; a transported header is not independent session authentication. User authentication/introspection must be tested through the gateway; approved machine routes require their separate caller capability. Controlled-network tests execute the actual embedded Python and prove read-only calls, explicit scoped actor, denial failures and rejection of zero/missing IDs:

```powershell
python scripts/tests/direct_service_smoke_policy_test.py
```

Four tests passed. Gateway route smoke intentionally treats anonymous 401 as boundary evidence; it is not a complete authenticated functional or role matrix. Combine this with authenticated two-school gateway probes, forbidden caller header stripping, public direct URL denial, machine-route OIDC audience/caller tests, actor/assignment revocation, and no side effects on denied nested/object IDs.

Status: source safeguards implemented and local controls measured. SEC-31/32 and VER-01/09/10 live acceptance criteria remain open until root records actual environment evidence; this report makes no risk acceptance or deployed assurance claim.

## Reviewed dev runtime-role preparation

`scripts/security/prepare-dev-runtime-roles.ps1` is dev-fixed and dry-run by default. Apply requires the exact SHA256 of `runtime-role-cutover.sql` printed by the reviewed dry-run. Five stable, labeled managed secrets hold independent cryptographically random 64-character hex passwords. Secret payloads use the Secret Manager REST API in memory, with no credential-bearing files, CLI arguments or output. Existing unmanaged secrets and database roles are refused; managed enabled versions are reused without implicit rotation.

The pinned PostgreSQL owner job receives password secret references, executes role prechecks, reviewed grants, passwords and management markers in one transaction, and exposes only a constant success marker. It uses 512Mi and dev VPC annotations. Only newly added temporary owner secret-access grants are removed in `finally`; permanent runtime accounts retain access solely to their own secret. The temporary job is deleted after execution. Preparation does not cut over service revisions. Root must review and separately authorize/execute cloud application and verify runtime login/RLS/ACL and cleanup evidence.

`python scripts/tests/runtime_role_preparation_test.py`: five tests passed on isolated PostgreSQL. Actual rendered shell/SQL proved scoped role creation, safe managed re-entry, atomic refusal of an unmanaged existing role, invalid hex rejection, reference-only dry-run job configuration, and wrong reviewed SHA refusal before cloud calls. No GCP resources were changed by these tests.

Final remaining-budget regression: operations `PublishDeadlineTest,OutboxRelayTest` 7 passed; billing `PublishDeadlineTest,OutboxRelayIntegrationTest` 5 passed; school `PublishDeadlineTest,OutboxRelayTest` 8 passed. All used Docker PostgreSQL where applicable, zero failures/errors/skips. Evidence: operations-outbox-final.log, billing-outbox-final.log, school-outbox-bounds.log. School's added fixture proves excessive batch12 is clamped10 and configuredzero clamps1, preserving remaining unpublished rows.

PowerShell 5 native stderr regression correction: ordinary gcloud progress is suppressed under a locally scoped Continue preference and checked native exit status; Stop is restored afterward. Auth/create/execute/delete all use this wrapper. Cleanup tracks job creation before invoking the CLI, including partial acceptance followed by CLI failure. A successful preparation explicitly exits0. Two local actual Windows PowerShell5 `.cmd` progress/exit-status fixtures passed; original five isolated PostgreSQL shell tests also rerun. No cloud changes were performed by these fixtures.

## Owner migration jobs before Java release

`invoke-service-migration-job.ps1` runs the exact immutable runtime image through the PropertiesLauncher migration-only entry point before Cloud Deploy release creation or direct Cloud Run update. It accepts only custoking-dev/dev or custoking-prod/prod in asia-south2, full commit SHA and SHA256 image from the environment registry. Cloud Deploy resolves explicit database host/name from the live target; direct mode reads the live runtime datasource URL. Missing/placeholder settings, embedded credentials and URL overrides fail closed. Dry-run requires an explicit URL and performs no cloud reads.

The nonce job has one task, maxRetries0, timeout300s, CPU1/768Mi, default VPC private-ranges-only. `ims-db-migration-ENV` alone accesses `db-password-ENV`; runtime password secrets are absent. The CLI execution wait is bounded600s. Windows PowerShell5 progress stderr is kept private and exit status checked. Cleanup tracks creation before invoking the CLI and deletes the exact nonce job even on partial creation/execution failure. Success additionally requires Completed=True and per-owned-schema successful version/count markers emitted only after Flyway migration/history inspection; bounded log-ingestion polling blocks release when markers are missing. Evidence records commit/image digest, elapsed time and schema versions/counts, with no SQL/credentials.

`migration_jobs.tf` adds the migration SA, exact owner-secret and release-repository access, release-SA actAs and a project custom role containing only run.jobs.delete for the trusted release principal. Cloud Run job deletion does not support a resource-name IAM condition; the exact nonce-name restriction is enforced by the script and is not an IAM security boundary. This principal already has create/update/run job permissions. Existing release custom job/read-log permissions are reused. Runtime identities never receive owner secret access from this file. Future owner-created tables require explicit narrow runtime grants in migrations or a reviewed ACL synchronization; no blanket default privileges are introduced.

Validation: five actual rendered-job/native-fixture tests passed (launcher/digest/env/VPC/memory/retries/timeouts, successful complete schema evidence and cleanup, partial-create failure cleanup, execution failure cleanup, missing-schema failure cleanup). `terraform fmt -check infra/terraform/cicd/migration_jobs.tf` passed. No Terraform/GCP application was performed. Live execution JSON/marker ingestion and IAM graph must be proved by root before runtime owner access is removed.

Final full-source verification (including migration-only and runtime migration policy tests): operations188 passed; billing90 passed; zero failures/errors/skips. Operations Maven native exit0; billing rechecked under explicit native stderr handling with IMS_MAVEN_NATIVE_EXIT=0. Evidence operations-final-full.log and billing-final-full.log. Terraform init -backend=false and validate passed without Terraform application; no lockfile changes. Extended migration orchestration7 tests, runtime secret matrix2 and release-trigger3 all passed.

Runtime IAM source now lists only each domain's dedicated database password secret; no runtime owner/shared password grant and no gateway JWT grant. Production application requires separately prepared and verified five runtime roles/secrets plus the existing production authorization switch. The grant script does not revoke historical grants; its plan explicitly reports that and lists allowed secret names. Root must perform live revocation only after healthy independent runtime-role evidence.

CI selection recognizes both migration and Cloud Deploy helper changes and requires configuration reconciliation for migration_jobs.tf. A TF-only change cannot silently skip IAM reconciliation and proceed with service releases. The existing protected reconciliation workflow still needs operator-run Terraform/IAM reconciliation documented; it only renders targets/pipelines itself.

IAM review caveat: the drafted resource.type/resource.name cleanup condition is not supported by the published Cloud Run resource attribute list. [Google IAM resource attributes](https://docs.cloud.google.com/iam/docs/conditions-resource-attributes) omits Cloud Run; [Cloud Run IAM documentation](https://docs.cloud.google.com/run/docs/securing/managing-access) documents request.host/path conditions for invocation, not job deletion. The unsupported condition was removed after root review; the final source grants only run.jobs.delete project-wide to the trusted release principal, without jobs.admin or setIamPolicy. Root will replace the earlier dev binding before use. The script's exact-name cleanup remains enforced as an application guard, not an IAM boundary. This is an inference from primary support documentation, not live IAM evidence.
