# Custoking Split-Project Migration Plan — `custoking` → `custoking-dev` + `custoking-prod`

**Prepared:** 2026-08-18 IST
**Supersedes:** `GCP-SPLIT-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md` (procedure) and extends
`GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md` (data criteria, still valid).
**Preflight evidence:** `GCP-MIGRATION-PREFLIGHT-EVIDENCE-2026-08-18.md`
**Basis:** live `gcloud`/`gh` discovery on 2026-08-18 against the source project, the destination
projects, and the GitHub repository. Every claim below was read from a live API or a repository file.
Nothing is carried over from the earlier runbook without re-verification.

New project numbers, organization IDs, billing account IDs, Drive folder IDs, and school UUIDs are
written as placeholders here and recorded in restricted evidence, per the evidence-handling rule.

---

## 1. Executive summary

The earlier runbook was written before the destination projects existed and before anyone had read the
repository's actual deployment machinery. Live discovery changes the picture in five material ways:

1. **No application code changes are required.** Every GCP-specific value reaches the services through
   environment variables injected at deploy time. Source contains zero project IDs, bucket names, or
   resource names.
2. **The single hardest problem is Artifact Registry, not data.** `build-release.yml` builds images
   only on `dev` and promotes the *same digest* to prod by looking up a `dev-approved-<sourceId>` tag
   **in the registry belonging to `GCP_PROJECT_ID`**. Split that variable per environment and every
   prod release fails immediately. This needs an explicit topology decision (section 4.1).
3. **There is no custom domain.** Zero Cloud Run domain mappings exist. Cutover therefore cannot be
   done by DNS, and every URL changes, because the `.a.run.app` host embeds a project-specific hash.
4. **Bucket names are globally unique and cannot be reused** while the source project still holds them,
   so the student-photo bucket names must change — which is a deploy-parameter change, not a code change.
5. **Dev and prod currently run byte-identical images** for all seven services, so the release ledger is
   seven digests, not fourteen.

Blocking issue, unchanged from the preflight: **the destination billing account is inaccessible to the
operating account**, so no destination budget or alert can be created. Nothing paid should be built until
that is resolved.

---

## 2. Verified current state

### 2.1 Source project `custoking`

| Area | Verified state |
| --- | --- |
| Cloud Run | 14 services in `asia-south2`, 7 per environment: `api-gateway`, `billing-service`, `frontend`, `identity-service`, `operations-service`, `platform-service`, `school-core-service`. Naming `custoking-<service>-<env>`. All `minScale=0`; dev `maxScale=4`, prod `maxScale=2` (gateway 3). |
| Cloud Run networking | Direct VPC egress (`network-interfaces` on `default`/`default`), `vpc-access-egress: private-ranges-only`. **No VPC connectors exist.** Ingress `all`. |
| Cloud Run identity | One dedicated runtime SA per service per environment (`ims-<svc>-<env>@`). |
| Domain mappings | **None.** Traffic reaches `*.a.run.app` directly. |
| Cloud SQL | `custoking-db-prod` POSTGRES_16 `db-g1-small` ALWAYS/RUNNABLE, backups + PITR + deletion protection on. `custoking-db-dev` POSTGRES_16 `db-f1-micro` NEVER/STOPPED, backups + PITR off, deletion protection on. Both private-IP only on `default`. Flag `max_connections=200`. Prod databases `postgres`,`custoking_prod`; users `app_rt`,`appuser`,`postgres`. |
| Networking | Single auto-mode `default` VPC; `asia-south2` subnet `10.190.0.0/20`, Private Google Access on. PSA range `google-managed-services-default` = `10.92.0.0/16`. DB hosts `10.92.0.4` (dev), `10.92.0.5` (prod). |
| Artifact Registry | One DOCKER repo `custoking` in `asia-south2`, **244 images, ~12 GB**. Cleanup policies: delete older than 7d, keep 3 most recent. |
| Buckets | `custoking-db-snapshots` (30d), `custoking-github-deploy-source` (14d), `custoking-scan-evidence` (30d), `custoking-student-photos-dev` (14d on transient prefixes), `custoking-student-photos-prod` (same), `custoking-terraform-state`, `custoking_cloudbuild` (**US region**), `asia-south2.deploy-artifacts.custoking.appspot.com`, plus 13 auto-generated `*_clouddeploy` buckets. All uniform bucket-level access. |
| Pub/Sub | dev is complete: reporting + notifications topics, both with DLQ and push subscription and a DLQ inspection subscription. **prod is not**: reporting has a push subscription but **no DLQ**; notifications has a topic but **no subscription at all**, and no `ims-notification-push-prod` service account exists. |
| Secret Manager | 44 secrets — 20 matched `-dev`/`-prod` pairs plus 4 unsuffixed helpers (`create-app-rt-role-sql`, `diag-sql`, `seed-full-sql`, `seed-superadmin-sql`). |
| Cloud Deploy | 14 delivery pipelines, one per service per environment. Execution identity `clouddeploy-prod-deployer@`; **no `clouddeploy-dev-deployer` exists**. |
| Cloud Scheduler | **No jobs exist in any location.** (`asia-south2` is not a Cloud Scheduler region.) |
| Observability | 25 alert policies, 8 uptime checks (2 dev, 6 prod), 12 log-based metrics (7 dev, 5 prod — prod lacks `async_scheduler_failure_count` and `trace_export_failure_count`), sink `custoking-compliance-india` → regional `asia-south2` log bucket at 180d. **Exactly one notification channel** (a single operator email). No backup channel. |
| BigQuery | One dataset `billing_export`. |
| Terraform state | `gs://custoking-terraform-state` with prefixes `cicd`, `observability/dev`, `observability/prod`. |
| Human IAM | Three owners on the source project, one of whom is not an operator of the destination org. |
| MSG91 | `setup-msg91-static-egress.ps1` exists but **was never applied** — no Cloud Router, no NAT, no reserved external IP. There is therefore **no IP allowlist dependency**. Both environments run `notification_delivery_provider: logging` with `msg91_dry_run: "true"`. |

### 2.2 GitHub repository `custokingkr-dev/ims-v1`

- Environments `dev` (branch policy: `dev`) and `prod` (branch policy: `main`, required reviewers set).
- **Zero GitHub Actions secrets** at repository or environment level. Authentication is entirely WIF.
- No branch-protection rule and no rulesets on `main`; the only gate on production is the `prod`
  environment's required reviewers.
- 17 repository-level variables and 4 dev / 9 prod environment variables (section 6 lists the exact
  target values).
- The `dev` environment sets no `RELEASE_BUILDER_SERVICE_ACCOUNT`, `ROLLBACK_SERVICE_ACCOUNT`,
  `DEPLOYMENT_CONFIG_SERVICE_ACCOUNT`, or `RECOVERY_OPERATOR_SERVICE_ACCOUNT`, so all dev CI falls back
  to the shared repository-level `DEPLOY_SERVICE_ACCOUNT` (`github-actions-sa@`).

### 2.3 Workload Identity Federation

One pool `github-pool` / provider `github-provider` in the source project. The attribute condition pins
the immutable repository ID and owner ID and then allowlists exactly eight `(ref, workflow_ref)` pairs
covering `build-release`, `rollback`, `reconcile-deployment-config` (each on both `dev` and `main`),
plus `gcp-cost-controls` and `recovery-drill` (both `main` only).

### 2.4 Release ledger, captured 2026-08-18

All seven services run the **same digest in dev and prod**. Seven unique digests, one per service, are
the entire set that must exist in the destination registries. The digest values are recorded in
restricted evidence.

### 2.5 Data volumes — the migration is small

| Asset | Measured 2026-08-18 |
| --- | --- |
| Prod database, actual bytes used | **146 MB** (on a 10 GB PD_SSD with autoresize) |
| Prod student photos | **72 MB across 1,175 objects** |
| Dev student photos | 558 KB across 18 objects |
| `custoking-db-snapshots` | 269 KB, 2 objects |
| `custoking-scan-evidence` | 563 KB, 24 objects |

The entire production dataset is **under 250 MB**. Export, transfer, and import are minutes of work, not
hours. This substantially lowers the risk of the cutover window and means the duplicate-resource cost of
coexistence is driven almost entirely by Cloud SQL instance-hours, not by storage or transfer.

Prod Cloud SQL specifics that must be reproduced: `ZONAL` (no HA), 14 retained backups, PITR on with 7-day
transaction-log retention in Cloud Storage, backup window 20:30 in `asia-south2`, `sslMode:
ENCRYPTED_ONLY` with `requireSsl: false`, `ipv4Enabled: false`. Each instance has a generated service
account (`p<project-number>-<hash>@gcp-sa-cloud-sql`); the destination instances will get **new** ones,
which must be granted explicitly on the destination buckets before any export or import.

**Operational trap:** the dev database is `STOPPED`, so its databases and users cannot even be listed
without starting it. The dev migration must start it, and `gcp-cost-controls.yml` runs a scheduled stop
(`30 14 * * *`) that will otherwise stop it mid-migration. Suspend that schedule for the dev window.

### 2.6 Cloud Run jobs — missing from every prior inventory

Ten Cloud Run jobs exist in `asia-south2` and appear in no earlier migration document:
`ims-app-rt-dev`, `ims-app-rt-prod`, `ims-direct-service-smoke`, `ims-gateway-smoke-sql-dev`,
`ims-gateway-smoke-sql-prod`, `ims-q-dev`, `ims-scale-fixture-dev`, `ims-seed-dev`, `ims-seedfull-dev`,
`otel-probe-dev-20260813`.

`ims-app-rt-dev` / `ims-app-rt-prod` are not disposable — they provision the `app_rt` runtime database
role. Classify each job as recreate, re-run from its script, or retire, and record the decision. Several
are clearly one-off load-test or probe residue and should be retired rather than migrated.

### 2.7 Access model

Only `frontend` and `api-gateway` are public (`roles/run.invoker` → `allUsers`), in both environments.
The five backend services are private and invokable only by named service accounts: the gateway, identity,
operations and platform runtime identities, plus `direct-service-smoke@` and
`service-<project-number>@gcp-sa-monitoring-notification` — the authenticated uptime-check identity, whose
name embeds the project number and therefore **must be re-granted per destination project**.

Cloud Deploy has all 14 targets present with `requireApproval: false`. The **only** gate on a production
deploy is the `prod` GitHub environment's required reviewers.

---

## 3. Blockers

| # | Blocker | Consequence | To clear |
| --- | --- | --- | --- |
| B1 | Destination billing account inaccessible to the operating account | No destination budgets, no alert recipients, no spend visibility, no way to know whether it carries trial credit | Grant billing admin on it, or re-link both destination projects to the billing account already controlled |
| B2 | Source organization not visible to the operating account | Source org policies unreadable; eventual source decommissioning has no named owner | Identify and record the source-org holder before cutover |
| B3 | Cross-organization migration not formally approved | Runbook §4 assumed one organization | Record the decision explicitly (section 4.6) |

B1 must be cleared before any paid destination resource is created. B2 and B3 must be cleared before the
production cutover, not before dev work starts.

---

## 4. Decisions required before execution

These are genuine forks. Each is stated with a recommendation, but none should be treated as decided.

### 4.1 Artifact Registry topology — the critical one

`build-release.yml:340` computes one registry for both environments:
`$GCP_REGION-docker.pkg.dev/$GCP_PROJECT_ID/$ARTIFACT_REGISTRY_REPOSITORY`. Prod then requires a
`dev-approved-<sourceId>` tag to already exist there (`:351-362`), failing with *"No dev-approved image
exists … Deploy this source to dev successfully before production."* Prod never builds.

| Option | Mechanics | Trade-off |
| --- | --- | --- |
| **A. Shared registry (recommended)** | Introduce `ARTIFACT_REGISTRY_PROJECT_ID`, distinct from `GCP_PROJECT_ID`. Both environments read and write one registry. Grant `custoking-prod`'s Cloud Deploy deployer and release identity `artifactregistry.reader` on it cross-project. | Preserves build-once-promote exactly. One deliberate, narrow coupling between the two projects. Cheapest — no image copying, no egress. |
| **B. Per-project registries with promotion copy** | Each project owns a registry; a promotion step copies the digest dev→prod. | Full isolation, but adds a copy step, and pulling images to a GitHub runner is billed internet egress — the exact trap already recorded in the cost history. Copy must run inside `asia-south2` to avoid it. |
| C. Rebuild in prod | — | Rejected: destroys build-once-promote and the digest-equality guarantee. |

Recommendation: **A**. It keeps the release contract intact and costs nothing. Isolation of *runtime*
(data, IAM, billing, quotas) is what the split is for; a read-only image registry is a poor isolation target.

**The variable-scoping mechanism makes option A a one-line fix.** `build-images` (`:106`) declares **no**
`environment:`, so it resolves `vars.*` at the **repository** level. The deploy job (`:246`) declares
`environment: <target_env>`, so it resolves them at the **environment** level. Therefore:

- setting `GCP_PROJECT_ID` per environment cleanly retargets runtime — which is what we want — and
  simultaneously retargets the registry — which is what breaks prod;
- introducing `ARTIFACT_REGISTRY_PROJECT_ID` **at repository level only** makes every job, in both
  environments, resolve the same registry regardless of environment. No workflow restructuring needed
  beyond substituting that variable at `:340` and `:166`.

Note the same scoping applies to `WORKLOAD_IDENTITY_PROVIDER` and `RELEASE_BUILDER_SERVICE_ACCOUNT`:
`build-images` authenticates with the **repository-level** values. Since `build-images` only ever runs for
dev, the repository-level WIF provider must point at `custoking-dev`, and each environment must set its own.

**Cost evidence for this decision.** Measured over the seven days to 2026-08-18, Artifact Registry cost is
**95% egress, not storage**: ₹94.15/day on `Network Internet Egress AsiaPacific to AsiaPacific` versus
₹4.18/day on storage. Daily egress ran 9.65 GB (08-11), 28.16 GB (08-13) and 16.98 GB (08-14) before the
2026-08-16 verdict-cache fix, then **2.63 GB on 08-16 and 0 GB on 08-17** — the fix is bill-confirmed.
Option B would reintroduce a recurring egress line that was just eliminated, *unless* the copy executes
inside `asia-south2`, where GCP-to-GCP transfer is not billed as internet egress. Cross-organization does
not change that: the network path, not the org boundary, determines the charge. Option B is therefore
viable but only with that constraint enforced, and it buys isolation of an artifact store that does not
hold customer data.

### 4.2 Where the shared registry lives (only if A)

`custoking-prod` (prod-owned, dev reads), a third small `custoking-shared` project, or keep it in
`custoking`. Keeping it in `custoking` prolongs the source project's life indefinitely and contradicts
eventual decommissioning. Recommendation: **`custoking-prod`**, since prod is the environment whose
supply chain most needs to be authoritative.

### 4.3 Bucket naming

`custoking-student-photos-dev` / `-prod` cannot be recreated while the source holds them, and the source
must stay intact for rollback. New names are unavoidable. This is a **deploy-parameter** change, not a
code change — `STUDENT_PHOTO_BUCKET` is injected and read as `${STUDENT_PHOTO_BUCKET:}`. But the
`deploy/cloudrun/*.yaml` `from-param` comment currently derives the name as
`custoking-student-photos-${env}`, so it must become a first-class parameter.

Recommendation: `ck-student-photos-dev` / `ck-student-photos-prod` in the respective projects, and treat
the same question for `db-snapshots`, `scan-evidence`, `github-deploy-source`, and `terraform-state`.

### 4.4 Resource naming inside the new projects

The `-dev`/`-prod` suffix is redundant once the project encodes the environment, but removing it touches
every Cloud Deploy target, pipeline name, secret name, alert name, and several hardcoded strings.

Recommendation: **keep names byte-identical** (`custoking-db-dev` inside `custoking-dev`). It looks
redundant and it is worth it: it keeps `rollback.yml`, `build-release.yml`'s dev-database step, the
observability module's `custoking-db-<env>` derivation, and every secret name working unchanged.

### 4.5 Dev CI identities are now forced

`enable_dev_identities` is `false` today because dev shares `github-actions-sa@custoking`. That account
will not exist in `custoking-dev`. The migration therefore **forces** `enable_dev_identities = true`
(`github-release-dev`, `github-rollback-dev`, `github-config-dev`, `clouddeploy-dev-deployer`) and
`dev_release_service_account` must move to `github-release-dev@custoking-dev`. This closes a
long-standing gap rather than creating work.

### 4.6 Cross-organization approval, and the URL change

Two decisions to record explicitly: that two organizations is intended; and how users reach the new
production URL, given there is no domain mapping and the `.a.run.app` hostname will change. Options are
to accept a one-time URL change and communicate it, or to introduce a custom domain now — which is the
only approach that makes this cutover, and every future one, a DNS change instead of a user-visible break.

**The URLs are, however, predictable.** Every Cloud Run service publishes two hostnames: the legacy
`<service>-<hash>-<region-code>.a.run.app` and the deterministic
`<service>-<PROJECT_NUMBER>.<region>.run.app`. Because the destination project numbers are already known,
**all 14 destination URLs can be computed before anything is deployed.** That removes what would otherwise
be a bootstrap deadlock: the gateway carries 13 `*_UPSTREAM` variables and the frontend carries
`API_UPSTREAM`, all of which must be known at deploy time. Adopt the deterministic form in the Cloud Deploy
targets and the deadlock disappears; keep using the hash form and you must deploy, observe, re-render and
redeploy.

### 4.7 The Drive OAuth client is owned by the source project

**Verified, not assumed:** the client IDs behind `student-photo-import-drive-oauth-client-id-dev` and
`-prod` both carry the **source project's** number, so the OAuth client and its consent screen live in
`custoking`. The scope is *not* determinable from the repository — the client uses `UserCredentials`
(client id + secret + refresh token) against `https://www.googleapis.com/drive/v3/files`, and the granted
scope lives inside the consented refresh token, not in code.

| Option | Consequence |
| --- | --- |
| ~~Keep the client in `custoking`~~ | **NO LONGER AVAILABLE.** The decision to delete `custoking` removes this option: OAuth clients cannot be moved between projects, so the client dies with it. See `GCP-SOURCE-DELETION-CONTINUITY-2026-08-18.md` §4 — recreation is now a hard blocker on the critical path, not a choice. |
| Recreate in the destination organization | Requires a new consent screen, a new client, and a **fresh refresh token obtained through interactive consent**. If the consent screen is External and the granted scope is a restricted Drive scope, Google verification applies and can take weeks; if left in Testing mode, refresh tokens expire after 7 days. |

Resolve this **before** Phase 1, by reading the source project's OAuth consent screen to establish the
publishing status and the granted scope. It is the single item most likely to derail the schedule, and it
cannot be answered from the repository.

---

## 4A. What coexistence actually costs

Measured from the billing export, seven days to 2026-08-18. All figures gross INR; **net payable is
currently ₹0 because free-trial credit still offsets 100% of spend** (July gross ₹15,259, credits
−₹15,259; August-to-date gross ₹7,094, credits −₹7,094).

| Service | ₹/day | Note |
| --- | --- | --- |
| Cloud Run | 302.92 | CPU 103, requests 95, internet egress 91 — load-test inflated |
| Cloud SQL | 188.98 | **Small (prod) instance 103.87**, micro (dev) 7.39, plus vCPU/RAM/storage lines |
| Artifact Registry | 98.33 | 94.15 egress, 4.18 storage |
| Compute Engine | 10.95 | inter-zone transfer only; no VMs or disks exist |
| Secret Manager | 9.91 | version replica storage |
| Cloud Storage | 1.03 | |

Current burn is ~₹612/day, well above the ~₹146/day idle floor, because load testing is running. The
number that matters for this migration is the **marginal cost of a duplicate production database: about
₹104/day, roughly ₹3,120/month.** Duplicate Cloud Run at `minScale=0` is near-free when idle, and
duplicate storage is negligible at 250 MB.

Two consequences:

1. Coexistence is affordable in absolute terms, but it is **not free once trial credit ends**. Keep the
   duplicate-prod window short and give it an explicit end date, as the superseded runbook's §11 required.
2. Because the destination billing account is inaccessible (B1), **none of this is measurable on the
   destination side**. That is the practical reason B1 blocks building, not merely a governance point.

---

## 5. Work breakdown — GCP infrastructure

Per destination project, in dependency order. Dev first, in full, as the rehearsal.

1. **Project baseline** — labels, budgets and alert recipients (blocked on B1), billing export to a
   BigQuery dataset in the destination, audit log configuration, org policy baseline confirmation.
2. **APIs** — exactly 16 are enabled on the source and missing from `custoking-dev`:
   `cloudbuild`, `clouddeploy`, `cloudresourcemanager`, `cloudscheduler`, `compute`, `deploymentmanager`,
   `drive`, `iam`, `iamcredentials`, `oslogin`, `privilegedaccessmanager`, `recommender`, `secretmanager`,
   `servicenetworking`, `sts`, `vpcaccess` (all `.googleapis.com`). Enable deliberately and record the
   service agents created. Several are almost certainly not needed — no Cloud Scheduler job exists, no
   VPC connector exists (Direct VPC egress is used), and `deploymentmanager`/`oslogin`/
   `privilegedaccessmanager` look like defaults rather than dependencies. Justify each rather than
   copying the source list wholesale.
3. **Networking** — VPC, `asia-south2` subnet with Private Google Access, and a PSA range for Cloud SQL.
   The source uses `10.92.0.0/16` for PSA and `10.190.0.0/20` for the subnet; ranges may be reused since
   the projects do not peer, but must be recorded. Cloud Run uses Direct VPC egress, so **no connector
   is needed**.
4. **Identities** — 7 runtime SAs, Cloud Deploy deployer, GitHub release/rollback/config SAs, cost
   controller, recovery operator, and the Pub/Sub push identities. Least privilege per resource; do not
   reproduce the source's broad default-compute bindings.
5. **WIF** — new pool and provider per destination project. The attribute condition must be rebuilt with
   destination-appropriate ref scoping: `custoking-dev`'s provider must trust the `dev` branch for
   build/rollback/config **and the `main` branch for `gcp-cost-controls.yml`**, which is scheduled on
   `main` but acts on dev resources. Run a positive test and a wrong-ref negative test for each.
6. **Artifact Registry** — per section 4.1/4.2. Seed only the seven live digests; do not copy 244 images.
   Any copy must execute inside `asia-south2`, never through a GitHub-hosted runner.
7. **Buckets** — per section 4.3, with the source's lifecycle rules, uniform access, and the source's
   `custoking_cloudbuild` US bucket classified before anything is copied.
8. **Cloud SQL** — POSTGRES_16, matching tiers and `max_connections=200`, private IP, deletion
   protection. Dev stays the stopped-on-idle cheapest tier. Prod tier changes are a separate decision.
9. **Secrets** — recreate all 44 names with per-service IAM; supply values through the approved
   secret-transfer ceremony, never through files, logs, or shell history.
10. **Pub/Sub** — recreate topics, subscriptions, DLQs, and OIDC push identities in a paused state with
    destination Cloud Run audiences. **This is the moment to close the prod gaps** identified in 2.1:
    prod reporting DLQ, prod notification subscription, and `ims-notification-push-prod`.
11. **Cloud Deploy** — 7 targets and 7 pipelines per environment.
12. **Observability** — 25 alert policies, 8 uptime checks, 12 log metrics, the compliance sink and
    180-day regional bucket, and **at least two notification channels** (the source has one, which fails
    the runbook's own primary-and-backup requirement).
13. **Terraform state** — a new state bucket per destination and separate backend prefixes.

---

## 6. Work breakdown — GitHub

### 6.1 Variables

Repository-level variables that are now **environment-specific** and must move to the `dev` and `prod`
environments (or be renamed per environment):

`GCP_PROJECT_ID`, `WORKLOAD_IDENTITY_PROVIDER`, `DEPLOY_SERVICE_ACCOUNT`, `DEV_CLOUDSQL_INSTANCE`,
`DEV_GCP_PROJECT_ID`, `DEV_COST_WORKLOAD_IDENTITY_PROVIDER`, `DEV_COST_CONTROLLER_SERVICE_ACCOUNT`,
`COST_CONTROLLER_SERVICE_ACCOUNT`, `DIRECT_SMOKE_SERVICE_ACCOUNT`, `CLOUD_BUILD_SOURCE_STAGING_DIR`,
`CLOUD_DEPLOY_SOURCE_STAGING_DIR`, `DB_HOST`, `DEV_DB_HOST`, `PROD_DB_HOST`.

New variables required:

- `ARTIFACT_REGISTRY_PROJECT_ID` (section 4.1) — otherwise prod promotion breaks.
- `STUDENT_PHOTO_BUCKET` per environment (section 4.3) — the name is no longer derivable from `${env}`.
- `RELEASE_BUILDER_SERVICE_ACCOUNT`, `ROLLBACK_SERVICE_ACCOUNT`, `DEPLOYMENT_CONFIG_SERVICE_ACCOUNT` on
  the **dev** environment (section 4.5).

`DB_HOST` values change: destination Cloud SQL will not receive `10.92.0.4` / `10.92.0.5`.
`DB_NAME` (`custoking_dev` / `custoking_prod`) can stay if section 4.4 is accepted.

### 6.2 Note on the fallbacks

Several workflow `env:` blocks default to the source project when a variable is unset — for example
`GCP_PROJECT_ID: ${{ vars.GCP_PROJECT_ID || 'custoking' }}`. During migration these fallbacks are
dangerous: a missing variable silently retargets a workflow at the *old live project* instead of failing.
Every `|| 'custoking'` and `|| 'custoking-scan-evidence'` fallback should become a hard failure before
cutover begins.

### 6.3 Environments and protection

Branch policies (`dev`→`dev`, `main`→`prod`) and the prod required reviewers carry over unchanged. Worth
resolving separately: `main` has no branch protection and no ruleset, so the environment reviewers are
the only production gate.

---

## 7. Work breakdown — repository code changes

Application code needs **no changes**. The changes are confined to deployment machinery.

| File | Change | Why |
| --- | --- | --- |
| `.github/workflows/build-release.yml:340` | Registry must use `ARTIFACT_REGISTRY_PROJECT_ID`, not `GCP_PROJECT_ID` | Otherwise prod promotion fails at `:351-362` |
| `.github/workflows/build-release.yml:35,37,43` | Remove `custoking` fallbacks | Prevents silent retarget at the old project |
| `.github/workflows/build-release.yml:313,324,1099` | `custoking-db-dev` hardcoded | Only safe if section 4.4 keeps the name; otherwise parameterize |
| `.github/workflows/recovery-drill.yml:49,52` | `-ProjectId custoking`, `-SourceInstance custoking-db-prod` hardcoded | Must resolve to the prod project |
| `.github/workflows/rollback.yml:43,90,149` | `custoking` fallback; service/pipeline names built as `custoking-$service-<env>` | Safe only under section 4.4 |
| `.github/workflows/reconcile-deployment-config.yml:21` | `custoking` fallback | Same as above |
| `.github/workflows/gcp-cost-controls.yml:91` | Billing export table default embeds the **source billing account ID** | Must repoint to the destination billing export (blocked on B1) |
| `scripts/render-clouddeploy-targets.ps1:50,62` | Validation regexes hardcode `@custoking.iam.gserviceaccount.com` | **Will reject every rendered destination target** |
| `deploy/clouddeploy/targets-dev.yaml`, `targets-prod.yaml` | 84 `custoking` references each: `project_id`, all 7 runtime SAs, the Cloud Deploy execution SA, `run.location`, and **all 7 hardcoded `*-l7mhms5c2a-em.a.run.app` service URLs** | The URL hash is project-specific; these are currently not placeholders and the render script does not substitute them |
| `deploy/clouddeploy/delivery-pipelines.yaml` | 28 references | Pipeline/target naming |
| `deploy/clouddeploy/targets-stage.yaml` | 76 references | Unused (stage unsupported) — decide whether to migrate or delete |
| `deploy/cloudrun/*.yaml` (7 files) | `from-param` derivations for `STUDENT_PHOTO_BUCKET`, project IDs, and the default `305630109861-compute@` service account | Bucket name no longer derivable from `${env}` |
| `infra/terraform/cicd/variables.tf` | Defaults for `project_id`, `project_number`, AR repo, all four bucket names, 14 runtime SA emails, `dev_release_service_account`, `enable_dev_identities` | Per-project tfvars |
| `infra/terraform/cicd/versions.tf` | Backend bucket documented as `custoking-terraform-state` | New state bucket and prefix per project |
| `deploy/gcp/observability/variables.tf` + 8 `.tf` files | `project` default, `custoking-db-<env>` derivation, student-photo bucket derivation | Per-project tfvars |
| `deploy/skaffold.yaml`, `deploy/gcp/direct-service-smoke-job.template.yaml` | Project references | Deploy tooling |
| `scripts/*.ps1` (~15 with live GCP coupling) | `recovery-drill`, cost, smoke, catalog and audit scripts take `-ProjectId` but several default to `custoking` | Repoint per environment |

**Deliberately unchanged:** all of `services/**` and `frontend/**`. The only `custoking` strings there
are the Java package `com.custoking.ims`, `spring.application.name`, a frontend brand label, a
localStorage key, and a business route named `approve-custoking`. None is a GCP reference.

---

## 8. Sequencing

**Phase 0 — unblock.** Clear B1. Record B3. Decide 4.1–4.4.

**Phase 1 — build `custoking-dev`.** Sections 5 and 6 for dev only. Repository changes land behind
variables so the source project keeps deploying from `dev` until the moment of switchover.

**Phase 2 — dev data and rehearsal.** Execute the companion data-integrity plan for dev: ledgers,
export/import, object manifest with CRC32C comparison, photo-reference and Drive reconciliation, bounded
event drain. Run the full CI, gateway, tenant-isolation, student lifecycle, attendance, fees, reporting,
recovery, tracing and alert-receipt suites. Prove the cost-control workflow authenticates from `main`
into the new dev project and stops the new dev SQL instance. **Exercise rollback to source dev.** Soak.

**Phase 3 — production preparation.** Build `custoking-prod` from the accepted dev procedure. Copy the
exact production digests from the release ledger. Complete an isolated import and full integrity
comparison before the window. Prove destination backups, PITR, deletion protection, and an independent
restore drill. Prove prod WIF without changing traffic. Add the missing prod Pub/Sub DLQ and notification
subscription. Configure at least two notification channels and fire a test alert through both.

**Phase 4 — production cutover.** Write freeze, drain or bound events, final export/import, full ledger
comparison, then enable destination traffic. Because there is no DNS layer, "enable traffic" means
publishing the new URLs — decide in 4.6 whether a domain is introduced first.

**Phase 5 — stabilization.** Source stays intact and recoverable. Decommissioning is a separate approved
change requiring B2 resolved.

---

## 9. Verification gates

Every gate needs a named owner, a timestamp, a restricted-evidence reference, and an independent verifier.
Carry forward all ten gates from the superseded runbook's section 12, and add five that live discovery
showed were missing:

| Added gate | Reason |
| --- | --- |
| A prod release promotes successfully end-to-end in the destination topology | Section 4.1 is the highest-risk change and cannot be proved by inspection |
| Prod Pub/Sub reporting DLQ and notification subscription exist and are exercised | Both absent in the source today |
| At least two notification channels exist and both received a test alert | The source has exactly one |
| The Drive OAuth decision in 4.7 is made and evidenced, and a photo import runs end-to-end in the destination | **Now verified as a real tether:** the client belongs to the source project. Whichever option is chosen has a consequence that must be accepted deliberately. |
| Every Cloud Run job in 2.6 is classified recreate / re-run / retire, and `ims-app-rt-*` has run against the destination databases | The `app_rt` runtime role does not exist until that job runs; services will fail to authenticate without it |
| Uptime-check invoker IAM is re-granted to the destination monitoring service agent | Its name embeds the project number, so the source grant does not carry over and authenticated uptime checks silently fail |
| `gcp-cost-controls.yml`'s scheduled stop is suspended for the duration of each migration window | Otherwise it stops the dev database mid-migration (2.5) |
| Every `|| 'custoking'` fallback is removed or converted to a hard failure | Prevents a silent retarget at the live source project |

---

## 10. Rollback

Unchanged in principle from the superseded runbook: route back to the intact source, never reconstruct
deleted source resources, reconcile any destination-only writes explicitly, preserve both environments
until incident review. Two additions from live discovery:

- Rollback is **not** a DNS change, because no domain mapping exists. It is a republication of the source
  URLs, which is slower and more visible. Section 4.6 directly determines rollback quality.
- If section 4.1 option A is adopted and the shared registry lives in `custoking-prod`, then rolling back
  to the source dev environment still depends on a destination project. Either keep the source registry
  populated through stabilization, or accept that dependency knowingly.
