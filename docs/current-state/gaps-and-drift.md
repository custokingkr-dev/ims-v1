# Gaps, Drift, and Missing Verification

Last reconciled: 2026-08-24 against current source and executed migration evidence.

**24 August delta:** MIG-01 is complete. Development runs in `custoking-dev` and production runs in
`custoking-prod`; production passed the full relation digest, object, durable-event, smoke, and repaired
release-path checks. The legacy source project is now observed in `DELETE_REQUESTED` state; this execution
did not request or alter deletion. Production school-core uses service-account Drive access, and a temporary
job under the deployed runtime identity proved the configured Drive root remains readable, listable, and
writable. Billing-export, backup/log-retention, and formal deletion custody still require named owners.

Production async topology is now active: all four authenticated relay schedules return HTTP 200 and the
reporting and notification push subscriptions have dedicated OIDC identities, explicit retry, seven-day
retention, and DLQs. A production PITR clone/schema-validation drill passed a 603.96-second RTO and proved
complete cleanup. Governed development capacity testing remains fail-closed because month-to-date gross
cost is already over its approved budget; GitHub protection, policy ownership, real provider delivery, and
the named-school canary remain owner/authority gates.

Attendance partitioning has now passed a durable 25,000,000-row forward/rollback rehearsal with equal
counts and checksums plus uniqueness, FK/check, RLS/bypass, pruning/default, index, and exact-cleanup gates.
Repository-side DATA-01 now has a reviewed maintenance-window operator design rather than unsafe automatic
Flyway startup DDL, plus a PostgreSQL statistics reporter and disabled-by-default Terraform for row, index,
and sequential-scan thresholds. The remaining live gap is an owner-approved restored-clone run, maintenance
freeze/cutover decision, and explicit observability plan/apply. No production partition DDL or live DATA-01
alert was applied.

**14 August delta:** the OpenTelemetry background-flush fix is deployed to dev and production. Production
commit `754f0417` passed exact-digest deployment, seven live HTTP checks, exact current-revision tracing, and
a 30-minute exporter-error query with zero failures. The primary operator confirmed alert-email receipt.
OBS-01 now requires one full school-day stability window and a tested backup recipient. The approved GCP
migration target is two new projects; see the authoritative remaining-work register and split-project
runbook. That dated visibility blocker was superseded by the completed development rehearsal and 19 August
production cutover.

The authoritative prioritized closure plan is
[../REMAINING-WORK-2026-08-12.md](../REMAINING-WORK-2026-08-12.md). Production services and production
reporting have already moved to dedicated identities; older statements below that they still use default
Compute are superseded. Remaining default-Compute exposure is concentrated in eight Cloud Run jobs, live
dev Cloud Deploy targets, broad project roles, and a legacy platform invoker binding.

Production deployment state changed materially on 2026-08-11. The seven services now use dedicated runtime
identities and immutable-digest Cloud Deploy revisions; reporting push uses a dedicated OIDC identity without
a query credential; Cloud SQL is private-IP-only and `ENCRYPTED_ONLY`; and production workflow identities are
split by release, configuration, and rollback duties. Exact live state and rollback evidence are in
`docs/PRODUCTION-DEPLOYMENT-2026-08-11.md`. Older statements below about default Cloud Run identities,
permissive production SQL, the reporting query credential, or repository-only WIF conditions are retained as
historical drift and are superseded by that dated record. Remaining gaps include branch protection, two SQL
jobs on the default Compute identity, a governed real reporting/DLQ observation, real MSG91 validation,
database/HA capacity approval, and legal/canary acceptance.

This file intentionally lists unresolved or partially verified items. These are not assumptions.

## Product Internationalization

School timezone, country, locale, currency, and phone region are now tenant settings and reach
the main workspace projection. The active fee workspace and dashboard use them. Some older
Supply OS, invoice, command-center drawer, and legacy fee components still contain India-specific
currency labels, GST wording, or `en-IN` formatting. These screens must be converted before a
non-India tenant can use those modules without India-specific presentation or tax semantics.

This is not a storage blocker for onboarding a non-India school. It is a product-localization and
tax-rule blocker for enabling every commercial module in that tenant.

## Critical or High Priority

### Notification Delivery Is Intentionally In Safe Mode

Verified live platform-service prod env includes:

```text
NOTIFICATION_DELIVERY_PROVIDER=logging
MSG91_DRY_RUN=true
```

The live Cloud Run env verified after the 2026-08-05 foundation deployment explicitly
selects `logging` and dry-run. A private production database audit found one active
sender profile and zero profiles with an MSG91 SMS flow ID. Production therefore stays
in safe mode while bounded retry/dead-letter handling remains active.

Impact:

- Pub/Sub notification ingress and notification DB flows are wired with production retry/DLQ controls, but
  actual MSG91 delivery is not proven enabled.
- Dev and production notification Pub/Sub ingress use dedicated OIDC push subscriptions with retry/DLQ;
  canonical duplicate delivery and poison-to-DLQ probes passed using the logging/dry-run provider. Real
  consented MSG91 delivery remains unproven.
- Current prod config is explicitly dry-run/logging.

Required follow-up:

- Configure an approved MSG91 SMS flow ID on the applicable sender profile.
- Validate MSG91 templates, integrated numbers, auth, and the static-egress allowlist.
- Change the prod target to `NOTIFICATION_DELIVERY_PROVIDER=msg91` and `MSG91_DRY_RUN=false`.
- Run a controlled provider smoke with an approved recipient.

### Monitoring Backup Route and Full Stability Evidence Remain

Production observability Terraform was applied on 2026-08-05. It created email
channel `11561348974326363261` and attached it to all 41 production alert
policies. Six production uptime checks are active at a 900-second period.

Impact:

- The primary operator has confirmed receipt and the trace fix has completed production promotion, but no
  named backup recipient has been proven and the full school-day stability window is incomplete.

Required follow-up:

- Add/verify the named backup route and prove the same test incident reaches primary and backup.
- Capture one full school day in both promoted environments without recurring exporter failures.

### Compliance Log Routing Is Live; Retention Ownership Requires Review

Production now has `custoking-compliance-india` in `asia-south2` with 180-day
retention and a project sink for audit, security, Cloud Run request, and error logs.
Legal/security ownership must still confirm whether 180 days is the required policy
before the bucket is ever locked.

## Infrastructure Drift

### Source Custom Deploy Role Is Missing Live

Source file exists:

```text
deploy/gcp/github-deploy-runtime-operator-role.yaml
```

But:

```text
gcloud iam roles describe githubDeployRuntimeOperator --project=custoking
```

returned not found. The project now has the unrelated
`custokingRecoveryBucketIamOperator` role for recovery-bucket IAM only; it does not
replace the missing deploy role.

Live deploys are succeeding through predefined role bindings on `github-actions-sa`:

- `roles/cloudbuild.builds.editor`
- `roles/iam.serviceAccountUser`
- `roles/run.developer`
- `roles/storage.admin`

Impact:

- Live IAM is broader/different than the source runbook's custom-role posture.

Required follow-up:

- Either create/update the custom role and cut over IAM, or update docs/source to reflect the intentional predefined-role model.

### CI/CD Affected-Service Promotion Is Active, But Hardening Remains

The former active CI/CD files and `cloudbuild.yaml` were retired on 2026-08-03. The replacement GitHub Actions and Cloud Deploy implementation is active as of 2026-08-04. The implementation notes are documented in:

```text
docs/current-state/deployment-cicd.md
```

Verified:

- foundation commit `a8acfbe78992404a87d30d0e7eb19ace1b0638a2` deployed successfully to dev and prod.
- GitHub dev run `30997099794` succeeded.
- Production releases `rel-prod-a8acfbe78992-1` and `rel-prod-a8acfbe78992-2` reached `SUCCEEDED`; the second release replaced the frontend revision that failed when the first workflow exhausted the regional 20 vCPU quota.
- prod `/gateway-health` returned `UP`.

Remaining gaps:

- GitHub `dev` Environment branch restriction still needs a repository admin to add `dev` as the only allowed deployment branch in the GitHub UI.
- Stage target templates exist, but stage promotion is not active until a real stage database, GitHub Environment, and `-stage` secrets exist.
- Release workflow verifies every changed Cloud Run revision, exact digest, 100 percent traffic, changed frontend HTTP response, and gateway health. Authenticated business-flow smokes are still not automatic.
- Deploy service account still has broad legacy roles, including `roles/cloudbuild.builds.editor` and `roles/storage.admin`.
- Production actions are not pinned by SHA.
- Cloud Monitoring/SLO-based canary gates are not automatic.
- Cloud Quotas currently marks this project ineligible for a Cloud Run CPU increase because it lacks enough usage history; production promotion is serialized to remain within the current 20 vCPU limit.
- Do not restore the old `cloudbuild.yaml` deployment path unless there is a documented emergency reason.

### Terraform CLI Availability Was Repaired For This Shell

`Get-Command terraform` now resolves to:

```text
C:\Users\Shubham-Work\AppData\Local\Microsoft\WinGet\Packages\Hashicorp.Terraform_Microsoft.Winget.Source_8wekyb3d8bbwe\terraform.exe
```

Impact:

- The new CI/CD Terraform module can be formatted and validated locally.
- Observability Terraform can also be planned from this shell if credentials are available.

Required follow-up:

- Keep Terraform available on operator machines.
- Re-run `terraform -chdir=deploy/gcp/observability plan` for dev and prod when changing observability resources.

## Documentation Drift

### Historical Docs Still Mention Retired Service Names

Older dated plans still mention separate physical services such as:

- tenant-school-service
- student-service
- attendance-service
- fee-service
- catalog-service
- workflow-service
- firefighting-service
- reporting-service
- notification-service
- audit-service

Current deployed topology is:

- frontend
- api-gateway
- identity-service
- school-core-service
- operations-service
- platform-service
- billing-service

Current active docs have been updated:

- `README.md`
- `docs/ARCHITECTURE-HLD.md`
- `docs/ARCHITECTURE-LLD.md`
- `docs/current-state/*`

Impact:

- New operators may still hit stale service names if they use historical plans instead of current-state docs.

Required follow-up:

- Keep historical-plan archive banners visible.
- Prefer `docs/current-state/` and the root `README.md` for current topology.

### Old Artifact References Project `custoking-ims`

`artifacts/real-environment-readiness-final.json` references:

```text
projectId: custoking-ims
```

Current project is:

```text
custoking
```

Impact:

- That artifact is stale and should not be used as current production evidence.

Required follow-up:

- Generate a new real-environment readiness artifact for project `custoking`, or mark old artifacts as archived.

## Verification Gaps

### Fresh Prod Async Business Mutation Was Not Run

The latest observability check verified:

- Pub/Sub topics/subscriptions.
- Publisher startup.
- outbox/inbox backlog counts.
- trace/log plumbing.

It did not create a new prod business event because that would mutate prod.

Impact:

- Infrastructure and backlog health are verified.
- A fresh producer -> Pub/Sub -> platform-service -> projector trace is not documented from the latest pass.

Required follow-up:

- Run an approved prod write-path smoke or use a dedicated harmless test event path.
- Capture event id, Pub/Sub delivery, inbox row, projection result, and trace id.

### End-to-End External Notification Delivery Not Verified

No approved real MSG91 send was verified in the latest pass.

Impact:

- Notification storage/retry/inbox may be healthy while external provider delivery remains unverified.

Required follow-up:

- Decide dry-run vs real send posture.
- Verify provider credentials/templates.
- Run controlled send smoke.

### Monitoring SLO Direct List Not Available in Current SDK

The local `gcloud` SDK did not provide `gcloud monitoring services list`.

Impact:

- SLO source exists in Terraform and related burn-rate alert policies exist, but direct SLO inventory was not produced by command in this pass.

Required follow-up:

- Use Cloud Console, Monitoring API, or a newer SDK component to list Monitoring services/SLOs directly.

### Observability API Disabled

`observability.googleapis.com` was not in the enabled services list.

Impact:

- Newer observability trace-scope commands fail.
- Existing Monitoring, Logging, Cloud Trace, dashboards, alerts, uptime checks, and OTEL export are not blocked by this.

Required follow-up:

- Enable only if trace scopes or other observability API features are needed.

## Lower Priority Cleanups

### GCP Service Account Strategy Is Broad

All fourteen dev/prod Cloud Run services now use dedicated per-service identities. Eight of nine Cloud
Run jobs and all seven live dev Cloud Deploy targets still use the default Compute service account.

Dev and production reporting Pub/Sub push callers use dedicated service accounts distinct from the
platform runtime identity.

Impact:

- Simpler deployment.
- Less least-privilege isolation between services.

Required follow-up:

- Move the remaining jobs and live dev Cloud Deploy targets to their dedicated identities.
- Remove the legacy platform invoker binding and broad default-compute permissions after those cutovers
  have been observed and rollback evidence exists.

### Dev and Prod Share One Project

The verified design intentionally uses one project with env suffixes.

Impact:

- Simpler setup and promotion.
- IAM, quota, logs, and billing are shared at project level.

Required follow-up:

- If stricter isolation is required later, plan project split intentionally rather than ad hoc.

### Platform-Service Min Instances Not Set

Live config shows min instances not set for platform-service in both environments.

Impact:

- Reporting projection and notification retry schedules run only when an instance exists.
- Pub/Sub push traffic can cold-start the service, but background-only retry/projection timing may depend on instance presence.

Required follow-up:

- Decide whether platform-service should also run with `--min-instances=1`, like outbox-producing services and gateway.
