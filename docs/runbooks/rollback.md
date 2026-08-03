# Rollback Runbook

Rollback must be quicker than debugging.

## When To Roll Back

Rollback immediately when:

- Prod canary smoke fails.
- Gateway or domain service 5xx rate rises after a release.
- Student/school login flow stops working.
- Student photo import or list rendering breaks production workflows.
- A migration causes unexpected write/read failures.
- The release operator cannot prove the issue is unrelated to the new revision.

## GitHub Rollback

Use:

```text
Actions -> CD / Rollback target
service: all or one service
environment: dev | prod
release_id: optional
reason: required incident reason
```

If `release_id` is empty, Cloud Deploy rolls back to the last successful release for the target.

The workflow calls:

```text
gcloud deploy targets rollback <service>-<env>
```

## Rollback Order

For `service=all`, the workflow rolls back public edges first:

```text
frontend
api-gateway
platform-service
billing-service
operations-service
identity-service
school-core-service
```

This prioritizes restoring user traffic quickly.

## After Rollback

1. Confirm Cloud Deploy created rollback rollouts.
2. Confirm the gateway health endpoint is healthy.
3. Confirm the failed user workflow.
4. Attach `rollback-evidence` to the incident.
5. Do not redeploy the bad release until it has a documented root cause or a compensating fix.

## Database Rule

Application rollback does not automatically undo database changes.

If a release included destructive or data-rewriting migrations, use the migration rollback note from that release evidence before running application rollback.
