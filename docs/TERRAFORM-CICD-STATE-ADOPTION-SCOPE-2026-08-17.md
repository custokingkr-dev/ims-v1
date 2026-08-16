# Scope: bringing `infra/terraform/cicd` under state management

Status: scope only. Nothing here has been executed.
Evidence date: 2026-08-16 (UTC), verified against the live `custoking` project.

## Problem

`infra/terraform/cicd` declares the CI/CD identity surface — service accounts, workload identity
federation, Artifact Registry, and their IAM — but Terraform holds no state for any of it.

```
$ terraform state list
No state file was found!

$ terraform plan
Plan: 95 to add, 0 to change, 0 to destroy.
```

There is no `backend` block, no local `terraform.tfstate`, and `gs://custoking-terraform-state`
contains only `observability/dev` and `observability/prod`. The resources exist in GCP; Terraform
simply does not know about them.

Consequence: `terraform apply` is currently unsafe. It would attempt to create ten service accounts,
a workload identity pool and provider, and the Artifact Registry repository that already exist,
failing partway through after potentially mutating IAM on the identities all CI/CD depends on.

`infra/terraform/cicd/README.md` already documents an import-first sequence. This document scopes
what that actually involves against the live project, because the README predates the current drift.

## What the 95 resources are

| Resource type | Count | Behaviour if applied while it already exists |
| --- | ---: | --- |
| `google_service_account_iam_member` | 34 | Idempotent no-op; import for accuracy |
| `google_project_iam_member` | 25 | Idempotent no-op; import for accuracy |
| `google_service_account` | 10 | **Fails** — must import |
| `google_storage_bucket_iam_member` | 9 | Idempotent no-op; import for accuracy |
| `google_project_service` | 8 | **Fails** — must import |
| `google_artifact_registry_repository_iam_member` | 4 | Idempotent no-op; import for accuracy |
| `google_artifact_registry_repository` | 1 | **Fails** — must import |
| `google_bigquery_dataset_iam_member` | 1 | Idempotent no-op; import for accuracy |
| `google_iam_workload_identity_pool` | 1 | **Fails** — must import |
| `google_iam_workload_identity_pool_provider` | 1 | **Fails** — must import |
| `google_project_iam_custom_role` | 1 | **Fails** — must import |

**22 must be imported or the apply fails. 73 are additive IAM members** which would succeed as
no-ops, but should still be imported so state reflects reality rather than accumulating implicitly.

## The blocking decision: four dev identities do not exist

This is the part that makes this a migration rather than bookkeeping. Of the ten declared service
accounts, four have no live counterpart:

| Declared | Live | Consequence |
| --- | --- | --- |
| `github-release-prod` | exists | import |
| `github-rollback-prod` | exists | import |
| `github-config-prod` | exists | import |
| `github-cost-controller` | exists | import |
| `custoking-recovery-operator` | exists | import |
| `clouddeploy-prod-deployer` | exists | import |
| `github-release-dev` | **missing** | genuine create |
| `github-rollback-dev` | **missing** | genuine create |
| `github-config-dev` | **missing** | genuine create |
| `clouddeploy-dev-deployer` | **missing** | genuine create |

Every prod identity exists; **no dev identity does.** Dev currently runs as the shared
`github-actions-sa`, which the repository-level `DEPLOY_SERVICE_ACCOUNT` variable supplies as a
fallback whenever a dev environment sets nothing. This was already observed on 2026-08-16 while
granting scan-evidence bucket IAM, where binding only the declared `github-release-dev` would have
silently failed open and cached nothing on dev.

So the config describes an intended end state that was never reached. Adopting it as written does
not merely record reality — it would create four new identities and start a migration.

**This decision must be made before any import begins**, because it determines whether the four
declarations are imported, created, or deleted:

- **Option A — complete the migration.** Create the four identities, bind their roles, and move dev
  off the shared account. Better isolation: dev would no longer authenticate as an account that also
  holds prod-adjacent permissions. Largest change, and every dev workflow's service-account variable
  must move in step or dev CI breaks.
- **Option B — match reality.** Remove the four declarations and keep dev on `github-actions-sa`.
  Smallest change and makes state truthful immediately, but permanently records a shared-identity
  posture that the config's own author evidently intended to fix.
- **Option C — split.** Adopt the six existing identities now, leave the four declared but excluded
  from this module, and treat the dev-identity migration as separate work with its own rollback plan.

Option C is recommended. It separates a low-risk bookkeeping exercise from a change that can break
dev CI, and lets the first apply reach a clean no-op before anything is migrated.

## Phases

### Phase 0 — backend

Without a remote backend, any state produced lives on one workstation and is not shared, which is not
state management. Mirror the pattern `deploy/gcp/observability` already uses against
`gs://custoking-terraform-state`, under a `cicd/` prefix. Enable versioning on the prefix so a
corrupted state can be rolled back.

Definition of done: `terraform init` reports the GCS backend, and `terraform state list` runs without
error against an empty remote state.

### Phase 1 — import the 22 hard-fail resources

Import in dependency order: `google_project_service`, then the pool and provider, then the Artifact
Registry repository and custom role, then the six existing service accounts. Import writes state
only; it does not mutate infrastructure, so this phase is reversible by deleting the state object.

After each import, `terraform plan` should show that resource moving out of "to add". Do not batch
blindly — a wrong import ID binds state to the wrong object and is then hard to unpick.

Definition of done: plan shows 0 hard-fail resources remaining to create.

### Phase 2 — import the 73 IAM members

Mechanical but voluminous. Each `google_project_iam_member` imports as
`"<project> <role> <member>"`, and the conditional bindings added on 2026-08-16 need their condition
title included or Terraform will not match them.

Pay particular attention to the two conditional bindings, since a mismatch here shows up as a
spurious create-and-replace rather than a clean no-op:

- `google_storage_bucket_iam_member.release_scan_evidence_object_admin` — condition
  `scan_evidence_trivy_prefix_only`
- `google_storage_bucket_iam_member.recovery_bucket_policy_operator` — condition
  `recovery_bucket_and_validation_prefix_only`

Definition of done: plan shows 0 to add, 0 to change, 0 to destroy — or only the four dev identities
if Option A was chosen.

### Phase 3 — first apply

Only once the plan is a clean no-op. Read it line by line. Any `destroy` or `replace` on an identity,
pool, or provider means an import bound the wrong object; stop and fix state rather than applying.

Definition of done: apply completes with no changes, and CI/CD still authenticates — verified by a
real dev release and a `Ops / GCP cost controls` run, not by inspection alone.

## Risks

| Risk | Mitigation |
| --- | --- |
| Wrong import ID binds state to the wrong object | Import one at a time; check plan after each |
| First apply destroys or replaces a live identity | Treat any destroy/replace in the plan as a stop condition |
| Breaking WIF mid-migration locks all workflows out of GCP | Do not touch the pool or provider outside a reviewed plan; `gcloud` remains the break-glass path |
| Conditional bindings re-created rather than matched | Import with the condition; verify plan shows no change |
| Local state created by accident before Phase 0 | Do Phase 0 first; never run apply from a workstation without the backend configured |

## Explicitly out of scope

- The dev-identity migration itself under Option C.
- `deploy/gcp/observability`, which already has remote state and is unaffected.
- Any change to the live IAM granted on 2026-08-16. Those bindings are correct and working; this work
  records them, it does not alter them.

## Effort

The resource counts are exact. Wall-clock is not estimated here: Phase 2 is 73 largely mechanical
imports whose duration depends on how many need their IDs derived by hand, and guessing a figure
would be less useful than the counts above.
