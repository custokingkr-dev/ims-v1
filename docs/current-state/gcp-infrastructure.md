# GCP Infrastructure

Last reconciled: 2026-08-12 with `gcloud.cmd` against project `custoking`.

## 2026-08-14 Delta

- Governed cost-control workflow run `31795809595` succeeded from `main`; direct `gcloud` inspection showed
  `custoking-db-dev` `STOPPED`, tier `db-f1-micro`, activation policy `NEVER`.
- The approved future layout is development in `custoking-dev` and production in `custoking-prod`. Neither
  destination project was visible to the operating account on 14 August, so every live resource documented
  below still belongs to the mixed source project `custoking`.
- Ten Cloud Run jobs are now visible. The one-shot `otel-probe-dev-20260813` is successful temporary evidence
  and remains a cleanup candidate; eight regular jobs still use the default Compute service account.
- The four dev async-relay Scheduler jobs are in `asia-south1`, are paused, and no production relay Scheduler
  jobs exist.

## 2026-08-12 Authoritative Reconciliation

The detailed inventory below records the earlier deployment baseline. Where it conflicts with this table,
this reconciliation and the dated production evidence take precedence.

| Area | Verified live state |
| --- | --- |
| Cloud Run | 14/14 services Ready; all runtime services use dedicated per-service identities; concurrency 80; minimum scale 0; all declare ingress `all`; frontend/gateway are public and Spring services remain IAM-private |
| Scaling | prod max instances: gateway 3, frontend 2, each Spring service 2; dev max instances 4 except frontend 2 |
| Cloud SQL dev | `STOPPED`, `db-f1-micro`, zonal, 15 GB, encrypted-only, backups/PITR disabled, `max_connections=200` |
| Cloud SQL prod | `RUNNABLE`, `db-g1-small`, zonal, 10 GB auto-resize, private IP, encrypted-only, backups/PITR/deletion protection enabled, `max_connections=200` |
| Pub/Sub | reporting push active in dev/prod with dedicated OIDC identities; dev reporting has DLQ; prod reporting has no DLQ; dev notification has push+DLQ; prod notification has neither |
| Scheduler | four dev async-relay jobs in `asia-south1`, all paused; no production relay jobs |
| Cloud Run jobs | nine total; eight use default Compute, including both prod SQL jobs; only direct-service-smoke uses a dedicated identity |
| Cloud Deploy | production targets use `clouddeploy-prod-deployer`; live dev targets still use default Compute despite dedicated identity in source templates |
| Artifact Registry | about 6.13 GB; delete-after-7-days and keep-last-3 policies active; registry scanning disabled |
| Student photo bucket | about 2.34 GB; uniform access, public-access prevention, seven-day soft delete and 14-day temporary-prefix lifecycle |
| GitHub | public repository; no visible ruleset/classic protection on `main` or `dev`; prod Environment review exists, admin bypass disabled, self-review allowed |

Secrets values, database credentials, token query values, and student identifiers are intentionally absent.

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

All listed services were Ready on 2026-08-10.

| Service | URL | Latest ready revision |
| --- | --- | --- |
| `custoking-api-gateway-dev` | `https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app` | `custoking-api-gateway-dev-msnlxox9` |
| `custoking-api-gateway-prod` | `https://custoking-api-gateway-prod-l7mhms5c2a-em.a.run.app` | `custoking-api-gateway-prod-msg1rcnp` |
| `custoking-billing-service-dev` | `https://custoking-billing-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-billing-service-dev-msnltq4a` |
| `custoking-billing-service-prod` | `https://custoking-billing-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-billing-service-prod-msg1hrl6` |
| `custoking-frontend-dev` | `https://custoking-frontend-dev-l7mhms5c2a-em.a.run.app` | `custoking-frontend-dev-msnlzoas` |
| `custoking-frontend-prod` | `https://custoking-frontend-prod-l7mhms5c2a-em.a.run.app` | `custoking-frontend-prod-msgd3rq4` |
| `custoking-identity-service-dev` | `https://custoking-identity-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-identity-service-dev-msnlprct` |
| `custoking-identity-service-prod` | `https://custoking-identity-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-identity-service-prod-msg17seu` |
| `custoking-operations-service-dev` | `https://custoking-operations-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-operations-service-dev-msnlrqsq` |
| `custoking-operations-service-prod` | `https://custoking-operations-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-operations-service-prod-msg1cz4c` |
| `custoking-platform-service-dev` | `https://custoking-platform-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-platform-service-dev-msnlvpk9` |
| `custoking-platform-service-prod` | `https://custoking-platform-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-platform-service-prod-msgczd9t` |
| `custoking-school-core-service-dev` | `https://custoking-school-core-service-dev-l7mhms5c2a-em.a.run.app` | `custoking-school-core-service-dev-msnlnfnf` |
| `custoking-school-core-service-prod` | `https://custoking-school-core-service-prod-l7mhms5c2a-em.a.run.app` | `custoking-school-core-service-prod-msgcuzgs` |

## Cloud Run IAM

Verified service-level invoker bindings:

- Public:
  - `custoking-api-gateway-dev`: `roles/run.invoker=allUsers`
  - `custoking-api-gateway-prod`: `roles/run.invoker=allUsers`
  - `custoking-frontend-dev`: `roles/run.invoker=allUsers`
  - `custoking-frontend-prod`: `roles/run.invoker=allUsers`
- Private Spring services:
  - identity, school-core, operations, billing in both envs allow the Monitoring service agent for authenticated uptime checks.
  - platform-service allows the Monitoring service agent and the dedicated reporting/notification push callers that exist in each environment.
  - a legacy default-Compute platform invoker binding remains and must be removed after job/target IAM cutover evidence.

Platform-service additionally allows:

- `serviceAccount:305630109861-compute@developer.gserviceaccount.com` (legacy)
- dev: `serviceAccount:ims-reporting-push-dev@custoking.iam.gserviceaccount.com`
- dev: `serviceAccount:ims-notification-push-dev@custoking.iam.gserviceaccount.com`
- prod: `serviceAccount:ims-reporting-push-prod@custoking.iam.gserviceaccount.com`

The dedicated bindings support Pub/Sub push with OIDC. Production notification has no subscription;
the default-Compute binding is now legacy rather than an active reporting dependency.

## Runtime Service Account

Both environments use a dedicated identity per Cloud Run service:

| Service | Dev / prod runtime identity | Resource-specific access |
| --- | --- | --- |
| Identity | `ims-identity-<env>` | scoped secrets; telemetry; invoke school core |
| School core | `ims-school-core-<env>` | scoped secrets; telemetry; reporting publish; photo objects; self-signing |
| Operations | `ims-operations-<env>` | scoped secrets; telemetry; reporting publish; invoke school core |
| Platform | `ims-platform-<env>` | scoped secrets; telemetry; invoke school core and operations |
| Billing | `ims-billing-<env>` | scoped secrets; telemetry; reporting publish |
| API gateway | `ims-api-gateway-<env>` | scoped secrets; Cloud Trace; invoke five private backends |
| Frontend | `ims-frontend-<env>` | no project-level role |

The dev effective-policy audit verified 44 secret assignments, 12 project-role assignments, three
topic publishers, nine Cloud Run invoker edges, one bucket role and one self-signing role with zero
missing bindings. Dev runtime identities do not have broad build, deploy, or project-wide secret
roles.

Broad project-level IAM bindings still present on the default Compute service account:

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

| Service type | CPU | Memory | Min instances | Max instances |
| --- | --- | --- | --- | --- |
| api-gateway | 1 vCPU | 512 MiB | `0` | 3 |
| identity-service | 1 vCPU | 768 MiB | `0` | 2 |
| school-core-service | 1 vCPU | 2 GiB | `0` | 2 |
| operations-service | 1 vCPU | 768 MiB | `0` | 2 |
| billing-service | 1 vCPU | 768 MiB | `0` | 2 |
| platform-service | 1 vCPU | 768 MiB | `0` | 2 |
| frontend | 1 vCPU | 512 MiB | `0` | 2 |

The live `asia-south2` Cloud Run CPU allocation quota was verified on 2026-08-05 as `20,000` milli-vCPU (20 vCPU). Cloud Quotas currently reports this project as ineligible for an increase because it does not yet have enough usage history. Production Cloud Deploy therefore promotes one service to stable before creating the next release, limiting overlap between old and canary instances. This quota limits concurrent capacity; it does not create cost by itself.

Outbox publishers are configured in both envs:

- school-core: `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID=ims-reporting-events-v1-<env>`
- operations: `OPERATIONS_OUTBOX_PUBSUB_TOPIC_ID=ims-reporting-events-v1-<env>`
- billing: `BILLING_OUTBOX_PUBSUB_TOPIC_ID=ims-reporting-events-v1-<env>`

## Cloud SQL

| Instance | Database version | Region | State | Tier | Private IP |
| --- | --- | --- | --- | --- | --- |
| `custoking-db-dev` | `POSTGRES_16` | `asia-south2` | `STOPPED` | `db-f1-micro` | `10.92.0.4` |
| `custoking-db-prod` | `POSTGRES_16` | `asia-south2` | `RUNNABLE` | `db-g1-small` | `10.92.0.5` |

Databases:

- Dev: `postgres`, `custoking_dev`
- Prod: `postgres`, `custoking_prod`

Users present on both instances:

- `app_rt`
- `appuser`
- `postgres`

Dev is temporarily retained at 4 vCPU/7.5 GiB with a 15-GiB disk and activation policy `ALWAYS` for the
active corrective capacity rerun. This is not an approved permanent cost posture. After evidence capture,
the operator must remove the synthetic fixture and explicitly downsize/stop it. Dev now enforces Cloud SQL
`ENCRYPTED_ONLY`; fresh `pg_stat_ssl` evidence recorded 16/16 application clients encrypted and zero
unencrypted, followed by a 40/40 gateway smoke.

Production recovery controls were applied and verified on 2026-08-05:

- automated regional backups in `asia-south2`
- 14 retained backups
- seven days of point-in-time recovery transaction logs
- deletion protection enabled
- monthly isolated restore drill through `.github/workflows/recovery-drill.yml`

A live production drill passed on 2026-08-05 and cleanup verification found zero
temporary restore instances and zero validation exports afterward.

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
| `custoking-db-snapshots` | `ASIA-SOUTH2` |
| `custoking-student-photos-dev` | `ASIA-SOUTH2` |
| `custoking-student-photos-prod` | `ASIA-SOUTH2` |
| `custoking-terraform-state` | `ASIA-SOUTH2` |
| `custoking_cloudbuild` | `US` |

Student photo buckets use uniform bucket-level access and public access prevention. The Cloud Run runtime service account has object administration access for the student photo buckets.

Cloud Logging also owns `custoking-compliance-india` as a log bucket in
`asia-south2` with 180-day retention. It is not a Cloud Storage bucket.

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
| `ims-reporting-service-push-dev` | `ims-reporting-events-v1-dev` | platform-service dev `/api/v1/pubsub/reporting-events` (no query) | `ims-reporting-push-dev` | 30s | ACTIVE |
| `ims-reporting-service-push-prod` | `ims-reporting-events-v1-prod` | platform-service prod `/api/v1/pubsub/reporting-events` (no query) | `ims-reporting-push-prod` | 30s | ACTIVE |
| `ims-notification-service-push-dev` | `ims-notifications-events-v1-dev` | platform-service dev `/api/v1/pubsub/notifications` (no query) | `ims-notification-push-dev` | 30s | ACTIVE |

The live push endpoints have no query credential and use audiences equal to the platform Cloud Run service
URL. Both dev subscriptions use 10-600 second retry, ten delivery attempts and dedicated dead-letter topics;
guarded notification idempotency/DLQ probes passed with the logging provider, so no external message was
sent. Production reporting has no DLQ. The production notification topic still has no subscription.

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

Verified drift: the source file `deploy/gcp/github-deploy-runtime-operator-role.yaml`
exists, but the `githubDeployRuntimeOperator` custom role is not live. Deploys are
currently succeeding with predefined project-level roles.
`custokingRecoveryBucketIamOperator` is a separate live three-permission custom role
used only by the recovery workflow. `roles/cloudbuild.builds.editor` remains present
on the deploy identity even though `cloudbuild.yaml` deployment is retired.

Recovery workflow identity:

```text
custoking-recovery-operator@custoking.iam.gserviceaccount.com
```

It is repository-scoped through the same Workload Identity provider, has Cloud SQL
administration for isolated drills, object administration on
`custoking-db-snapshots`, and the custom bucket-IAM role on that bucket so temporary
clone service identities can be granted and revoked.

## Cloud Deploy

Cloud Deploy is enabled and active in `asia-south2`.

Cloud Deploy retains one delivery pipeline per service per environment:

```text
custoking-<service>-dev
custoking-<service>-prod
```

Normal dev code releases update affected Cloud Run services directly by immutable digest. Dev Cloud Deploy pipelines use a standard strategy when deployment configuration changes or an operator explicitly requests configuration reconciliation. Prod pipelines use canary percentages `5`, `25`, `50`, then stable and consume dev-approved image digests.

Latest verified production Cloud Deploy releases for foundation commit `a8acfbe78992404a87d30d0e7eb19ace1b0638a2`:

| Release | Services | State |
| --- | --- | --- |
| `rel-prod-a8acfbe78992-1` | school-core, identity, operations, billing, platform, api-gateway | `SUCCEEDED` |
| `rel-prod-a8acfbe78992-2` | frontend | `SUCCEEDED` |

The frontend required a fresh release after the first revision exhausted the 20 vCPU regional quota and became permanently failed. The release orchestrator now completes one service before creating the next and protects `PENDING_RELEASE` from premature phase advancement.

## Cloud Run Jobs

Live jobs:

- `ims-app-rt-dev`
- `ims-app-rt-prod`
- `ims-direct-service-smoke`
- `ims-q-dev`
- `ims-seed-dev`
- `ims-seedfull-dev`

`ims-direct-service-smoke` exists but is not invoked by `build-release.yml`. Automatic release verification now checks each changed service's Cloud Run readiness, exact image digest, latest revision traffic, changed frontend HTTP response, and final public gateway health. The job remains available for deeper authenticated business checks.

Verified live IAM for the direct smoke runtime:

- `direct-service-smoke@custoking.iam.gserviceaccount.com` has `roles/run.invoker` on `custoking-school-core-service-dev` and `custoking-school-core-service-prod`.
- It has `roles/secretmanager.secretAccessor` on `catalog-read-token-dev`, `tenant-school-read-token-dev`, `catalog-read-token-prod`, and `tenant-school-read-token-prod`.

## Notification Delivery Runtime

Verified deployed platform-service env vars include:

- `NOTIFICATION_DELIVERY_PROVIDER=logging`
- `MSG91_DRY_RUN=true`
- `MSG91_AUTH_KEY` injected from Secret Manager

The deployed provider is intentionally logging/dry-run because the production sender profile does not yet have an MSG91 SMS flow ID. Notification persistence, retry, and dead-letter handling are active; real MSG91 delivery is not enabled. See [gaps-and-drift.md](gaps-and-drift.md).
