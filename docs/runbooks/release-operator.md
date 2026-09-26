# Release Operator Runbook

## Release Board

```text
PR   -> CI / PR
dev  -> affected images -> fast Cloud Run dev release -> dev approval tags
dev -> main PR -> approved signed digests -> prod Environment review -> Cloud Deploy prod canary
targets/pipelines/renderers -> configuration-only reconciliation -> separate service release
```

The source of truth is [build-release.yml](../../.github/workflows/build-release.yml), [reconcile-deployment-config.yml](../../.github/workflows/reconcile-deployment-config.yml), and [the change classifier](../../scripts/resolve-affected-ci-targets.ps1). A successful configuration-only or no-op workflow is not evidence that an application was deployed.

## Normal Dev Release

1. Merge the reviewed change to `dev` after its required PR checks pass.
2. Do not also click manual dispatch for the same commit. The push already starts deployment; a newer dev run can cancel the in-progress dev run.
3. Open `CD / Deploy branch environment` in GitHub Actions.
4. Confirm the affected-service list matches the changed paths.
5. Confirm each image was either built once or reused by source ID.
6. Confirm `Verify changed Cloud Run services` and `Gateway health smoke after rollout` passed.
7. Confirm digest signing and dev approval tagging completed after the smoke checks. Retain the `release-evidence-dev-<sha>` artifact.

Normal application changes use direct Cloud Run deployment by immutable digest. A service manifest under `deploy/cloudrun/`, or `deploy/skaffold.yaml`, selects Cloud Deploy for the affected dev release, provided no reconciliation trigger is present. The verifier restores dev's `LATEST` traffic mode when a prior Cloud Deploy rollout left traffic pinned to a named revision.

## Configuration Changes: Reconcile, Then Release

| Changed source | Automatic result |
| --- | --- |
| Selected environment's `deploy/clouddeploy/targets-<environment>.yaml` | `deployment_reconciliation_required=true`; image builds and release are skipped |
| `deploy/clouddeploy/delivery-pipelines.yaml`, `scripts/render-clouddeploy-targets.ps1`, or `scripts/render-clouddeploy-pipelines.ps1` | Same reconciliation block |
| `deploy/cloudrun/<service>.yaml` without those triggers | Affected service release uses Cloud Deploy; targets/pipelines are not reapplied |
| `deploy/skaffold.yaml` without those triggers | All services are affected and dev uses Cloud Deploy |

Target-file classification is environment-specific: a prod target edit alone does not require dev target reconciliation. PR detection without an environment checks all target files.

1. Keep target/pipeline/renderer configuration in a separately reviewable commit. Merge it to `dev`; expect `configuration-reconciliation-required` if the dev classifier sees a relevant trigger. Combining application changes with this commit does not deploy those changes.
2. Wait for any active deployment in the same environment to finish. Dispatch **Ops / Reconcile deployment configuration** from `dev`, with its sole input `environment=dev`. Review the selected project, environment variables and `DEPLOYMENT_CONFIG_SERVICE_ACCOUNT` before it runs.
3. Confirm both rendered targets and pipelines applied successfully. This workflow creates **zero releases and zero rollouts** and does not update running application revisions. Retain its run URL, commit, environment and successful application evidence.
4. After reconciliation, merge a separate intended service or service-manifest commit to `dev`. Its deployment change range must exclude the control-plane files above. The resulting application release must finish its tests, scans, rollout, smoke, signing and approval tagging before promotion.
5. For production, promote configuration through a **`dev` to `main` PR**, then dispatch reconciliation from `main` with `environment=prod`, using the `prod` Environment review. After that succeeds, promote the separately dev-tested service/manifest change through another `dev` to `main` PR. If a prior promotion already included both configuration and application changes, its blocked release is still not a deployment: use a subsequent qualifying service/manifest commit or a manual release of an eligible post-configuration SHA.

Re-running the same configuration commit after reconciliation still hits the classifier block. `force_full_deploy` does not override it. A manual release also compares the selected SHA with its first parent, so select a post-configuration SHA whose comparison has no reconciliation trigger. Do not bypass the block with a direct operator deployment.

## Manual Dev Release

Run **CD / Deploy branch environment** from the `dev` branch and select `target_environment=dev`. The release SHA must be reachable from `origin/dev`.

Use `force_full_deploy` only when all seven services are intended. Supplying an explicit `commit_sha` also forces all services into the affected matrix, even if `force_full_deploy=false`. Neither option reapplies targets/pipelines or bypasses configuration reconciliation. There is no `apply_deployment_config` input; use the separate reconciliation workflow.

## Production Release

1. Complete dev acceptance for the intended service sources. Open a PR **from `dev` to `main`**; `promotion-source-policy` rejects PRs to `main` from other branches. Do not substitute a feature-branch PR or direct push for the promotion path.
2. Complete any configuration reconciliation sequence above. A `main` push starts the production workflow, but a configuration trigger suppresses its release.
3. Review the exact main SHA, changed-service matrix, dev evidence, migrations, required secrets, rollback plan and production project at the `prod` Environment gate. Confirm the repository's required-reviewer protection is actually configured; the YAML `environment: prod` alone does not create reviewers. Obtain the production approval before the release job proceeds.
4. The release resolves `dev-approved-src-*` tags for each service source ID and verifies a Sigstore signature issued to this workflow on `refs/heads/dev`. It does not rebuild images. It copies the approved digest to the production registry when needed and checks the copied digest is identical.
5. Cloud Deploy advances each affected service through `5`, `25`, `50`, and stable before creating the next service release. Inspect the rollout result; do not treat creation of a release as completion.
6. Confirm service digest/revision/traffic checks, frontend check when applicable, and gateway health. Retain `release-evidence-prod-<sha>`, configuration run evidence when applicable, and the GitHub Environment approval record.

If production reports a missing dev-approved image, deploy that exact source on `dev`; do not create or move an approval tag manually.

## Manual Inputs

| Input | Use |
| --- | --- |
| `target_environment` | Release workflow only: `dev` requires branch `dev`; `prod` requires branch `main` |
| `commit_sha` | Release workflow only: optional reachable commit from the owning branch; an explicit value forces all seven services |
| `force_full_deploy` | Release workflow only: includes all seven services; does not override reconciliation |
| `environment` | Reconciliation workflow only: `dev` from `dev`, or `prod` from `main`; applies targets/pipelines only |

Manual production dispatch uses `main` and `target_environment=prod`, observes the same source/signature and Environment gates, and never builds an unapproved source.

## Evidence Review

Check:

- `images.json`: source ID, immutable OCI index, and runnable manifest digest for each affected service.
- `deployment.json`: deployment mode and Cloud Run submissions or Cloud Deploy rollouts.
- `services-smoke.json`: exact runtime digest, ready revision, and traffic validation.
- `gateway-smoke.json`: final public health result.
- `dev-approvals.json`: digests eligible for production.

These files belong to an application release. A reconciliation run records its configuration-only result in the job summary instead; do not expect dev approval tags or application smoke evidence from that run. Do not call a dev release complete if smoke succeeded but digest signing or approval tagging failed.

## Timing Expectations

Timing depends on runner queues, affected tests/browser checks, cache state, vulnerability scans, Cloud Run startup and canary progression. Use the run's job timestamps to separate these phases. A green no-op/configuration summary may finish quickly because it created no application release; elapsed time is not deployment evidence. No duration here is an SLA.

## Failure Handling

- Build failure: fix the service; no approval tag is created.
- Dev deployment or smoke failure: roll back dev and fix forward.
- Missing prod approval tag: deploy the source to dev successfully.
- Signing/verification or digest conflict: retain the evidence, fix the approval chain and rerun through the normal workflow; do not move tags to make it pass.
- Canary failure: stop advancing and use `CD / Rollback target`.
- Deployment configuration failure: inspect the rendered targets/pipelines, correct and reconcile them, then release a qualifying separate commit. Do not use direct deployment to bypass it.

See [rollback.md](rollback.md) and [deployment-evidence.md](deployment-evidence.md).
