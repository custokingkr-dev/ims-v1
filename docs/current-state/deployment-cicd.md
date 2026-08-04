# CI/CD Current State

Last source audit: 2026-08-04.

## Status

The active implementation is GitHub Actions, Artifact Registry, Cloud Run, and Google Cloud Deploy.

Branch ownership is fixed:

```text
dev  -> dev
main -> prod
```

There is no automatic stage deployment. Stage templates remain source-only.

The retired `cloudbuild.yaml` path must not be restored as a second deployment system.

## Active Files

```text
.github/workflows/ci-pr.yml                 pull request tests and image scans
.github/workflows/build-release.yml         dev and prod releases
.github/workflows/rollback.yml              dev and prod rollback
.github/workflows/security-scan.yml         scheduled container scan
.github/workflows/_detect-changes.yml       affected-service matrix
scripts/resolve-affected-ci-targets.ps1     change-to-service mapping
scripts/resolve-image-source-id.ps1         content-addressed source identity
scripts/invoke-direct-cloudrun-release.ps1  fast dev deployment
scripts/invoke-clouddeploy-release.ps1      Cloud Deploy release creation
scripts/wait-clouddeploy-rollouts.ps1       coordinated rollout monitoring
scripts/verify-cloudrun-release.ps1         revision, digest, traffic, and frontend checks
scripts/smoke-gateway-health.ps1             public gateway health check
```

## End-to-End Flow

```text
Pull request
  -> detect affected services
  -> test affected services
  -> build affected containers with cache
  -> Trivy and Gitleaks gates

dev push
  -> cancel older dev deployment
  -> detect affected services
  -> reuse or build content-addressed images
  -> deploy affected services to Cloud Run
  -> verify ready revision, exact digest, and 100% traffic
  -> check frontend when changed
  -> check gateway health
  -> attach dev-approved promotion tags

main push
  -> detect affected services
  -> require the GitHub prod Environment
  -> resolve dev-approved immutable digests
  -> create affected Cloud Deploy releases
  -> advance affected canaries
  -> verify revisions, digests, traffic, frontend, and gateway
```

## Change Detection

The release workflow consumes the same shared build catalog used by PR CI.

Normal service changes affect only their owning deployment unit:

```text
services/identity-service/**      -> identity-service
services/school-core-service/**   -> school-core-service
services/operations-service/**    -> operations-service
services/platform-service/**      -> platform-service
services/billing-service/**       -> billing-service
services/api-gateway/**           -> api-gateway
frontend/**                       -> frontend
```

A service-specific `deploy/cloudrun/<service>.yaml` change affects that service. Global workflow, catalog, Skaffold, relevant environment target, or delivery-pipeline changes affect the full fleet. A `targets-dev.yaml` change does not redeploy prod, and a `targets-prod.yaml` change does not redeploy dev.

Documentation-only commits complete as a no-op and do not build or deploy containers.

Manual dispatch supports:

- `force_full_deploy`: include every service.
- `apply_deployment_config`: reapply targets and pipelines and use Cloud Deploy for dev.
- `commit_sha`: deploy an ancestor of the environment branch. Selecting an explicit commit automatically reconciles all services so the environment cannot become a partial version of that commit.

## Build and Cache Model

Dev is the artifact build environment. Production does not rebuild application images.

For each affected service, `resolve-image-source-id.ps1` hashes:

- the service build-context Git tree;
- normalized Docker build arguments;
- the source identity scheme version.

Docker base images are pinned by digest. Dependabot checks the Docker contexts weekly, so a base-image refresh changes reviewed source and receives a new source ID rather than silently changing an existing image identity.

BuildKit provenance and SBOM attestations produce an OCI index. Release evidence records both the approved index digest and its runnable `linux/amd64` manifest digest. Cloud Run is deployed from the immutable index, while post-deployment verification compares the runtime manifest digest Cloud Run reports.

The resulting Artifact Registry tags are:

```text
src-<source-id>
sha-<git-commit>
dev-approved-src-<source-id>
```

`src-*` is created only when absent. Repeated releases reuse its digest rather than overwriting it. `sha-*` is a traceability alias. `dev-approved-*` is added only after the dev deployment and all automatic checks succeed. Tag creation is idempotent only when the existing digest matches; any attempted tag move fails as a conflict.

Builds use four matrix workers and a separate GitHub Actions BuildKit cache scope per image. Provenance and SBOM attestations are generated when an image is first built. PR and scheduled security builds use the same cache scopes but do not publish release images.

The frontend Dockerfile installs dependencies before copying source files, so source-only edits can reuse the `npm ci` layer.

## Dev Deployment

Normal dev changes use:

```text
gcloud run deploy <affected-service> --image=<immutable-digest>
```

Only the image is updated, so existing Cloud Run environment variables, secret references, service accounts, networking, scaling, and probes remain intact. A service already serving the exact runnable digest in `LATEST` mode is recorded as `already-current` without creating a redundant revision. Other updates are submitted with `--async`, allowing independent services to create revisions concurrently. The verification loop then waits up to 15 minutes for every affected service to expose the expected ready digest at 100 percent traffic. If a previous Cloud Deploy rollout pinned dev traffic to a named revision, verification restores `LATEST` traffic mode after the new revision is ready; this also makes the next direct deployment route normally.

If deployment configuration changed, dev automatically uses the Cloud Deploy path instead. This renders and applies the target configuration and deploys the affected manifest(s), preventing configuration changes from being skipped by the fast image-only path.

Push and manual dev runs share one concurrency group. A newer dev run cancels an older run, so two trigger types cannot deploy the same environment concurrently.

## Production Promotion

Production resolves `dev-approved-src-<source-id>` for every affected service. A missing approval tag fails before release creation with an instruction to deploy that source to dev.

Cloud Deploy remains the production controller. Each affected service gets its own release because a Cloud Deploy Cloud Run target supports one Cloud Run service.

Production strategy:

```text
5% -> 25% -> 50% -> stable
```

The rollout monitor polls all affected releases in one loop and advances ready canary phases without serially waiting for an entire service pipeline before observing the next one.

Production runs are never automatically cancelled. Deploy and rollback share the same environment concurrency group, so those mutations cannot overlap.

## Deployment Configuration

Targets and delivery pipelines are applied only when their source changed or when `apply_deployment_config` is selected. This removes an unconditional control-plane update from normal releases.

Sources:

```text
deploy/clouddeploy/targets-dev.yaml
deploy/clouddeploy/targets-prod.yaml
deploy/clouddeploy/delivery-pipelines.yaml
deploy/cloudrun/*.yaml
deploy/skaffold.yaml
```

Target rendering still requires the environment-specific database values and Google Drive root folder ID. GitHub Environment variables are read by the `release` job because that job owns the selected environment.

## Automatic Verification

Every changed service is checked after deployment:

- Cloud Run service `Ready=True`;
- latest created revision equals latest ready revision;
- revision image digest equals the resolved release digest;
- the latest revision receives 100 percent traffic;
- changed frontend returns an HTTP success response;
- gateway `/gateway-health` returns `UP` for the final environment state.

These checks prove deployment readiness and artifact identity. Authenticated business-flow smoke tests remain operator-run because the workflow does not hold production user credentials.

## Evidence

Each release uploads:

```text
release-evidence/
  images.json
  deployment.json
  services-smoke.json
  gateway-smoke.json
  dev-approvals.json      dev only
  summary.md
```

`images.json` is the canonical artifact mapping from commit, service, source ID, promotion tag, immutable OCI index digest, and runnable manifest digest. `deployment.json` records direct Cloud Run submissions or Cloud Deploy pipelines, releases, targets, and rollouts. `services-smoke.json` records the verified Cloud Run revisions.

## Rollback

Dev rollback uses Cloud Run traffic because normal dev releases are direct Cloud Run deployments. With no explicit revision, the workflow selects the ready revision immediately older than the revision currently receiving 100 percent traffic.

Prod rollback remains Cloud Deploy target rollback and waits for the resulting rollouts. Both paths run gateway health afterward and upload `rollback-evidence`.

## Remaining Hardening

- Add the `dev` Environment branch restriction in GitHub settings when repository-admin access is available.
- Pin third-party actions by commit SHA.
- Replace broad legacy deploy-account roles with the intended least-privilege role.
- Add Cloud Monitoring SLO verification to production canary phases.
- Add authenticated business-flow smoke through short-lived test identities rather than stored user tokens.
- Activate stage only after a real stage database, secrets, and GitHub Environment exist.
