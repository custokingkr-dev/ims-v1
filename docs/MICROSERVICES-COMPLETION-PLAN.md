# Microservices Completion Plan

## Current State

The repository is physically split into backend/BFF, frontend, API gateway, and extracted services for identity, tenant-school, student, attendance, fee, catalog, workflow, firefighting, reporting, billing, notification, and audit.

The current migration gate is:

```powershell
docker compose up -d --build
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunDbAudit -RunSmoke
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 -GatewayBaseUrl http://localhost `
  -SuperadminEmail e2e-superadmin@local.test `
  -SuperadminPassword password `
  -AdminEmail e2e-admin@local.test `
  -AdminPassword password
```

Before removing any legacy `public` tables, run the non-destructive compatibility audit against the target database:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\audit-legacy-compatibility-state.ps1 `
  -PostgresContainer custoking-postgres `
  -Database postgres `
  -DbUser postgres `
  -OutputJson legacy-compatibility-audit.json
```

The JSON output contains a `summary` block with mapped-table counts, public tables that still contain rows, backfill/schema issue counts, and per-status counts. Add `-RowsOnlyJson` only when a downstream tool expects the older raw row-array shape.

## Completion Order

### 1. Production-Readiness Gate

Acceptance criteria:

- Runtime boundary audit passes.
- Deployment boundary audit passes.
- Service authorization boundary audit passes.
- DB boundary audit passes.
- Local feature smoke passes.
- Read-only deployment smoke passes against staging.
- Gateway and backend propagate `X-Request-ID` and `traceparent`.

Status:

- Local gates are in place.
- Staging read-only smoke script is in place.
- Read-only smoke can write `deployment-readiness-smoke.json` for promotion evidence.
- Correlation propagation is being enforced at gateway and backend outbound HTTP boundaries.
- Extracted service APIs fail closed when internal service tokens are missing or mismatched.
- `docs\MICROSERVICE-OBSERVABILITY-RUNBOOK.md` defines health, smoke, correlation, async health, Cloud Run signal, and promotion artifact checks.

### 2. Staging Deployment

Acceptance criteria:

- Cloud Build deploys all services and the gateway.
- Backend health reports all enabled extracted services as reachable.
- Read-only smoke passes against the staging gateway using short-lived bearer tokens.
- No direct production write smoke is run.

Operator command:

```powershell
$env:IMS_SMOKE_SUPERADMIN_TOKEN = "<superadmin-access-token>"
$env:IMS_SMOKE_ADMIN_TOKEN = "<school-admin-access-token>"
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 `
  -GatewayBaseUrl https://<staging-gateway-url> `
  -SchoolId <known-school-id> `
  -StudentId <known-student-id> `
  -AdminUserId <known-admin-user-id> `
  -ClassId <known-class-id> `
  -SectionId <known-section-id> `
  -AttendanceDate <yyyy-mm-dd>
```

### 3. Service Authorization Hardening

Acceptance criteria:

- Every internal service has a route-level authorization matrix.
- Each service rejects missing/invalid internal tokens.
- Backend/BFF checks end-user permissions before delegating commands.
- Direct service-prefix routes remain local/staging diagnostic routes only.

Implementation notes:

- Keep Cloud Run private service invocation as the transport boundary.
- Keep service tokens as application-level defense-in-depth.
- Add route-level permission assertions in service controllers before removing backend compatibility paths.
- Keep `scripts/audit-service-authorization-boundaries.ps1` green as the minimum internal-token regression gate.

### 4. Event and Read-Model Maturity

Acceptance criteria:

- Notification fanout is fully event-driven.
- Reporting dashboards consume service-owned tables or event-fed projections.
- Cross-service request-time joins are replaced where they affect latency, ownership, or availability.
- Event consumers are idempotent and expose failed/dead-letter counts.

Priority order:

1. Notification delivery and reconciliation.
2. Reporting command center and dashboard aggregates.
3. Fee/student/school projection reads.
4. Audit backfill and legacy audit decommission.

### 5. Public Shadow Removal

Acceptance criteria:

- No live service runtime depends on `public` compatibility metadata.
- Operators have run legacy audit/data backfills.
- `scripts\audit-legacy-compatibility-state.ps1 -FailOnNeedsBackfill` passes for the target environment.
- Legacy public tables are either archived or explicitly retained as read-only historical data.
- DB boundary audit remains green after cleanup.

Removal order:

1. Audit legacy table after backfill validation.
2. Tenant/school metadata public shadows.
3. Any remaining legacy command-center/reporting public shadows.

Operator flow:

1. Run `scripts\audit-legacy-compatibility-state.ps1` and review `NEEDS_BACKFILL_REVIEW` or `NO_TARGET_TABLE` rows.
2. Run domain backfills or explicit archival for any reviewed tables.
3. Re-run with `-FailOnNeedsBackfill -OutputJson <artifact-path>` as the promotion gate.
4. Generate a reviewable archive-first SQL plan:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\generate-legacy-public-retirement-sql.ps1 `
  -CompatibilityAuditJson <artifact-path> `
  -OutputSql legacy-public-retirement.sql
```

5. Review the SQL with the DBA/operator. By default it creates timestamped archive snapshots in `legacy_public_archive` and leaves `DROP TABLE ... RESTRICT` statements commented.
6. Use `-IncludeDropStatements` only during an approved destructive cleanup window after staging validation.
7. Use `-OmitTransaction` only when a DBA wants to wrap the generated SQL in an external validation transaction.
8. Use `-FailOnPublicRows` only when the target policy is complete removal of all mapped legacy public data, not when public tables are intentionally retained as read-only history.

### 6. Backend Compatibility Retirement

Acceptance criteria:

- Frontend route ownership is explicit.
- Gateway/BFF routing contract is documented.
- Service APIs have contract tests.
- All removed backend compatibility routes have frontend or gateway replacements.

Rule:

Do not remove backend compatibility controllers until staging read-only smoke and targeted UI workflows pass through the replacement route.

### 7. Independent CI/CD

Acceptance criteria:

- Each extracted service has an image build/test job.
- Shared migration gate remains as an integration job.
- Cloud Build promotion can deploy a changed service independently when its contracts are compatible.
- Rollback is documented per service.

Current guardrails:

- `scripts\microservice-build-catalog.ps1` is the local catalog for backend, frontend, gateway, and extracted service image contexts.
- `scripts\microservice-test-catalog.ps1` is the local catalog for backend, frontend, and extracted service test commands.
- `scripts\audit-microservice-build-catalog.ps1` verifies that GitHub CI, Cloud Build, and local verification include every catalogued image.
- `scripts\audit-microservice-test-catalog.ps1` verifies that GitHub CI and the local test runner include every catalogued test target.
- `docs\MICROSERVICE-ROLLBACK-RUNBOOK.md` documents per-service Cloud Run rollback, coupled rollback order, notification-provider failure handling, and the forward-only database rule.
- `scripts\audit-rollback-runbook.ps1` verifies that the rollback runbook covers every catalogued service and the required operator commands.
- `scripts\audit-observability-runbook.ps1` verifies that health checks, read-only smoke artifacts, correlation headers, async health, and promotion artifacts are documented.
- Use targeted local image verification when working on one service:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 `
  -RunBuilds `
  -BuildServices fee-service,catalog-service
```

- Use targeted local service tests when working on one service:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 `
  -RunServiceTests `
  -TestServices notification-service
```

- Use full image verification before release:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-microservice-migration.ps1 -RunBuilds -RunServiceTests
```

## Production Cutover Checklist

- [ ] Rebase migration branch on current `master`.
- [ ] Run local migration gate.
- [ ] Build all service images.
- [ ] Deploy to staging.
- [ ] Run read-only staging smoke with bearer tokens.
- [ ] Validate backend `/actuator/health`.
- [ ] Validate gateway `/gateway-health`.
- [ ] Validate Cloud Run private invoker grants.
- [ ] Validate Secret Manager tokens for all services.
- [ ] Validate MSG91 is still dry-run unless production templates/domain/static egress are complete.
- [ ] Validate logs include `requestId` across gateway/backend/service calls.
- [ ] Promote to production.
- [ ] Run read-only production smoke.
- [ ] Monitor error rate, latency, outbox pending age, notification failed inbox rows, and service health.

Promotion preflight:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-promotion-preflight.ps1 `
  -Environment staging `
  -DeploymentSmokeJson deployment-readiness-smoke.json `
  -LegacyCompatibilityJson legacy-compatibility-audit.json
```

For production, include release evidence:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-real-environment-readiness-preflight.ps1 `
  -ProjectId <project-id> `
  -Region <region> `
  -DeploymentSmokeJson deployment-readiness-smoke.json `
  -LegacyCompatibilityJson legacy-compatibility-audit.json `
  -OutputJson real-environment-readiness-preflight.json `
  -OutputMarkdown real-environment-readiness-preflight.md

powershell -ExecutionPolicy Bypass -File scripts\invoke-production-readiness-bundle.ps1 `
  -Environment production `
  -ProjectId <project-id> `
  -Region <region> `
  -Repository <artifact-registry-repo> `
  -Tag <image-tag> `
  -BuildId <cloud-build-id> `
  -DeploymentSmokeJson deployment-readiness-smoke.json `
  -LegacyCompatibilityJson legacy-compatibility-audit.json `
  -ArtifactDir promotion-artifacts
```

The preflight writes `real-environment-readiness-preflight.json` and
`real-environment-readiness-preflight.md`, then the bundle produces the final
promotion artifacts.

The bundle creates:

- `promotion-artifacts\cloud-run-revisions.json`
- `promotion-artifacts\image-digests.json`
- `promotion-artifacts\cloud-build-evidence.json`
- `promotion-artifacts\secret-manager-evidence.json`
- `promotion-artifacts\cloud-run-iam-evidence.json`
- `promotion-artifacts\legacy-public-retirement.sql`
- `promotion-artifacts\legacy-retirement-evidence.json`
- `promotion-artifacts\rollback-drill-evidence.json`
- `promotion-artifacts\promotion-bundle-manifest.json`
- `promotion-artifacts\production-readiness-report.json`
- `promotion-artifacts\production-readiness-report.md`

The bundle runs these individual exporters internally: `scripts\export-cloud-run-revisions.ps1`,
`scripts\export-image-digests.ps1`, `scripts\export-cloud-build-evidence.ps1`,
`scripts\export-secret-manager-evidence.ps1`, and `scripts\export-cloud-run-iam-evidence.ps1`.
Their canonical artifact names are `cloud-run-revisions.json`, `image-digests.json`,
`cloud-build-evidence.json`, `secret-manager-evidence.json`, and
`cloud-run-iam-evidence.json`.
It also runs `scripts\new-legacy-retirement-evidence.ps1` and
`scripts\new-rollback-drill-evidence.ps1` to produce `legacy-retirement-evidence.json`
and `rollback-drill-evidence.json`.
`scripts\new-promotion-bundle-manifest.ps1` creates the final
`promotion-bundle-manifest.json`.

For troubleshooting, the individual preflight remains:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\invoke-promotion-preflight.ps1 `
  -Environment production `
  -DeploymentSmokeJson deployment-readiness-smoke.json `
  -LegacyCompatibilityJson legacy-compatibility-audit.json `
  -CloudBuildJson promotion-artifacts\cloud-build-evidence.json `
  -ImageDigestJson promotion-artifacts\image-digests.json `
  -RevisionInventoryJson promotion-artifacts\cloud-run-revisions.json `
  -SecretManagerEvidenceJson promotion-artifacts\secret-manager-evidence.json `
  -CloudRunIamEvidenceJson promotion-artifacts\cloud-run-iam-evidence.json `
  -LegacyRetirementEvidenceJson promotion-artifacts\legacy-retirement-evidence.json `
  -RollbackDrillEvidenceJson promotion-artifacts\rollback-drill-evidence.json `
  -PromotionBundleManifestJson promotion-artifacts\promotion-bundle-manifest.json
```

When running individual files from the repository root instead of `promotion-artifacts`,
use `-CloudBuildJson cloud-build-evidence.json`, `-ImageDigestJson image-digests.json`,
`-SecretManagerEvidenceJson secret-manager-evidence.json`, and
`-CloudRunIamEvidenceJson cloud-run-iam-evidence.json`, plus
`-PromotionBundleManifestJson promotion-bundle-manifest.json`.

## Rollback Policy

- Prefer Cloud Run revision rollback for application failures.
- Do not roll back database migrations. Apply forward-only repair migrations.
- If an extracted service fails, disable backend delegation only when the compatibility fallback still exists for that domain. Migrated paths with removed fallbacks must roll back service and backend revisions together.
- If notification provider delivery fails, keep inbox retry enabled, set MSG91 dry-run only in non-production, and pause Pub/Sub push subscription if duplicate provider sends are possible.
- Use `docs\MICROSERVICE-ROLLBACK-RUNBOOK.md` for per-service and coupled Cloud Run rollback operations.
