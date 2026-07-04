# Greenfield Deployment Plan — `custoking` (dev + prod)

Deploys the Custoking IMS microservices stack to a **brand-new GCP project `custoking`**, with
**two environments in one project** separated by an `-<env>` suffix (`dev`, `prod`), driven
entirely through **GitHub Actions** (build-once-promote). Demos run from a seeded demo tenant in
`dev` — no third environment.

**Deploy topology**

- One project `custoking`; region `asia-south2` (change via the `GCP_REGION` repo variable).
- Every Cloud Run service, Secret Manager secret, and Pub/Sub topic is suffixed `-dev` / `-prod`.
- One shared Artifact Registry repo (`custoking`); images are env-agnostic, tagged by commit SHA,
  and the **same image is promoted dev → prod** (never rebuilt).
- Both environments run `SPRING_PROFILES_ACTIVE=prod` so dev faithfully mirrors prod (RLS,
  gateway-enforce, real Pub/Sub publisher all active in dev).

**GitHub-driven flow** (`.github/workflows/`)

```
push to main ─► release.yml
                 ├─ deploy-dev   → deploy.yml (env=dev, build + push :sha, deploy, smoke)
                 └─ promote-prod → deploy.yml (env=prod, skip_build, SAME :sha)  ── gated by
                                                                                    prod Environment
                                                                                    protection rule
```

`deploy.yml` is the reusable engine → `gcloud builds submit cloudbuild.yaml` with
`_ENV`, `_SKIP_BUILD`, `_DB_HOST`, `_DB_NAME`, `_COMMIT_SHA`.

---

## Prerequisites

- `gcloud` authenticated as a project **Owner** (or Editor + Project IAM Admin) on `custoking`.
- A GCP **billing account** linked to `custoking` (the previous project failed on billing —
  confirm `gcloud billing projects describe custoking` shows `billingEnabled: True`).
- Repo admin on GitHub (to set Variables and Environments).
- Run the shell blocks below in Cloud Shell or git-bash. Set once:

```bash
export PROJECT=custoking
export REGION=asia-south2
gcloud config set project "$PROJECT"
PROJNUM="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')"
```

---

## Phase 0 — Project bootstrap (once per project)

**0.1 Enable APIs**

```bash
gcloud services enable \
  run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  sqladmin.googleapis.com secretmanager.googleapis.com pubsub.googleapis.com \
  compute.googleapis.com vpcaccess.googleapis.com servicenetworking.googleapis.com \
  iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com \
  cloudresourcemanager.googleapis.com --project="$PROJECT"
```

**0.2 Artifact Registry** (shared across envs)

```bash
gcloud artifacts repositories create custoking \
  --repository-format=docker --location="$REGION" --project="$PROJECT"
```

**0.3 VPC + private services access** (for Cloud SQL private IP; `default` VPC assumed)

```bash
gcloud compute addresses create google-managed-services-default \
  --global --purpose=VPC_PEERING --prefix-length=16 --network=default --project="$PROJECT"
gcloud services vpc-peerings connect \
  --service=servicenetworking.googleapis.com \
  --ranges=google-managed-services-default --network=default --project="$PROJECT"
```

**0.4 Cloud Build source-staging bucket**

```bash
gsutil mb -l "$REGION" -p "$PROJECT" gs://${PROJECT}-github-deploy-source
gsutil lifecycle set deploy/gcp/github-deploy-source-bucket-lifecycle.json \
  gs://${PROJECT}-github-deploy-source
```

---

## Phase 1 — Per-environment infrastructure (run for `dev`, then `prod`)

Set `ENV` and run **the whole of Phase 1** twice — once `dev`, once `prod`.

```bash
export ENV=dev          # then repeat the entire phase with ENV=prod
```

**1.1 Cloud SQL instance** — `dev` is a small, **stoppable** instance; `prod` is db-g1-small.

```bash
# tier: dev = db-f1-micro (stoppable when idle), prod = db-g1-small
TIER=$([ "$ENV" = prod ] && echo db-g1-small || echo db-f1-micro)
gcloud sql instances create custoking-db-$ENV \
  --database-version=POSTGRES_16 --tier="$TIER" --region="$REGION" \
  --no-assign-ip --network=default \
  --database-flags=max_connections=200 --project="$PROJECT"

gcloud sql databases create custoking_$ENV --instance=custoking-db-$ENV --project="$PROJECT"
gcloud sql users create appuser --instance=custoking-db-$ENV \
  --password="$(openssl rand -base64 24)" --project="$PROJECT"   # capture this into db-password-$ENV below

# Note the PRIVATE IP — it becomes DB_HOST for this environment's GitHub Environment.
gcloud sql instances describe custoking-db-$ENV --project="$PROJECT" \
  --format='value(ipAddresses[0].ipAddress)'
```

> Cost tip: stop `custoking-db-dev` when not testing —
> `gcloud sql instances patch custoking-db-dev --activation-policy=NEVER`.

**1.2 Secrets** — all 17, suffixed `-$ENV`. Generate strong values; the DB passwords must match
the Cloud SQL users you create (`appuser` → `db-password-$ENV`; the `app_rt` runtime role you make
in 1.3 → `app-rt-password-$ENV`).

```bash
SECRETS=(db-password app-rt-password jwt-secret \
  identity-introspection-token tenant-school-read-token student-read-token \
  attendance-read-token fee-read-token catalog-read-token workflow-read-token \
  firefighting-read-token reporting-read-token audit-ingest-token \
  notification-status-token billing-service-token msg91-auth-key)

for s in "${SECRETS[@]}"; do
  # jwt-secret must be >=32 chars; tokens should be random.
  openssl rand -base64 36 | gcloud secrets create "${s}-${ENV}" --data-file=- --project="$PROJECT"
done
# Overwrite db-password-$ENV and app-rt-password-$ENV with the ACTUAL Cloud SQL passwords:
printf '%s' "$APPUSER_PASSWORD" | gcloud secrets versions add db-password-$ENV --data-file=- --project="$PROJECT"
printf '%s' "$APP_RT_PASSWORD"  | gcloud secrets versions add app-rt-password-$ENV --data-file=- --project="$PROJECT"
```

Grant the runtime service account read access (see 1.5 for which SA runs the services):

```bash
for s in "${SECRETS[@]}"; do
  gcloud secrets add-iam-policy-binding "${s}-${ENV}" \
    --member="serviceAccount:${PROJNUM}-compute@developer.gserviceaccount.com" \
    --role="roles/secretmanager.secretAccessor" --project="$PROJECT" >/dev/null
done
```

**1.3 Database roles (`app_rt`)** — create the unprivileged runtime role on this env's DB with the
default-privilege grants, so tables Flyway later creates (incl. `billing.outbox_events`) are
readable/writable by `app_rt`. Use the existing role script against `custoking-db-$ENV`:

```bash
# Set the appuser password Cloud SQL expects, then run scripts/create-app-rt-role.sql as appuser.
# Reuse the in-VPC one-off Cloud Run Job psql pattern (postgres:16-alpine) against the env instance,
# passing PGHOST=<private IP of custoking-db-$ENV>, PGUSER=appuser, PGDATABASE=custoking_$ENV.
# The script CREATEs app_rt, GRANTs USAGE+DML on all 12 schemas, and sets ALTER DEFAULT PRIVILEGES
# FOR ROLE appuser so future tables auto-grant to app_rt.  Verify with scripts/audit-app-rt-privileges.sql.
```

> **Ordering matters:** run 1.3 *before* the first service deploy so `app_rt` and the default
> privileges exist when Flyway (as `appuser`) creates the schemas and tables.

**1.4 Pub/Sub topics** — one per env (subscriptions come after first deploy, in Phase 3):

```bash
gcloud pubsub topics create ims-reporting-events-v1-$ENV --project="$PROJECT"
gcloud pubsub topics create ims-notifications-events-v1-$ENV --project="$PROJECT"
# billing's runtime SA must be able to publish to the reporting topic:
gcloud pubsub topics add-iam-policy-binding ims-reporting-events-v1-$ENV \
  --member="serviceAccount:${PROJNUM}-compute@developer.gserviceaccount.com" \
  --role="roles/pubsub.publisher" --project="$PROJECT"
```

**1.5 Runtime service account** — the default compute SA (`${PROJNUM}-compute@…`) runs all Cloud
Run services here; it already has the secret + publisher grants above. (Optionally create dedicated
per-service SAs later for tighter least-privilege — Phase 6.6.)

---

## Phase 2 — Workload Identity Federation + GitHub wiring (once)

**2.1 Deploy service account + roles**

```bash
gcloud iam service-accounts create github-actions-sa --project="$PROJECT"
DEPLOY_SA="github-actions-sa@${PROJECT}.iam.gserviceaccount.com"
# Custom deploy role (Cloud Build submit, Cloud Run deploy, Secret access) — reuse the repo's definition:
gcloud iam roles create githubDeployRuntimeOperator --project="$PROJECT" \
  --file=deploy/gcp/github-deploy-runtime-operator-role.yaml
for R in projects/$PROJECT/roles/githubDeployRuntimeOperator \
         roles/cloudbuild.builds.editor roles/storage.admin roles/iam.serviceAccountUser; do
  gcloud projects add-iam-policy-binding "$PROJECT" --member="serviceAccount:${DEPLOY_SA}" --role="$R"
done
gcloud iam service-accounts create direct-service-smoke --project="$PROJECT"
```

**2.2 WIF pool + provider bound to this repo**

```bash
gcloud iam workload-identity-pools create github-pool --location=global --project="$PROJECT"
gcloud iam workload-identity-pools providers create-oidc github-provider \
  --location=global --workload-identity-pool=github-pool \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository=='<OWNER>/<REPO>'" --project="$PROJECT"
# allow the repo to impersonate the deploy SA
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA" --project="$PROJECT" \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/${PROJNUM}/locations/global/workloadIdentityPools/github-pool/attribute.repository/<OWNER>/<REPO>"
```

**2.3 GitHub repository Variables** (Settings → Secrets and variables → Actions → Variables):

| Variable | Value |
|---|---|
| `GCP_PROJECT_ID` | `custoking` |
| `GCP_REGION` | `asia-south2` |
| `WORKLOAD_IDENTITY_PROVIDER` | `projects/<PROJNUM>/locations/global/workloadIdentityPools/github-pool/providers/github-provider` |
| `DEPLOY_SERVICE_ACCOUNT` | `github-actions-sa@custoking.iam.gserviceaccount.com` |
| `DIRECT_SMOKE_SERVICE_ACCOUNT` | `direct-service-smoke@custoking.iam.gserviceaccount.com` |
| `CLOUD_BUILD_SOURCE_STAGING_DIR` | `gs://custoking-github-deploy-source/source` |

**2.4 GitHub Environments** (Settings → Environments) — create **`dev`** and **`prod`**:

- On **each** environment set environment Variables `DB_HOST` (the env's Cloud SQL private IP,
  e.g. `10.x.x.x:5432`) and `DB_NAME` (`custoking_dev` / `custoking_prod`).
- On **`prod`** add a protection rule: **Required reviewers** (yourself/the team). This is the gate
  that holds `promote-prod` until approved.

---

## Phase 3 — First deploy + Pub/Sub push subscriptions (per env)

**3.1 First deploy** — build once to dev, then promote to prod:

- Push to `main` → `release.yml` builds `:sha`, deploys **dev**, then waits on the **prod** gate;
  approve it to promote the same image to prod.
- Or manually: run **GCP / Deploy** (`workflow_dispatch`) with `environment=dev`,
  `deploy_services=all`, `skip_build=false`; then again with `environment=prod`, `skip_build=true`.

Flyway runs on first boot and creates all 12 schemas in `custoking_$ENV`.

**3.2 Pub/Sub push subscriptions WITH OIDC** — *must* be created **after** the platform service
exists (the push endpoint is its URL) and **with** OIDC auth, or Cloud Run's IAM edge silently
403s every event. This is the exact step that failed in the previous project.

```bash
export ENV=dev          # repeat for prod
PLATFORM_URL="$(gcloud run services describe custoking-platform-service-$ENV \
  --region="$REGION" --format='value(status.url)' --project="$PROJECT")"
RPT_TOKEN="$(gcloud secrets versions access latest --secret=reporting-read-token-$ENV --project="$PROJECT")"
NTF_TOKEN="$(gcloud secrets versions access latest --secret=notification-status-token-$ENV --project="$PROJECT")"
COMPUTE_SA="${PROJNUM}-compute@developer.gserviceaccount.com"

# OIDC prerequisites (idempotent): platform must accept the OIDC caller; Pub/Sub must mint its token.
gcloud beta services identity create --service=pubsub.googleapis.com --project="$PROJECT"
gcloud iam service-accounts add-iam-policy-binding "$COMPUTE_SA" --project="$PROJECT" \
  --member="serviceAccount:service-${PROJNUM}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountTokenCreator"
gcloud run services add-iam-policy-binding custoking-platform-service-$ENV --region="$REGION" \
  --member="serviceAccount:$COMPUTE_SA" --role="roles/run.invoker" --project="$PROJECT"

# Reporting projection subscription (OIDC + app token).
gcloud pubsub subscriptions create ims-reporting-service-push-$ENV \
  --topic=ims-reporting-events-v1-$ENV \
  --push-endpoint="${PLATFORM_URL}/api/v1/pubsub/reporting-events?token=${RPT_TOKEN}" \
  --push-auth-service-account="$COMPUTE_SA" \
  --push-auth-token-audience="$PLATFORM_URL" \
  --ack-deadline=30 --project="$PROJECT"

# Notification delivery subscription (same OIDC pattern).
gcloud pubsub subscriptions create ims-notification-service-push-$ENV \
  --topic=ims-notifications-events-v1-$ENV \
  --push-endpoint="${PLATFORM_URL}/api/v1/pubsub/notifications?token=${NTF_TOKEN}" \
  --push-auth-service-account="$COMPUTE_SA" \
  --push-auth-token-audience="$PLATFORM_URL" \
  --ack-deadline=30 --project="$PROJECT"
```

**3.3 Verify the event pipeline** (dev): create a test invoice via the superadmin UI, then confirm
`reporting.billing_invoice_read` gets the row within ~20s, and that
`billing-service-$ENV` logs `PubSubDomainEventPublisher active`. If `num_undelivered_messages`
climbs, the OIDC/invoker grant hasn't propagated — wait a few minutes and re-check.

---

## Phase 4 — Seed the demo tenant (dev only)

Once dev is healthy, provision the demo tenant used for demos:

- Deploy sets `DEMO_ADMIN_PASSWORD` (add it as a `dev`-only secret if you want the demo admin
  auto-created), producing `admin@demo.custoking.com`.
- Seed a clean, presentable school/zone/class dataset under that tenant. Keep destructive dev
  testing on a *different* tenant so demos stay stable.

---

## Ongoing operations

| Action | How |
|---|---|
| **Ship a change** | Merge to `main` → auto dev deploy → approve the `prod` gate to promote the same image. |
| **Single service** | Run **GCP / Deploy** dispatch: `environment`, `deploy_services=<svc>`, `skip_build` (true to promote an already-built SHA). |
| **Rollback** | `gcloud run services update-traffic custoking-<svc>-prod --region=$REGION --to-revisions=<PREVIOUS>=100`. |
| **Canary (optional)** | Deploy with `--no-traffic --tag=canary`, shift 10% via `update-traffic --to-tags=canary=10`, then `--to-latest` after smoke. |
| **Stop dev cost** | `gcloud sql instances patch custoking-db-dev --activation-policy=NEVER` (restart with `ALWAYS`). |

## What changed in the repo for this

- `cloudbuild.yaml` — added `_ENV` (suffixes every service/secret/topic) and `_SKIP_BUILD`
  (build-once-promote); `_DB_HOST`/`_DB_NAME` are passed per environment.
- `.github/workflows/deploy.yml` — environment-parameterized engine; project identity from repo
  Variables; per-env `DB_HOST`/`DB_NAME` from GitHub Environments; `skip_build` input.
- `.github/workflows/release.yml` — new CD workflow: build+deploy dev → gated promote to prod.
- The legacy per-service `gcp-deploy-*.yml` workflows are superseded by these two and can be removed.
