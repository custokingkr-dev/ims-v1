# Security and Governance Planned Changes

> Historical workstream plan and evidence. Production runtime identities and production reporting OIDC
> have since been completed. Current IAM drift and governance gates are in
> [../REMAINING-WORK-2026-08-12.md](../REMAINING-WORK-2026-08-12.md).

Date: 2026-08-11 IST
Scope: repository controls, GitHub governance, Google Cloud runtime/deployment IAM, Pub/Sub push authentication, secret lifecycle, dependency and container security, and public ingress
Live project: `custoking` (`305630109861`)
Repository: `custokingkr-dev/ims-v1`

## Safety and Evidence Boundary

This workstream used read-only GitHub and Google Cloud inspection, guarded GitHub security workflows,
and the approved dev-only release path. Final commit `2eec4690` deployed school-core, frontend and gateway
to dev through run `31509672530`; production, GitHub settings, IAM, service accounts and secrets were not
mutated. Secret payloads were never read by the repository audit tool and no secret value is recorded here.
A Dependabot-enable request was attempted but GitHub rejected it
with `404` because the active principal is not an administrator; no setting changed.

The final detailed machine-readable audit is generated locally at
`artifacts/security-governance/readiness-final-20260811.json`. The artifact is intentionally ignored by
Git. Run the same redacted audit with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  scripts/audit-security-governance-readiness.ps1 `
  -IncludeSecretVersionMetadata `
  -OutputJson artifacts/security-governance-readiness.json
```

The evidence below was collected and rechecked on 2026-08-11 IST. A `404` from the classic branch-protection
endpoint plus an empty ruleset inventory is treated as “no visible protection.” Dependabot and
secret-scanning alert APIs originally returned `403` and `404`. A later repository-permission check
proved that the operator has `push` and `triage`, but not `admin`; the Dependabot response explicitly
states that alerts are disabled. An attempt to enable them through GitHub's documented endpoint
returned `404` because the operator is not an administrator, so no repository setting changed.
Secret-scanning status and alert count remain unknown rather than assumed to be zero.

## Executive Decision

Production security/governance is **not approved for promotion yet**. Ten independently verified
gates remain:

1. Seven production Cloud Run services still run as the default compute service account.
2. Eight of nine Cloud Run jobs still run as the default compute service account.
3. All 14 Cloud Deploy targets use the default compute service account for render/deploy execution.
4. The production reporting push endpoint still contains a credential-bearing query string.
5. Production reporting OIDC still uses the default compute service account.
6. GitHub WIF trust is repository-name-only, without immutable repository ID, ref, or workflow
   restriction.
7. `main` has no visible classic branch protection or ruleset.
8. `dev` has no visible classic branch protection or ruleset.
9. The default `main` branch has 51 open HIGH container findings (zero CRITICAL) across 296 Trivy alerts;
   `dev` is separately clean at HIGH/CRITICAL after the completed remediation and scan.
10. Production Cloud SQL still allows unencrypted connections; the five database-backed production
    services do not explicitly require TLS, one production SQL smoke job explicitly disables it,
    and fresh production database-side session evidence is absent. Dev has passed the separate
    application-client transport gate documented in section 4.5, but its 24-hour observation is
    still open and it does not close the production gate.

The CodeQL workflow is active and final run
[`31509672266`](https://github.com/custokingkr-dev/ims-v1/actions/runs/31509672266) passed Java/Kotlin and
JavaScript/TypeScript on dev commit `2eec4690`. GitHub's ref-scoped alert API reports zero open CodeQL
alerts on `refs/heads/dev`. Stable-category container run
[`31509695990`](https://github.com/custokingkr-dev/ims-v1/actions/runs/31509695990) passed all seven images;
the dev inventory is 239 Trivy alerts, all MEDIUM/LOW (209/30) and zero HIGH/CRITICAL. This branch-specific
result does not clear the stale default-branch inventory or its production promotion gate.

## 1. Runtime and Deployment IAM

### 1.1 Verified live state

All seven dev services use dedicated runtime identities. All seven prod services use:

```text
305630109861-compute@developer.gserviceaccount.com
```

Project roles currently granted to that default identity are:

- `roles/artifactregistry.writer`
- `roles/cloudbuild.builds.builder`
- `roles/cloudtrace.agent`
- `roles/iam.serviceAccountUser`
- `roles/logging.logWriter`
- `roles/run.admin`
- `roles/secretmanager.secretAccessor`
- `roles/serviceusage.serviceUsageConsumer`
- `roles/telemetry.tracesWriter`

This account is not only a runtime identity. Live inventory found these additional dependencies:

- all 14 Cloud Deploy targets use it for `RENDER`, `DEPLOY`, `VERIFY`, `PREDEPLOY`, `POSTDEPLOY`,
  and `ANALYSIS`;
- `ims-app-rt-dev`, `ims-app-rt-prod`, both gateway SQL smoke jobs, `ims-q-dev`,
  `ims-scale-fixture-dev`, `ims-seed-dev`, and `ims-seedfull-dev` use it;
- production reporting Pub/Sub uses it to mint its OIDC token;
- project-wide `roles/run.admin` currently supplies implicit service invocation that will disappear
  when that role is removed.

Removing any default-compute role before migrating these consumers can break rollouts, schema
provisioning, seed/load jobs, authenticated service calls, or reporting delivery. Google documents
that Cloud Deploy defaults to the compute identity and recommends an alternate execution service
account; a Cloud Run execution identity needs Cloud Deploy Job Runner plus runtime-specific access.
See [Cloud Deploy service accounts](https://docs.cloud.google.com/deploy/docs/cloud-deploy-service-account)
and [Cloud Deploy execution environments](https://docs.cloud.google.com/deploy/docs/execution-environment).

### 1.2 Production runtime preparation already available

`scripts/configure-runtime-service-accounts.ps1` defines:

- seven per-service production identities;
- 44 resource-level Secret Manager accessor bindings across their consumers;
- 12 minimal tracing/service-usage project grants;
- nine service-scoped Cloud Run invoker edges;
- three reporting-topic publishers;
- one student-photo bucket object-user assignment;
- one school-core self-signing assignment.

Its live read-only production preflight passed with zero missing secrets, topics, buckets, or Cloud
Run services. Production dry-run no longer requires `-AllowProduction`; only an actual `-Apply`
requires both `-Apply` and `-AllowProduction`.

### 1.3 Required execution order

1. Run the production dry-run and archive its JSON.
2. Apply the per-service IAM matrix, but do not change production traffic yet.
3. Create distinct Cloud Deploy execution identities for dev and prod.
4. Grant each execution identity only project-level `roles/clouddeploy.jobRunner` and
   `roles/run.developer`. Grant `roles/iam.serviceAccountUser` on the seven matching runtime
   identities at each service-account resource, not at project scope. Grant Artifact Registry
   Reader only on the `custoking` repository. The repository Terraform now models these exact
   boundaries and does not grant Secret Manager Viewer or Logging Viewer.
5. Apply the checked-in explicit `RENDER`/`DEPLOY` `executionConfigs` for all 14 targets; never rely
   on the default execution identity. The source renderer rejects default-compute fallback.
6. Create dedicated identities for the eight jobs that still use default compute. Give each only
   its required Cloud SQL, secret, storage, and invocation permissions.
7. Apply the checked-in prod target runtime parameters only after the seven dedicated identities
   and their resource permissions exist.
8. Deploy one service at a time through the existing canary path and run authenticated smokes.
9. Migrate reporting push as described below.
10. Observe one school-day peak with no `PERMISSION_DENIED` events.
11. Remove default-compute roles one at a time. Re-run rollout, job, reporting, and service-call
    tests after each removal.
12. Retain logging/tracing roles only if Cloud Asset/IAM evidence proves a remaining workload uses
    them.

### 1.3.1 Mandatory two-commit cutover boundary

Commit A is the deployable application/dependency change set. It must not contain a file that makes
`resolve-affected-ci-targets.ps1` select deployment-configuration reconciliation before the new dev
execution identity exists.

Commit B is source-complete but control-plane-gated. Keep these deployment-IAM files together on
the feature branch/PR and do not merge or apply them yet:

- `infra/terraform/cicd/main.tf`
- `infra/terraform/cicd/variables.tf`
- `infra/terraform/cicd/outputs.tf`
- `infra/terraform/cicd/README.md`
- `deploy/clouddeploy/targets-dev.yaml`
- `deploy/clouddeploy/targets-prod.yaml`
- `scripts/render-clouddeploy-targets.ps1`
- `scripts/render-clouddeploy-pipelines.ps1`
- `scripts/resolve-affected-ci-targets.ps1`
- `.github/workflows/_detect-changes.yml`
- `.github/workflows/build-release.yml`
- `.github/workflows/reconcile-deployment-config.yml`
- `.github/workflows/rollback.yml`
- `.github/workflows/recovery-drill.yml`
- `scripts/invoke-cloudsql-restore-drill.ps1`
- `deploy/gcp/recovery-bucket-iam-operator-role.yaml`
- `scripts/configure-security-governance-controls.ps1`
- `scripts/audit-security-governance-controls.ps1`
- this security/governance change document

The pin-only bootstrap commit `7470be781b5fbfcfcff0cb43944cecf68b7f427c` already delivered the
reviewed immutable action references to `dev` without changing workflow behaviour. Consequently,
Commit B contains only the five workflow files above whose deployment-control behaviour changes;
the other eight workflow files are no longer part of the pending cutover diff.

The target files and renderer are invalid as a live cutover without the Terraform identities and
IAM bindings; the Terraform module is invalid against current live prerequisites until the seven
prod runtime identities exist. Source now separates configuration reconciliation from release
creation: target/pipeline/renderer-only changes produce a zero-service matrix, the automatic
build/release jobs remain skipped, and the workflow directs an operator to the manual,
environment-gated configuration workflow. That workflow uses a separate dev/prod identity, applies
only the seven targets and seven pipelines selected for its environment, and creates no release or
rollout. A target plus one service change
retains exactly that one service in detection but still blocks automatic release until configuration
reconciliation is complete; the service must then be released in a separate service-specific
commit. This removes the prior fleet-release hazard at source.

Commit B can move only after branch protection, a reviewed/import-complete Terraform plan,
production runtime-IAM preparation, live dev execution and configuration identity creation, exact
WIF/variable canaries, a successful manual dev configuration reconciliation, and a separate
one-service dev release canary. Production remains a later, explicitly approved cutover. None of
those live changes occurred in this workstream.

### 1.4 Acceptance gate

- zero prod Cloud Run services on default compute;
- zero Cloud Deploy targets on default compute;
- zero active Cloud Run jobs on default compute unless a documented exception exists;
- exact service-account-level `actAs` edges, not project-wide `roles/iam.serviceAccountUser`;
- no project-wide `roles/run.admin` or `roles/secretmanager.secretAccessor` on default compute;
- canary, rollback, recovery, outbox, Pub/Sub, photo import, schema, and authenticated gateway tests
  all pass.

### 1.5 Rollback

Keep the old identities and bindings during the observation window. If a canary fails, return
traffic to the prior revision, restore the previous target execution account, and re-run the failed
operation. Do not remove a newly created identity or the legacy binding until logs prove there are
no callers. The IAM policy snapshot and exact failed permission are mandatory rollback evidence.

Stage is deliberately unsupported in the renderer and Terraform module. There is no stage target
manifest, runtime matrix, or protected GitHub Environment; no dormant stage deploy identity is
created.

## 2. Production Reporting Pub/Sub OIDC

### 2.1 Verified live state

`ims-reporting-service-push-prod` is active, but:

- its push URL has a query string;
- its OIDC caller is the default compute service account;
- `ims-reporting-push-prod@custoking.iam.gserviceaccount.com` does not yet exist;
- prod platform configuration still requires the application shared token.

Dev already uses the desired dedicated OIDC shape. Google requires the push identity to have Cloud
Run Invoker and the Pub/Sub service agent to have Service Account Token Creator on the push
identity. See [authenticated Pub/Sub push](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions)
and [Cloud Run service-to-service authentication](https://docs.cloud.google.com/run/docs/authenticating/service-to-service).

### 2.2 Planned cutover

1. Deploy platform code/config that permits Cloud Run IAM-only reporting push in prod, while the
   existing application-token mode remains available for rollback.
2. Run `scripts/configure-reporting-pubsub-push-oidc.ps1 -Environment prod` as a dry-run.
3. Confirm the desired audience exactly equals the prod platform service URL and the desired push
   endpoint contains no query string.
4. During the approved window, apply with both `-Apply` and `-AllowProduction`.
5. Publish a harmless unknown event type and require an authenticated `204` request with no query
   string in Cloud Run request logs.
6. Verify subscription backlog and oldest unacked age return to normal.
7. Rotate `reporting-read-token-prod` only after all non-Pub/Sub consumers are enumerated and can
   move together.

### 2.3 Acceptance and rollback

Acceptance requires the dedicated identity, exact audience, no query, `204` delivery, no backlog,
and no new `401`/`403`. If delivery fails, reconstruct the old endpoint from Secret Manager without
printing the token, restore the old push config, and restore shared-token enforcement. Never paste
the rollback URL into logs or evidence.

Dedicated service accounts and IAM bindings have no direct usage charge.

## 3. GitHub Branch Governance

### 3.1 Verified live state

- repository visibility: public;
- default branch: `main`;
- rulesets: zero;
- no visible classic protection on `main` or `dev`;
- prod Environment: two required reviewers, admin bypass disabled, and only `main` allowed;
- dev Environment: no protection rules and no deployment branch restriction;
- active operator permissions: `push` and `triage`, but not `admin` or `maintain`;
- at original inspection, action references used mutable tags; the gated source change now pins all
  external actions to the reviewed immutable commits documented in section 6.4.

The CI `summary` job is stable and aggregates affected-service tests, Docker builds, and the secret
scan. The new CodeQL jobs have unique names. GitHub documents that branch protection can require
reviews and successful status checks, and that duplicate job names can make required checks
ambiguous. See [managing protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)
and [protected branch behavior](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).

### 3.2 Prepared guarded change

`scripts/configure-security-governance-controls.ps1` is dry-run by default. Branch protection,
Environment policy, Dependabot settings, and WIF each have an independent apply switch; every
external mutation also requires `-AllowExternalMutation`. `-ApplyGitHub` remains a compatibility
alias for branch protection plus Environment policy. Its proposed policy for both branches is:

- pull requests required with one approval;
- stale reviews dismissed;
- approval from someone other than the last pusher;
- admins included;
- conversations resolved;
- force pushes and deletion disabled;
- strict required checks:
  - `summary`;
  - `analyze (java-kotlin)`;
  - `analyze (javascript-typescript)`;
- dev Environment restricted to `main` and `dev`. `main` must remain allowed because GitHub runs
  scheduled workflows only from the default branch and `gcp-cost-controls.yml` uses the dev
  Environment. A `dev`-only rule would break the database start/stop schedule.

Run the new workflows successfully once before applying protection, then verify the exact check
names from the commit checks API. The two CodeQL names have been confirmed on `dev`; `summary` has
not had a fresh PR execution since its corrected aggregate failure logic was added, so branch
protection remains postponed until that PR evidence exists and the current integration push is
finished. All external actions are now source-pinned to reviewed commits with readable version
comments; let Dependabot propose future pin updates, but require the same tag-object/commit review.

### 3.3 Rollback

If a check name is wrong, update only the required-check list; do not disable review/force-push
protection. GitHub settings are an external admin operation and must be captured before/after as
JSON. A break-glass bypass must be time-bound and recorded.

## 4. Workload Identity Federation and GitHub Deployment Identity

### 4.1 Verified live state

The WIF provider is active. Its condition is only:

```text
assertion.repository=='custokingkr-dev/ims-v1'
```

It maps only `google.subject` and `attribute.repository`. It does not restrict immutable repository
ID (`1207086249`), owner ID (`274906704`), branch/ref, workflow file, or environment.

`github-actions-sa@custoking.iam.gserviceaccount.com` has these project roles:

- `roles/artifactregistry.writer`
- `roles/cloudbuild.builds.editor`
- `roles/clouddeploy.admin`
- `roles/cloudsql.editor`
- `roles/iam.serviceAccountUser`
- `roles/logging.viewer`
- `roles/run.developer`
- `roles/secretmanager.viewer`
- `roles/serviceusage.serviceUsageConsumer`
- `roles/storage.admin`

The repository custom role file exists, but `githubDeployRuntimeOperator` is not live. The current
identity is therefore both broadly trusted and broadly authorized.

Google recommends restricting WIF principal sets using attributes and conditions. GitHub exposes
immutable repository IDs plus `ref` and `workflow_ref` claims for cloud trust policies. See
[Google WIF attributes and conditions](https://docs.cloud.google.com/iam/docs/workload-identity-federation),
[Google's WIF best practices](https://docs.cloud.google.com/iam/docs/best-practices-for-using-workload-identity-federation),
and [GitHub OIDC claims](https://docs.github.com/en/actions/reference/security/oidc).

A capped sample of the 1,000 most recent 30-day audit-log method events for
`github-actions-sa` showed 181 `iam.serviceAccounts.actAs`, 168 Cloud Deploy pipeline updates, 126
rollout creations, 126 release creations, 103 access-token generations, 84 target updates, 71
Artifact Registry tag creations, 63 rollout advances, 55 Cloud Run service replacements, 22 Cloud
SQL instance updates, and one Artifact Registry tag deletion. This proves the current identity is
actively broad; because the query was capped, it does not prove that an unobserved permission is
unused.

### 4.2 Prepared guarded change

The governance script proposes a condition requiring:

- repository ID `1207086249`;
- owner ID `274906704`;
- exact coupled `ref`/`workflow_ref` pairs for `build-release.yml`, `rollback.yml`,
  `reconcile-deployment-config.yml`, `gcp-cost-controls.yml`, and `recovery-drill.yml` at their
  approved branches.

The dry-run now executes nine deterministic claim tests: three allowed cases and denials for a
wrong repository ID, wrong owner ID, feature ref, unlisted workflow, and the cost-control workflow
from the wrong branch, plus a deliberately mismatched otherwise-valid `ref` and `workflow_ref`.
These are local policy-equivalence tests; an actual GitHub-issued OIDC token
must still prove one allowed dev authentication after the provider change.

It also executes twelve service-account-scope tests: four exact allowed identities and eight
denials. These prove that dev release, rollback, and configuration workflow refs cannot select the
corresponding prod identities (and the inverse), and that cost control and recovery cannot select
their main-only identities from dev. These tests model the service-account binding after provider
admission; they are not a substitute for a GitHub-issued OIDC canary.

`infra/terraform/cicd` now maps immutable repository/owner IDs plus `ref`, `workflow_ref`, and an
extracted `workflow_file`, and carries the same exact coupled claim allowlist. Service-account
impersonation binds to the complete `attribute.workflow_ref`: dev/prod release, dev/prod rollback,
dev/prod configuration reconciliation, main-only cost control, and main-only recovery have separate
scopes. The recovery account already exists live and must be imported before plan/apply; the other
seven GitHub identities are new. Terraform also models the recovery account's existing Cloud SQL Admin/custom
bucket-policy binding, while the custom role source adds only exact-object get/delete. It does not
silently remove the live Object Admin binding; that removal
requires a successful cleanup drill. The previous repository-name-only source and shared
release/cost binding would have reintroduced excessive trust and have been removed.

The dev release identity retains Cloud SQL Editor because the deploy workflow starts a stopped dev
database, Cloud Run Developer plus resource-scoped `actAs` for direct dev deployment, and
resource-scoped `actAs` only on the dev Cloud Deploy execution account. The prod release identity
can act only as the prod execution account and has Cloud Run Viewer for release verification and
gateway smoke discovery. Both normal release identities have Cloud Deploy Releaser, not Operator.
Configuration reconciliation is a manual, environment-gated workflow with separate dev/prod
identities. Each reconciler has a custom role containing target/pipeline create/get/list/update and
read-only location/operation polling only, and can act only as its matching execution account. The
role excludes releases, rollouts, deletes, tag changes, and IAM-policy changes; the workflow applies
only its environment-filtered targets/pipelines. The initial create permission is project-scoped, so
resource-level target/pipeline IAM should replace that bootstrap binding after all resources exist.
Google documents resource-specific Cloud Deploy IAM for both resource types in
[Cloud Deploy IAM restrictions](https://docs.cloud.google.com/deploy/docs/securing/iam). Dev/prod
rollback identities are also split: dev can update Cloud Run traffic, while
prod receives Cloud Deploy Operator, Cloud Run Viewer, and `actAs` only on the prod execution
account. A pre-auth workflow check additionally couples dev to `refs/heads/dev` and prod to
`refs/heads/main`. At inspection time, the live GitHub variables
`DEPLOY_SERVICE_ACCOUNT` and `COST_CONTROLLER_SERVICE_ACCOUNT` both still point to the legacy
`github-actions-sa`; source preparation is not a live migration.

WIF changes require both `-ApplyWorkloadIdentity` and `-AllowExternalMutation`. Branch protection
must be applied first. After that interim restriction is proven, provision these source-defined
identities:

- dev build/release operator;
- prod release operator protected by the prod Environment;
- dev rollback operator;
- prod rollback operator protected by the prod Environment;
- dev deployment-configuration reconciler;
- prod deployment-configuration reconciler protected by the prod Environment;
- cost-control operator;
- recovery operator (already separately present).

Normal release creation should use `roles/clouddeploy.releaser`, not project-wide Cloud Deploy
Admin. Configuration reconciliation should use a distinct reviewed path. `actAs` must be granted
only on the intended Cloud Deploy execution identities. Remove obsolete Cloud Build Editor and
replace Storage Admin with bucket/resource-level access proven by audit logs.

### 4.3 Acceptance and rollback

Test one dev authentication and one approved prod authentication before removing the old trust.
Confirm an unlisted workflow and a feature-branch token are denied. Keep the former WIF provider
condition in a restricted evidence file for emergency rollback; restoring repository-only trust is
a security regression and requires incident-level approval.

### 4.4 Recovery workflow identity and data boundary

The live `custoking-recovery-operator` has project-level Cloud SQL Admin, a custom three-permission
bucket-IAM operator role on `custoking-db-snapshots`, bucket Object Admin, and the old mutable
repository-name WIF member. Terraform now imports that existing identity and Cloud SQL/custom-role
bindings, adds only the exact `recovery-drill.yml` WIF member, and does not model the broad Object
Admin grant. The custom role source adds only `storage.objects.get` and
`storage.objects.delete` to its bucket-policy permissions. Its Terraform binding permits bucket
policy operations on the validation bucket and object get/delete only under `recovery-drills/`.
The temporary clone receives Storage Object Creator, not Object Admin. Service Usage Consumer is
also modeled for authenticated API consumption. After an approved schema-only drill
passes, remove Object Admin and prove cleanup through the custom role before deleting the legacy
repository-name WIF member.

The live validation bucket is regional `asia-south2`, has uniform bucket-level access and public
access prevention enforced, a 30-day live-object deletion rule, and seven-day soft delete. No live
`recovery-drills/` object existed at inspection time. Soft delete means an object removed by the
workflow is retained in a non-readable soft-deleted state for seven days and can be restored only by
a principal with `storage.objects.restore`; the proposed custom role deliberately omits that
permission. See [Cloud Storage soft delete](https://docs.cloud.google.com/storage/docs/soft-delete)
and [Cloud Storage IAM roles](https://docs.cloud.google.com/storage/docs/access-control/iam-roles).

The earlier helper exported the entire restored database, which would temporarily copy production
PII and leave its deleted form retained for the soft-delete window. That is not an acceptable normal
production validation contract. The integrated production path must call the Cloud SQL Admin API
with `sqlExportOptions.schemaOnly=true`, while dev may use a full synthetic-data export. If the
schema-only request cannot be formed or verified, production fails closed before export. Evidence
is limited to successful PITR clone, expected database presence, and a non-empty schema export; the
script does **not** prove row counts, row checksums, or application-level data correctness. Google
documents the schema-only export field in the
[Cloud SQL Admin API export context](https://docs.cloud.google.com/sql/docs/postgres/admin-api/rest/v1/Operation).

Recovery evidence is finalized only after cleanup verification. `status=PASSED` requires
`cleanupConfirmed=true` together with `validationObjectRemoved=true`,
`temporaryBucketIamRemoved=true`, and `temporaryInstanceRemoved=true`. The helper verifies the
object and clone are absent and the temporary Storage Object Creator member is no longer in the
bucket policy. If any cleanup step cannot be confirmed, it first overwrites the artifact with
`status=cleanup-failed`, `cleanupConfirmed=false`, per-resource booleans, and cleanup errors, then
throws. A successful database validation with failed cleanup is therefore never reported as a
passed recovery drill.

No production recovery workflow was executed in this workstream.

Read-only configuration evidence shows production automated backups and PITR enabled with 14
retained backups and seven transaction-log retention days. Development currently has automated
backups disabled and no active PITR setting. The helper's dev dry-run therefore failed closed before
clone/export creation. A temporary dev PITR certification, including its backup-storage cost and
reversal, remains an explicit operations change; source validation is not recovery evidence.

### 4.5 Cloud SQL transport encryption and endpoint identity

#### Verified live state

Read-only inspection initially found the following on 2026-08-11. Private IP is a
network-reachability control; it is not evidence that the PostgreSQL session is encrypted. The
subsequent enforced dev state is recorded separately below so the baseline and outcome are not
conflated.

| Environment | Engine/tier/availability | Network and CA mode | Server transport policy |
| --- | --- | --- | --- |
| dev, initial | PostgreSQL 16, `db-custom-4-7680`, zonal | private IPv4 only; `GOOGLE_MANAGED_INTERNAL_CA` | `sslMode=ALLOW_UNENCRYPTED_AND_ENCRYPTED`; `requireSsl=false` |
| dev, enforced evidence at 2026-08-11 07:43:24Z | PostgreSQL 16, `db-custom-4-7680`, zonal | private IPv4 only; `GOOGLE_MANAGED_INTERNAL_CA` | `sslMode=ENCRYPTED_ONLY`; `requireSsl=false`; encrypted connections enforced |
| prod | PostgreSQL 16, `db-g1-small`, zonal | private IPv4 only; `GOOGLE_MANAGED_INTERNAL_CA` | `sslMode=ALLOW_UNENCRYPTED_AND_ENCRYPTED`; `requireSsl=false` |

The seven Cloud Run service targets were checked individually:

| Consumers | Checked-in state before this change | Initial deployed dev/prod state | Baseline evidence-backed conclusion |
| --- | --- | --- | --- |
| identity, school-core, operations, platform and billing runtime pools | direct private-IP pgJDBC URL with no `sslmode` | direct URL with no `sslmode` | pgJDBC defaults to `prefer`: TLS is attempted, but plaintext fallback is permitted; current session encryption is **unknown**, not assumed |
| the same five services' Flyway connections | direct private-IP pgJDBC URL with no `sslmode` | direct URL with no `sslmode` | the same fallback risk applies to migrations |
| API gateway and frontend | no JDBC URL or database credential | no JDBC URL or database credential | not Cloud SQL clients |

The initial live psql-based job inventory was different and is therefore called out separately. Dev jobs
`ims-app-rt-dev`, `ims-q-dev`, `ims-seed-dev`, and `ims-seedfull-dev`, plus production job
`ims-app-rt-prod`, set `PGSSLMODE=require`. Dev jobs `ims-gateway-smoke-sql-dev` and
`ims-scale-fixture-dev`, and production job `ims-gateway-smoke-sql-prod`, set
`PGSSLMODE=disable`; those jobs were configured to use plaintext whenever they ran. At that initial
inspection point, the database-side `pg_stat_ssl` view had not been captured, so the review did not
claim that the JVM sessions were encrypted. The current dev result is in the verified enforcement
subsection below; the production inventory remains a blocker.

This distinction follows the official driver and platform contracts. pgJDBC documents `prefer` as
its default and `verify-full` as the mode that encrypts while validating both the trusted CA and
hostname. Google documents `ALLOW_UNENCRYPTED_AND_ENCRYPTED`, `ENCRYPTED_ONLY`, and
`TRUSTED_CLIENT_CERTIFICATE_REQUIRED`, and notes that changing the policy affects new connections;
old unencrypted connections must be closed. Cloud SQL connectors and the Auth Proxy automatically
encrypt and verify client/server identity, while this deployment currently uses direct private-IP
TCP. References:
[pgJDBC connection properties](https://jdbc.postgresql.org/documentation/use/),
[pgJDBC SSL](https://jdbc.postgresql.org/documentation/ssl/),
[Cloud SQL SSL/TLS enforcement](https://docs.cloud.google.com/sql/docs/postgres/configure-ssl-instance),
[direct SSL/TLS authorization](https://docs.cloud.google.com/sql/docs/postgres/authorize-ssl), and
[Cloud Run private-IP connectivity](https://docs.cloud.google.com/sql/docs/postgres/connect-run).

#### Source change completed

- Both runtime and Flyway URLs in the five database-backed Cloud Run manifests now append
  `sslmode=require`. This is the compatibility phase: it prevents plaintext fallback but does not
  provide the hostname/CA identity assurance of `verify-full`.
- All four checked-in psql job constructors now set `PGSSLMODE=require`:
  `invoke-create-app-rt-role-cloudsql.ps1`, `invoke-production-gateway-smoke.ps1`,
  `invoke-scale-fixture.ps1`, and `audit-legacy-compatibility-cloudsql.ps1`. The two reusable-job
  helpers (`invoke-production-gateway-smoke.ps1` and `invoke-scale-fixture.ps1`) also inspect an
  already-existing job before execution. If its mode is not exactly `require`, they run
  `gcloud run jobs update ... --update-env-vars=PGSSLMODE=require`. Cloud Run's update operation
  merges that one environment key; it does not replace other environment variables or change the
  image, command, arguments, secrets, VPC settings, resources, retries, timeout, or task count. A
  failed reconciliation stops the helper before the SQL job can execute.
- `audit-cloudsql-transport-security.ps1` checks checked-in consumers and, for dev/prod, reads the
  Cloud SQL policy, deployed service URLs, deployed job modes, and a separately captured
  `pg_stat_ssl` evidence envelope. It fails closed unless the server rejects plaintext, every
  discovered client requires encryption, and evidence is environment/project/instance matched,
  no older than 30 minutes, non-empty, and reports zero unencrypted client backends. `-ReportOnly`
  is evidence collection only; it does not turn a failed gate into a pass. Its source mode also
  proves all three existing-job helper paths contain exactly one update block, that the block merges
  `PGSSLMODE=require`, and that it contains no other Cloud Run configuration mutation flag.
- `audit-cloudsql-transport-security.sql` returns only aggregate application-client connection
  counts for `current_database()`. It emits no database address, username, query text, school
  identifier, or credential. It excludes its own audit connection so a disconnected application
  fleet cannot pass on the evidence session alone, and excludes the documented Cloud SQL system
  user `cloudsqladmin` so platform-owned local sessions are not misclassified as application
  plaintext. The governance audit pins both scope predicates as well as the normalized query hash.
- `capture-cloudsql-transport-evidence.ps1` is the guarded executor for that SQL. It defaults to dev
  and has no job-creation path. It requires the exact environment-matched gateway SQL job, verifies
  that the existing job has private VPC access, a PostgreSQL client image, and a shell command, then
  reconciles only `PGSSLMODE=require`. The normalized query is pinned by SHA-256, transported in
  memory to one argument-only execution override, written only to an execution-local `/tmp` file,
  and removed by a shell `EXIT` trap. The reviewed normalized-query hash is
  `fd7da354158e77e775113d696f747e657356ac78976adaef07d3c31ec1ed526f`; the governance audit
  recomputes it instead of trusting this documentation. A unique marker scopes the log read to one
  result.
- The capture helper rejects missing, duplicate, malformed, negative, empty, inconsistent, or
  extra-field output. It atomically writes directly under `artifacts/` only an envelope containing
  `environment`, `projectId`, `instance`, `capturedAtUtc`, `clientBackends`, `encryptedBackends`, and
  `unencryptedBackends`. It never writes the access token, database address, database role, SQL text,
  credential, query text, application-user data, or school data. Its local temporary output is
  deleted in `finally`; its default filename includes a millisecond-resolution UTC timestamp and an
  existing requested path is rejected both before cloud access and immediately before the atomic
  same-directory rename. The rename never uses overwrite/`-Force`, so prior evidence is immutable.
  It creates no Cloud Run job, secret, VPC resource, certificate, or database object. The bounded
  Cloud Run execution is deleted after parsing and its absence is verified before the evidence file
  is finalized; execution cleanup failure therefore cannot produce a completed evidence artifact.
  The operator identity consequently needs an authenticated session and narrowly scoped job
  get/update/run, execution get/delete, Cloud SQL describe, and log-read capabilities; missing
  cleanup or verification authority is a hard failure, not a reason to retain the execution or
  evidence.
- Production capture fails before cloud access unless both
  `-AllowProductionEvidenceCapture` and the exact
  `-ConfirmProductionInstance custoking-db-prod` confirmation are present. This is evidence
  collection, not authorization to change the server TLS policy or deploy an application revision.

Changing source alone does not update already-created Cloud Run jobs. The authorized dev execution
has now reconciled the two previously noncompliant dev jobs, deployed the five database-backed dev
clients with `sslmode=require`, and enforced the dev server policy as recorded below. Production was
not changed: its gateway-smoke job, service URLs, server policy and fresh session evidence remain
subject to the explicit production procedure and approval window. No production service, job,
Cloud SQL flag, certificate, or IAM policy was changed by this workstream.

#### Exact staged dev-to-production migration

1. **Source gate.** Run
   `scripts/audit-cloudsql-transport-security.ps1 -Environment source`. It must pass before any
   deployment. Review the generated manifests to confirm both runtime and Flyway URLs retain
   `sslmode=require`.
2. **Dev clients first.** Update the two existing noncompliant dev job definitions to
   `PGSSLMODE=require`, either through the helper's new existing-job reconciliation path or with the
   explicit merge commands below. Deploy the five database-backed service manifests to dev; wait for
   every new revision and Flyway migration to become Ready. Do not change the server policy first,
   because an undiscovered plaintext-only caller would be disconnected without a tested replacement.

   ```powershell
   gcloud run jobs update ims-gateway-smoke-sql-dev --project=custoking --region=asia-south2 --update-env-vars=PGSSLMODE=require
   gcloud run jobs update ims-scale-fixture-dev --project=custoking --region=asia-south2 --update-env-vars=PGSSLMODE=require
   ```
3. **Dev compatibility proof.** Run authenticated service smoke tests and every SQL job. Inspect the
   deployed configuration with the transport audit in report-only mode. Recycle old Cloud Run
   revisions/connections, capture the aggregate `pg_stat_ssl` result, and prove all client backends
   are encrypted before enforcing the server flag.
4. **Dev enforcement.** Patch only `custoking-db-dev` to
   `sslMode=ENCRYPTED_ONLY`. Restart/recycle client pools because existing plaintext sessions are not
   terminated by the policy change. Repeat migrations, authenticated smoke, scheduled/job paths,
   scale/load tests and the fresh session audit. Observe database connection errors, handshake
   latency, CPU, connection count and service 5xx for at least 24 hours.

   ```powershell
   gcloud sql instances patch custoking-db-dev --project=custoking --ssl-mode=ENCRYPTED_ONLY --quiet
   ```
5. **Production preflight after the approved 23:00 IST window begins.** Confirm a usable rollback
   revision, export the instance configuration, update/recreate `ims-gateway-smoke-sql-prod` with
   `PGSSLMODE=require`, and deploy the five production services one at a time. For each revision,
   verify Ready state, Flyway success, authenticated health, and absence of TLS errors before
   continuing. API gateway and frontend need no database transport change.

   ```powershell
   gcloud run jobs update ims-gateway-smoke-sql-prod --project=custoking --region=asia-south2 --update-env-vars=PGSSLMODE=require
   ```
6. **Production enforcement.** Only after every production client passes, patch
   `custoking-db-prod` to `sslMode=ENCRYPTED_ONLY`, recycle all old pools/revisions, capture fresh
   `pg_stat_ssl` evidence and run the fail-closed audit. Observe the same metrics through the agreed
   canary window. Do not remove the prior revisions during observation.

   ```powershell
   gcloud sql instances patch custoking-db-prod --project=custoking --ssl-mode=ENCRYPTED_ONLY --quiet
   ```
7. **Endpoint-identity phase.** Choose either direct JDBC with distributed Google server CA,
   resolvable certificate-matching DNS and `sslmode=verify-full`, or the Cloud SQL Java Connector on
   private IP. Test runtime, Flyway and every psql/operator path in dev before production. Do not set
   `TRUSTED_CLIENT_CERTIFICATE_REQUIRED` until client certificates or a connector-compatible design
   exist for every caller, including jobs and migrations.

Execution status as of 2026-08-11: dev steps 1 through 4 reached the functional and transport
acceptance point, including the post-enforcement smoke and fresh scoped session evidence. The full
24-hour dev observation in step 4 remains open. Production steps 5 and 6 and the endpoint-identity
decision in step 7 remain pending; dev success is not authorization to execute them.

Create the dev session envelope through the existing secret-injected private-VPC job, then consume it
with the fail-closed audit. The capture command is intentionally shown separately because it creates
a bounded Cloud Run execution and may first merge the TLS environment key on the existing job:

```powershell
$devEvidence = "artifacts/cloudsql-transport-dev-$([datetime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')).json"
powershell -File scripts/capture-cloudsql-transport-evidence.ps1 `
  -Environment dev `
  -OutputJson $devEvidence

powershell -File scripts/audit-cloudsql-transport-security.ps1 `
  -Environment dev `
  -PgStatSslEvidencePath $devEvidence
```

Production requires both confirmations and remains limited to the approved post-23:00 IST window:

```powershell
$prodEvidence = "artifacts/cloudsql-transport-prod-$([datetime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')).json"
powershell -File scripts/capture-cloudsql-transport-evidence.ps1 `
  -Environment prod `
  -AllowProductionEvidenceCapture `
  -ConfirmProductionInstance custoking-db-prod `
  -OutputJson $prodEvidence

powershell -File scripts/audit-cloudsql-transport-security.ps1 `
  -Environment prod `
  -PgStatSslEvidencePath $prodEvidence
```

Do not hand-author or edit the envelope. Capture it only after authenticated smoke traffic has opened
fresh application connections and all prior client pools/revisions have been recycled. The helper
fails rather than reporting an application-fleet pass when the only connection is its own audit
session, because the SQL excludes `pg_backend_pid()` and requires another client backend.

#### Verified dev enforcement result and evidence scope

During dev verification, the raw session diagnostic returned 18 client backends: 16 with
`pg_stat_ssl` encryption and two without it. A non-sensitive role grouping showed that both apparent unencrypted
rows belonged to `cloudsqladmin`, which Google documents as a Cloud SQL system user rather than an
application database user. Those two rows therefore identify platform-managed sessions, not two
verified plaintext application connections. See
[Cloud SQL PostgreSQL users and system roles](https://docs.cloud.google.com/sql/docs/postgres/users).

The permanent evidence query now makes its application-client boundary reviewable and stable:

- `backend_type = 'client backend'` selects client processes;
- `pid <> pg_backend_pid()` excludes the evidence collector itself;
- `datname = current_database()` prevents sessions for other databases from being represented as
  this application's evidence;
- `usename IS DISTINCT FROM 'cloudsqladmin'` excludes the documented Cloud SQL system user; and
- the left join to `pg_stat_ssl`, with a missing row treated as false, fails closed for every
  remaining application-client session.

Accordingly, the seven-field envelope is evidence for application clients connected to the current
database, not an assertion that every Cloud SQL-managed process appears in `pg_stat_ssl`. After the
dev server was changed to `ENCRYPTED_ONLY` and clients were recycled, the immutable local artifact
`artifacts/cloudsql-transport-dev-enforced-20260811T074206452Z.json` recorded 16 client backends,
16 encrypted and zero unencrypted at `2026-08-11T07:42:34.4245020Z`. The paired fail-closed audit
`artifacts/dev-transport-audit-enforced-20260811T074206Z.json` recorded at
`2026-08-11T07:43:24.0274973Z`:

- server `sslMode=ENCRYPTED_ONLY` and `encryptedConnectionsEnforced=true`;
- all five deployed database-backed services use `sslmode=require` for runtime and Flyway;
- all six discovered dev SQL jobs use `PGSSLMODE=require`;
- `activeSessionEncryptionVerified=true`, `compliant=true`, and no violations; and
- the post-enforcement authenticated smoke passed all 40 of 40 checks.

These artifacts are local, ignored operational evidence rather than source-controlled fixtures.
Their counts and timestamps are recorded here for review, while future approvals require a fresh
capture within the audit's age limit. The result closes the dev application-client encryption gate
only. It does not complete the 24-hour observation, prove certificate/hostname identity under
`sslmode=require`, or change/approve production.

#### Acceptance, rollback, cost and blocker

Acceptance requires server `sslMode` of `ENCRYPTED_ONLY` (or the intentionally selected stricter
client-certificate mode), `require`/`verify-ca`/`verify-full` on every deployed client, more than zero
client backends, encrypted count equal to client count, zero unencrypted backends, and all functional
checks passing. The evidence must be captured again after old sessions are recycled.

Rollback client failure by routing each service to its prior revision and restoring the prior job
definition. If server enforcement is the cause, patch that one instance back to
`ALLOW_UNENCRYPTED_AND_ENCRYPTED`, recycle affected clients, and record the exception; this restores
compatibility but also reopens the plaintext path, so it is not an accepted steady state. Roll back
`verify-full` by returning to the proven `require` revision while keeping `ENCRYPTED_ONLY` whenever
the incident permits. Never delete certificates or previous revisions until the observation window
closes.

```powershell
gcloud sql instances patch INSTANCE_NAME --project=custoking --ssl-mode=ALLOW_UNENCRYPTED_AND_ENCRYPTED --quiet
```

TLS policy and Google-managed certificates do not require another always-on GCP resource. Expected
cost is limited to modest handshake/CPU overhead and short canary overlap; measure it during the dev
soak rather than inventing a fixed amount. A connector requires dependency/IAM work but avoids
operating a separate proxy sidecar. This is intentionally cheaper than adding an independent proxy
service solely for encryption.

**Production blocker:** production promotion cannot pass while the production server permits
plaintext, any deployed service/job permits fallback or disables TLS, old sessions have not been
recycled, or fresh `pg_stat_ssl` evidence is missing/noncompliant. If authenticated endpoint identity
is required by the approved security policy, `require` is only an interim control and production
closure additionally requires the tested `verify-full` or connector phase. The stricter mutual-TLS
server mode remains blocked until every client has a tested certificate/connector path.

## 5. Secrets and Rotation

### 5.1 Verified live state

- 44 Secret Manager secrets exist;
- none has `rotationPeriod` plus `nextRotationTime` configured;
- none has a rotation-notification Pub/Sub topic configured;
- every secret has exactly one enabled version;
- 38 latest enabled versions were more than 30 days old at inspection time;
- Cloud Run manifests reference `key: latest`;
- secret payloads were not read.

Age alone is not proof of compromise and not every entry is a rotatable credential: SQL seed and
diagnostic artifacts must be classified separately. Google warns that using `latest` for production
can make a bad value immediately affect new instances and recommends version-pinned gradual
rollout. Secret Manager rotation schedules send notifications; they do not rotate an external
provider or database by themselves. See [Secret Manager rotation recommendations](https://docs.cloud.google.com/secret-manager/docs/rotation-recommendations)
and [rotation schedules](https://docs.cloud.google.com/secret-manager/docs/secret-rotation).

### 5.2 Rotation classes and plan

| Class | Examples | Required safe sequence |
| --- | --- | --- |
| URL-exposed legacy token | reporting read token | Remove URL exposure, enumerate remaining consumers, create version, canary both sides, disable old version. |
| Internal service tokens | catalog, student, attendance, workflow, billing, audit | Add dual-read capability or coordinate caller/callee revisions; validate all routes; then disable old version. |
| JWT signing | JWT secret | Add key ID/key ring and overlap verification keys; rotate signer; wait for maximum token TTL; retire old key. A one-key swap logs users out. |
| Database credentials | app runtime and Flyway passwords | Change database role credential and secret as one operation; canary connections; retain tested emergency recovery path. |
| External provider/OAuth | MSG91 and Drive credentials | Create provider-side replacement first, deploy numeric secret version, validate, then revoke old provider credential. |
| Operator passwords | superadmin | Reset with named owner, verify access, revoke prior value, record break-glass custody. |
| Static SQL content | seed/diagnostic SQL | Classify and remove if obsolete; do not create meaningless calendar rotation. |

Before automating schedules, implement a re-entrant rotation worker and numeric-version deployment.
Rotation notifications and service accounts have negligible direct cost at this volume; operational
failure risk, not API cost, is the constraint.

`deploy/gcp/secret-rotation-policy.json` is a **proposal pending security-owner approval**. It
accounts for all 44 live secret names without reading payloads and assigns proposed functional
roles and intervals; these labels are not named custodians and do not constitute business or
security approval:

- application platform: internal service tokens, proposed 90-day coordinated interval;
- database platform: database credentials, proposed 90-day coordinated interval, plus classification of the
  four SQL-like static artifacts;
- identity/security: JWT signing, proposed 180-day interval only after overlapping verification keys exist;
- integration platform: MSG91 and Drive OAuth credentials;
- security operations: event-driven privileged operator-password rotation.

`scripts/audit-secret-rotation-policy.ps1` compares the policy with live Secret Manager metadata,
reports unowned/missing names and schedule/topic counts, and explicitly never calls secret-version
access. Calendar schedules remain blocked because Google requires a configured Pub/Sub topic and a
subscriber/worker; emitting notifications with no re-entrant rotator would not rotate anything and
would create false assurance. The repository proposal does not create schedules, topics, versions,
credentials, or assignments of named people.

### 5.3 Acceptance and rollback

Every credential needs an owner, rotation SLA, last-tested timestamp, consumer inventory, and
rollback version. A new version is not accepted until a canary uses its numeric version. Disable,
then destroy old versions only after the overlap window and evidence review.

## 6. Dependency, Container, and Source Scanning

### 6.1 Verified live state before remediation (default-branch historical snapshot)

- weekly Trivy workflow latest run: successful;
- default-branch open Trivy alerts: 296 total, 51 HIGH, 223 MEDIUM, 20 LOW, 2 without normalized
  severity, zero CRITICAL;
- 69 alerts were created by the latest 2026-08-09 scan, including fixable HIGH packages in the old
  frontend `nginx:1.27-alpine` runtime;
- Dependabot covered only GitHub Actions and Docker images;
- no CodeQL workflow existed at that snapshot;
- Artifact Analysis container/on-demand scanning APIs were not enabled;
- Dependabot alerts are disabled and require a repository administrator to enable;
- secret-scanning status and alert inventory remain unverified because the API returns `404` to the
  non-admin operator.

A green scheduled run was therefore not evidence of zero HIGH findings.

### 6.2 Repository changes completed

- Added Dependabot coverage for both npm projects and all five Maven projects.
- Added CodeQL `security-extended` analysis for Java and JavaScript/TypeScript on PRs, branch pushes,
  a weekly schedule, and manual dispatch. GitHub supports no-build analysis for Java and JS/TS in a
  public repository; see [CodeQL compiled-language build modes](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages).
- Split Trivy reporting from enforcement. Both PR and scheduled image gates now fail on any HIGH or
  CRITICAL result, including findings without a fix; SARIF still uploads on failure.
- Added the same pinned Trivy gate to `build-release.yml` after its immutable Artifact Registry
  resolution and before either direct dev deployment or Cloud Deploy release creation. Each
  affected `registry/image@sha256:...` reference is scanned directly; the workflow never scans a
  mutable tag or locally rebuilt substitute. It collects every affected service before enforcing
  the aggregate result, blocks on HIGH/CRITICAL including unfixed findings, and preserves
  digest-keyed table, SARIF, and JSON evidence in the existing release-evidence artifact.
- Upgraded the frontend runtime from the obsolete pinned nginx/Alpine image to the official pinned
  `nginx:1.30.4-alpine3.24` multi-architecture digest.
- Updated transitive `nanoid` from `3.3.16` to `3.3.18`, removing the npm HIGH finding.
- Raised the frontend npm CI gate from CRITICAL to HIGH.

Fresh GitHub run
[`31443426825`](https://github.com/custokingkr-dev/ims-v1/actions/runs/31443426825), against
`080cbba0cdecd4abb25712f5c60f422b040528bf`, correctly failed five Java image jobs while frontend
and API gateway passed. Every reported HIGH was in a JAR/language package; no HIGH/CRITICAL OS
package was reported:

- `CVE-2025-55163`, `io.grpc:grpc-netty-shaded` 1.71.0, fixed in 1.75.0: operations, billing, and
  school-core;
- `CVE-2026-54291`, `org.postgresql:postgresql` 42.7.11, fixed in 42.7.12: all five Java services;
- `CVE-2026-54512` and `CVE-2026-54513`, `com.fasterxml.jackson.core:jackson-databind` 2.18.2,
  fixed in 2.18.8 or later: identity only.

The repository remediation raises gRPC to 1.75.0 in operations/billing/school-core, PostgreSQL to
42.7.12 in all five Java services, and the identity Jackson override to 2.18.8. The scanner was not
weakened: it still blocks HIGH/CRITICAL findings and includes unfixed findings. All five rebuilt
local final images then passed exact Trivy 0.70.0 scans with zero HIGH/CRITICAL findings across the
Ubuntu 26.04 OS package set, Java JAR packages, and the embedded Pebble Go binary.

Local verification of the rebuilt final frontend image with the same Trivy major used by
`trivy-action@v0.36.0` reported zero HIGH/CRITICAL OS or library vulnerabilities. The frontend was
then upgraded from React Router 6.30.4 to the fixed 7.18.2 release after confirming that its v7
future flags and Node/React versions met the documented migration prerequisites. The obsolete v6
future prop was removed. All 147 tests and both Vite builds pass, and frontend plus gateway npm
audits now report zero vulnerabilities. See the official
[React Router v7 migration/changelog](https://reactrouter.com/home/changelog).

During the integrated audit, the first parallel Windows run timed out once in the existing
Excel-image test at its five-second limit. That same test passed in 472 ms when isolated and all 147
tests passed together on immediate rerun. This is recorded as CI flake risk, not hidden as a router
failure; the fresh Linux PR run remains authoritative.

The old SARIF streams were reconciled without dismissal. The missing stable `category: ${{ matrix.name }}`
was restored, and run `31509695990` uploaded all seven current dev images into the original per-service
streams. Every job passed; GitHub closed the 30 legacy HIGH alerts from commit `7e379992`. The final dev
inventory is zero HIGH/CRITICAL, while 209 MEDIUM and 30 LOW Trivy findings remain visible for normal
versioned remediation. Release run `31509672530` separately scanned the three changed immutable digests and
passed every HIGH/CRITICAL and SARIF evidence gate before deployment.

### 6.3 Optional Artifact Analysis cost

Google lists automatic or on-demand Artifact Analysis at **USD 0.26 per scanned image digest**. A
seven-image full release is approximately USD 1.82; one seven-image weekly full scan is about USD
7.28 over four weeks, with partial affected-service builds costing less. Tags do not trigger a new
digest scan charge. See [Artifact Analysis pricing](https://cloud.google.com/artifact-analysis/pricing).

Enable Artifact Analysis only after budget approval and use it as a registry-side second opinion;
the existing GitHub Trivy gates remain the zero-additional-GCP-cost primary control. The release
gate scans only the affected immutable digests on the existing GitHub runner, so it creates no
Artifact Analysis scan charge and does not rescan unrelated services.

### 6.4 Immutable GitHub Actions evidence and remaining gates

All 68 external action invocations across all 13 workflow files are pinned to an exact 40-character
commit SHA and retain a readable version comment. No local composite action file exists under
`.github/actions`; local reusable workflow references continue to use `./.github/workflows/...` and
are therefore tied to the checked-out repository commit.

The following mapping was initially resolved on 2026-08-11 and each changed entry was revalidated
directly through GitHub's authoritative Git data (most recently Buildx and CodeQL on 2026-08-26):
`GET /repos/{owner}/{repo}/git/ref/tags/{tag}`, followed by
`GET /repos/{owner}/{repo}/git/tags/{objectSha}` when the ref pointed to an annotated tag, and then
`GET /repos/{owner}/{repo}/commits/{commitSha}`. The Trivy and CodeQL refs are annotated tags; their
tag-object SHA is not executable source and was correctly dereferenced to the terminal commit.

| Reviewed release | Git ref object | Pinned terminal commit |
|---|---|---|
| `actions/checkout@v4` | `11d5960a326750d5838078e36cf38b85af677262` | [`11d5960a326750d5838078e36cf38b85af677262`](https://github.com/actions/checkout/commit/11d5960a326750d5838078e36cf38b85af677262) |
| `actions/checkout@v7` | `3d3c42e5aac5ba805825da76410c181273ba90b1` | [`3d3c42e5aac5ba805825da76410c181273ba90b1`](https://github.com/actions/checkout/commit/3d3c42e5aac5ba805825da76410c181273ba90b1) |
| `actions/setup-java@v5` | `b6effb05e454b25005698d916606bdc6ffcbf961` | [`b6effb05e454b25005698d916606bdc6ffcbf961`](https://github.com/actions/setup-java/commit/b6effb05e454b25005698d916606bdc6ffcbf961) |
| `actions/setup-node@v7` | `820762786026740c76f36085b0efc47a31fe5020` | [`820762786026740c76f36085b0efc47a31fe5020`](https://github.com/actions/setup-node/commit/820762786026740c76f36085b0efc47a31fe5020) |
| `actions/upload-artifact@v4` | `ea165f8d65b6e75b540449e92b4886f43607fa02` | [`ea165f8d65b6e75b540449e92b4886f43607fa02`](https://github.com/actions/upload-artifact/commit/ea165f8d65b6e75b540449e92b4886f43607fa02) |
| `actions/upload-artifact@v7` | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | [`043fb46d1a93c77aae656e7c1c64a875d1fc6a0a`](https://github.com/actions/upload-artifact/commit/043fb46d1a93c77aae656e7c1c64a875d1fc6a0a) |
| `aquasecurity/trivy-action@v0.36.0` | annotated tag `a9c7b0f06e461e9d4b4d1711f154ee024b8d7ab8` | [`ed142fd0673e97e23eac54620cfb913e5ce36c25`](https://github.com/aquasecurity/trivy-action/commit/ed142fd0673e97e23eac54620cfb913e5ce36c25) |
| `docker/build-push-action@v7` | `53b7df96c91f9c12dcc8a07bcb9ccacbed38856a` | [`53b7df96c91f9c12dcc8a07bcb9ccacbed38856a`](https://github.com/docker/build-push-action/commit/53b7df96c91f9c12dcc8a07bcb9ccacbed38856a) |
| `docker/login-action@v4` | `dbcb813823bdd20940b903addbd779551569679f` | [`dbcb813823bdd20940b903addbd779551569679f`](https://github.com/docker/login-action/commit/dbcb813823bdd20940b903addbd779551569679f) |
| `docker/setup-buildx-action@v4` (release `v4.3.0`) | `37fe631027851001ddb9b187196cc803df7f5f0e` | [`37fe631027851001ddb9b187196cc803df7f5f0e`](https://github.com/docker/setup-buildx-action/commit/37fe631027851001ddb9b187196cc803df7f5f0e) |
| `github/codeql-action@v4` (`init`, `analyze`, `upload-sarif`; release `v4.37.7`) | annotated tag `faaa5d804fc648d0fdb28822a8e36cf7d0a6132c` | [`ff2f1c621b7f889edc0d3c761ac2e6a3f8cdb0dd`](https://github.com/github/codeql-action/commit/ff2f1c621b7f889edc0d3c761ac2e6a3f8cdb0dd) |
| `gitleaks/gitleaks-action@v3` | `e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e` | [`e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e`](https://github.com/gitleaks/gitleaks-action/commit/e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e) |
| `google-github-actions/auth@v3` | `7c6bc770dae815cd3e89ee6cdf493a5fab2cc093` | [`7c6bc770dae815cd3e89ee6cdf493a5fab2cc093`](https://github.com/google-github-actions/auth/commit/7c6bc770dae815cd3e89ee6cdf493a5fab2cc093) |
| `google-github-actions/setup-gcloud@v3` | `aa5489c8933f4cc7a4f7d45035b3b1440c9c10db` | [`aa5489c8933f4cc7a4f7d45035b3b1440c9c10db`](https://github.com/google-github-actions/setup-gcloud/commit/aa5489c8933f4cc7a4f7d45035b3b1440c9c10db) |

`scripts/audit-security-governance-controls.ps1` now scans every workflow and any future local
`action.yml`/`action.yaml`. It rejects a mutable external ref, a missing readable version comment,
an unreviewed action/version pair, or a SHA that differs from the reviewed mapping. Upgrades require
repeating the tag-object dereference and updating the action, comment, allowlist, and evidence in
one reviewed change.

Remaining supply-chain gates are:

- keep CodeQL green and remediate any future HIGH/CRITICAL source finding;
- promote the reviewed dependency/runtime remediations to `main` only in the approved production window,
  run the stable-category scan there and prove its current 51 HIGH/zero CRITICAL backlog is closed rather
  than administratively dismissing it;
- triage the 209 MEDIUM and 30 LOW dev Trivy findings with versioned owner/expiry records;
- have a repository administrator enable Dependabot alerts/security updates and verify secret
  scanning; enable push protection only after the current integration push so it cannot strand the
  worktree on an unreviewed false positive;
- add an exception file with owner, expiry, and rationale if a vulnerability truly cannot be fixed;
- retain the completed dev release evidence and repeat the same exact-digest table/SARIF/JSON gate for
  production; source blocks deployment/promotion when any selected digest has a HIGH/CRITICAL finding or
  required scan evidence is absent.

### 6.5 MixedMorning runtime failure reconciliation and security disposition

The aborted dev `MixedMorning` run `20260811121235` was reconciled from the local k6 summary and
guardrail evidence, sanitized Cloud Run request/application logs, and historical Cloud Monitoring
metrics for `2026-08-11T12:12:35Z` through `12:18:50Z`. Log processing emitted only timestamps,
status codes, service names, sanitized path components, latency, and classified platform messages;
credentials, bearer tokens, request bodies, query strings, and query-bearing URLs were neither
printed nor recorded. This investigation was read-only and made no live configuration, IAM,
deployment, traffic, quota, database, or secret change.

The k6 run issued 28,317 requests: 28,042 were observed as `2xx`, 227 as `4xx`, one as `5xx`, and
47 failed locally with status zero. Every non-success result is reconciled below:

| k6-visible result | Count | Cloud Run evidence and exact disposition |
|---|---:|---|
| Gateway-generated `429` | 175 | Cloud Run rejected the request before gateway-container invocation because no instance was available. |
| Forwarded school-core `429` received within 60 seconds | 52 | School-core Cloud Run reported no available instance; the gateway forwarded the response. |
| Forwarded school-core `503` | 1 | Attendance-report summary failed in 1.04 seconds because the project had recently exceeded the Cloud Run CPU-allocation quota. |
| Status zero: late school-core `429` | 11 | The same no-available-instance response reached the gateway after 71.31-72.28 seconds, beyond the client timeout. |
| Status zero: late school-core `500` | 21 | Cloud Run used `500` for the same no-available-instance condition; all arrived after 71.29-72.20 seconds. |
| Status zero: late successful `200` | 15 | Successful responses completed after 71.70-78.61 seconds and therefore timed out at the client. |
| **Total k6 failures** | **275** | **227 observed `429` + one observed `503` + 47 status-zero timeouts.** |

The load script does not override the HTTP request timeout, so the applicable k6 default is 60
seconds; see [k6 HTTP request parameters](https://grafana.com/docs/k6/latest/javascript-api/k6-http/params/).
The 47 responses that completed after 60 seconds exactly equal the 47 status-zero failures.

The `429` path accounting also closes exactly:

| Sanitized flow | k6-observed `429` | Gateway no-instance `429` | School-core no-instance `429` received before timeout | School-core `429` after timeout |
|---|---:|---:|---:|---:|
| Synthetic login | 39 | 39 | 0 | 0 |
| Student list | 33 | 18 | 15 | 2 |
| Command center | 18 | 18 | 0 | 0 |
| Attendance daily summary | 43 | 29 | 14 | 4 |
| Fee structure | 32 | 19 | 13 | 4 |
| Fee defaulters | 24 | 24 | 0 | 0 |
| Attendance report summary | 38 | 28 | 10 | 1 |
| **Total** | **227** | **175** | **52** | **11** |

Gateway request logs contain 238 `429`, 21 `500`, and one `503` response. The 175 gateway-local
`429` entries all carry Cloud Run's no-available-instance platform message and have no corresponding
container request record. The remaining 63 `429`, all 21 `500`, and the single `503` exactly match
the 85 school-core error request records by status and sanitized path. Identity and platform service
request logs contain zero error responses in the window. The lone k6-visible `5xx` is the prompt
attendance-report `503`; the 21 `500` responses completed only after k6 had timed out.

Historical capacity evidence is consistent with those platform messages:

- the gateway reached all four configured active instances and its minute-level request-concurrency
  p99 estimate reached approximately 85, effectively its configured concurrency ceiling of 80;
- school-core remained at three active instances despite `maxScale=4`, while its concurrency p99
  estimate likewise reached approximately 85;
- gateway container CPU p99 peaked near 16% and school-core near 56%; their memory remained roughly
  29-34%, so container CPU or memory exhaustion is not the evidenced rejection cause;
- Cloud SQL CPU crossed 80% for three fresh samples and reached 100%, triggering the certification
  guardrail; Cloud SQL memory remained near 47% and connections peaked at 85;
- the explicit Cloud Run CPU-allocation-quota `503` explains why additional service capacity was not
  available while long database-backed reads held the existing request-concurrency slots.

The security disposition is therefore precise:

- **capacity/quota:** confirmed for every HTTP error and timeout in this run;
- **authentication or authorization:** not implicated; there were no `401` or `403` responses, all
  39 failed login attempts were gateway platform rejections before identity, and identity recorded
  no error requests;
- **token refresh:** not implicated; the run lasted approximately six minutes, while
  `load-tests/school-day-mixed-read.js` defaults to a 12-minute per-VU token refresh interval;
- **application rate limiting:** not implicated; every gateway-local `429` has the Cloud Run
  platform rejection message, while every container-observed `429` was forwarded from school-core.
  No response is attributable to the gateway token bucket;
- **functional code exception:** not evidenced. Slow database-backed reads remain a performance and
  capacity-design gap, but naming a defective query requires Query Insights or sanitized execution
  plans rather than inference from HTTP status alone.

This runtime reconciliation did not itself close source scanning. The later remediations did: 25
test-only SQL constructions were parameterized, and all six runtime findings received narrow code fixes
with regression tests. CodeQL run `31509672266` passed and the dev alert API now reports zero CodeQL alerts.
Production remains blocked by the stale `main` Trivy HIGH backlog and the other security, capacity and
operational gates documented here.

## 7. Public Ingress, Load Balancer, and WAF

### 7.1 Verified live state

Only four service/environment combinations are public through Cloud Run IAM:

- frontend dev/prod;
- API gateway dev/prod.

Identity, school-core, operations, platform, and billing are IAM-private in both environments. This
is a positive control. All services currently have network ingress `all`, but IAM still rejects
unauthorized calls to the five domain services.

There is no Cloud Armor policy, URL map, backend service, serverless NEG, or global forwarding rule.
The public frontend and API gateway are therefore reached directly on `run.app`, without a WAF or
edge rate policy.

Google documents that `internal-and-cloud-load-balancing` prevents direct internet access while
allowing an external Application Load Balancer, and recommends disabling the default URL to prevent
bypassing Cloud Armor. See [Cloud Run ingress restrictions](https://docs.cloud.google.com/run/docs/securing/ingress)
and [Cloud Armor with serverless backends](https://docs.cloud.google.com/armor/docs/integrating-cloud-armor).

### 7.2 Cost-conscious option

Use one external Application Load Balancer with one URL map and two serverless NEGs: frontend for
normal paths and API gateway for `/api/*`. Keep the five domain services off the load balancer.
Select Cloud Armor Standard, not Enterprise, unless measured attack risk justifies Enterprise.

At current published list prices:

- first five forwarding rules: USD 0.025/hour, about USD 18.25/month at 730 hours;
- one Armor Standard policy: USD 0.006849315/hour, about USD 5/month;
- each Armor Standard rule: USD 0.001369863/hour, about USD 1/month;
- global Armor requests: USD 0.75 per million;
- load-balancer data processing and internet egress remain usage-based.

A policy plus five rules and one forwarding rule is therefore roughly **USD 28.25/month before
traffic/data charges**. See [Cloud Load Balancing pricing](https://cloud.google.com/load-balancing/pricing)
and [Cloud Armor pricing](https://cloud.google.com/armor/pricing).

### 7.3 Required design and acceptance

Before implementation, supply the production domain and DNS authority. Configure managed TLS,
host/path routing, baseline OWASP rules, rate limits on login/refresh/import endpoints, and preview
mode before enforcement. Then change only frontend/API gateway ingress to
`internal-and-cloud-load-balancing` and disable their default URLs after Pub/Sub, uptime checks,
smokes, and client configuration no longer depend on them.

Acceptance requires direct `run.app` internet access to fail, custom-domain gateway/frontend access
to pass, no internal service exposure, correct client IP/header handling, and measured false-positive
rate. Roll back by re-enabling the Cloud Run default URLs and prior ingress before detaching the
load-balancer resources. Never delete the only working ingress path first.

## 8. Exact Change Inventory

Files added:

- `.github/workflows/codeql-analysis.yml`
- `.github/workflows/reconcile-deployment-config.yml`
- `scripts/render-clouddeploy-pipelines.ps1`
- `scripts/audit-security-governance-readiness.ps1`
- `scripts/audit-security-governance-controls.ps1`
- `scripts/configure-security-governance-controls.ps1`
- `scripts/audit-secret-rotation-policy.ps1`
- `scripts/audit-cloudsql-transport-security.ps1`
- `scripts/audit-cloudsql-transport-security.sql`
- `scripts/capture-cloudsql-transport-evidence.ps1`
- `deploy/gcp/secret-rotation-policy.json`
- `docs/workstreams/SECURITY-GOVERNANCE-CHANGES-2026-08-11.md`

Files changed:

- `.github/dependabot.yml`
- `.github/workflows/_build-image.yml`
- `.github/workflows/ci-pr.yml`
- `.github/workflows/security-scan.yml`
- `.github/workflows/_detect-changes.yml`
- `.github/workflows/_smoke-environment.yml`
- `.github/workflows/_test-java-service.yml`
- `.github/workflows/_test-node-service.yml`
- `.github/workflows/build-release.yml`
- `.github/workflows/gcp-cost-controls.yml`
- `.github/workflows/recovery-drill.yml`
- `.github/workflows/rollback.yml`
- `deploy/clouddeploy/targets-dev.yaml`
- `deploy/clouddeploy/targets-prod.yaml`
- `deploy/gcp/recovery-bucket-iam-operator-role.yaml`
- `frontend/Dockerfile`
- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/src/main.tsx`
- `scripts/configure-runtime-service-accounts.ps1`
- `scripts/configure-reporting-pubsub-push-oidc.ps1`
- `scripts/render-clouddeploy-targets.ps1`
- `scripts/resolve-affected-ci-targets.ps1`
- `scripts/invoke-cloudsql-restore-drill.ps1`
- `scripts/invoke-create-app-rt-role-cloudsql.ps1`
- `scripts/invoke-production-gateway-smoke.ps1`
- `scripts/invoke-scale-fixture.ps1`
- `scripts/audit-legacy-compatibility-cloudsql.ps1`
- `deploy/cloudrun/identity-service.yaml`
- `deploy/cloudrun/school-core-service.yaml`
- `deploy/cloudrun/operations-service.yaml`
- `deploy/cloudrun/platform-service.yaml`
- `deploy/cloudrun/billing-service.yaml`
- `infra/terraform/cicd/main.tf`
- `infra/terraform/cicd/variables.tf`
- `infra/terraform/cicd/outputs.tf`
- `infra/terraform/cicd/README.md`
- `services/identity-service/pom.xml`
- `services/school-core-service/pom.xml`
- `services/operations-service/pom.xml`
- `services/platform-service/pom.xml`
- `services/billing-service/pom.xml`

The two existing production configurators are now inspectable without a production authorization
flag, but still require explicit double authorization to mutate production.

## 9. Validation Performed

```text
Security governance static/parse audit: PASS
MixedMorning sanitized HTTP reconciliation: PASS; all 227 observed 4xx, one observed 5xx and 47
  status-zero failures reconcile to Cloud Run no-instance/capacity responses, the CPU-allocation
  quota response, or responses completing after k6's 60-second timeout; no credential or query
  string was emitted
MixedMorning security classification: PASS; no 401/403, identity/platform error, token refresh or
  gateway application-rate-limit failure was evidenced
Runtime CodeQL remediation: PASS; six narrow fixes and regression tests, CodeQL run `31509672266`
  successful, ref-scoped dev API reports zero open CodeQL alerts
Governance configurator dry-run: PASS; no external mutation
WIF claim policy matrix: PASS; 3 allow and 6 deny cases
WIF service-account scope matrix: PASS; 4 allowed exact identities and 8 cross-branch/main-only denials
Secret rotation proposal inventory: PASS; all 44 live names map to proposed functional roles; named
  owner and interval approval remains pending; no payload access
Terraform format/validation: PASS
Configuration reconciler custom role audit: PASS; target/pipeline create/get/list/update and
  read-only polling only; no release, rollout, delete, tag, or IAM-policy permissions
Cloud Deploy target schema: PASS; 7 dev and 7 prod targets each use exact RENDER+DEPLOY execution
  identities and environment-specific runtime identities; no default Compute identity in source
Cloud Deploy renderer: PASS for dev/prod with placeholders; stage rejected by parameter contract
All 13 GitHub workflow YAML files plus target templates/rendered outputs: PASS parse
GitHub workflow semantic validation: PASS; actionlint v1.7.12 checked all 13 workflows from the
  checksum-verified Windows archive (`sha256:6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9`)
External GitHub Actions pin audit: PASS; 52 invocations, 16 action-path/version combinations,
  14 reviewed repository/version mappings, all exact 40-character commits with version comments
Pinned action entrypoints: PASS through GitHub Contents API; all 16 referenced root/subpath
  `action.yml` or `action.yaml` files exist at their pinned commit
Affected-service resolver: PASS; target-only and pipeline-only select zero services and require
  reconciliation; target plus one service selects exactly one; one service manifest selects exactly one
Cloud SQL transport source audit: PASS; 10/10 runtime/Flyway URLs require TLS and all four checked-in
  psql job constructors plus the evidence helper use `PGSSLMODE=require` with no plaintext fallback;
  3/3 existing-job paths merge only `PGSSLMODE=require` and reject additional configuration mutations
Cloud SQL transport evidence capture safety: PASS source/parse; dev default, exact SQL checksum,
  private-VPC existing job only, production double confirmation before cloud access, exact seven-field
  immutable timestamped envelope, marker-scoped result, container/local/execution cleanup, and no
  overwrite path; executed in dev only through the guarded existing-job path
Cloud SQL raw dev session diagnostic: REVIEWED; 18 total, 16 encrypted and 2 without `pg_stat_ssl`;
  both non-SSL rows grouped to the documented `cloudsqladmin` system user, not application clients
Cloud SQL permanent scoped dev evidence: PASS; current database/application-client scope reports
  16/16 encrypted and 0 unencrypted in `cloudsql-transport-dev-enforced-20260811T074206452Z.json`
Cloud SQL dev fail-closed transport audit: PASS; server `ENCRYPTED_ONLY`, 5/5 services and 6/6 jobs
  require TLS, fresh active-session evidence verified, `compliant=true`, no violations
Cloud SQL dev post-enforcement authenticated smoke: PASS; 40/40 checks
Cloud SQL production transport audit: BLOCKED; production server/service/job/session-evidence gates
  remain and no production mutation was performed
Recovery production negative gates: PASS; missing prod authorization, wrong prod source, prod flag on
  dev, and prod KeepOnFailure fail before cloud access/mutation
Recovery cleanup evidence ordering: PASS static/parse check; PASSED is finalized only after all
  three cleanup confirmations, and cleanup-failed evidence is written before throwing
Recovery dev read-only dry-run: BLOCKED safely before clone/export because live automated backups are
  disabled and PITR is not enabled; no recovery resources created
Production runtime IAM dry-run: PASS; 0 missing prerequisites; no mutation
Production reporting OIDC dry-run: PASS; legacy query/default identity detected; no mutation
Live redacted readiness audit `readiness-final-20260811.json`: PASS as evidence collector; 9 scripted
  blockers reported, plus the separately documented production SQL transport gate = 10 executive gates
Frontend Docker production build: PASS
Trivy 0.70.0 HIGH/CRITICAL scan of rebuilt final frontend image: PASS, 0 findings
Frontend suite/build: PASS, 147/147 tests; npm audit --audit-level=moderate PASS, 0 findings; React Router 7.18.2
API gateway npm audit --audit-level=high: PASS, 0 findings
GitHub Security / Container scan run 31443426825: correctly FAILED on 10 HIGH finding occurrences
  representing 4 distinct JAR CVEs across 5 Java image jobs; frontend and gateway passed; no
  HIGH/CRITICAL OS findings
Five remediated Java Maven service suites under JDK 25 after integration: PASS; 1,023 tests, 0
  failures, 0 errors, 0 skipped (identity 117, school-core 500, operations 124, platform 228,
  billing 54)
Five rebuilt Java images, Trivy 0.70.0 HIGH/CRITICAL OS/JAR/Go scan: PASS, 0 findings
Final CodeQL run 31509672266: PASS, Java/Kotlin and JavaScript/TypeScript; dev open CodeQL = 0
Stable-category container run 31509695990: PASS, 7/7 images; dev Trivy = 239 total,
  0 CRITICAL, 0 HIGH, 209 MEDIUM, 30 LOW
Three-service dev release 31509672530: PASS; exact-digest HIGH/CRITICAL and SARIF evidence,
  Cloud Run verification, gateway health and retained release evidence
```

## 10. External Approval Checklist

The following cannot be completed safely by a source-only change:

- repository administrator enables Dependabot alerts/security updates, verifies secret scanning,
  and enables push protection after the current integration push;
- repository administrator applies branch protection only after a fresh PR proves `summary` and the
  two CodeQL checks; branch protection is the final GitHub mutation;
- GCP IAM administrator applies WIF restriction, creates/splits deploy identities, and changes
  target/job execution accounts;
- production operator approves and observes per-service IAM canaries;
- production operator approves reporting OIDC migration and token rotation;
- database/platform operator completes the remaining 24-hour dev TLS observation, then performs a
  newly approved production client-first/server-second cutover with fresh scoped `pg_stat_ssl`
  evidence; the dev rollout, 40/40 smoke and immediate scoped transport proof are complete;
- security/database owners decide whether `ENCRYPTED_ONLY` plus `require` is an interim control or
  whether verified endpoint identity (`verify-full` or Cloud SQL Java Connector) is mandatory for
  production closure;
- secret owners define per-secret consumers, rotation SLAs, and provider/database procedures;
- product/security owner decides whether the approximately USD 28.25/month WAF/load-balancer
  baseline is justified and supplies DNS/domain control;
- security owner reviews the remaining MEDIUM/LOW dev Trivy backlog, the stale `main` HIGH backlog,
  Dependabot/secret-scanning availability and any approved exceptions; dev CodeQL is currently zero.

Until these gates are completed and evidenced, broad default-compute roles and the old production
reporting path must remain in place, production Cloud SQL transport must not be represented as
remediated, and production promotion remains blocked.
