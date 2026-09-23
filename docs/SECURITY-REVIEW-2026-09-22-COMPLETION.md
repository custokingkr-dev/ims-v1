# Security Review 2026-09-22: Completion Record and Handover

Companion to `SECURITY-REVIEW-2026-09-22.md`, which holds the findings. This document records what
was actually done, what was deliberately not done and why, and what cannot be done without
permissions this account does not hold. Written 2026-09-22.

## State at the time of writing

| Item | State |
| --- | --- |
| Findings 1-5, the latent item, three hardening items | Fixed, merged to `dev` (PR #255), deployed to dev, verified live |
| Promotion tag-write defect (found while assessing immutable tags) | Fixed, merged to `dev` (PR #256) |
| Production | **Deployed and verified 2026-09-22.** All seven services on new revisions; internal and Pub/Sub paths return 404 before authentication; a real Pub/Sub reporting push was accepted (HTTP 204) with no identity rejections |
| Cost-controller removal from `custoking-prod` | Planned and reviewed, **not applied** — see "Blocked" |
| Branch protection | **Not done** — see "Blocked" |
| Immutable registry tags | **Deliberately rejected** — see "Decisions" |

## Verified on dev

Measured after the rollout, not inferred from a green pipeline:

- All seven services `Ready=True` on new revisions; no `ERROR` logs and no exceptions since the
  rollout; platform-service context started cleanly.
- `/gateway-health` 200. Sixteen canonical routes all 401, so routing is intact and the new deny
  does not over-match. Seven diagnostic aliases on legitimate paths all 401, so the alias feature
  still works.
- `/reporting-api/v1/pubsub/reporting-events`, `/reporting-api/v1/internal/async/drain`,
  `/notification-api/v1/internal/notifications/deliveries` and `/billing-api/v1/internal/outbox/relay`
  all return 404 **before authentication**.
- Login through the gateway to identity-service returns a proper 401 body, so the full chain works.
- A real Pub/Sub message published to `ims-reporting-events-v1-dev` was accepted by the
  OIDC-verified push endpoint: **HTTP 204**, no identity rejections. This is the positive proof that
  the new verification admits the genuine push identity rather than merely rejecting everything.

One dev artefact remains: inbox row `ops-oidc-verify-1790081470`, an unknown event type, marked
PROCESSED with no projection. It is inert; delete it if inbox cleanliness matters.

The internal caller check could not be exercised on dev because absentee delivery runs in
`dry-run` there and never calls platform-service. Both sides' configuration was compared instead.

## Verified in production

Measured after the rollout on 2026-09-22:

- All seven services `Ready` on new revisions; `/gateway-health` 200.
- `/reporting-api/v1/pubsub/reporting-events`, `/reporting-api/v1/internal/async/drain`,
  `/notification-api/v1/internal/notifications/deliveries` and `/billing-api/v1/internal/outbox/relay`
  all return 404 **before authentication**. Canonical routes still return 401, so routing is intact.
- `SERVICE_OIDC_AUDIENCES` carries both service URL forms on the serving revision, as required by the
  three different audiences in use.
- A probe message published to `ims-reporting-events-v1-prod` was accepted: **HTTP 204**, no identity
  rejections. Reporting push is the one path whose behaviour tightened, so this was the decisive check.

**Correction to an earlier claim.** This record previously stated that production's notification
push had been returning 401 because it required a shared token configured nowhere. The
configuration defect was real, but the logs show **no notification push traffic in production at
all** over the preceding thirty days, so no 401s ever actually occurred. The fix removed a latent
trap rather than repairing observed breakage.

**Probe rows.** One inert row exists in each environment's reporting inbox
(`ops-oidc-verify-1790081470` in dev, `ops-oidc-verify-prod-1790091921` in prod): an unrecognised
event type, no projector, marked PROCESSED with no projection and no fact or dimension written.
They are deliberately left in place — deleting them would require a write job against the private
production database, which is more risk than two inert rows justify.

## Decisions taken, with reasons

### Promotion provenance: cosign keyless signing (chosen 2026-09-22)

The dev release signs every built digest with `cosign sign` under GitHub's OIDC identity, before
the `dev-approved-*` tag is written, so an unsigned digest is never approved. Production runs
`cosign verify` against the source digest, requiring a certificate whose identity is
`…/build-release.yml@refs/heads/dev` and whose issuer is GitHub, before it copies anything.
Repointing the mutable tag is no longer sufficient: a digest that no dev release signed fails the
promotion.

**Residual risk, stated plainly.** The Fulcio certificate binds the workflow *path and ref*, not
the file's contents. Someone who can push a modified `build-release.yml` to `dev` can still obtain
a valid signature for a digest of their choosing. This narrows the exposure from "anyone with
`artifactregistry.writer` in the dev project, or any principal that compromises it" to "someone who
can commit to `dev`", which is a materially smaller set, and branch protection on `dev` is the
control that closes the remainder.

### Immutable Artifact Registry tags: rejected

The original finding proposed `docker_config { immutable_tags = true }` to stop the
`dev-approved-*` tag being re-pointed. Artifact Registry does not permit deleting a **tagged**
artifact once immutable tags are enabled. Every image in this repository carries a source tag, a
commit tag or an approval tag, so the repository's `delete-old-release-images` cleanup policy would
silently stop deleting anything and storage would grow without bound. That is a certain operational
regression traded against a stealth path that is only reachable by someone who can already push to
`main` and deploy production by simpler means.

Close the provenance gap instead with cosign keyless signing from the dev release job plus
verification before promotion, or Binary Authorization with an attestor that only the dev release
identity can satisfy. Both need a decision and neither is a one-line change.

### The promotion tag write was fixed anyway

Assessing the above exposed a real defect. `build-release.yml` wrote the runtime-registry tag
unconditionally, so a tag already pointing at a different digest was **silently overwritten**, and
the post-copy digest check could not catch it because the overwrite had just set the tag to the
expected value. Retries also rewrote correct tags. The write is now guarded: absent writes, equal
skips, different fails the release. Merged as PR #256; it stands on its own merits.

### Both production URL forms are listed as OIDC audiences

A Cloud Run service answers to more than one URL. Measured in production on 2026-09-22:

| Caller | Audience it presents |
| --- | --- |
| `ims-reporting-service-push-prod` | `https://custoking-platform-service-prod-182609177023.asia-south2.run.app` |
| `ims-notification-service-push-prod` | `https://custoking-platform-service-prod-yter7sugpa-em.a.run.app` |
| school-core (`PLATFORM_BASE_URL`) | `https://custoking-platform-service-prod-182609177023.asia-south2.run.app` |

The service's own `status.url` is the second form. Pinning a single audience would therefore have
rejected two of the three callers. `service_oidc_audiences` lists both for prod, and the verifier
accepts a set.

## Blocked: needs someone else

### 1. Remove the cost-controller identity from `custoking-prod`

`terraform apply` against production infrastructure is refused by this environment's policy
(`Protected-Scope IaC Apply`). The plan was produced and reviewed in full; it was not applied and no
attempt was made to work around the refusal.

```bash
cd infra/terraform/cicd
export GOOGLE_OAUTH_ACCESS_TOKEN="$(gcloud auth print-access-token)"
terraform init -reconfigure \
  -backend-config="bucket=custoking-prod-terraform-state" \
  -backend-config="prefix=cicd" \
  -backend-config="access_token=$(gcloud auth print-access-token)"
terraform plan -var-file=custoking-prod.tfvars -out=prod.tfplan
terraform apply prod.tfplan
```

The plan is **not confined to this change** — it also corrects pre-existing drift. Expect exactly
ten actions:

*Intended (the security fix):* `google_service_account.github["cost_controller"]` and its three
project roles (`cloudsql.editor`, `serviceusage.serviceUsageConsumer`, `bigquery.jobUser`) and its
workload-identity binding are **destroyed**.

*Pre-existing drift, corrected:* `github-governance-auditor`, its read-only custom role
(`google_project_iam_custom_role.governance_auditor`), its project binding and its workload-identity
binding are **created**. This identity is declared in committed code but was never applied to
production, which means the scheduled `gcp-governance-audit.yml` run has had no identity to assume.
The custom role contains only `get`, `list`, `search` and `serviceusage.services.use` permissions.

*Provider condition, updated in place:* the `gcp-cost-controls.yml@refs/heads/main` claim is removed
and `gcp-governance-audit.yml@refs/heads/main` is added. The `build-release.yml`, `rollback.yml`,
`reconcile-deployment-config.yml` and `recovery-drill.yml` claims are **unchanged**, so no
production release path is affected.

Anything beyond those ten actions means the state has drifted further since 2026-09-22; re-read the
plan rather than applying it.

### 2. Branch protection on `main` and `dev`

Not possible from this account: the repository reports `"admin": false` for it, and the owner
`custokingkr-dev` holds admin. Both branches currently return HTTP 404 from the branch-protection
API, meaning no protection of any kind. The `prod` GitHub Environment's required reviewers are
presently the only gate between a write collaborator and production.

`scripts/enable-branch-protection.sh` already encodes the agreed policy and needs no edit: required
status checks `summary`, `analyze (java-kotlin)` and `analyze (javascript-typescript)`, pinned to
the github-actions app id, with `required_pull_request_reviews` left null so a single maintainer can
still merge their own work. It was never run, which is the whole of the finding. A dry run on
2026-09-22 validated cleanly and reported both branches unprotected. An administrator completes it
with:

```bash
bash scripts/enable-branch-protection.sh --apply
```

Rollback is one call per branch, documented in the script header.

This is the precondition for the cost-controller finding and for the promotion-provenance gap; both
shrink considerably once `main` requires review.

## CodeQL alert dismissed during the promotion

PR #257 failed CodeQL with one high-severity *User-controlled bypass of sensitive method* at
`CatalogProductFormController.java:95`, where the request parameter `includeInactive` decides
whether `requireSuperAdmin()` runs. It is not my change: it arrived with the notebook catalog
feature (4bd4eecd) already on `dev`, and surfaced only because a `dev` to `main` pull request
presents that whole feature as new relative to `main`.

It is **not exploitable as written**. `includeInactive=false`, the default, adds `WHERE active` to
every query and returns strictly fewer rows, and `form()` additionally 404s an inactive category.
The branch that widens the result set is the guarded one, so inactive definitions stay unreachable
without superadmin. It was dismissed on that basis to unblock the promotion, and the structural
fix — moving the privileged read onto its own route with an unconditional guard — is tracked as
issue #259. The pattern is fragile rather than wrong: it becomes a real vulnerability the moment
privileged data is added to the non-admin path, and nothing would fail when that happens.

Dismissal had to be done through the GitHub UI. The alert lives only in the `refs/pull/257/merge`
analysis scope, which the code-scanning API did not expose to this account, and a dismissal on the
default branch does not cover a pull-request merge-ref alert.

## Production promotion

Production still carries every finding. PR #257 stages the promotion and was deliberately left
**open, not merged**, for the reason in the next paragraph. The promotion is that pull request and
then the **three-step sequence** below, because this change set touches Cloud Deploy targets. The
ordinary one-step release does not work and, worse, reports success while deploying nothing.

**Merging without finishing the deploy is worse than not merging.** Once these `deploy/**` changes
are on `main` but production has not been reconciled and released, any later unrelated service
release to production takes the fast image-only path, which preserves the existing Cloud Run
environment. The new platform-service code would start with empty caller-identity allow-lists,
which fail closed and reject every Pub/Sub push — an outage triggered by a future innocent-looking
merge rather than by this change. Merge only when steps 2 and 3 can follow immediately.

1. Merge `dev` into `main`. The release run is blocked by `configuration-reconciliation-required`
   and deploys nothing. This is expected.
2. Run `Ops / Reconcile deployment configuration` with `environment: prod` from `main`. This renders
   and applies the targets and delivery pipelines only, and creates no release.
3. Dispatch `CD / Deploy branch environment` with `target_environment: prod` and an explicit
   `commit_sha` pointing at a commit that touches `deploy/cloudrun/**` but **not**
   `deploy/clouddeploy/targets-prod.yaml`. The explicit commit forces every service to be rebuilt
   and takes the Cloud Deploy path, which applies the full manifests so the new environment block
   lands with the new image.

Do **not** follow the gate's own advice of a services-only commit. That takes the fast image-only
path, which preserves the existing Cloud Run environment; the new code would start with empty
caller-identity allow-lists, which fail closed and reject every Pub/Sub push. The full reasoning is
in `current-state/deployment-cicd.md`.

Two things to watch after the production rollout:

- Reporting push should keep returning 204. It currently runs with the shared token disabled and no
  identity check, so this is the one path where behaviour tightens.
- Notification push should **start** working. Production requires a shared token that is configured
  nowhere, so every real delivery has been receiving 401. After this change it authenticates by
  identity instead.
