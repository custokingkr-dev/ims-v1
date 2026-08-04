# Deployment Evidence Runbook

Every release must answer:

```text
What source changed?
What immutable image digest shipped?
Was that digest tested on dev before prod?
Which revision or rollout received it?
What checks passed?
How is it rolled back?
```

## Release Artifact

`CD / Deploy branch environment` uploads:

```text
release-evidence/
  images.json
  deployment.json
  services-smoke.json
  gateway-smoke.json
  dev-approvals.json
  summary.md
```

`dev-approvals.json` exists only for dev. Its tags are created after all automatic dev checks pass.

## Required Review

For each service in `images.json`, match:

```text
sourceId -> resolvedTag -> digest -> immutableRef -> runtimeDigest -> runtimeRef
```

For normal dev releases, `deployment.json` records the asynchronous Cloud Run submissions and `services-smoke.json` records the ready revisions. A dev configuration release and every prod release record the Cloud Deploy pipeline, release, target, and rollout.

BuildKit attestations make `immutableRef` an OCI index. `services-smoke.json` must show the matching `runtimeRef` child manifest from `images.json`, a ready revision, and 100 percent traffic. `gateway-smoke.json` must show `UP`.

## Production Proof

The production resolver accepts only `dev-approved-src-<source-id>`. This is the machine-enforced proof that production is consuming a digest that completed dev deployment checks.

Do not manually move a `dev-approved-*` tag. A manual tag would bypass the evidence chain. The workflow treats an existing tag at the same digest as an idempotent success and rejects a tag that points anywhere else.

## Rollback Evidence

`CD / Rollback target` uploads:

```text
rollback-evidence/
  rollback.json
  deployment.json       prod
  gateway-smoke.json
```

Dev evidence records traffic movement between revisions. Prod evidence records Cloud Deploy rollback rollouts.

## Retention

Keep release and rollback evidence for at least 90 days, and longer when it supports school onboarding, billing, photo import, a database migration, or an incident.
