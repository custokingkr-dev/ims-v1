# Gaps, Drift, and Missing Verification

Last verified: 2026-08-05.

This file intentionally lists unresolved or partially verified items. These are not assumptions.

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

- Pub/Sub notification ingress and notification DB flows may work, but actual MSG91 delivery is not proven enabled.
- Current prod config is explicitly dry-run/logging.

Required follow-up:

- Configure an approved MSG91 SMS flow ID on the applicable sender profile.
- Validate MSG91 templates, integrated numbers, auth, and the static-egress allowlist.
- Change the prod target to `NOTIFICATION_DELIVERY_PROVIDER=msg91` and `MSG91_DRY_RUN=false`.
- Run a controlled provider smoke with an approved recipient.

### Monitoring Email Receipt Requires Human Verification

Production observability Terraform was applied on 2026-08-05. It created email
channel `11561348974326363261` and attached it to all 41 production alert
policies. Six production uptime checks are active at a 900-second period.

Impact:

- Alert routing is configured, but mailbox receipt and channel verification require
  the human mailbox owner.

Required follow-up:

- Complete Google email-channel verification and send a test alert.
- Verify at least one test incident reaches the operator channel.

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

All Cloud Run services currently use the default compute service account.

Impact:

- Simpler deployment.
- Less least-privilege isolation between services.

Required follow-up:

- Consider per-service runtime service accounts after production stabilizes.
- Split Secret Manager and Pub/Sub permissions by service.

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
