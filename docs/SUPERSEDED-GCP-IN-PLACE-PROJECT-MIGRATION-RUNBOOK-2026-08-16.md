# Superseded: Custoking In-Place GCP Project Move Analysis

> **DO NOT EXECUTE THIS RUNBOOK.** On 14 August 2026 the approved target changed to two newly created
> projects: `custoking-dev` for development and `custoking-prod` for production. This document is retained
> only as historical analysis of the rejected in-place organization-move option. The authoritative
> executable procedure is `GCP-SPLIT-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md`. Its prerequisites and
> GO/NO-GO gates supersede every command, schedule, recommendation, and sign-off below.

**Planned cutover:** Sunday, 16 August 2026 (IST)
**Inventory verified:** 13 August 2026
**Status:** Planning and discovery only; **not approved for execution**
**Change type:** Prefer an in-place Resource Manager project move and a separately controlled billing-account handover
**Authoritative scope:** Live project `custoking`; repository documentation was used only to locate integrations and automation and was not treated as current cloud state

**Mandatory data companion:** `docs/GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md`

## 1. Executive decision

“Move to a new GCP account” is not one Google Cloud operation. It can mean any of the following:

1. **Identity and billing handover:** add the new Google identity, transfer billing responsibility, and leave the project in its current organization.
2. **In-place project migration (recommended if the organization is changing):** move the existing project to the destination organization/folder, keeping the project ID, project number, and project-owned resources.
3. **Rebuild into a new project:** create a different project and copy/recreate all resources and data.

The recommended Sunday path is option 2, with the billing switch executed as a separate checkpoint after the organization move is healthy. Google documents an organization move as a metadata operation rather than a data transfer: project ID/number, project-owned data, service accounts, and direct project IAM remain, while inherited organization/folder policies and IAM change. This minimizes downtime, engineering effort, duplicate spend, endpoint changes, and data-copy risk.

Option 1 is preferable if “new account” means only a new administrator/payment owner and the project does not need to leave its present organization.

Option 3 is **not a safe one-Sunday substitution**. It changes project-number-scoped identities, Cloud Run endpoints, Workload Identity Federation resource names, service accounts, bucket names, networking, and CI/CD references and requires separately built and tested database/object replication. If an in-place move is impossible, Sunday must become a no-go unless a replacement project has already been built and rehearsed.

Official references:

- [Migrate projects between organization resources](https://docs.cloud.google.com/resource-manager/docs/project-migration)
- [Create a migration plan](https://docs.cloud.google.com/resource-manager/docs/create-migration-plan)
- [Perform a project migration](https://docs.cloud.google.com/resource-manager/docs/perform-migration)
- [Handle special cases](https://docs.cloud.google.com/resource-manager/docs/handle-special-cases)
- [Change a project's billing account](https://docs.cloud.google.com/billing/docs/how-to/modify-project)

## 1A. Coverage assertion: what “everything” means

This runbook is designed to preserve **the complete live GCP project as it exists at the approved freeze timestamp**. It does not claim that every application issue is fixed or that every dev-tested change is already in production. Moving the project and promoting software are different changes and must not be combined implicitly.

Current coverage status:

| Coverage question | Status on 13 August 2026 | What closes it |
|---|---|---|
| Every discoverable live GCP resource type | Substantially inventoried, but not yet authoritative | Enable Cloud Asset Inventory with approval; archive full asset inventory and run Move Analysis against the exact destination |
| Every enabled Google API | All 44 names captured; principal and auxiliary services queried; all 40 advertised Dataform locations returned zero repositories and Dataplex wildcard inventory returned zero lakes | Cloud Asset inventory plus authoritative Datastore/legacy Firestore proof |
| Every resource configuration and IAM policy | Runbook requires export/diff, but final freeze snapshots do not exist yet | Saturday machine-readable exports and hashes, including direct/inherited IAM, service IAM, org policies, quotas, jobs, buckets, SQL, deploy, observability, billing, and external integrations |
| Every data object/record | Planning aggregate covers all 21 buckets and 3,289 enumerated generations, but freeze-time restricted manifests and row reconciliation are not complete | Execute the data companion: fresh exports, isolated restore/fingerprint validation, database invariants, live/soft-deleted Storage manifests, DB-to-photo/Drive reconciliation, and post-move comparison |
| Every deployed image/revision | All 14 current Cloud Run revisions and immutable image digests are discoverable; a final source-commit-to-digest matrix is not complete | Freeze-time manifest mapping environment, service, source commit/source ID, image digest, revision, Cloud Deploy release/rollout, database migration version, and smoke evidence |
| Every repository fix | **No**—four fixes are on `origin/dev` and are not in `origin/main`/production | Make a separate, approved promotion decision before the configuration freeze, or explicitly migrate with production remaining on its existing release |
| Every known issue | All known issue registers must travel with the handover, but several issues remain open | Reconcile the dated issue register and GitHub/operations evidence; each item gets `closed`, `accepted`, `deferred`, or `not applicable` plus owner and evidence |
| Every destination policy effect | Unknown because destination organization/folder and access have not been supplied | Destination inventory, policy/IAM/quota comparison, administrator attestations, and clean-enough Move Analysis |

The change commander may say “all migration scope is accounted for” only when every row above is closed by evidence. Until then, the accurate statement is: **all verified primary services are covered by the plan, but total completeness is gated and not yet certified**.

### Verified release/fix baseline

Git and GitHub were refreshed on 13 August 2026:

- Production deploys from `main`; current `origin/main` is `cba5a4a4`, whose latest commit is documentation-only. The seven live production services are on immutable Artifact Registry digests, primarily Cloud Deploy release `rel-prod-3b47abe41fed-1`; platform is on `rel-prod-4482ff2a588c-1`. This is a split per-service release baseline, not one asserted source commit for all production images.
- Development deploys from `dev`; current `origin/dev` is `08125d2f`. GitHub deployment run `31697137819` succeeded and deployed/verified five affected dev services, passed the gateway health smoke, and created dev-approved immutable image tags. Frontend and gateway were unchanged by that final commit.
- These four changes are in dev but not in production:
  1. `0a80a8ca` — permanently delete student records.
  2. `92a387af` — return not found after deletion.
  3. `201aa0c7` — prevent reporting from resurrecting a deleted student.
  4. `08125d2f` — flush OpenTelemetry traces within the Cloud Run CPU window.
- The active workspace also contains uncommitted documentation/ignore-file changes and four untracked documentation files, including this runbook and its data companion. They are not deployed software and do not move with GCP. Preserve, review, and checkpoint them separately before the freeze without overwriting user work.
- Five Dependabot pull requests are open; multiple dependency PR checks currently fail. Open pull requests and their check state are external GitHub handover items, not project-move data.

No production promotion is implied by this migration runbook. Before Saturday’s freeze, record one of two explicit decisions:

1. promote the dev-tested changes through the existing protected production workflow and establish a new production baseline; or
2. leave production on its current images and carry the four changes as “dev only / production pending.”

### Known active operational issue

The scheduled `Ops / GCP cost controls` workflow is currently broken. Scheduled runs execute from `main`, but its `dev-database` job targets the GitHub `dev` Environment, whose branch policy permits only `dev`. GitHub rejects the job before any step starts with: `Branch "main" is not allowed to deploy to dev due to environment protection rules.` The latest verified failed run is `31666873139`.

This is not caused by GCP and an in-place project move will neither fix nor erase it. It must be repaired and successfully tested before relying on automated dev Cloud SQL start/stop control during or after the handover. The current dev database state must be checked directly during cutover regardless of workflow status.

### Known-work register that must accompany the move

The repository’s current remaining-work register is not proof of closure. At minimum, the following IDs must be reconciled before freeze and included in the handover ledger:

- P0/launch: `PRIV-01`, `GOV-01`, `PERF-01`, `DB-01`, `DATA-01`, `ASYNC-01`, `NOTIFY-01`, `DATA-02`, `PILOT-01`.
- P1/canary hardening: `IAM-01`, `SEC-01`, `SEC-02`, `IMG-01`, `OBS-01`, `IAC-01`, `REL-01`, `ONB-01`.
- P2/product/maintainability: `FE-01`, `TEST-01`, `TEST-02`, `CODE-01`, `AUDIT-01`, `DOC-01`, `PROD-01`, `JAVA-01`.
- Newly confirmed operational issue: `MIG-OPS-01` — repair and prove the scheduled GCP cost-control workflow.
- Newly confirmed data gate: `MIG-DATA-01` — current restore artifacts do not validate application rows; perform a full non-PII source/restore integrity-ledger comparison.
- Newly confirmed data gate: `MIG-DATA-02` — reconcile every active internal student-photo reference with its GCS generation/CRC32C and school UUID path.
- Newly confirmed reliability decision: `MIG-DATA-03` — the production notification topic has no subscription or retention; prove it is intentionally inactive or configure/test the reviewed production push/DLQ path separately.
- Newly confirmed evidence defect: `MIG-DATA-04` — a diagnostic SQL execution logged errors but exited zero; every audit query must use `psql -v ON_ERROR_STOP=1` and propagate failure.
- Migration gates in this document: destination identity/hierarchy/billing, import/export policies, Move Analysis, complete evidence manifests, external OAuth/Drive/MSG91 ownership, and rollback authority.

Each row needs: current status, environment, owner, evidence link, risk if deferred, deadline, and one of `closed`, `accepted for migration`, `deferred after migration`, or `not applicable`. Project migration is allowed to preserve a known issue only when the issue is explicitly accepted; it must never silently convert “open” into “done.”

## 2. Current verified status and hard blocker

The source project is active and has a source organization parent. The current operator can administer the project and billing account but cannot enumerate or administer the source organization itself.

The effective source organization policy for `constraints/resourcemanager.allowedExportDestinations` is currently **DENY ALL**. This blocks moving the project out of the organization even though project-level mover roles exist.

Sunday is a **NO-GO** until all of these are true:

- A source Organization Policy Administrator explicitly permits the selected destination organization.
- A destination Organization Policy Administrator permits imports from the source organization using `constraints/resourcemanager.allowedImportSources`.
- The destination organization/folder, target billing account, and destination administrator identity are provided and verified.
- The project-move analysis is clean enough to proceed and all warnings have named owners and dispositions.

No change should be attempted on Sunday with placeholders, an unknown destination, or a blanket policy exception that has not been reviewed.

## 3. Unknowns that must be supplied—do not infer them

Record these in the private change ticket, not in this public repository:

| Required value | Owner | Verification evidence | Deadline |
|---|---|---|---|
| Destination Google principal email | Business owner | Sign-in verified; principal can access Cloud Console | Thursday |
| Destination organization ID, or explicit confirmation that no organization exists | Destination org admin | Resource Manager organization/folder output | Thursday |
| Destination folder ID, if used | Destination org admin | Folder exists and import policy is effective there | Thursday |
| Target billing account ID | Target billing admin | Account is open, currency/payment profile confirmed, access test passes | Thursday |
| Source organization administrator | Business owner | Named person can edit export policy and IAM | Thursday |
| Destination organization administrator | Business owner | Named person can edit import policy and IAM | Thursday |
| Authorized cutover time on 16 August 2026 IST | Change owner | Written approval and stakeholder notification | Friday |
| Whether the OAuth consent screen is Internal or External | OAuth/project owner | Console screenshot/export and test-user assessment | Friday |
| Google Drive owner and access model for both configured roots | Integration owner | New administrator/runtime access tested without copying credentials | Friday |
| MSG91 account ownership and recovery contacts | Integration owner | Login/recovery verified; production dry-run state acknowledged | Friday |
| Whether any Marketplace purchases/entitlements or support cases depend on the source organization/billing account | Billing/support admins | Console export or signed attestation | Friday |

If the destination has no organization, do not assume a normal organization-to-organization command applies. Escalate to Google Cloud Support and select either an identity/billing handover or a separately planned new-project migration.

## 4. Live source inventory verified on 13 August 2026

Counts are live discovery results, not copied from the existing architecture documents. A final machine-readable inventory must be captured on Saturday because resources can change after this verification.

| Area | Verified live state | Migration treatment |
|---|---|---|
| Project | One active project; project ID `custoking`; organization parent present | Keep the same project ID and project number using an in-place move |
| Billing | One open INR billing account linked; one project-filtered monthly budget | Billing account/budget do not move with the project; pre-create equivalent guardrails on the target billing account |
| Enabled APIs | 44 enabled services | Remain enabled in an in-place project move; snapshot the list and recheck |
| Cloud Run services | 14 ready services in `asia-south2`, seven dev and seven prod | Project-owned; URLs should remain because the project number/ID remain; smoke every service and gateway route |
| Cloud Run jobs | 10 jobs, including dev/prod jobs, smoke/seed/scale jobs, and one temporary OpenTelemetry diagnostic job | Retain for the move; classify temporary jobs for later cleanup only after evidence capture |
| Cloud SQL | Two private-IP PostgreSQL 16 instances: prod `db-g1-small` and dev `db-f1-micro` | Project-owned; no copy for in-place move. Create fresh exports and validate recovery independently |
| Cloud SQL protection | Prod has backups/PITR/14 retained backups/deletion protection and 16 visible successful backups; latest ended 12 August 21:14:25 UTC. Dev has no automated backup/PITR and only one old 23 July on-demand backup | Fresh dev/prod backups and logical exports are mandatory; restore the exact exports and compare integrity ledgers; current drills do not validate rows |
| Cloud Storage | 21 buckets; 3,210 live objects, 3,289 generations, 2,804,295,328 bytes, and zero enumerated generations missing CRC32C. Prod photos contain 1,767 objects/2,362,058,740 bytes; dev photos contain 22/10,300,841 | Capture restricted live plus soft-deleted manifests; compare generation/size/CRC32C, classify lifecycle expiry, reconcile DB photo keys, and never commit object names |
| Artifact Registry | One Docker repository in `asia-south2`, approximately 6.15 GB, with cleanup policies | Retains image digests; export repository configuration and digest inventory |
| Secret Manager | 44 secrets and 63 versions (48 enabled, 6 disabled, 9 destroyed). All 107 deployed references resolve to 43 existing secrets, use `latest`, and have project/secret access | Repeat the reference/IAM resolver after the move; export metadata/version state only—never plaintext payloads |
| Service accounts | 26 service accounts; no user-managed service-account keys found | Project service accounts remain. Revalidate each service-account IAM policy and runtime impersonation |
| Workload Identity Federation | One GitHub pool and one provider; conditions bind immutable repository/workflow metadata | Project-number path remains for in-place move. Run a real dev GitHub authentication/deploy preflight after the move |
| GitHub integration | Public repository uses variables for project, region, provider, service-account names, private database addresses, and Drive root identifiers; no GitHub secrets were visible in the live repository settings query | External to GCP; it does not move. Do not change variables for an in-place move unless verification proves a value changed |
| Cloud Deploy | 14 delivery pipelines and 14 Cloud Run targets in `asia-south2` | Project-owned. Export pipeline/target/release/rollout metadata and validate a non-production deployment after handover |
| Pub/Sub | 6 topics/5 subscriptions, zero backlog in a sampled six-hour window, no snapshots/topic retention/acked retention. Production notification has no subscription; production reporting has no DLQ | Reconcile durable DB outbox/inbox state and push/OIDC; require explicit inactive acceptance or a separate reviewed fix for the unsubscribed notification path |
| Cloud Scheduler | 4 dev asynchronous relay jobs in `asia-south1`; all were paused | Preserve exact state. Do not resume paused jobs as part of migration |
| Networking | Default auto-mode VPC; private service access peering; a reserved RFC1918 `/24` service range; direct VPC egress from Cloud Run; no Serverless VPC Access connectors | Project-owned. Recheck peering, routes, private SQL connectivity, and destination inherited networking constraints |
| Compute/load balancing | No VM instances, disks, snapshots, images, instance groups, forwarding rules, managed SSL certificates, or Shared VPC attachment found | Re-enumerate Saturday; no data-copy action is currently indicated |
| Cloud Run custom domains | No Cloud Run domain mappings returned | Current traffic uses `run.app` endpoints; recheck Saturday |
| BigQuery billing export | US dataset contains 1,918,820 pricing rows, 171,932 detailed rows, and 72,174 standard rows. The pricing transfer is personal-identity-owned and explicitly targets the current billing account | Preserve historical tables; configure the target account's standard/detailed/pricing exports separately because the current transfer does not retarget automatically |
| Logging | Three sinks, including an India-region compliance log bucket with 180-day retention; default and required system buckets also present | Project-owned. Verify sinks, destinations, exclusions, retention, and new log arrival |
| Monitoring | 110 alert policies, 14 dashboards, 8 uptime checks, and one email notification channel | Project-owned. Verify channel, uptime state, alert policy enabled state, and telemetry arrival |
| IAM | Direct project bindings, inherited bindings, two project-level custom roles, and multiple direct service-account policies | Direct project bindings/custom project roles remain; inherited access changes. Export and diff both before and after |
| API keys | One Maps Platform key with API restrictions; application restrictions require a manual confirmation | Project-owned. Do not rotate during the move; verify restrictions and usage afterward |
| Liens/tags/PAM | No project liens, no project tags, and no project-level PAM entitlements returned | Recheck Saturday and include inherited organization controls in the destination review |
| Terraform state | GCS backend bucket with versioning enabled | Snapshot bucket metadata/object generation; run `terraform plan` only after access validation and never apply during cutover |
| Google Drive import | Dev and prod root folder identifiers and OAuth credentials are referenced by deployed configuration | Drive data/ownership is external to GCP. Verify both roots and OAuth behavior under the new identity/organization |
| MSG91 | External notification credentials/configuration exist | External account is not transferred by Resource Manager. Verify ownership, recovery, sender configuration, and current dry-run behavior |
| Auxiliary enabled APIs | Analytics Hub exchanges, BigQuery connections/reservations/capacity/data policies, Dataplex lakes, Dataform repositories across all 40 advertised locations, Deployment Manager, VPC connectors, Cloud Build triggers/private pools, Endpoints, and legacy GCR returned no resources; Datastore remains incompletely proven | Retain API names; use Cloud Asset and an authoritative Datastore query to close the remaining absence proof |

### Google migration special-case register

The following register deliberately distinguishes project discovery from organization-level proof. A blank or disabled project API is not proof that an inherited organization dependency cannot exist.

| Google-documented special case | Current evidence | Required disposition before GO |
|---|---|---|
| Source/destination inherited quotas | Project services were inventoried; destination quota hierarchy is not accessible yet | Diff effective project and organization quotas for active services, especially Cloud Run, Cloud SQL, networking, build/deploy, Artifact Registry, Pub/Sub, Logging, Monitoring, and Secret Manager |
| Customer-managed encryption keys/Cloud KMS | Cloud KMS was not in the enabled-service inventory | Confirm through Cloud Asset analysis and KMS inventory; identify external-key projects and destination policy impact if any are found |
| Shared VPC | No Shared VPC host/service-project attachment was found | Recheck with Compute Shared VPC and move analysis |
| VPC Service Controls | Current operator cannot inspect the source/destination organization perimeters | Both organization security admins attest project number is outside all perimeters, or remove it early enough for documented propagation and rerun analysis |
| Context-Aware Access service-account policies | Not visible with present project-only authority | Organization security admins query access bindings and attest none apply; allow propagation time if removal is required |
| Organization-level custom IAM roles | Two project-level custom roles are known; inherited source organization roles are not visible | Export source bindings/roles and recreate/replace any organization custom-role binding in destination before move |
| Cross-project service accounts | Project owns its runtime/deployment accounts; complete cross-project use cannot be proven from project-only discovery | Move analysis plus IAM/asset query identifies accounts consumed by or consuming other projects; review domain-restriction impact |
| OAuth Internal consent screen | User type is unresolved; Drive OAuth is in use | Record user type/scopes/verification; ensure intended destination users can authorize and account for Google's documented up-to-24-hour behavior |
| OS Login/external users | Effective `compute.requireOsLogin` was not enforced and no VMs were found | Recheck effective policy and Compute inventory; grant external-user role only if an actual OS Login dependency appears |
| Service-account `actAs` enforcement | Source/destination organization legacy enforcement posture is unknown | Compare documented enforcement constraints and grant narrow Service Account User permissions to deployment operators where actually required |
| Bucket Lock/liens | No project liens were returned; bucket-lock/retention metadata must still be captured | Export every bucket retention policy/lock and rerun liens immediately before move |
| Dedicated/Partner Interconnect and shared VM reservations | No Compute runtime/load-balancer resources were found, but organization/network-owner evidence is incomplete | Recheck interconnect/VLAN attachment/reservation inventory and obtain network-admin attestation |
| App Hub management project | Not established by current discovery | Move analysis/destination admin confirms this is not an app-enabled-folder management project |
| Backup and DR Service | Backup and DR API was not observed in the enabled-service inventory | Recheck API/resource inventory and move analysis; if enabled, follow Google's disable/outage/re-enable procedure in a separate approved step |
| BigQuery Sharing/data exchanges | Only billing-export dataset/tables were found | Query Analytics Hub/data exchanges; coordinate with Support if any exist |
| BigQuery policy-tag taxonomies | Not visible in the current dataset discovery | Query Data Catalog policy-tag taxonomies and export/import/rebind any result as Google requires |
| Inherited PAM grants | No project-level entitlements were returned; inherited scoped grants remain unproven | Source admin revokes active scoped inherited grants or records expiry and direct-removal plan before move |
| Open Google Support cases | Not visible to current operator | Support admin exports open cases and arranges metadata update after migration |
| Marketplace subscriptions/commitments | Not proven absent | Billing admin exports subscriptions, private offers, CUDs/reservations, and confirms billing-switch impact; active Marketplace subscriptions make an unreviewed billing switch a no-go |
| Essential Contacts | Essential Contacts API was not enabled at project level | Export project/folder/org contacts where applicable and install destination operational/security/billing contacts |

### Inventory visibility limits

- Cloud Asset Inventory API was not enabled during planning, so no service was enabled merely for discovery. Enable it only with change approval, then run the official move analysis.
- The current operator cannot inspect source-organization IAM/policies beyond effective project behavior and cannot inspect the destination until its IDs/access are provided.
- Planning-time Storage enumeration completed for all buckets/generations, including CRC32C presence and critical lifecycle/soft-delete aggregates. Detailed names remain restricted and a fresh Saturday manifest is mandatory because objects can change.
- Dataform was exhaustively queried across all 40 advertised locations and returned zero repositories; Dataplex wildcard inventory returned zero lakes. The legacy Datastore endpoint still did not yield an authoritative database inventory. Do not infer Datastore absence; resolve it through Cloud Asset and an authorized service-specific query.
- OAuth consent-screen organization behavior, Marketplace purchases, organization-level VPC Service Controls, deny policies, principal access boundaries, inherited PAM grants, and support cases require the respective organization/billing administrators to verify them.

These limits are migration gates, not reasons to assume absence.

## 5. Why the in-place move is lowest-risk and lowest-cost

An in-place move avoids operating duplicate Cloud Run services, databases, storage, monitoring, and deployment infrastructure. It also avoids bulk data egress/copy charges and prevents a second set of endpoints and service accounts from being created.

The expected incremental cost is limited to temporary backup/export storage, inventory operations, restore validation, and normal monitoring. Do not create a parallel project or copy all buckets unless the in-place move is rejected and a separately approved rebuild plan exists.

The organization move and billing switch must remain separate checkpoints:

1. Move the existing project while the known current billing link remains intact.
2. Verify runtime, IAM, policies, data access, CI/CD, and external integrations.
3. Link the already-validated target billing account.
4. Recreate the budget and configure target billing export.
5. Verify target-account charges/export while retaining historical old-account export tables.

Google states that changing a project's billing account should not interrupt services, but reporting can take time to settle and Marketplace/CUD behavior needs separate review. Historical and new charges may appear under different accounts around the change boundary.

## 6. Ownership and authority

Every role below must have a named primary and backup in the private change ticket.

| Role | Required authority | Responsibility |
|---|---|---|
| Change commander | No technical role required | Owns go/no-go decisions, timestamps, communication, and stop conditions |
| Source organization admin | Organization Policy Admin plus Resource Manager move/export authority | Authorizes export, validates source inherited policies, and preserves rollback path |
| Destination organization admin | Organization Policy Admin plus project creator/mover authority at destination | Authorizes import, validates destination inherited policies/IAM, and accepts project |
| Billing admin | Project Billing Manager plus Billing Account User/Administrator as appropriate on both accounts | Validates account status, performs/reverses billing link, creates budget/export |
| Project/IAM operator | Project-level IAM/admin roles | Captures direct IAM, ensures destination principals have direct access, verifies service accounts/WIF |
| Application/deployment operator | GitHub and Cloud Deploy access | Freezes deployments, runs baseline/post-move smoke, validates WIF and dev deployment |
| Database/recovery operator | Cloud SQL and Storage access | Produces and validates exports; verifies SQL health/data without restoring over live data |
| Security verifier | Read-only organization/project security access | Reviews org policies, VPC-SC, deny policies, key restrictions, OAuth, audit logs |
| Business verifier | Application tenant/school test accounts | Confirms login, student/photo workflows, and representative business reads/writes |

No single personal login should be the only account capable of rollback.

## 7. Required preparation schedule

### Thursday, 13 August — identify the actual destination

- [ ] Obtain every value in section 3 through authoritative console/CLI evidence.
- [ ] Decide explicitly between identity/billing handover and in-place organization migration.
- [ ] Name all operators and backups.
- [ ] Create the private change ticket and evidence location.
- [ ] Confirm destination payment profile, currency, account status, quotas, and billing-admin access.
- [ ] Give the destination administrator a direct project role sufficient for read-only validation; do not remove existing owners.
- [ ] Confirm both organization administrators can edit the required import/export constraints.
- [ ] Confirm the destination can accept the project in the chosen organization/folder.

### Friday, 14 August — clear policy and special-case gates

- [ ] Enable Cloud Asset Inventory only after approval.
- [ ] Run `gcloud asset analyze-move` against the exact destination and archive the JSON result.
- [ ] Resolve every blocker; assign and accept every warning. Rerun until the result used for approval is current.
- [ ] Configure the narrowest source `allowedExportDestinations` rule for the exact destination organization.
- [ ] Configure the narrowest destination `allowedImportSources` rule for the exact source organization.
- [ ] Compare effective source and destination organization policies, IAM allow/deny policies, principal access boundaries, and resource location restrictions.
- [ ] Compare effective source and destination organization-level quota overrides; confirm the moved project will not land over a lower limit.
- [ ] Verify no VPC Service Controls perimeter blocks migration or runtime services. Google documents VPC-SC as a migration special case.
- [ ] Verify no Context-Aware Access policy for service accounts blocks migration, and allow the documented propagation time if one must be removed.
- [ ] Verify custom roles: the two project-level custom roles move; any inherited organization-level custom role references need destination equivalents before the move.
- [ ] Verify Cloud KMS/customer-managed-key dependencies, cross-project service accounts, service-account `actAs` enforcement, OS Login, Shared VPC, Interconnect/VLAN attachments, shared VM reservations, and App Hub management-project status.
- [ ] Verify OAuth consent screen user type. If it is Internal, test the documented cross-organization implications and authorization delay before approving Sunday.
- [ ] Verify Google Drive folder ownership/sharing and token behavior with the identities that will operate after migration.
- [ ] Verify there are no organization-bound Marketplace entitlements, support cases, BigQuery Sharing/data exchanges, policy tags/taxonomies, Backup and DR resources, Bucket Locks/liens, Essential Contacts gaps, or inherited PAM grants that need separate handling.
- [ ] Validate target billing linkage permission without switching production billing.
- [ ] Create the target budget/notification design and target BigQuery export design; do not assume the source budget moves.
- [ ] Capture screenshots/JSON of all resolved policy gates in private evidence.

### Saturday, 15 August — freeze configuration and capture recoverable evidence

Start a deployment/configuration freeze after the final approved release. User traffic and database writes do not need to be stopped for a metadata-only project move; deployments, IAM edits, policy edits, schema changes, secret rotations, and infrastructure changes do.

- [ ] Export final project metadata and ancestors.
- [ ] Export enabled APIs and relevant quota/limit state.
- [ ] Export project IAM, service-account IAM, project custom roles, service accounts, API key restrictions, WIF pool/provider/attribute conditions, and organization policy results.
- [ ] Export direct and inherited IAM allow/deny policies, principal access boundaries where visible, cross-project service-account references, Essential Contacts, and effective quota overrides using organization-authorized readers.
- [ ] Export Cloud Run services, revisions, traffic, IAM, scaling, ingress, VPC egress, environment-variable names, and secret references. Do not export secret values.
- [ ] Export all Cloud Run job configuration and latest execution status.
- [ ] Export Cloud SQL instance configuration, flags, users by name only, databases, backup/PITR/deletion protection, network, and recent backup status.
- [ ] Produce fresh encrypted logical exports for both production and development databases to the approved recovery bucket.
- [ ] Validate each exact export by restoring/importing it to an isolated temporary instance/database. Run the complete non-PII structural, Flyway, table-count/fingerprint, cross-schema, durable-event, and student-photo-key ledger in the data companion. Never restore over a live database.
- [ ] Export every GCS bucket's IAM, uniform-access/public-access prevention state, location, lifecycle, retention/lock, soft-delete, versioning, CORS, and labels.
- [ ] Produce restricted live and soft-deleted student-photo/critical-bucket manifests containing object name, generation, size, CRC32C, storage class, and update/delete time. Record lifecycle rules and separately classify lifecycle-eligible changes. Store neither names nor contents in Git.
- [ ] Capture the Terraform-state object's current generation and a protected backup/version reference.
- [ ] Export Artifact Registry settings, cleanup policies, package/version names, and image digests.
- [ ] Export Cloud Deploy pipelines, targets, releases, rollouts, approval state, and execution service accounts.
- [ ] Export Pub/Sub topics/subscriptions/IAM, push endpoints, OIDC service accounts, retry/dead-letter policies, ack deadlines, filters, and exact topic/subscription state.
- [ ] Reconcile every DB outbox/inbox with Pub/Sub backlog. Drain to zero or record a bounded event set, and resolve/accept the production notification topic's missing subscription and the production reporting subscription's missing DLQ.
- [ ] Export Scheduler jobs and paused/enabled state.
- [ ] Export network, subnet, peering, reserved range, routes, firewall rules, and private service access.
- [ ] Export Secret Manager metadata, IAM, and version enabled/disabled/destroyed state without accessing payloads.
- [ ] Export Logging sinks/buckets/views/exclusions/retention, log-based metrics, Monitoring dashboards/policies/channels, uptime checks, and SLOs.
- [ ] Export BigQuery dataset/table metadata, schemas, partitioning, IAM, and non-sensitive row-count/last-ingestion checks for billing export tables.
- [ ] Export KMS keys/key-ring references, BigQuery policy-tag taxonomies, Analytics Hub exchanges, Backup and DR resources, and App Hub metadata if the authoritative inventory returns any.
- [ ] Export current billing account link, budget configuration, and billing export configuration.
- [ ] Export GitHub repository/environment variables, workflow file revisions, environment protection rules, and relevant service-account WIF bindings. Redact values not intended for the public repository.
- [ ] Baseline all application health, login, representative tenant reads/writes, student records/photos, async delivery, database connectivity, logs, traces, and error-rate/latency dashboards.
- [ ] Reconcile active database photo keys to the correct GCS bucket, live generation/CRC32C, student ID, and immutable school UUID path; report but do not delete orphan candidates.
- [ ] Export every schema's Flyway history/checksum and prove it matches the approved environment's source commit. All audit SQL must use `psql -v ON_ERROR_STOP=1` and propagate a nonzero exit on error.
- [ ] Record all SHA-256 hashes for exported evidence files in a manifest.
- [ ] Confirm there were no resources created/changed after the inventory timestamp.

Private evidence should be stored under an approved restricted location, with a local working convention such as `outputs/gcp-project-migration/2026-08-16/`. The directory must not be committed and must never contain plaintext credentials or secret payloads.

## 8. Sunday cutover procedure

The exact wall-clock start remains **unresolved** until written approval. Use the following T-relative sequence in a low-traffic window.

### T−120 minutes: open bridge and confirm evidence

- [ ] Attendance: change commander, both organization admins, both billing admins, project/IAM operator, application operator, database operator, security verifier, and business verifier.
- [ ] Confirm GitHub deployment/configuration freeze; note the last approved commit and Cloud Deploy rollout.
- [ ] Confirm latest database exports and restore-validation evidence are successful.
- [ ] Confirm the complete data-companion GO/NO-GO ledger is signed: restored export fingerprints, database invariants, durable events, GCS live/soft-delete manifests, DB-photo/Drive reconciliation, and BigQuery history.
- [ ] Confirm storage manifests/checksums and Terraform-state generation.
- [ ] Confirm all pre-move baseline tests are green and alerting telemetry is current.
- [ ] Confirm the exact release/fix decision: either the four dev-only fixes were promoted with successful production evidence, or production remains intentionally on its recorded pre-fix image/revision matrix.
- [ ] Confirm every known-work ledger item has a status, owner, evidence, and accepted disposition; no open item is mislabeled as complete.
- [ ] Confirm `MIG-OPS-01` scheduled cost-control automation is fixed and has one successful scheduled-equivalent test, or assign a documented manual dev SQL state procedure and risk acceptance.
- [ ] Confirm current billing account is open and remains linked for the organization-move phase.
- [ ] Confirm old administrators will retain rollback access through stabilization.

### T−60 minutes: final policy analysis and go/no-go

- [ ] Rerun project ancestors, liens, tags, effective policies, IAM, and `analyze-move` against the exact destination.
- [ ] Confirm source export and destination import constraints are effective.
- [ ] Confirm destination inherited policies do not deny Cloud Run, Cloud SQL, Storage, Secret Manager, Artifact Registry, Logging, Monitoring, Pub/Sub, Scheduler, BigQuery, service-account impersonation, or WIF usage.
- [ ] Confirm no new blocker or unowned warning exists.
- [ ] Verify destination principals can inspect the project before move via direct project IAM.
- [ ] Change commander records **GO** or **NO-GO**. Any failed mandatory item means no-go.

### T0: move the existing project

Only the authorized organization operator runs the move command, using the exact approved destination. Capture command, timestamp, operator, output, and audit-log correlation.

Do not simultaneously change billing, IAM, secrets, deployments, or database configuration.

### T+0 to T+15: control-plane verification

- [ ] Project is ACTIVE and ancestors show the exact destination organization/folder.
- [ ] Project ID and project number are unchanged.
- [ ] Billing link is still the approved pre-move account.
- [ ] Direct project IAM and project custom roles match the baseline.
- [ ] Expected inherited destination IAM/policies are effective; no unexpected denies appear.
- [ ] Enabled APIs, quotas, service accounts, WIF provider, and API key are present.
- [ ] Cloud SQL instances are RUNNABLE; private addresses and peering remain; no restore is performed.
- [ ] Cloud Run services/jobs, Artifact Registry, buckets, secrets, Pub/Sub, Scheduler, BigQuery, Cloud Deploy, Logging, and Monitoring enumerate successfully.

### T+15 to T+45: application and integration verification

- [ ] Public frontend and gateway health checks pass.
- [ ] All seven production services and seven development services are ready with expected traffic revisions.
- [ ] Production login/authentication succeeds using an approved test identity.
- [ ] Representative school/tenant read and authorized write succeed.
- [ ] Student details and student-photo read path succeeds; one approved non-production photo workflow verifies Storage and Drive access.
- [ ] Database connection, transaction, migrations-at-current-version, and row-level tenant isolation checks pass.
- [ ] Lightweight post-move data ledger has no unaccepted orphan, failed Flyway row, invalid constraint/index, missing active photo generation, or unexpected destructive delta.
- [ ] Pub/Sub push/OIDC test succeeds; retry/DLQ and paused Scheduler state remain correct.
- [ ] Secret references resolve without revealing secret values.
- [ ] Logs, metrics, traces, uptime checks, dashboards, and notification channel receive current telemetry.
- [ ] GitHub WIF authentication succeeds through a controlled dev workflow. If safe and approved, deploy a no-op or known dev revision through Cloud Deploy and verify rollout.
- [ ] Google Drive roots remain accessible and OAuth flows/tokens work under the intended post-move operation model.
- [ ] MSG91 configuration is reachable while preserving the approved dry-run/live-delivery state.

### Billing checkpoint, only after runtime acceptance

- [ ] Record the current billing link and exact switch timestamp.
- [ ] Link the project to the verified target billing account.
- [ ] Confirm the target account is now linked and the project remains enabled.
- [ ] Create/verify the target monthly budget with 50%, 80%, 100%, and 100% forecast thresholds or the separately approved thresholds.
- [ ] Configure the target billing account's standard, detailed, and pricing export as required, using a dataset in a project linked to that billing account.
- [ ] Preserve the existing historical billing-export tables; do not rename, truncate, or overwrite them.
- [ ] Verify first target export rows when Google produces them. Do not fail runtime solely because billing export ingestion is asynchronous; keep an owner and deadline until verified.

### T+45 to close: acceptance and unfreeze

- [ ] Repeat the full smoke suite and compare latency/error/telemetry with baseline.
- [ ] Confirm no unexpected IAM denial, audit anomaly, queue backlog, failed revision, SQL connectivity error, or missing telemetry.
- [ ] Business verifier signs off login, tenant data, students, and photos.
- [ ] Security verifier signs off destination policies and audit trail.
- [ ] Billing admin signs off target link, budget, and export follow-up.
- [ ] Unfreeze deployments/config changes only after the change commander records acceptance.
- [ ] Keep old authorized identities and the source rollback policy available through the agreed stabilization window; revoke only via a separate access-removal change.

## 9. Validation matrix

| Component | Minimum pass condition | Failure action |
|---|---|---|
| Resource hierarchy | Exact approved organization/folder; project ID/number unchanged | Stop; validate audit log and policies; prepare reverse move only if approved |
| Billing | Correct account linked; project enabled; target budget configured | Relink old account if authorized and still open; keep services unchanged |
| IAM | Direct bindings/custom project roles match; intended new admins work; runtime SAs can impersonate/access | Correct narrow destination policy/IAM; do not remove old access |
| Cloud Run | 14 services ready; traffic/revisions unchanged; public/internal route smoke green | Diagnose inherited policies/IAM/network before rollback decision |
| Cloud SQL | Both instances RUNNABLE; source/restored ledgers match; post-move Flyway/structure/invariants/representative reads are green | Do not restore automatically; distinguish access/policy failure from independently proven data damage |
| Storage/photos | All live and soft-deleted critical generations reconcile by size/CRC32C; lifecycle deltas are explained; every active DB photo key resolves; photo path works | Correct IAM/org policy; recover only exact missing generations with evidence; never bulk-delete or overwrite during migration |
| Secrets | 44 names/version states present; deployed references resolve | Correct IAM/policy; never copy values into logs or ticket |
| Artifact Registry/Deploy | Repository/images/digests present; 14 pipelines/targets visible; dev verification succeeds | Restore IAM/execution identities; avoid production rollout during diagnosis |
| WIF/GitHub | STS exchange and intended dev workflow succeed | Validate provider condition, service-account IAM, destination org restrictions |
| Pub/Sub/Scheduler | 6 topics/5 subscriptions present; DB outbox/inbox event set reconciles; push/OIDC works; missing prod notification subscription is accepted/fixed; 4 schedulers remain paused | Correct IAM/OIDC/policy; avoid blind replay or resuming jobs |
| Network | VPC/subnets/peering/range/routes unchanged; SQL private connectivity works | Correct destination constraints; no network recreation without review |
| BigQuery | Historical dataset/tables readable; target export configured and later receives rows | Keep old history; repair target export permissions/system service account |
| Observability | Sinks/buckets/retention, 110 alerts, 14 dashboards, 8 uptime checks, channel, logs/metrics/traces work | Treat loss of telemetry as a cutover failure until resolved |
| External integrations | Both Drive roots/OAuth and MSG91 ownership/config verified | Keep integration in safe/dry-run mode; escalate to external owner |
| Business | Login, tenant isolation, student details/photos, representative read/write pass | Keep freeze and begin incident/rollback decision |

## 10. Rollback plan

Google does not provide an automatic one-click rollback for a completed organization migration. A reverse move is a new migration and therefore needs policies, permissions, and a destination prepared in advance.

### Before T0

If any gate fails, do nothing. Leave the project, billing, applications, and data unchanged.

### Move command fails without parent changing

- Preserve the output and audit evidence.
- Confirm ancestors and service health.
- Do not retry blindly or start a new-project rebuild.
- Resolve the specific policy/permission blocker and obtain a new go decision.

### Parent changes but inherited destination policy breaks runtime

1. Keep the deployment/configuration freeze.
2. Identify the exact deny/inherited policy using audit logs, Policy Troubleshooter, and the baseline diff.
3. Prefer a narrow, pre-approved correction in the destination/import folder if it can restore service safely.
4. If acceptance cannot be restored within the approved recovery window, execute the pre-authorized reverse organization move using the already-prepared source import policy and destination export policy.
5. Revalidate the original hierarchy and the entire validation matrix.

Do not restore databases or copy buckets just because an IAM/policy check failed. An in-place project move does not copy or transform the data; a restore is appropriate only when independent evidence proves corruption or loss.

### Billing switch fails

- Confirm whether the project is still linked to the old or target account.
- If service/billing enablement is at risk, relink the old open billing account using pre-validated access.
- Keep historical exports untouched.
- Record charges around the switch boundary on both accounts because reporting can lag.

### External integration fails

- Leave GCP project data unchanged.
- Keep notification delivery in its approved safe/dry-run state if applicable.
- Restore Drive/OAuth or MSG91 identity/account access through the external system owner.
- Do not expose or rotate credentials during an organization rollback unless there is a separate security reason.

## 11. Security and data handling rules

- Never put Secret Manager payloads, OAuth client secrets, refresh tokens, database passwords, MSG91 credentials, billing payment data, or downloaded student data/photos in Git or the change ticket.
- Do not create service-account keys. The verified project currently has none; preserve keyless WIF/service identity operation.
- Export secret metadata and IAM, not values. If the security owner requires escrow, use a separately approved encrypted secret-recovery process.
- Store detailed IAM, organization IDs, billing IDs, principal emails, private IPs, Drive folder IDs, and evidence in restricted storage—not this public repository.
- Retain existing owners only for the stabilization/rollback window, then remove old access through an independently reviewed least-privilege change.
- Ensure student-photo manifests remain restricted because object names may be personal data even when image content is not exported.
- Preserve Cloud Audit Logs, compliance log retention, and exact cutover timestamps.

## 12. Command templates—do not run with placeholders

These templates support the runbook; they are not authorization to mutate GCP. Store full JSON outputs in restricted evidence and redact before sharing.

```powershell
$ProjectId = 'custoking'
$DestinationOrgId = '<DESTINATION_ORG_ID>'
$DestinationFolderId = '<DESTINATION_FOLDER_ID>' # use either folder or org, not both
$TargetBillingAccountId = '<TARGET_BILLING_ACCOUNT_ID>'
$EvidenceRoot = '<RESTRICTED_EVIDENCE_PATH>'

# Read-only hierarchy and baseline.
gcloud projects describe $ProjectId --format=json
gcloud projects get-ancestors $ProjectId --format=json

# Read-only best-effort migration analysis after Cloud Asset Inventory is approved/enabled.
gcloud asset analyze-move `
  --project=$ProjectId `
  --destination-organization=$DestinationOrgId `
  --format=json

# MUTATING: only the authorized organization operator at T0.
gcloud beta projects move $ProjectId --organization=$DestinationOrgId
# Or, if the approved destination is a folder:
# gcloud beta projects move $ProjectId --folder=$DestinationFolderId

# MUTATING: only after the organization move passes runtime acceptance.
gcloud billing projects link $ProjectId --billing-account=$TargetBillingAccountId

# Read-only post-checks.
gcloud projects describe $ProjectId --format=json
gcloud projects get-ancestors $ProjectId --format=json
gcloud billing projects describe $ProjectId --format=json
```

Use the official analysis contract as the source of truth for interpreting the move assessment:

- [Cloud Asset `analyzeMove` REST method](https://docs.cloud.google.com/asset-inventory/docs/reference/rest/v1/TopLevel/analyzeMove)
- [`gcloud asset analyze-move`](https://docs.cloud.google.com/sdk/gcloud/reference/asset/analyze-move)

## 13. New-project fallback scope (not approved for Sunday)

If the destination cannot accept an in-place move, a new-project program must explicitly recreate or migrate all of the following:

- Project APIs, quotas, IAM, two custom roles, 26 service accounts, WIF, API key, and every service-account policy.
- VPC/subnets/firewalls/routes, private service access, reserved addresses, and new Cloud SQL private networking.
- Two Cloud SQL instances, users/roles, schema/data, backup/PITR policies, application cutover, replication or controlled write outage, and restore rehearsal.
- 21 buckets, unique bucket names, IAM/lifecycle/retention/soft-delete/versioning/CORS, every object generation/checksum, Terraform-state backend migration, and student-photo validation.
- Artifact Registry images and digest mapping.
- 14 Cloud Run services, 10 jobs, new endpoints, IAM, environment variables, Secret Manager bindings, autoscaling, ingress, VPC configuration, and all inter-service URLs.
- 14 Cloud Deploy pipelines/targets and associated execution identities/artifact buckets.
- 6 Pub/Sub topics, 5 subscriptions, push/OIDC endpoints, DLQs, and 4 paused Scheduler jobs.
- 44 secrets through a secure value transfer/rotation process.
- Logging sinks/buckets/retention, metrics, 110 alert policies, 14 dashboards, 8 uptime checks, and notification channels.
- BigQuery billing-export history strategy and target export setup.
- GitHub variables/environments/WIF resource paths and workflow validation.
- Drive OAuth consent/client ownership, root-folder sharing, MSG91 account configuration, Maps key restrictions, and all external allowlists/callbacks.
- DNS/custom domains if any appear in the final inventory; current Cloud Run domain-mapping discovery returned none.
- A traffic cutover, rollback environment, data-reconciliation plan, and duplicate-resource cost budget.

This fallback needs a separate design, test environment, rehearsal, approved outage/RPO/RTO, and cost estimate. It must not be improvised during the organization-move window.

## 14. Final go/no-go sign-off

All fields must be completed in the private change record.

| Gate | Owner | Evidence reference | Result |
|---|---|---|---|
| Migration type and destination are unambiguous | Change commander | | GO / NO-GO |
| Source export and destination import policies effective | Organization admins | | GO / NO-GO |
| Move analysis has no blocker and every warning is accepted | Security verifier | | GO / NO-GO |
| Destination inherited IAM/policies reviewed | Security verifier | | GO / NO-GO |
| Target administrator and rollback identities tested | Project/IAM operator | | GO / NO-GO |
| Target billing account, budget, and export plan verified | Billing admin | | GO / NO-GO |
| Fresh prod/dev exports restored and validated | Database operator | | GO / NO-GO |
| Source/restored DB integrity ledgers and Flyway histories match | Database operator | | GO / NO-GO |
| Live/soft-deleted Storage manifests and DB-photo reconciliation complete | Recovery operator | | GO / NO-GO |
| Durable outbox/inbox/Pub/Sub reconciliation and notification-path decision complete | Application operator | | GO / NO-GO |
| Baseline smoke and observability green | Application operator | | GO / NO-GO |
| Drive/OAuth and MSG91 ownership/access verified | Integration owner | | GO / NO-GO |
| Freeze, communications, operator attendance, and rollback window approved | Change commander | | GO / NO-GO |

The change proceeds only when every row is GO. A deadline does not override a failed gate.

## 15. Source notes

- Organization migration behavior, retained project metadata, required permissions, and general move duration: [Google Cloud Resource Manager migration documentation](https://docs.cloud.google.com/resource-manager/docs/project-migration) and [perform migration](https://docs.cloud.google.com/resource-manager/docs/perform-migration).
- Planning, inventory, inherited-policy analysis, import/export folders, and rollback preparation: [Create a migration plan](https://docs.cloud.google.com/resource-manager/docs/create-migration-plan).
- OAuth, VPC-SC, Shared VPC, custom roles, Bucket Lock, BigQuery taxonomies, PAM, and other exceptions: [Handle special cases](https://docs.cloud.google.com/resource-manager/docs/handle-special-cases).
- Billing link permissions, operational expectations, charge attribution timing, Marketplace, and commitments: [Change a project's billing account](https://docs.cloud.google.com/billing/docs/how-to/modify-project).
- Billing export prerequisites and historical export behavior: [Set up Cloud Billing export to BigQuery](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-setup).
- Billing-account organization association is distinct from project migration: [Move a billing account to a different organization](https://docs.cloud.google.com/billing/docs/how-to/modify-billing-account).
- GitHub keyless-federation controls: [Workload Identity Federation best practices](https://docs.cloud.google.com/iam/docs/best-practices-for-using-workload-identity-federation).
- Data recovery, Storage checksum, and message replay semantics used by the mandatory data companion: [Cloud SQL restore](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/restore), [Cloud Storage data validation](https://docs.cloud.google.com/storage/docs/data-validation), and [Pub/Sub replay/retention](https://docs.cloud.google.com/pubsub/docs/replay-overview).
