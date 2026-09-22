# Security Review 2026-09-22: Findings and Remediation

Status: remediation merged via PR #255 (`fix/security-review-2026-09-22`). Two follow-ups remain
open (see the end). This is a dated record; for the live authorization model read
`INTERNAL-SERVICE-AUTHORIZATION.md`.

## Method

Whole-codebase review in six domain passes (identity, gateway and tenant isolation, school-core,
billing/platform/operations, frontend, CI/infrastructure), each candidate then re-verified
independently by reading the code before it was accepted. Nothing was exploited against a live
environment. The auth core, row-level security, frontend and identity-service produced no
findings; the new notebook-order feature was found tighter than the legacy catalog code beside it.

## Root cause behind most findings

The API gateway's diagnostic alias routes (`/reporting-api/v1/`, `/audit-api/v1/`,
`/notification-api/v1/`, ...) rewrote any authenticated user's request onto the upstream's whole
`/api/v1/**` surface, including `/api/v1/internal/**` and `/api/v1/pubsub/**`, while injecting the
per-service token and the gateway's own Cloud Run identity token. Every controller whose guard was
"Cloud Run IAM is the boundary" was therefore reachable by any signed-in user, because the gateway
is itself an invoker of every backend.

## Findings and fixes

| # | Severity | Finding | Fix (commit) |
| --- | --- | --- | --- |
| 1 | Critical | Any user could `POST /reporting-api/v1/pubsub/reporting-events`; prod ran the reporting push with the shared token off; `student.deleted.v1` deleted reporting rows for any school by student id under an RLS bypass and left a permanent tombstone. | Gateway refuses any route whose rewritten upstream path is under `/api/v1/internal` or `/api/v1/pubsub`, before authentication. platform-service push ingest, when the shared token is off, verifies the Pub/Sub OIDC bearer: Google-signed, audience in the service's own URL set, email in the configured push service-account set. (`940850d0`) |
| 2 | Medium | `POST /api/v1/notifications/logs` (canonical route) accepted a body `schoolId` with no tenant check and wrote through an RLS bypass; fee-defaulter and low-attendance views read those rows. No caller existed. | Route, DTO and repository removed; a gateway test fails if the route inventory regains it. (`e625e180`) |
| 3 | Medium | `PATCH /supply/orders/{id}/status` wrote the client's status onto legacy orders with only `order:update`, which the RBAC seed grants to school admins; legacy create persisted a client-chosen status. `CATALOG_PRODUCT_FORM_ENABLED` is unset in every environment, so every order took this path. | Status PATCH requires superadmin (its only caller is the platform-admin orders panel); create always starts as `DRAFT`. (`2aafeee6`) |
| 4 | Medium | `github-cost-controller` existed in `custoking-prod` with project-wide `roles/cloudsql.editor`, and `gcp-cost-controls.yml@main` was trusted by the prod WIF provider while running outside the reviewer-gated Environments. | Identity, roles, WIF claim and billing-export grant are created only in projects that host `dev`. Requires `terraform apply` with `custoking-prod.tfvars`. (`d1112147`) |
| 5 | Low-Medium | Audit ingest took `userId`, `schoolId`, `actorEmail` and `timestamp` from the body behind the token the gateway injects. The table is dormant today. | Non-superadmin principals are bound to their own identity, school and the server clock. (`fe35eada`) |
| L | Latent High | `/api/v1/internal/notifications/deliveries` was gated by the same token the gateway injects, and its consent evidence is self-asserted; harmless while the provider is `logging`, a send-to-anyone primitive once MSG91 goes live. | The endpoint additionally requires the caller's Cloud Run identity to be the school-core runtime service account (`INTERNAL_OIDC_REQUIRE=true`). (`413bc6d5`) |
| H | Hardening | Refresh ignored `deleted_at`; `GET /users/{id}` returned platform-level users to tenants; create-student accepted a bucket object key as `photoUrl`. | All three closed. (`40c45c48`, `2fed6f2e`) |

Found in passing and repaired by the same change: the prod notification Pub/Sub push required a
shared token that was never configured, so every real push received 401. Both push subscriptions
now authenticate by OIDC identity.

## Configuration introduced

platform-service (all rendered from Cloud Deploy target parameters; no new secrets):

| Variable | Target parameter | Purpose |
| --- | --- | --- |
| `SERVICE_OIDC_AUDIENCES` | `service_oidc_audiences` | This service's own URLs. Prod lists both URL forms because the notification push subscription carries the legacy hash-form URL as its audience and the reporting one the deterministic form (verified live 2026-09-22). |
| `PUBSUB_PUSH_OIDC_SERVICE_ACCOUNTS` | `pubsub_push_service_accounts` | `ims-reporting-push-{env}` and `ims-notification-push-{env}`. |
| `INTERNAL_OIDC_REQUIRE` | `internal_oidc_require` | `true` in every deployed environment; default `false` for local runs. |
| `INTERNAL_OIDC_SERVICE_ACCOUNTS` | `internal_caller_service_accounts` | `ims-school-core-{env}`. |

`notification_pubsub_require_shared_token` is now `false` in prod as well as dev.

## Verification

- api-gateway: 74 tests including the alias-deny end-to-end case and the route-inventory guard.
- platform-service 268, school-core-service 788, identity-service 122 tests (unit plus
  Testcontainers RLS suites), all passing.
- `terraform validate` and `terraform fmt -check` on `infra/terraform/cicd`.
- `scripts/audit-security-governance-controls.ps1` and
  `scripts/audit-service-authorization-boundaries.ps1` pass.
- Live check of the four push subscriptions' OIDC audience and service account.

## Release sequencing this change required

Changing `deploy/clouddeploy/targets-*.yaml` sets `deployment_reconciliation_required`, which blocks
`build-images` and `release` while still reporting the run as successful. The remediation therefore
deployed in three steps, not one: merge (deploys nothing), then `Ops / Reconcile deployment
configuration` for the environment, then a release pinned with an explicit `commit_sha` to a commit
that touches `deploy/cloudrun/**` but not the targets, so the Cloud Deploy path applies the full
manifests. Following the gate's own advice instead — a services-only commit — would have taken the
fast image-only path and started the new code against the previous environment block, where the
empty caller-identity allow-lists fail closed and reject every Pub/Sub push. The full sequence is in
`current-state/deployment-cicd.md`; prod needs the same three steps.

## Open follow-ups

1. **Immutable Artifact Registry tags.** Production promotion resolves the mutable
   `dev-approved-<sourceTag>` tag in the dev registry and never binds digest to source. The pipeline
   never re-points a tag (it fails on conflict), so `docker_config { immutable_tags = true }` looks
   compatible, but its interaction with the seven-day cleanup policy was not verified. Enable on dev
   first. This is a stealth path for someone who can already push to `main`, not an escalation.
2. **Branch protection on `main` and `dev`.** Neither branch requires review; the `prod` GitHub
   Environment reviewer is the only gate. Needs repository admin.
