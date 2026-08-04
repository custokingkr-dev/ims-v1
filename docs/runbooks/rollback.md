# Rollback Runbook

Rollback must be quicker than debugging.

## When To Roll Back

Rollback when a new release causes login failure, elevated 5xx responses, broken student/photo workflows, unsafe migration behavior, or an unresolved production canary failure.

## GitHub Workflow

```text
Actions -> CD / Rollback target
service: all or one service
environment: dev | prod
release_id: optional prod release or dev revision
reason: required
```

Deploy and rollback share one environment concurrency lock and cannot mutate the same environment simultaneously.

## Dev Rollback

Dev uses Cloud Run revision traffic because normal dev deployment is direct Cloud Run.

- Empty `release_id`: move 100 percent traffic to the ready revision immediately older than the revision currently serving 100 percent.
- Explicit `release_id`: treat it as a Cloud Run revision name. This is allowed only when one service is selected.

The workflow waits for the traffic command, checks gateway health, and records the old and new revision names.

## Production Rollback

Prod uses:

```text
gcloud deploy targets rollback <service>-prod
```

Empty `release_id` selects the last successful release known by Cloud Deploy. The workflow waits for rollback rollouts, advances phases when required, checks gateway health, and uploads evidence.

## Fleet Order

For `service=all`:

```text
frontend
api-gateway
platform-service
billing-service
operations-service
identity-service
school-core-service
```

## After Rollback

1. Confirm `gateway-smoke.json` reports `UP`.
2. Re-run the failed user workflow.
3. Attach `rollback-evidence` to the incident.
4. Do not redeploy the bad artifact without a root cause or compensating fix.

Application rollback does not reverse a database migration. Use forward-compatible migrations and a separately reviewed data recovery procedure when data changed.
