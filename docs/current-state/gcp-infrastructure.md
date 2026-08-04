# GCP Infrastructure

Last verified: 2026-08-04 with `gcloud.cmd` against project `custoking`.

## Project and Region

| Item | Value |
| --- | --- |
| GCP project ID | `custoking` |
| Project number | `305630109861` |
| Primary region | `asia-south2` |
| Network | `default` |
| Subnet | `default` in `asia-south2` |
| Private services range | `google-managed-services-default`, `10.92.0.0`, purpose `VPC_PEERING` |

Main enabled APIs relevant to this stack:

- `artifactregistry.googleapis.com`
- `cloudbuild.googleapis.com`
- `clouddeploy.googleapis.com`
- `cloudresourcemanager.googleapis.com`
- `cloudtrace.googleapis.com`
- `compute.googleapis.com`
- `iam.googleapis.com`
- `iamcredentials.googleapis.com`
- `logging.googleapis.com`
- `monitoring.googleapis.com`
- `pubsub.googleapis.com`
- `run.googleapis.com`
- `secretmanager.googleapis.com`
- `servicenetworking.googleapis.com`
- `serviceusage.googleapis.com`
- `sqladmin.googleapis.com`
- `storage.googleapis.com`
- `sts.googleapis.com`
- `telemetry.googleapis.com`
- `vpcaccess.googleapis.com`

`observability.googleapis.com` was not enabled in the live API list. Existing Cloud Monitoring, Logging, and Cloud Trace functionality is working without that API, but newer trace-scope commands that require it are unavailable.

## Cloud Run Services

All listed services were Ready on 2026-08-04.

| Service | URL | Latest ready revision |
| --- | --- | --- |
| `custoking-api-gateway-dev` | `https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app` | `custoking-api-gateway-dev-mseb1ggj` |
| `custoking-api-gateway-prod` | `https://custoking-api-gateway-prod-l7mhms5c2a-em.a.run.app` | `custoking-api-gateway-prod-msecp82x` |
| `custoking-billing-service-dev` | `https://custoking-billing-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-billing-service-dev-mseb00d8` |
| `custoking-billing-service-prod` | `https://custoking-billing-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-billing-service-prod-msecnzyt` |
| `custoking-frontend-dev` | `https://custoking-frontend-dev-l7mhms5c2a-em.a.run.app` | `custoking-frontend-dev-mseb22a1` |
| `custoking-frontend-prod` | `https://custoking-frontend-prod-l7mhms5c2a-em.a.run.app` | `custoking-frontend-prod-msecpvpk` |
| `custoking-identity-service-dev` | `https://custoking-identity-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-identity-service-dev-mseaypug` |
| `custoking-identity-service-prod` | `https://custoking-identity-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-identity-service-prod-msecmmbk` |
| `custoking-operations-service-dev` | `https://custoking-operations-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-operations-service-dev-mseazdli` |
| `custoking-operations-service-prod` | `https://custoking-operations-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-operations-service-prod-msecn9qa` |
| `custoking-platform-service-dev` | `https://custoking-platform-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-platform-service-dev-mseb0sod` |
| `custoking-platform-service-prod` | `https://custoking-platform-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-platform-service-prod-msecomtz` |
| `custoking-school-core-service-dev` | `https://custoking-school-core-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-school-core-service-dev-mseay1qp` |
| `custoking-school-core-service-prod` | `https://custoking-school-core-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-school-core-service-prod-mseclx1n` |

## Cloud Run IAM

Verified service-level invoker bindings:

- Public:
  - `custoking-api-gateway-dev`: `roles/run.invoker=allUsers`
  - `custoking-api-gateway-prod`: `roles/run.invoker=allUsers`
  - `custoking-frontend-dev`: `roles/run.invoker=allUsers`
  - `custoking-frontend-prod`: `roles/run.invoker=allUsers`
- Private Spring services:
  - identity, school-core, operations, billing in both envs allow the Monitoring service agent for authenticated uptime checks.
  - platform-service in both envs allows the Monitoring service agent and the default compute service account.

Platform-service additionally allows:

- `serviceAccount:305630109861-compute@developer.gserviceaccount.com`

That binding supports Pub/Sub push with OIDC using the default compute service account.

## Runtime Service Account

All Cloud Run services currently run as:

```text
305630109861-compute@developer.gserviceaccount.com
```

Project-level IAM bindings verified for the default compute service account:

- `roles/artifactregistry.writer`
- `roles/cloudbuild.builds.builder`
- `roles/cloudtrace.agent`
- `roles/iam.serviceAccountUser`
- `roles/logging.logWriter`
- `roles/run.admin`
- `roles/secretmanager.secretAccessor`
- `roles/serviceusage.serviceUsageConsumer`
- `roles/telemetry.tracesWriter`

The default compute service account IAM policy includes `roles/iam.serviceAccountTokenCreator` for itself and the Pub/Sub service agent:

```text
service-305630109861@gcp-sa-pubsub.iam.gserviceaccount.com
```

## Cloud Run Runtime Config

Common Spring service config:

- `SPRING_PROFILES_ACTIVE=prod` in dev and prod.
- Runtime DB user is `app_rt`.
- Flyway DB user is `appuser`.
- OTEL endpoint is `https://telemetry.googleapis.com`.
- OTEL traces protocol is `http/protobuf`.
- OTEL logs and metrics exporters are disabled.
- Live trace sample ratio is `0.05` unless overridden by the deployment workflow environment variables.
- Java services use `vpc-egress=private-ranges-only` with `network=default` and `subnetwork=default`.
- Gateway and frontend do not use the VPC network attachment in the current live config.

Scaling verified from Cloud Run annotations:

| Service type | Min instances | Max instances |
| --- | --- | --- |
| api-gateway | not set (`0`) | 3 |
| identity-service | not set (`0`) | 2 |
| school-core-service | not set (`0`) | 2 |
| operations-service | not set (`0`) | 2 |
| billing-service | not set (`0`) | 2 |
| platform-service | not set (`0`) | 2 |
| frontend | not set (`0`) | 2 |

Outbox publishers are configured in both envs:

- school-core: `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID=ims-reporting-events-v1-<env>`
- operations: `OPERATIONS_OUTBOX_PUBSUB_TOPIC_ID=ims-reporting-events-v1-<env>`
- billing: `BILLING_OUTBOX_PUBSUB_TOPIC_ID=ims-reporting-events-v1-<env>`

## Cloud SQL

| Instance | Database version | Region | State | Tier | Private IP |
| --- | --- | --- | --- | --- | --- |
| `custoking-db-dev` | `POSTGRES_16` | `asia-south2` | `RUNNABLE` | `db-f1-micro` | `10.92.0.4` |
| `custoking-db-prod` | `POSTGRES_16` | `asia-south2` | `RUNNABLE` | `db-g1-small` | `10.92.0.5` |

Databases:

- Dev: `postgres`, `custoking_dev`
- Prod: `postgres`, `custoking_prod`

Users present on both instances:

- `app_rt`
- `appuser`
- `postgres`

## Artifact Registry

Artifact Registry repository:

```text
asia-south2-docker.pkg.dev/custoking/custoking
```

Repository format: Docker.

Images are environment-agnostic and tagged by commit SHA. Service names, secrets, DB names, and Pub/Sub topics are environment-suffixed at deploy time.

## Storage Buckets

Live buckets:

| Bucket | Location |
| --- | --- |
| `custoking-github-deploy-source` | `ASIA-SOUTH2` |
| `custoking-student-photos-dev` | `ASIA-SOUTH2` |
| `custoking-student-photos-prod` | `ASIA-SOUTH2` |
| `custoking-terraform-state` | `ASIA-SOUTH2` |
| `custoking_cloudbuild` | `US` |

Student photo buckets use uniform bucket-level access and public access prevention. The Cloud Run runtime service account has object administration access for the student photo buckets.

## Secret Manager

Secret names verified in project `custoking`:

- `app-rt-password-dev`
- `app-rt-password-prod`
- `attendance-read-token-dev`
- `attendance-read-token-prod`
- `audit-ingest-token-dev`
- `audit-ingest-token-prod`
- `billing-service-token-dev`
- `billing-service-token-prod`
- `catalog-read-token-dev`
- `catalog-read-token-prod`
- `create-app-rt-role-sql`
- `db-password-dev`
- `db-password-prod`
- `diag-sql`
- `fee-read-token-dev`
- `fee-read-token-prod`
- `firefighting-read-token-dev`
- `firefighting-read-token-prod`
- `identity-introspection-token-dev`
- `identity-introspection-token-prod`
- `jwt-secret-dev`
- `jwt-secret-prod`
- `msg91-auth-key-dev`
- `msg91-auth-key-prod`
- `notification-status-token-dev`
- `notification-status-token-prod`
- `reporting-read-token-dev`
- `reporting-read-token-prod`
- `seed-full-sql`
- `seed-superadmin-sql`
- `student-read-token-dev`
- `student-read-token-prod`
- `superadmin-password-dev`
- `superadmin-password-prod`
- `tenant-school-read-token-dev`
- `tenant-school-read-token-prod`
- `workflow-read-token-dev`
- `workflow-read-token-prod`

Secret values are intentionally not documented.

## Pub/Sub

Topics:

- `projects/custoking/topics/ims-reporting-events-v1-dev`
- `projects/custoking/topics/ims-reporting-events-v1-prod`
- `projects/custoking/topics/ims-notifications-events-v1-dev`
- `projects/custoking/topics/ims-notifications-events-v1-prod`

Subscriptions:

| Subscription | Topic | Push target | OIDC service account | Ack deadline | State |
| --- | --- | --- | --- | --- | --- |
| `ims-reporting-service-push-dev` | `ims-reporting-events-v1-dev` | platform-service dev `/api/v1/pubsub/reporting-events` | default compute SA | 30s | ACTIVE |
| `ims-notification-service-push-dev` | `ims-notifications-events-v1-dev` | platform-service dev `/api/v1/pubsub/notifications` | default compute SA | 30s | ACTIVE |
| `ims-reporting-service-push-prod` | `ims-reporting-events-v1-prod` | platform-service prod `/api/v1/pubsub/reporting-events` | default compute SA | 30s | ACTIVE |
| `ims-notification-service-push-prod` | `ims-notifications-events-v1-prod` | platform-service prod `/api/v1/pubsub/notifications` | default compute SA | 30s | ACTIVE |

The literal push endpoint query tokens are not documented. They are secret-bearing URL query parameters.

## Workload Identity Federation

WIF pool:

```text
projects/305630109861/locations/global/workloadIdentityPools/github-pool
```

Provider:

```text
projects/305630109861/locations/global/workloadIdentityPools/github-pool/providers/github-provider
```

Provider state: `ACTIVE`.

Provider condition:

```text
assertion.repository=='custokingkr-dev/ims-v1'
```

Deploy service account:

```text
github-actions-sa@custoking.iam.gserviceaccount.com
```

The deploy service account allows Workload Identity User for:

```text
principalSet://iam.googleapis.com/projects/305630109861/locations/global/workloadIdentityPools/github-pool/attribute.repository/custokingkr-dev/ims-v1
```

Verified project-level IAM bindings for `github-actions-sa`:

- `roles/artifactregistry.writer`
- `roles/cloudbuild.builds.editor`
- `roles/clouddeploy.admin`
- `roles/iam.serviceAccountUser`
- `roles/logging.viewer`
- `roles/run.developer`
- `roles/secretmanager.viewer`
- `roles/serviceusage.serviceUsageConsumer`
- `roles/storage.admin`

Verified drift: the source file `deploy/gcp/github-deploy-runtime-operator-role.yaml` exists, but no project custom role was found by `gcloud iam roles list --project=custoking`, and `githubDeployRuntimeOperator` could not be described. Deploys are currently succeeding with predefined project-level roles. `roles/cloudbuild.builds.editor` remains present even though `cloudbuild.yaml` deployment is retired.

## Cloud Deploy

Cloud Deploy is enabled and active in `asia-south2`.

The active deployment model uses one delivery pipeline per service per environment:

```text
custoking-<service>-dev
custoking-<service>-prod
```

Dev pipelines use a standard strategy. Prod pipelines use canary percentages `5`, `25`, `50`, then stable.

Latest verified API gateway rollouts for commit `8607912b7f85378086c4602294f610f907538084`:

| Environment | Release | Rollout | State |
| --- | --- | --- | --- |
| dev | `rel-dev-8607912b7f85-1` | `rel-dev-8607912b7f85-1-to-api-gateway-dev-0001` | `SUCCEEDED` |
| prod | `rel-prod-8607912b7f85-1` | `rel-prod-8607912b7f85-1-to-api-gateway-prod-0001` | `SUCCEEDED` |

## Cloud Run Jobs

Live jobs:

- `ims-app-rt-dev`
- `ims-app-rt-prod`
- `ims-direct-service-smoke`
- `ims-q-dev`
- `ims-seed-dev`
- `ims-seedfull-dev`

`ims-direct-service-smoke` exists. The current `build-release.yml` workflow does not invoke this job; the automatic post-rollout smoke currently calls only the public gateway `/gateway-health` endpoint through `scripts/smoke-gateway-health.ps1`.

Verified live IAM for the direct smoke runtime:

- `direct-service-smoke@custoking.iam.gserviceaccount.com` has `roles/run.invoker` on `custoking-school-core-service-dev` and `custoking-school-core-service-prod`.
- It has `roles/secretmanager.secretAccessor` on `catalog-read-token-dev`, `tenant-school-read-token-dev`, `catalog-read-token-prod`, and `tenant-school-read-token-prod`.

## Notification Delivery Runtime

Verified deployed platform-service env vars include:

- `MSG91_DRY_RUN=true`
- `MSG91_AUTH_KEY` injected from Secret Manager

The deployed Cloud Run env did not show `NOTIFICATION_DELIVERY_PROVIDER`. In `platform-service` application config, the default is `notification.delivery.provider=logging`. Therefore, based on current verified config, real MSG91 delivery is not proven enabled in prod; notification records and Pub/Sub ingress may work while provider delivery remains logging/dry-run. See [gaps-and-drift.md](gaps-and-drift.md).
