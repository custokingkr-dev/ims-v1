# Release Operator Runbook

Status: CI/CD v2 implementation runbook.

## Release Board

```text
PR -> CI / PR
dev  -> CD / Deploy branch environment -> dev Cloud Deploy releases + rollouts
main -> CD / Deploy branch environment -> prod Cloud Deploy releases + rollouts
     -> prod canary 5, 25, 50, then stable
```

## Important Implementation Decision

Cloud Deploy Cloud Run targets support one Cloud Run service per target. Custoking therefore uses one delivery pipeline per service:

```text
custoking-school-core-service
custoking-identity-service
custoking-operations-service
custoking-billing-service
custoking-platform-service
custoking-api-gateway
custoking-frontend
```

Each service has environment-specific active pipelines:

```text
custoking-<service>-dev   -> <service>-dev
custoking-<service>-prod  -> <service>-prod
```

GitHub Actions is responsible for branch-to-environment ownership and coordinated fleet order. Cloud Deploy is responsible for each service's environment rollout, prod canary traffic, and rollback history.

The stage target templates exist in source, but stage is not active until a real `stage` GitHub Environment, stage database, and `-stage` GCP secrets exist.

## Dev Release

1. Merge or push to `dev`.
2. GitHub Actions runs `CD / Deploy branch environment`.
3. The workflow builds and pushes all service images to Artifact Registry using `sha-<commit>` tags.
4. The workflow resolves each image digest.
5. The workflow renders the dev Cloud Deploy targets from GitHub variables, then applies target/pipeline YAML.
6. The workflow creates one Cloud Deploy release per service in `custoking-<service>-dev` and starts the initial rollout to `<service>-dev`.
7. The workflow waits for every Cloud Deploy rollout to reach `SUCCEEDED`.
8. The workflow resolves `custoking-api-gateway-dev` from Cloud Run and calls `/gateway-health`.
9. The workflow uploads `release-evidence`.

## Prod Release

1. Merge or push to `main`.
2. GitHub Actions runs `CD / Deploy branch environment`.
3. The workflow requires the GitHub `prod` Environment approval gate when configured.
4. The workflow builds and pushes the `main` commit images using immutable `sha-<commit>` tags.
5. The workflow renders the prod Cloud Deploy targets from GitHub variables, then applies target/pipeline YAML.
6. The workflow creates one Cloud Deploy release per service in `custoking-<service>-prod` and starts the initial rollout to `<service>-prod`.
7. The workflow waits for every Cloud Deploy rollout to reach `SUCCEEDED`.
8. The workflow auto-advances Cloud Deploy canary phases when no deploy phase is still running. Any failed/canceled/halted rollout fails the workflow.
9. The workflow resolves `custoking-api-gateway-prod` from Cloud Run and calls `/gateway-health`.

Cloud Deploy then uses the prod target canary:

```text
5 percent -> 25 percent -> 50 percent -> stable
```

## Service Order

The release workflow builds and creates service releases in this order:

```text
school-core-service
identity-service
operations-service
billing-service
platform-service
api-gateway
frontend
```

Use the same order for manual coordinated work unless there is a narrower service-only change.

## Evidence

For every release, keep:

- GitHub workflow run URL.
- `release-evidence` artifact.
- Cloud Deploy release IDs.
- Image digests.
- Production approval record.
- Rollback target.
- `release-evidence/smoke.json`.

## Cleanup Stale Releases

Cloud Deploy releases cannot be deleted. Stale failed or canceled releases can be abandoned so no new rollouts can be created from them:

```powershell
./scripts/abandon-stale-clouddeploy-releases.ps1 -Environment dev,prod
./scripts/abandon-stale-clouddeploy-releases.ps1 -Environment dev,prod -Execute
```

The first command is a dry run. The second command abandons only failed/canceled releases by default. Do not use `-PruneSucceeded` unless you intentionally want to abandon old successful rollback targets after keeping the latest successful releases.

## Dev GitHub Environment Branch Restriction

The workflow already enforces `dev` branch -> `dev` environment and `main` branch -> `prod` environment. To add the matching GitHub UI protection later, use a repository admin account:

1. Open GitHub repository settings.
2. Go to `Environments`.
3. Open `dev`.
4. Set deployment branches to `Selected branches`.
5. Add only `dev`.
6. Save the rule.

The previous API attempt failed with `403` because the active account did not have repository admin rights.

## Fast Health Check

After dev/prod deployment, check:

```powershell
gcloud run services describe custoking-api-gateway-<env> `
  --project=custoking `
  --region=asia-south2 `
  --format="value(status.url)"
```

Then call:

```text
<gateway-url>/gateway-health
```
