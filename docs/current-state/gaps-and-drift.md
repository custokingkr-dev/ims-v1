# Gaps, Drift, and Missing Verification

Last verified: 2026-07-09.

This file intentionally lists unresolved or partially verified items. These are not assumptions.

## Critical or High Priority

### Notification Delivery Is Not Proven Real in Prod

Verified live platform-service prod env includes:

```text
MSG91_DRY_RUN=true
```

The live Cloud Run env did not show `NOTIFICATION_DELIVERY_PROVIDER`. In `platform-service` `application.yml`, the default is:

```text
notification.delivery.provider=logging
```

Impact:

- Pub/Sub notification ingress and notification DB flows may work, but actual MSG91 delivery is not proven enabled.
- Current prod config appears dry-run/logging unless provider selection is set elsewhere outside the verified env.

Required follow-up:

- Decide if prod should send real MSG91 messages now.
- If yes, set `NOTIFICATION_DELIVERY_PROVIDER=msg91` and `MSG91_DRY_RUN=false` after validating MSG91 templates and auth.
- Run a controlled provider smoke with an approved recipient.

### Alert Policies Have No Verified Notification Channels

`gcloud beta monitoring channels list --project=custoking` returned no channels.

Impact:

- 80 alert policies are enabled, but no email/SMS/PagerDuty/etc. target was verified.
- Alerts may be visible in Cloud Monitoring but may not notify operators.

Required follow-up:

- Create notification channels.
- Re-apply observability Terraform with `notification_channel_ids`.
- Verify at least one test incident reaches the operator channel.

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

returned not found, and project custom role list was empty.

Live deploys are succeeding through predefined role bindings on `github-actions-sa`:

- `roles/cloudbuild.builds.editor`
- `roles/iam.serviceAccountUser`
- `roles/run.developer`
- `roles/storage.admin`

Impact:

- Live IAM is broader/different than the source runbook's custom-role posture.

Required follow-up:

- Either create/update the custom role and cut over IAM, or update docs/source to reflect the intentional predefined-role model.

### Direct Service Smoke Was Re-Enabled And Needs Next-Run Evidence

`release.yml` now requests:

```yaml
run_direct_smoke: true
run_gateway_smoke: true
```

The direct smoke job template now uses env-suffixed secrets (`catalog-read-token-<env>`, `tenant-school-read-token-<env>`), and the deployed gateway smoke receives the GitHub environment's `DB_HOST`, `DB_NAME`, and `db-password-<env>` secret.

Impact:

- The smoke gates are part of the next main-line release path.
- Evidence from the next dev/prod deployment run must be captured to confirm both smoke gates pass in live CI/CD.

Required follow-up:

- Confirm the next release run shows successful direct-service and gateway smoke steps for dev and prod.
- If a smoke step fails, fix the smoke setup rather than disabling the gate.

### Terraform CLI Not Found on Current PATH

`Get-Command terraform` returned no command in the current PowerShell environment.

Impact:

- Terraform source is present, and live observability resources exist, but this shell cannot currently run `terraform plan/apply`.

Required follow-up:

- Add Terraform to PATH or document the installed location.
- Re-run `terraform -chdir=deploy/gcp/observability plan` for dev and prod.

## Documentation Drift

### README and Some Architecture Docs Still Mention Twelve Services

Older docs list separate services such as:

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

Impact:

- New operators may follow stale service names or outdated deployment assumptions.

Required follow-up:

- Update `README.md`, `docs/ARCHITECTURE-HLD.md`, and `docs/ARCHITECTURE-LLD.md` to point to the current-state docs or revise their service tables.

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
