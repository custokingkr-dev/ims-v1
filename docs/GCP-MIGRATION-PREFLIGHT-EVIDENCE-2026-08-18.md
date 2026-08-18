# Custoking Split-Project Migration — Preflight Evidence, 18 August 2026

**Prepared:** 2026-08-18 IST
**Method:** read-only `gcloud` discovery as the operating account, plus four deliberate API enablements
recorded in section 5 below.
**Runbook:** `GCP-SPLIT-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md`
**Companion:** `GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md`
**Status:** production migration remains **NO-GO**. One new hard blocker was found; the original blocker
is cleared.

Numeric project numbers, organization IDs, and billing account IDs are deliberately excluded from this
file and belong in the restricted migration evidence location, per the runbook's evidence-handling rule.

## 1. What changed since the runbook was written

The runbook and the data-integrity plan both record that, as of 14 August 2026, neither destination
project was visible to the operating account. **That is no longer true.** `custoking-dev` and
`custoking-prod` both exist and are `ACTIVE`.

Two facts were discovered that the runbook did not anticipate.

### 1.1 The migration is cross-organization, not merely cross-project

The destination projects were created from a second Google account and sit under a **different
organization** than the source project `custoking`. The runbook's section 4 wording — destinations exist
"under the intended organization/folders" — was written assuming a single organization. Two organizations
is a materially different change, and it must be recorded as a deliberate decision rather than absorbed as
an incidental detail.

Consequences that are now confirmed rather than assumed:

- Destination project-level IAM lists only the creating account as owner. The operating account reaches
  both destinations purely by **inheritance from organization-level roles** (owner, organizationAdmin,
  folderAdmin, projectCreator, billing.admin, billing.creator, billing.user, iam.securityAdmin,
  serviceUsageAdmin, workforcePoolAdmin). Two independent administrative identities therefore already
  exist on the destination side, satisfying the runbook's rollback-access rule without new grants.
- The destination organization enforces Google's secure-by-default policy set:
  `iam.disableServiceAccountKeyCreation`, `iam.disableServiceAccountKeyUpload`,
  `iam.automaticIamGrantsForDefaultServiceAccounts`, `storage.uniformBucketLevelAccess`,
  `compute.setNewProjectDefaultToZonalDNSOnly`, and `compute.restrictProtocolForwardingCreationForTypes`.
  Three of these actively agree with the runbook's own intent (no default-Compute breadth, uniform bucket
  access, no exported keys).
- **`iam.allowedPolicyMemberDomains` is not set**, so the cross-organization grants that Cloud SQL
  export/import and Storage Transfer Service require are permitted.
- **`gcp.resourceLocations` is not set**, so `asia-south2` is permitted.
- The enforced service-account-key ban is **not** a blocker: the repository contains no service account
  keys, and all five GCP-touching workflows (`build-release`, `gcp-cost-controls`,
  `reconcile-deployment-config`, `recovery-drill`, `rollback`) already authenticate through Workload
  Identity Federation.

### 1.2 The source organization is invisible to the operating account

`custoking` sits under an organization the operating account cannot describe. Its organization policies
cannot be read, and eventual decommissioning of the source project will require whoever holds that
organization. This is an unassigned owner on the retention and rollback path and must be resolved before
cutover, not during it.

## 2. New hard blocker: no access to the destination billing account

Both destination projects have billing **enabled and attached** — to a billing account the operating
account has **no permission on**. This was verified rather than inferred:

- listing billing accounts returns three accounts, and the destination account is **not among them**;
- describing the destination billing account returns `PERMISSION_DENIED`;
- after the Cloud Billing Budget API was enabled, listing budgets on the **source** account succeeded
  (returning the existing `Custoking Monthly Guardrail`, ₹5,000 INR), while the same call against the
  **destination** account still failed. The API was therefore not the cause; the denial is on the billing
  account itself.
- organization-level `roles/billing.admin` on the destination organization does **not** confer access,
  which indicates the destination billing account is not owned by that organization.

This fails two runbook requirements outright:

| Requirement | Effect |
| --- | --- |
| §4 — "billing is attached; environment-specific budgets and alert recipients are enabled **before paid resources are created**" | Budgets and alert recipients cannot be created at all on the destination account. |
| §11 — "create project-specific budgets"; "review daily gross cost for all three projects during coexistence" | Neither is possible; destination spend is unobservable to the operating account. |

Two further downstream effects:

- the BigQuery billing export and the daily `gcp-cost-controls` workflow are both bound to the source
  billing account. A second billing account needs its own export configured, or **all** cost reporting on
  the destinations is dark;
- whether the destination billing account carries its own free-trial credit — the single fact that
  determines how long source and destination can affordably coexist — is **unknowable** without access.

Given the prior budget incident recorded in `GCP-BUDGET-INCIDENT-2026-08-11.md`, creating paid resources
under an unobservable, un-budgeted billing account reproduces exactly the known failure mode.

**To clear:** either grant the operating account Billing Account Administrator (or at minimum Viewer plus
Budgets Admin) on the destination billing account, or re-link both destination projects to a billing
account the operating account already controls. Until one of these happens, no paid destination resource
should be created.

## 3. Section 4 hard prerequisites — live status

| # | Prerequisite | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Destinations exist and are visible to primary and backup operators | **PASS** | both `ACTIVE`; two admin identities via organization inheritance |
| 2 | Billing attached; environment budgets and alert recipients enabled | **FAIL** | attached, but account is inaccessible; see section 2 |
| 3 | Organization policies, allowed regions, IAM/SA constraints recorded for both destinations | **PARTIAL** | destination policy set enumerated (§1.1); **source organization policies unreadable** (§1.2) |
| 4 | `asia-south2` Cloud Run and Cloud SQL quotas sufficient | **PASS** | see section 4 |
| 5 | Required APIs and service agents enabled deliberately | **OPEN** | destinations carry only the default API set plus the four in section 5; deliberate enablement is a bootstrap task |
| 6 | Complete source inventory with zero unassigned resources | **OPEN** | not started |
| 7 | Release ledger of image digests and Flyway history per service per environment | **OPEN** | not started |
| 8 | Successful independent restore rehearsal on the cutover export mechanism | **OPEN** | not started |
| 9 | GitHub environments and WIF conditions scoped per destination | **OPEN** | no repository file references either destination project |
| 10 | Source routing restorable within approved RTO | **OPEN** | untested |
| 11 | Duplicate-resource cost ceiling and cleanup dates approved | **BLOCKED** | cannot be enforced without destination billing access |

Destinations are otherwise genuinely empty: zero Cloud Storage buckets, and no Cloud Run, Artifact
Registry, Pub/Sub, or Secret Manager services enabled prior to this exercise.

## 4. Quota evidence

Read from the Service Usage consumer-quota API against `custoking-dev`. `gcloud alpha` is not installed on
the operator workstation and cannot be installed without administrator rights, so the REST API was used.

| Service | Metric | Effective limit | Need per environment |
| --- | --- | --- | --- |
| Cloud Run | Services | 1000 | 7 |
| Cloud Run | Instances | 100 | well under, all services at min-instances 0 |
| Cloud Run | Total CPU allocation | 20000 | well under |
| Cloud Run | Total memory allocation | 40 GiB | well under |
| Cloud Run | Active revisions | 4000 | well under, including rollout overlap |
| Cloud Run | Regions Cloud Run may be used in | 3 | 1 (`asia-south2`) |
| Cloud SQL | instance-count constraint | none applicable at this scale | 1 |

No quota is near a binding limit. Prerequisite 4 passes. These are per-project defaults, so the result
holds even if the destination projects are later recreated to resolve the billing blocker.

## 5. Mutations made during this exercise

Four APIs were enabled, all on `custoking-dev`, all free, all reversible. No resource was created, no IAM
was changed, and the source project was not modified.

| API | Reason |
| --- | --- |
| `cloudbilling.googleapis.com` | every billing call failed against the previous quota project; required to read billing linkage at all |
| `billingbudgets.googleapis.com` | required to distinguish a missing API from a genuine permission denial on the destination billing account |
| `run.googleapis.com` | required to read Cloud Run quota |
| `sqladmin.googleapis.com` | required to read Cloud SQL quota |

## 6. Recommended sequencing from here

1. Resolve the destination billing access blocker (section 2). Nothing paid should be built first.
2. Record the cross-organization decision explicitly in the runbook, and assign an owner for source
   organization authority (section 1.2).
3. Reconcile the cost-control workflow and BigQuery billing export against whichever billing account
   ends up funding the destinations.
4. Only then proceed to the runbook's section 5 bootstrap order, beginning with `custoking-dev`.
