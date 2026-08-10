# Security and Governance Planned Changes

Date: 2026-08-11 IST
Scope: repository controls, GitHub governance, Google Cloud runtime/deployment IAM, Pub/Sub push authentication, secret lifecycle, dependency and container security, and public ingress
Live project: `custoking` (`305630109861`)
Repository: `custokingkr-dev/ims-v1`

## Safety and Evidence Boundary

This workstream used read-only GitHub and Google Cloud inspection. It did not change production,
deploy a revision, change a GitHub setting, change IAM, create a service account, rotate a secret,
or alter traffic. Secret payloads were never read by the repository audit tool and no secret value
is recorded here.

The detailed machine-readable audit is generated locally at
`artifacts/security-governance-readiness-2026-08-11.json`. The artifact is intentionally ignored by
Git. Run the same redacted audit with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File `
  scripts/audit-security-governance-readiness.ps1 `
  -IncludeSecretVersionMetadata `
  -OutputJson artifacts/security-governance-readiness.json
```

The evidence below was collected on 2026-08-11 IST. A `404` from the classic branch-protection
endpoint plus an empty ruleset inventory is treated as “no visible protection.” Dependabot and
secret-scanning alert APIs could not be read with the current operator token (`403` and `404`
respectively), so their alert counts are explicitly unknown rather than assumed to be zero.

## Executive Decision

Production security/governance is **not approved for promotion yet**. Nine independently verified
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
9. GitHub has 51 open HIGH container findings (zero CRITICAL) across 296 open Trivy alerts.

Source-side preparation and one verified image remediation are complete. The remaining changes are
live control-plane operations and deliberately require explicit authorization.

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
4. Grant each execution identity `roles/clouddeploy.jobRunner` and only the Cloud Run deployment
   permissions it needs. Grant `iam.serviceAccounts.actAs` on the seven runtime identities at the
   service-account resource, not at project scope.
5. Add explicit `executionConfigs` for all 14 targets; never rely on the default execution identity.
6. Create dedicated identities for the eight jobs that still use default compute. Give each only
   its required Cloud SQL, secret, storage, and invocation permissions.
7. Change prod target runtime parameters to the seven dedicated identities.
8. Deploy one service at a time through the existing canary path and run authenticated smokes.
9. Migrate reporting push as described below.
10. Observe one school-day peak with no `PERMISSION_DENIED` events.
11. Remove default-compute roles one at a time. Re-run rollout, job, reporting, and service-call
    tests after each removal.
12. Retain logging/tracing roles only if Cloud Asset/IAM evidence proves a remaining workload uses
    them.

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
- current action references use mutable major/version tags rather than immutable commit SHAs.

The CI `summary` job is stable and aggregates affected-service tests, Docker builds, and the secret
scan. The new CodeQL jobs have unique names. GitHub documents that branch protection can require
reviews and successful status checks, and that duplicate job names can make required checks
ambiguous. See [managing protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)
and [protected branch behavior](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).

### 3.2 Prepared guarded change

`scripts/configure-security-governance-controls.ps1` is dry-run by default. GitHub changes require
both `-ApplyGitHub` and `-AllowExternalMutation`. Its proposed policy for both branches is:

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
- dev Environment restricted to the `dev` branch.

Run the new workflows successfully once before applying protection, then verify the exact check
names from the commit checks API. Pin every third-party action to a reviewed full commit SHA in a
separate mechanical change; let Dependabot maintain those pins.

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
[Google WIF attributes and conditions](https://docs.cloud.google.com/iam/docs/workload-identity-federation)
and [GitHub OIDC claims](https://docs.github.com/en/actions/reference/security/oidc).

### 4.2 Prepared guarded change

The governance script proposes a condition requiring:

- repository ID `1207086249`;
- owner ID `274906704`;
- `refs/heads/main` or `refs/heads/dev`;
- an allowlist of `build-release.yml`, `rollback.yml`, `gcp-cost-controls.yml`, and
  `recovery-drill.yml` at the appropriate protected branch.

WIF changes require both `-ApplyWorkloadIdentity` and `-AllowExternalMutation`. Branch protection
must be applied first. After that interim restriction is proven, split identities:

- dev image builder;
- dev release operator;
- prod release operator protected by the prod Environment;
- rollback operator;
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

## 5. Secrets and Rotation

### 5.1 Verified live state

- 44 Secret Manager secrets exist;
- none has `rotationPeriod` plus `nextRotationTime` configured;
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

### 5.3 Acceptance and rollback

Every credential needs an owner, rotation SLA, last-tested timestamp, consumer inventory, and
rollback version. A new version is not accepted until a canary uses its numeric version. Disable,
then destroy old versions only after the overlap window and evidence review.

## 6. Dependency, Container, and Source Scanning

### 6.1 Verified live state before remediation

- weekly Trivy workflow latest run: successful;
- open Trivy alerts: 296 total, 51 HIGH, 223 MEDIUM, 20 LOW, 2 without normalized severity, zero
  CRITICAL;
- 69 alerts were created by the latest 2026-08-09 scan, including fixable HIGH packages in the old
  frontend `nginx:1.27-alpine` runtime;
- Dependabot covered only GitHub Actions and Docker images;
- no CodeQL workflow existed;
- Artifact Analysis container/on-demand scanning APIs were not enabled;
- operator token could not read Dependabot or secret-scanning alert inventories, so those counts
  remain unverified.

A green scheduled run was therefore not evidence of zero HIGH findings.

### 6.2 Repository changes completed

- Added Dependabot coverage for both npm projects and all five Maven projects.
- Added CodeQL `security-extended` analysis for Java and JavaScript/TypeScript on PRs, branch pushes,
  a weekly schedule, and manual dispatch. GitHub supports no-build analysis for Java and JS/TS in a
  public repository; see [CodeQL compiled-language build modes](https://docs.github.com/en/code-security/how-tos/find-and-fix-code-vulnerabilities/manage-your-configuration/codeql-for-compiled-languages).
- Split Trivy reporting from enforcement. Both PR and scheduled image gates now fail on any HIGH or
  CRITICAL result, including findings without a fix; SARIF still uploads on failure.
- Upgraded the frontend runtime from the obsolete pinned nginx/Alpine image to the official pinned
  `nginx:1.30.4-alpine3.24` multi-architecture digest.
- Updated transitive `nanoid` from `3.3.16` to `3.3.18`, removing the npm HIGH finding.
- Raised the frontend npm CI gate from CRITICAL to HIGH.

Local verification of the rebuilt final frontend image with the same Trivy major used by
`trivy-action@v0.36.0` reported zero HIGH/CRITICAL OS or library vulnerabilities. Frontend npm now
has zero HIGH/CRITICAL and two MODERATE React Router advisories; gateway npm has zero findings. The
moderate React Router fixes require a compatibility-reviewed route upgrade and are not silently
forced in this workstream.

The new workflow must run on GitHub before old SARIF alerts can be reconciled. Do not dismiss old
alerts in bulk without proving they are absent from the current digest.

### 6.3 Optional Artifact Analysis cost

Google lists automatic or on-demand Artifact Analysis at **USD 0.26 per scanned image digest**. A
seven-image full release is approximately USD 1.82; one seven-image weekly full scan is about USD
7.28 over four weeks, with partial affected-service builds costing less. Tags do not trigger a new
digest scan charge. See [Artifact Analysis pricing](https://cloud.google.com/artifact-analysis/pricing).

Enable Artifact Analysis only after budget approval and use it as a registry-side second opinion;
the existing GitHub Trivy gate remains the zero-additional-GCP-cost primary gate.

### 6.4 Remaining supply-chain gates

- run CodeQL and remediate any HIGH/CRITICAL source finding;
- reconcile the 51 existing HIGH GitHub alerts against current image digests;
- verify Dependabot alerts and secret-scanning/push-protection settings using a token with the
  required admin/security scopes;
- pin every external action to a full reviewed SHA;
- add an exception file with owner, expiry, and rationale if a vulnerability truly cannot be fixed;
- fail production promotion if any current digest has an unexpired HIGH/CRITICAL finding.

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
- `scripts/audit-security-governance-readiness.ps1`
- `scripts/audit-security-governance-controls.ps1`
- `scripts/configure-security-governance-controls.ps1`
- `docs/workstreams/SECURITY-GOVERNANCE-CHANGES-2026-08-11.md`

Files changed:

- `.github/dependabot.yml`
- `.github/workflows/ci-pr.yml`
- `.github/workflows/security-scan.yml`
- `frontend/Dockerfile`
- `frontend/package-lock.json`
- `scripts/configure-runtime-service-accounts.ps1`
- `scripts/configure-reporting-pubsub-push-oidc.ps1`

The two existing production configurators are now inspectable without a production authorization
flag, but still require explicit double authorization to mutate production.

## 9. Validation Performed

```text
Security governance static/parse audit: PASS
Governance configurator dry-run: PASS; no external mutation
Production runtime IAM dry-run: PASS; 0 missing prerequisites; no mutation
Production reporting OIDC dry-run: PASS; legacy query/default identity detected; no mutation
Live redacted readiness audit: PASS as evidence collector; 9 blockers reported
Frontend Docker production build: PASS
Trivy 0.70.0 HIGH/CRITICAL scan of rebuilt final frontend image: PASS, 0 findings
Frontend npm audit --audit-level=high: PASS; 2 MODERATE remain
API gateway npm audit --audit-level=high: PASS, 0 findings
```

## 10. External Approval Checklist

The following cannot be completed safely by a source-only change:

- repository administrator applies branch protection and verifies GitHub security settings;
- GCP IAM administrator applies WIF restriction, creates/splits deploy identities, and changes
  target/job execution accounts;
- production operator approves and observes per-service IAM canaries;
- production operator approves reporting OIDC migration and token rotation;
- secret owners define per-secret consumers, rotation SLAs, and provider/database procedures;
- product/security owner decides whether the approximately USD 28.25/month WAF/load-balancer
  baseline is justified and supplies DNS/domain control;
- security owner reviews current CodeQL/Trivy/Dependabot/secret-scanning alerts and owns exceptions.

Until these gates are completed and evidenced, broad default-compute roles and the old production
reporting path must remain in place, and production promotion remains blocked.
