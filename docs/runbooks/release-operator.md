# Release Operator Runbook

Status: CI/CD v2 implementation runbook.

## Release Board

```text
PR -> CI / PR
dev  -> CD / Deploy branch environment -> dev Cloud Deploy releases
main -> CD / Deploy branch environment -> prod Cloud Deploy releases
     -> prod canary 5, 25, 100
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
6. The workflow creates one Cloud Deploy release per service in `custoking-<service>-dev`.
7. Cloud Deploy deploys each release to `dev`.
8. The workflow uploads `release-evidence`.

## Prod Release

1. Merge or push to `main`.
2. GitHub Actions runs `CD / Deploy branch environment`.
3. The workflow requires the GitHub `prod` Environment approval gate when configured.
4. The workflow builds and pushes the `main` commit images using immutable `sha-<commit>` tags.
5. The workflow renders the prod Cloud Deploy targets from GitHub variables, then applies target/pipeline YAML.
6. The workflow creates one Cloud Deploy release per service in `custoking-<service>-prod`.
7. Cloud Deploy deploys each release to `prod`.

Cloud Deploy then uses the prod target canary:

```text
5 percent -> 25 percent -> 100 percent
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
