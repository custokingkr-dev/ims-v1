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

This order rolls `api-gateway` back before `identity-service`. For the passkey removal release that matters; see the next section.

## Passkey Removal Release (2026-11-12)

The release that removed passkeys and step-up verification changed `api-gateway`, `identity-service` and `frontend`. Their previous revisions together are the complete passkey stack, and their new revisions together have no step-up at all. A mix of the two blocks sensitive actions with a verification demand that nobody can satisfy:

| Mix | What happens |
| --- | --- |
| Previous `api-gateway`, new `identity-service` | The previous gateway runs its own step-up check against `stepUpExpiresAt` in identity's introspection response. The new identity no longer sends that field, so the gateway answers `403 STEP_UP_REQUIRED` ("Verify your passkey before this action") to every SUPERADMIN change, and to any user's exports, student deletes and changes to payments, billing payments, refunds, invoices, purges, roles, permissions and assignments. The new identity has no `/api/v1/auth/passkeys/**` endpoints, so nobody can verify. |
| New `api-gateway`, previous `identity-service` | The previous identity still checks step-up during introspection: every SUPERADMIN change, any path containing export, permanent-delete or purge, and changes to roles, permissions, payments, refunds and invoices. The new gateway turns its `403 STEP_UP_REQUIRED` into `401 Unauthorized` and refuses `/api/v1/auth/passkeys/**` before authentication. The browser refreshes once, retries, and shows an error. |
| Previous `api-gateway` and `identity-service`, new `frontend` | The previous gateway demands step-up, and the new frontend has no passkey dialog, so the user sees only the error message. |

A normal forward release passes through the second row for a few minutes, between the `api-gateway` and `identity-service` rollouts. That is expected.

1. **`identity-service` fails or stalls after `api-gateway` reached stable.** Nothing rolls back automatically, so the second row lasts until an operator acts. Roll back `api-gateway`. That is safe while `identity-service` and `frontend` still serve their previous revisions, because it restores the complete previous stack. If `identity-service` stopped partway through its canary, roll it back as well, so no traffic reaches its new revision. Rolling back only `identity-service` does not help, because the new gateway stays in front of it. Fixing `identity-service` forward is the alternative.
2. **Once `identity-service` has rolled forward, never roll back `api-gateway` on its own.** That reaches the first row and stays there with no time limit. Roll back `frontend`, `api-gateway` and `identity-service` together, in that order, or fix forward. Dispatch the three single-service rollbacks one after another. The shared environment lock runs them in sequence, but GitHub keeps only one pending run per environment, so wait for each run to start before dispatching the next. A `service=all` rollback also restores all three, but it rolls every other service back too.
3. **`service=all` passes through the first row.** It rolls `api-gateway` back before `identity-service`. In prod the workflow creates every Cloud Deploy rollback rollout before waiting for any of them, so the window lasts until `identity-service`'s rollout completes, normally minutes. In dev each traffic move takes seconds. The order is fixed in `.github/workflows/rollback.yml`.

These rules hold while a one-step rollback of any of the three services would reach a revision from before this release. Once a service has had a later release, its one-step rollback lands on a revision without passkeys; an explicit `release_id` can still reach an older one.

## After Rollback

1. Confirm `gateway-smoke.json` reports `UP`.
2. Re-run the failed user workflow.
3. Attach `rollback-evidence` to the incident.
4. Do not redeploy the bad artifact without a root cause or compensating fix.

Application rollback does not reverse a database migration. Use forward-compatible migrations and a separately reviewed data recovery procedure when data changed.
