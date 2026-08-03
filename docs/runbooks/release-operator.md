# Release Operator Runbook

Status: CI/CD v2 implementation runbook.

## Release Board

```text
PR -> CI / PR -> main -> CD / Build release and deploy dev
   -> Cloud Deploy releases per service
   -> dev
   -> manual promote stage
   -> manual promote prod
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

Each pipeline has three targets:

```text
<service>-dev -> <service>-stage -> <service>-prod
```

GitHub Actions is responsible for coordinated fleet order. Cloud Deploy is responsible for each service's target progression and prod canary rollout.

## Normal Release

1. Merge to `main`.
2. GitHub Actions runs `CD / Build release and deploy dev`.
3. The workflow builds and pushes all service images to Artifact Registry using `sha-<commit>` tags.
4. The workflow resolves each image digest.
5. The workflow applies Cloud Deploy target/pipeline YAML.
6. The workflow creates one Cloud Deploy release per service.
7. Cloud Deploy deploys each release to the first target, `dev`.
8. The workflow uploads `release-evidence`.

## Promote To Stage

Use GitHub Actions:

```text
Actions -> CD / Promote release
service: all
target: stage
release_id: empty
reason: <why this release is ready for stage>
```

Leaving `release_id` empty promotes the latest release for each selected service.

Stage currently has a deliberate placeholder:

```text
STAGE_DB_HOST_REQUIRED:5432
```

Do not promote to stage until a real stage database and `-stage` secrets exist.

## Promote To Prod

Use GitHub Actions:

```text
Actions -> CD / Promote release
service: all
target: prod
release_id: empty
reason: <ticket / approval / release note>
```

Prod promotion must use the GitHub `prod` Environment approval gate.

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
- Promotion reason.
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
