# Release Operator Runbook

## Release Board

```text
PR   -> CI / PR
dev  -> affected images -> fast Cloud Run dev release -> dev approval tags
main -> approved digests -> Cloud Deploy prod canary
```

## Normal Dev Release

1. Merge or push to `dev`.
2. Do not also click manual dispatch for the same commit. The concurrency guard prevents overlap, but the push is already sufficient.
3. Open `CD / Deploy branch environment` in GitHub Actions.
4. Confirm the affected-service list matches the changed paths.
5. Confirm each image was either built once or reused by source ID.
6. Confirm `Verify changed Cloud Run services` and `Gateway health smoke after rollout` passed.
7. Download `release-evidence` when the release supports a school onboarding, migration, billing change, or incident fix.

Normal code changes use direct Cloud Run deployment by immutable digest. A deployment-manifest, target, pipeline, or Skaffold change automatically uses Cloud Deploy instead. The verifier restores dev's `LATEST` traffic mode when a prior Cloud Deploy rollout left traffic pinned to a named revision.

## Manual Dev Release

Run the workflow from the `dev` branch and select `dev`.

Use `force_full_deploy` only when every service must be reconciled. Use `apply_deployment_config` when infrastructure source must be reapplied even though Git did not detect a deployment-file change.

## Production Release

1. Merge the dev-tested change to `main`.
2. The `main` push starts the production workflow.
3. Review the affected services at the protected `prod` Environment gate.
4. Approve only when each affected source was successfully deployed to dev.
5. The workflow resolves `dev-approved-src-*` tags. It does not rebuild.
6. Cloud Deploy advances each affected service through `5`, `25`, `50`, and stable before creating the next service release.
7. Confirm service digest checks, frontend check when applicable, and gateway health.
8. Retain `release-evidence` and the GitHub approval record.

If production reports a missing dev-approved image, deploy that exact source on `dev`; do not create or move an approval tag manually.

## Manual Inputs

| Input | Use |
| --- | --- |
| `target_environment` | Must match the branch: `dev` or `prod` |
| `commit_sha` | Optional ancestor commit from the owning branch |
| `force_full_deploy` | Includes all seven services |
| `apply_deployment_config` | Reapplies targets and pipelines; dev uses Cloud Deploy |

## Evidence Review

Check:

- `images.json`: source ID, immutable OCI index, and runnable manifest digest for each affected service.
- `deployment.json`: deployment mode and Cloud Run submissions or Cloud Deploy rollouts.
- `services-smoke.json`: exact runtime digest, ready revision, and traffic validation.
- `gateway-smoke.json`: final public health result.
- `dev-approvals.json`: digests eligible for production.

## Timing Expectations

Timing depends on cache state and Cloud Run startup:

- no-op documentation change: under one minute in normal runner conditions;
- cached frontend-only dev release: usually several minutes;
- cached single Java service: usually under ten minutes;
- first full-fleet cache warm-up: materially longer.

Treat these as operating expectations, not an SLA. Use job timestamps to distinguish runner queue time, image build time, and deployment time.

## Failure Handling

- Build failure: fix the service; no approval tag is created.
- Dev deployment or smoke failure: roll back dev and fix forward.
- Missing prod approval tag: deploy the source to dev successfully.
- Canary failure: stop advancing and use `CD / Rollback target`.
- Deployment configuration failure: inspect rendered targets and do not use direct deployment to bypass it.

See [rollback.md](rollback.md) and [deployment-evidence.md](deployment-evidence.md).
