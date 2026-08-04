# CI/CD Current State

Last verified: 2026-08-04 from repository files, GitHub Actions runs, and live GCP project `custoking`.

## Status

CI/CD v2 is implemented and active.

Verified deployment commit:

```text
83abe626f32d64e4701d6f3c838008bfaabfa3b4
```

Verified GitHub Actions runs:

| Environment | Branch | Run | Result |
| --- | --- | --- | --- |
| dev | `dev` | `30898224156` | success |
| prod | `main` | `30898227674` | success |

Verified production smoke:

```text
https://custoking-api-gateway-prod-l7mhms5c2a-em.a.run.app/gateway-health -> UP
```

The old `cloudbuild.yaml` deployment path is retired. The repository no longer contains `cloudbuild.yaml`, `.github/workflows/deploy.yml`, `.github/workflows/release.yml`, or `.github/workflows/promote.yml`.

## Active Workflow Files

```text
.github/workflows/
  ci-pr.yml
  build-release.yml
  rollback.yml
  security-scan.yml
  _detect-changes.yml
  _test-java-service.yml
  _test-node-service.yml
  _build-image.yml
  _smoke-environment.yml
```

## Branch Ownership

The deployment branch owns the environment:

| Branch | Environment | Trigger |
| --- | --- | --- |
| `dev` | dev | push or matching manual dispatch |
| `main` | prod | push or matching manual dispatch |

`build-release.yml` enforces this in code:

- `dev` can deploy only `dev`.
- `main` can deploy only `prod`.
- manual dispatch must run from the matching branch.
- manual `commit_sha` must be reachable from the matching remote branch.

The GitHub `prod` Environment is configured as the production approval gate. The workflow also uses the `dev` Environment, but the GitHub UI branch restriction for `dev` still needs a repository admin to add it. The workflow-level guard is already active.

## Release Workflow

Workflow:

```text
CD / Deploy branch environment
```

Source file:

```text
.github/workflows/build-release.yml
```

High-level flow:

```text
resolve target
-> GitHub Environment gate
-> build and push all seven images
-> apply Cloud Deploy targets and delivery pipelines
-> create one Cloud Deploy release per service
-> wait for every rollout to succeed
-> run gateway /gateway-health smoke
-> upload release-evidence
```

Operator view:

```text
Pull request
  |
  v
CI / PR
  - detect changed services
  - test changed Java, Node, or frontend units
  - build changed Docker images locally
  - run Trivy and Gitleaks gates
  |
  v
Merge to dev
  |
  v
CD / Deploy branch environment
  - deploys dev only
  - builds all seven deployable images
  - creates Cloud Deploy releases in asia-south2
  - waits for every rollout
  - smokes dev gateway health
  |
  v
Merge to main
  |
  v
GitHub prod Environment approval
  |
  v
CD / Deploy branch environment
  - deploys prod only
  - builds all seven deployable images
  - creates Cloud Deploy releases in asia-south2
  - auto-advances prod canary phases
  - smokes prod gateway health
```

Deployable units:

```text
school-core-service
identity-service
operations-service
billing-service
platform-service
api-gateway
frontend
```

Build behavior:

- all seven deployable units are built for each branch deployment.
- build matrix `max-parallel` is `2`.
- images are pushed to Artifact Registry with immutable `sha-<commit>` tags.
- image digests are resolved before Cloud Deploy release creation.
- Docker BuildKit provenance and SBOM output are enabled.

Current release workflow does not build only changed services. Path-aware changed-service selection exists for PR CI.

## Cloud Deploy Model

Cloud Deploy is active in `asia-south2`.

Cloud Deploy Cloud Run targets support one Cloud Run service, job, or worker pool per target, so this repo uses one delivery pipeline per service per environment:

```text
custoking-<service>-dev  -> <service>-dev
custoking-<service>-prod -> <service>-prod
```

Active service/environment pipelines:

```text
custoking-school-core-service-dev
custoking-school-core-service-prod
custoking-identity-service-dev
custoking-identity-service-prod
custoking-operations-service-dev
custoking-operations-service-prod
custoking-billing-service-dev
custoking-billing-service-prod
custoking-platform-service-dev
custoking-platform-service-prod
custoking-api-gateway-dev
custoking-api-gateway-prod
custoking-frontend-dev
custoking-frontend-prod
```

Source files:

```text
deploy/clouddeploy/delivery-pipelines.yaml
deploy/clouddeploy/targets-dev.yaml
deploy/clouddeploy/targets-prod.yaml
deploy/skaffold.yaml
deploy/cloudrun/*.yaml
```

`targets-stage.yaml` exists as a source template, but stage is not an active deployment environment. There is no branch-owned stage workflow and no verified stage database, GitHub Environment, or stage secrets.

## Rollout Strategies

Dev pipelines use a standard strategy.

Prod pipelines use Cloud Deploy canary:

```text
5 percent
25 percent
50 percent
stable
```

The release workflow waits for each rollout with:

```text
scripts/wait-clouddeploy-rollout.ps1
```

For prod, the workflow auto-advances the next canary phase when no deployment phase is still running. A rollout in a terminal bad state fails the workflow.

The latest verified deployment runs for `83abe626` completed every service rollout and gateway smoke:

| Environment | Release | Rollout | State |
| --- | --- | --- | --- |
| dev | GitHub run `30898224156` | all seven service rollouts | `SUCCEEDED` |
| prod | GitHub run `30898227674` | all seven service rollouts | `SUCCEEDED` |

Manual gateway health checks after those runs returned:

```text
dev  -> 200 {"status":"UP","service":"custoking-api-gateway"}
prod -> 200 {"status":"UP","service":"custoking-api-gateway"}
```

## Release Evidence

The release workflow uploads:

```text
release-evidence/
  release.json
  smoke.json
  summary.md
```

`release.json` records:

- commit SHA
- Artifact Registry root
- image tag
- image digest reference
- Cloud Deploy pipeline
- Cloud Deploy release id
- first target
- initial rollout id

`smoke.json` records:

- environment
- gateway Cloud Run service
- gateway URL
- `/gateway-health` endpoint
- health status
- timestamp

The only automatic post-rollout smoke currently implemented in `build-release.yml` is gateway `/gateway-health`. Direct service smoke jobs and deeper business-flow smokes exist as scripts/templates elsewhere in the repo, but they are not currently run by this release workflow.

## PR CI

Workflow:

```text
CI / PR
```

Source file:

```text
.github/workflows/ci-pr.yml
```

Verified behavior from source:

- detects changed services through `_detect-changes.yml`.
- runs Java tests for Maven services.
- runs Node gateway tests.
- runs frontend audit/test/build for frontend changes.
- builds changed Docker images locally.
- runs Trivy critical gate on built images.
- runs Gitleaks.

PR CI does not deploy.

## Scheduled Security Scan

Workflow:

```text
Security / Container scan
```

Source file:

```text
.github/workflows/security-scan.yml
```

Verified behavior from source:

- weekly scheduled run plus manual dispatch.
- builds each deployable image locally.
- scans HIGH and CRITICAL findings with Trivy.
- uploads SARIF through `github/codeql-action/upload-sarif@v4`.

## Rollback

Workflow:

```text
CD / Rollback target
```

Source file:

```text
.github/workflows/rollback.yml
```

Inputs:

```text
service: all or one deployable unit
environment: dev or prod
release_id: optional
reason: required
```

The workflow calls Cloud Deploy target rollback for the selected service(s) and uploads:

```text
rollback-evidence/rollback.json
```

For `service=all`, rollback order is:

```text
frontend
api-gateway
platform-service
billing-service
operations-service
identity-service
school-core-service
```

## GCP Authentication

GitHub Actions authenticates to GCP through Workload Identity Federation.

Verified live deploy service account:

```text
github-actions-sa@custoking.iam.gserviceaccount.com
```

Verified project-level roles on 2026-08-04:

```text
roles/artifactregistry.writer
roles/cloudbuild.builds.editor
roles/clouddeploy.admin
roles/iam.serviceAccountUser
roles/logging.viewer
roles/run.developer
roles/secretmanager.viewer
roles/serviceusage.serviceUsageConsumer
roles/storage.admin
```

The Cloud Build role remains present even though `cloudbuild.yaml` deployment is retired. That is IAM drift/broadness, not an active deployment path.

## Action Runtime Status

The workflows have been updated to current Node 24-compatible major versions where available:

```text
actions/checkout@v7
actions/setup-node@v7
actions/setup-java@v5
actions/upload-artifact@v7
docker/setup-buildx-action@v4
docker/login-action@v4
docker/build-push-action@v7
github/codeql-action/upload-sarif@v4
gitleaks/gitleaks-action@v3
aquasecurity/trivy-action@v0.36.0
```

Google actions are currently:

```text
google-github-actions/auth@v3
google-github-actions/setup-gcloud@v3
```

The production deployment path is not SHA-pinned. Treat SHA pinning as a future hardening task, not an implemented control.

## Stale Release Cleanup

Cloud Deploy releases cannot be deleted. Failed or canceled releases can be abandoned so no new rollouts can be created from them.

Script:

```text
scripts/abandon-stale-clouddeploy-releases.ps1
```

Verified on 2026-08-04:

- five failed dev releases from `rel-dev-8097cb5aadbf-1` were abandoned.
- a final dry run reported no stale failed/canceled releases in dev or prod.

The script does not prune successful releases unless `-PruneSucceeded` is explicitly provided.

## Current Gaps

Known gaps are documented in [gaps-and-drift.md](gaps-and-drift.md). CI/CD-specific gaps:

- GitHub `dev` Environment branch restriction still needs to be added by a repository admin in the GitHub UI.
- Stage remains inactive.
- Release workflow runs only gateway health smoke, not authenticated business smokes.
- Deploy service account still has broad legacy roles.
- Production actions are not pinned by SHA.
- There is no automated Cloud Monitoring SLO gate in the prod canary yet.
