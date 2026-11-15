# Moving custoking-dev and custoking-prod to the company's Google Cloud organization

> **Executed 2026-11-16 (IST): both projects moved; billing not yet switched.** See §12, "Execution record". It covers
> how the actual destination differed from what this plan assumed, and what remains.

**Status:** plan for review, not approved for execution. Written 2026-11-15 (IST) from a read-only inventory of both
projects, the organization, billing, GitHub and this repository, the same day.
**Decision recorded with the owner (2026-11-15):**

- move the **existing** projects to the new owner. Nothing is rebuilt and no data is copied;
- **dev first, then prod;**
- **upgrade the current billing account today**, so the trial deadline below cannot stop production.

**This repository is public.** Keep organization IDs, billing account IDs, people's email addresses, private IPs, Drive
folder IDs and evidence files out of it. Wherever this plan needs them it says `<DEST_ORG_ID>`, `<COMPANY_ADMIN>` and
so on; keep the real values in restricted storage.

---

## 0. Do this today: production stops tomorrow otherwise

Both projects, and one unrelated non-IMS project, bill to one account. That account is a **Google Cloud Free
Trial that ends on 2026-11-16**. GitHub issue #308 tracks it, and `gcp-cost-controls.yml` fails on purpose as the date
nears.

At trial end without an upgrade, Google closes the account and **stops every resource in every linked project**. There
is then a 30-day grace period, after which the resources are deleted.

**Owner action:** Cloud console → Billing → that billing account → **Activate / Upgrade**. You need to be its Billing
Account Administrator.
- Upgrading causes no interruption.
- The remaining credit stays usable until its original expiry, which is the same day, so little of it will be used.
- Expected cost after that is about ₹4,450 a month at today's usage, about 80% of it Cloud SQL.

**The trial countdown keeps failing after the upgrade.**
- It is a job in `gcp-cost-controls.yml`, and scheduled workflows run from `main`. From 7 days out it fails on every
  weekday run, including after the date. It also opens a new countdown issue whenever none is open.
- It cannot be removed from `main` today. The `promotion-source-policy` check only accepts pull requests into `main`
  from `dev`, and `dev → main` is the production promotion that is on hold (§9).
- So: after upgrading, **leave issue #308 open** with a comment that the account is upgraded. Leaving it open stops
  duplicate issues. Accept the red weekday runs.
- Put the countdown's removal on `dev` now, so it reaches `main` with the next promotion. Close #308 then.
- Alternative: the repository admin disables the workflow until then. That also pauses its dev-database start job,
  which is only a safety net now that the dev database is always on.

No part of this plan can finish before tomorrow. **Do not plan on a new free trial for the new account.**
- Google's Free Trial terms (§1.2) limit trials to new customers.
- The Cloud Terms of Service (§3.3(d)(iii)) forbid creating multiple accounts to avoid fees.
- The penalty can be immediate suspension (§4.2(d)), which would take production down.

---

## 1. What we are doing, and why this route

"A new account" is three separable things. This plan does them in this order:

| Step | What | Downtime | Changes for users, CI, code |
|---|---|---|---|
| 1. Organization move | Move `custoking-dev`, then `custoking-prod`, into the company's organization (a folder in it) | Google: services "remain active and don't experience downtime"; the checklist warns of possible "brief interruptions" | **None.** Project IDs, project numbers, URLs, service accounts, WIF, Artifact Registry paths, the DB and its private IP all stay |
| 2. Billing switch | Link both projects to a billing account owned by the company (company payments profile) | Google: "shouldn't result in any service interruption" | Budgets, billing export and a few billing literals in the repo |
| 3. Ownership clean-up | Company accounts become the owners; personal accounts step down after a stabilization period | None | Notification channels, Drive folder ownership |

**Rejected: rebuilding in new projects, as we did in August 2026.**
- It changes every project-bound identifier: about 96 non-test files in this repo, 32 GitHub variables, every
  user-facing URL (there is no custom domain), and 41 service accounts. It also needs a data copy and a freeze.
- It only makes sense if the organization move is impossible.
- The August move was abandoned for one reason: the old organization denied exports and we could not administer it.
  That is not the case now: the account that runs these steps is already an organization administrator here.

### What changes and what does not on an organization move

| Item | After the move | Source |
|---|---|---|
| Project ID and number, every resource, data | Unchanged; "not a data transfer" | Google: project-migration |
| Cloud Run URLs (both the hash and the `-<number>.asia-south2.run.app` forms) | Unchanged (same project number, services not recreated) | Google + inference |
| Cloud SQL instance, its private IP, VPC peering | Unchanged | Google ("databases remain online") + inference |
| Service accounts, roles granted **on the project**, project custom roles | Unchanged, move with the project | Google |
| Workload Identity Federation pools and providers (GitHub Actions) | "migrated with the project. No additional action is required" | Google: handle-special-cases |
| Artifact Registry paths, Secret Manager, Pub/Sub, Scheduler, Cloud Deploy, buckets, monitoring | Unchanged (project-scoped) | Inference; none is a Google special case |
| **Roles inherited from the old organization** | **Lost** | Google |
| **Organization policies** | **Replaced by the destination's** | Google |
| Organization-level quotas | Lost; project quotas stay | Google |
| Billing link | **Unchanged.** The project stays on the old billing account until step 2 | Google |
| GitHub variables, workflow files, application code | **No change.** The repository holds 46 variables (19 repository-level, 12 `dev`, 15 `prod`) and 0 secrets; none changes | Inventory |

---

## 2. Facts the plan depends on (verified 2026-11-15, read-only)

- **The organization holds only people, no structure.** Both projects sit directly under the current organization,
  which has no folders and no org-level custom roles. Two personal Google accounts hold all its roles, including
  Organization Admin, Project Mover and Billing Admin.
- **Project-level human access is a single binding.** On each project, only the second owner account holds `roles/owner`.
  **The account that runs our operations has no project-level role; all its access is inherited from the organization,
  so it would lose all access the moment a project moves.**
- **Moving is blocked by policy today.** `resourcemanager.allowedExportDestinations` and
  `resourcemanager.allowedImportSources` are effectively DENY-all on the current organization. Nobody holds Org Policy
  Admin yet, but an Organization Admin can grant it.
- **The rest of the organization's policies** are Google's secure defaults: no service-account keys, uniform bucket
  access, plus compute defaults. `iam.allowedPolicyMemberDomains` (domain-restricted sharing) is **not** enforced
  today, which is why `allUsers` and personal Gmail accounts can be granted.
- **Only three services are public (`allUsers`):** the prod frontend, the prod api-gateway, and the prod dashboard; dev
  exposes its frontend and api-gateway.
  - The dashboard does **not** use IAP (`dashboard_service.tf`: "IAP is off for good"). It signs people in itself, with
    Google Sign-In through a custoking-prod OAuth client and an email allowlist. Its consent screen's audience is what
    a move can affect (§6.1).
  - The other services are invoked by service accounts.
  - No bucket is public (0 public members across 18 buckets).
- **dev and prod are linked in three places:**
  - prod **releases** read images from dev's Artifact Registry. Five prod principals have reader on it; running prod
    services pull from prod's own registry;
  - prod's `cost-analysis-collector` holds BigQuery and Monitoring roles in dev. These grants are unused: the job
    writes to custoking-prod;
  - dev's `cost-metric-exporter` reads prod's `billing_export` dataset.

  Both cost jobs have failed on every run since 2026-11-03, for an unrelated reason. Their runs therefore prove
  nothing about cross-project access; the checks below compare IAM instead.
- **A dry-run move analysis found nothing blocking.** `gcloud asset analyze-move` on prod, run against an illustrative
  empty organization, reported 0 blockers and 4 warnings: inherited IAM, inherited org policy, possibly lower quotas,
  and PAM (0 org-level PAM entitlements exist). **It does not check the export/import policies**, so a clean analysis
  does not prove the move will succeed.
- **Unverified:**
  - VPC Service Controls: the Access Context Manager API is disabled, so a perimeter is unlikely.
  - The dashboard OAuth client's audience (Internal or External): weak evidence says External.
  - Essential Contacts: API disabled.
- **The billing account also funds one unrelated non-IMS project** in the same organization (named in restricted
  notes).
- **The prod estate:**
  - 8 Cloud Run services and 5 jobs;
  - Cloud SQL Postgres 16 (db-g1-small, ENTERPRISE, PITR on, about 267 MB used);
  - 9 buckets, 28 secrets, 22 service accounts, 4 topics with 2 push subscriptions;
  - 7 Cloud Deploy pipelines and targets;
  - 7 Scheduler jobs, in **asia-south1**;
  - 7 uptime checks, and 76 alert policies. 27 of them notify, including all 7 uptime checks. The other 49 (the
    per-service and SLO-burn policies) are muted on purpose by Terraform flags.
- **The dev estate:** 7 services, 3 jobs, 6 Scheduler jobs in asia-south1, 32 secrets, 21 service accounts, **0 uptime
  checks**, and 67 alert policies, of which only 3 (all spend-related) have a channel. **A dev breakage today pages
  no one**, and dev moves first (§6.1 step 8).

---

## 3. What the owner must provide before anything moves

| # | Needed | Why | Gate |
|---|---|---|---|
| P1 | **The company's Google Cloud organization.** Google Workspace or Cloud Identity Free on a domain the company owns, verified by DNS. Record its org ID. | Projects can only move into an organization. A new standalone (no-domain) organization is only created for new Free Trial sign-ups, which §0 rules out. | Org exists; ID recorded in restricted storage |
| P2 | **A folder in it for IMS**, for example `ims` | Lets us set IMS-specific policies (P4) without loosening the whole company organization | Folder ID recorded |
| P3 | **One migration operator account in the company domain**, for example `<COMPANY_ADMIN>` | Google requires **the same account** to hold the move roles in both organizations. A company account can be granted roles in the current organization (no domain restriction there). A personal Gmail account might be refused by the company organization's domain restriction. | Account exists, has MFA |
| P4 | **The destination policy decision** (see §4) | Domain-restricted sharing on the company org would cut users off from the public app | §4 checks pass on the folder |
| P5 | **A company billing account** under the company's payments profile (organization type, GST details) | Step 2. A billing account's payments profile cannot be changed, so a company payer needs a new billing account. | Account open; `<COMPANY_ADMIN>` is its Billing Account Administrator |
| P6 | Who keeps access afterwards: the two personal accounts, company accounts, both? | Today one personal account is the only direct project owner | Written list |
| P7 | Whether the unrelated non-IMS project stays, moves too, or gets its own billing | It shares the organization and billing account | Decision recorded |

**Not required for the move, but decide now:**
- Whether the GitHub repository will also move from the personal owner `custokingkr-dev` to a company GitHub
  organization. The WIF conditions pin the repository owner ID, so a transfer needs both providers updated first; that
  is a separate, later change (§9).
- Who owns the two Google Drive photo-import folders. They are owned by a person and shared with the runtime service
  accounts. The service accounts do not change, so the sharing keeps working; only ownership is a question.

---

## 4. Destination policy gate (P4): the one thing that can break the app

New Google Cloud organizations are created with secure-by-default policies. The one that matters here:

- **`iam.allowedPolicyMemberDomains` (domain-restricted sharing).** If enforced over the IMS folder, Google says
  "users outside of your organization won't be able to access public Cloud Run services". That is every school using
  the app. It would also refuse future grants to personal accounts and to accounts in other organizations.

**Plan:** set `iam.allowedPolicyMemberDomains` to **allow all on the IMS folder**, which matches today. Do this before
the move and verify it in effect.

If the company wants domain restriction later, decide it **after** the move as its own change. Google documents two
ways to keep the public app working under it:
- **Custom-constraint domain restriction** with a tag-conditioned exception for the public services. Google: allowing
  `allUsers` "is only available if you use custom organization policies". The managed constraint
  `iam.managed.allowedPolicyMembers` **cannot** express `allUsers`, so do not use it for this.
- **Turn off the Cloud Run invoker IAM check** (`--no-invoker-iam-check`) on the public services, and drop their
  `allUsers` bindings.

**Also compare, on the IMS folder** (the move itself needs no change to any of these; we only confirm they do not break
what runs):

| Constraint | Today | What to confirm |
|---|---|---|
| `storage.publicAccessPrevention` | Not enforced | Harmless: no bucket is public |
| `iam.disableServiceAccountKeyCreation` / `KeyUpload` | Enforced | Harmless: no user-managed keys exist; CI uses WIF |
| `iam.automaticIamGrantsForDefaultServiceAccounts` | Enforced | Harmless: runtime uses dedicated service accounts |
| `run.allowedIngress`, `run.allowedVPCEgress` | Allow all | Must still allow ingress `all` (every service uses it) |
| `gcp.resourceLocations` | Allow all | Must allow asia-south2, asia-south1 (Scheduler), `asia` (Cloud SQL backups), `us` (billing dataset, traces) |
| `iam.workloadIdentityPoolProviders` | Allow all | Must allow `token.actions.githubusercontent.com` |
| `sql.restrictPublicIp`, `sql.restrictAuthorizedNetworks` | Not enforced | Harmless: the database uses only a private IP |
| `iam.allowServiceAccountCredentialLifetimeExtension` | Deny (default) | Harmless |
| `essentialcontacts.allowedContactDomains` | Allow all | Only affects contact email addresses |

**Check effective values against the project after the move**, not only on the folder before it.

---

## 5. Access plan: no one may be locked out at any point

**Who may run a move.** Google requires the move roles to be held by **the same account in both organizations**. So
the move, and the reverse move if one is needed, must be run by `<COMPANY_ADMIN>`. "Another organization admin" is
not enough. Organization Administrator does **not** include setting organization policy, which is why the grants below
include `orgpolicy.policyAdmin`. That role can only be granted at organization level.

| When | Grant | Where | Why |
|---|---|---|---|
| Before dev move | `roles/owner` to `<COMPANY_ADMIN>`. The account is outside this organization, so Google requires the console; the invitation must be accepted. | **Directly on** custoking-dev and custoking-prod | Survives the move (direct project roles move with it). Also covers the project-level move permissions. |
| Before dev move | Also a **direct** project role for **the operations account**, which today has none: owner, or at least `roles/viewer` plus `roles/iam.securityReviewer` | Both projects | Otherwise it loses all access when the projects leave the organization, and the checks that read IAM, dataset access lists and effective policies cannot run |
| Before dev move | `roles/resourcemanager.folderViewer` and `roles/iam.securityReviewer` for the operations account. If the company organization refuses a personal account, `<COMPANY_ADMIN>` runs every company-side read instead and keeps the output in restricted storage. | **IMS folder** only | The operations account holds no role in the company organization and cannot be given one at its top level |
| Before dev move | `roles/resourcemanager.projectMover` (forward move: the project's current parent), **`roles/resourcemanager.projectCreator`** (reverse move: the old organization becomes the destination), `roles/orgpolicy.policyAdmin` | **Current** organization | Forward move, and a rollback that can actually run |
| Before dev move | `roles/orgpolicy.policyAdmin` | Company **organization** (org level only) | Set the IMS-folder policies (§4) and the import/export allowances |
| Before dev move | `roles/resourcemanager.projectMover` | **IMS folder** | Forward move: Project Mover on the destination folder. Reverse move: Project Mover on the project's parent at that time. |
| Before dev move | `roles/billing.admin` on the company billing account | Billing | Phase 3 relink, and the Terraform budgets |
| Before Phase 3 | `roles/billing.admin` on the **current** billing account as well. Allowed: the old organization has no domain restriction. | Billing | Google needs permissions on **both** the current and the target account to relink. The budget apply deletes the old budgets, which needs rights on the old account. Relinking back in a rollback needs rights there too. |
| After prod passes soak | Revoke the temporary mover, creator and policy-admin roles in both organizations; remove the export/import allowances | Both organizations | Google's own clean-up step |
| After billing switch | Personal accounts step down, per P6 | Projects, billing accounts | Least privilege, only after stabilization |

**Monitoring notification channels** today point at the two personal email addresses. They are made by Terraform, one
per address in `notification_email_addresses`, and every notifying policy uses only those. A channel added in the
console is attached to no alert, so it receives nothing.
- Add the company address there, in both observability tfvars, and **apply before the freeze**. This is an [owner]
  step.
- Check that the prod policies now list the new channel.

In prod, the 27 notifying policies and 7 uptime checks already cover a broken service; leave the 49 muted ones as they
are. If they should notify, flip the two Terraform flags and apply **before** the freeze; never edit them in the
console, because the next apply would revert it. Dev has nothing that pages (§6.1 step 8).

---

## 6. Procedure

**Who runs what.**
- Steps marked **[owner]** change IAM, organization policy, billing or the project's parent. They are run by
  `<COMPANY_ADMIN>` or another organization admin. The assistant's tooling is not permitted to make IAM or billing changes.
- Steps marked **[ops]** are read-only checks or pull requests, which the assistant can run or prepare.

### 6.1 Phase 0: preparation (about 2–3 hours, after P1–P7)

1. **[ops] Baseline** both projects into restricted storage:
   - `projects describe` / `get-ancestors` / `get-iam-policy`;
   - effective values of every §4 constraint;
   - Cloud Run services, revisions, URLs and service IAM; jobs;
   - Scheduler jobs with last-run status; Pub/Sub subscriptions with backlog;
   - uptime-check results;
   - Cloud SQL state;
   - bucket IAM;
   - WIF providers; the cross-project grants listed in §2;
   - the last green CI deploy.

   This is the diff target for every later check.
2. **[owner] Billing:** confirm the current account shows as upgraded and open (§0). Leave the projects on it.
3. **[owner] Access, first:** every §5 "before dev move" grant. These include `orgpolicy.policyAdmin`, which the next
   step needs.

   **Verify.** Company-side reads are run by `<COMPANY_ADMIN>`, or by the operations account if the IMS-folder grants
   in §5 were accepted.
   - each account can `projects describe` both projects through a **direct** binding;
   - `<COMPANY_ADMIN>` holds Project Mover and Project Creator on the current organization, Project Mover on the IMS
     folder, and Org Policy Admin on both organizations:
     `gcloud organizations get-iam-policy <ORG_ID> --flatten=bindings --filter="bindings.members:<COMPANY_ADMIN>"`
     for each organization, and `gcloud resource-manager folders get-iam-policy <IMS_FOLDER_ID>`.
4. **[owner] Destination:** the IMS folder exists (P2), and the §4 policies are set on it and **verified effective** on
   the folder.
5. **[owner] Allow the move in both directions**, so rollback is already possible:
   ```powershell
   # Current organization: allow exporting to, and importing back from, the company organization.
   gcloud resource-manager org-policies allow resourcemanager.allowedExportDestinations under:organizations/<DEST_ORG_ID> --organization=<SOURCE_ORG_ID>
   gcloud resource-manager org-policies allow resourcemanager.allowedImportSources      under:organizations/<DEST_ORG_ID> --organization=<SOURCE_ORG_ID>
   # Company organization (Google requires these at the organization root): the mirror image.
   gcloud resource-manager org-policies allow resourcemanager.allowedImportSources      under:organizations/<SOURCE_ORG_ID> --organization=<DEST_ORG_ID>
   gcloud resource-manager org-policies allow resourcemanager.allowedExportDestinations under:organizations/<SOURCE_ORG_ID> --organization=<DEST_ORG_ID>
   ```

   **[owner: `<COMPANY_ADMIN>`] Prove both directions on a canary before touching dev.** Google's `analyze-move`
   "does not perform any checks on the destination organization resource", so a clean analysis proves little.
   1. Create an empty project in the old organization, with no billing.
   2. Move it to the IMS folder, and read its effective policies there (§4).
   3. Move it back with `gcloud beta projects move <CANARY> --organization=<SOURCE_ORG_ID>`.
   4. Delete it.

   A successful round trip is a go/no-go condition for Phase 1. It exercises all four allowances, Project Mover on the
   old organization and on the IMS folder, and Project Creator on the old organization (the rollback path).
6. **[ops] Pre-checks against the real destination:**
   ```powershell
   gcloud asset analyze-move --project=custoking-dev  --destination-folder=<IMS_FOLDER_ID> --format=json
   gcloud asset analyze-move --project=custoking-prod --destination-folder=<IMS_FOLDER_ID> --format=json
   ```
   - Pass condition: 0 blockers.
   - Every warning has a written disposition: expected are inherited IAM (handled by §5), inherited policy (§4),
     quotas, and PAM (0 entitlements).
   - Cloud Asset API is enabled in prod but **not in dev**. Enabling it in dev is an [owner] step.
7. **[owner/ops] Close the unverified items in §2:**
   - VPC Service Controls: no perimeter contains either project. Enable the Access Context Manager API to check, or have
     an org admin attest.
   - **The dashboard's OAuth client audience is External.** Check in the console: Google Auth Platform → Audience; the
     deprecated IAP brand API is only weak evidence. If it is Internal, Google says that after a move "only destination
     organization resource members can authorize requests" (up to 24 hours to take effect), so personal-account sign-in
     to the dashboard would stop.
   - No open Google support cases or Marketplace subscriptions.
8. **[ops] Alerting before the move:**
   - the company address is on the Terraform channels (§5) and one test notification arrives;
   - **give dev something that pages for its soak.** Dev runs with `enable_alert_notifications = false`, so uptime
     checks alone alert no one. Either create temporary uptime checks for the dev frontend and api-gateway together with
     alert policies on the dev channels, or put twice-daily manual checks on the calendar. Remove them in Phase 4.
9. **Freeze:** from now until prod passes its soak:
   - no prod promotion. It is already on hold (§9);
   - no Terraform apply;
   - **no merges to `dev`** from the start of Phase 1 until the dev soak ends. Otherwise a regression cannot be blamed
     on the move or ruled out.

### 6.2 Phase 1: move dev (about 30 minutes; then 48–72 h soak)

**Go/no-go:** every Phase 0 item has evidence; `analyze-move` for dev shows 0 blockers; dev's last deploy is green.

1. **[owner: `<COMPANY_ADMIN>` only]** `gcloud beta projects move custoking-dev --folder=<IMS_FOLDER_ID>`. Record the
   time, output and audit-log entry. The command is beta; the console works too. "Generally takes less than a minute."
2. **[ops] Control-plane checks (T+0 to T+15):**
   - ancestors show the IMS folder;
   - ID and number unchanged;
   - billing link unchanged;
   - direct IAM and the project's 7 custom roles equal the baseline;
   - effective §4 values as decided;
   - Cloud SQL RUNNABLE;
   - every service, job, Scheduler job, topic, subscription, bucket, secret name and WIF provider enumerates.
3. **[ops] Runtime checks (T+15 to T+60):**
   - dev frontend and api-gateway answer as in the baseline;
   - sign in on dev as superadmin; open a school, the attendance Exceptions screen and Workflow set-up;
   - each Scheduler relay's next run succeeds (or trigger one with `gcloud scheduler jobs run`);
   - Pub/Sub push subscriptions show no growing backlog;
   - no new ERROR logs per service;
   - **GitHub → GCP:** run one dev deploy through `build-release.yml` with `workflow_dispatch`,
     `target_environment=dev` and `commit_sha=<baseline dev HEAD>`.
     - Naming a commit rebuilds every service on the normal direct dev path. A dispatch without one compares only the
       last commit; if that commit touched no service, the run is green **without ever signing in to Google Cloud**.
     - Do **not** use `force_full_deploy=true`: it moves dev onto Cloud Deploy.
     - Pass only if the "Authenticate to Google Cloud" steps ran and new revisions of that commit are serving.
   - **cross-organization links:** compare IAM with the baseline. Do not rely on the cost jobs, which fail for an
     unrelated reason. Specifically:
     - the dev Artifact Registry policy still lists the 5 prod readers
       (`gcloud artifacts repositories get-iam-policy custoking --location=asia-south2 --project=custoking-dev`);
     - `bq show --format=prettyjson custoking-prod:billing_export` still lists dev's `cost-metric-exporter`.
4. **Soak 48–72 h:** watch errors, Scheduler runs, the next scheduled jobs, and the temporary dev uptime checks or the
   manual checks (step 8 of Phase 0).

**If dev fails:** see §8. Fix forward first. If that cannot work, move dev back; the reverse allowances from step 5 are
already in place.

### 6.3 Phase 2: move prod (one low-traffic window, about 1 hour)

**Go/no-go:**
- dev passed its soak;
- `analyze-move` for prod shows 0 blockers;
- a fresh prod Cloud SQL export exists and has been restore-checked. It is not needed by the move (no data moves), but
  it is cheap insurance at about 267 MB.

**Tell schools:** a short notice of a possible brief interruption, in a late-evening IST window.

1. **[owner: `<COMPANY_ADMIN>` only]** `gcloud beta projects move custoking-prod --folder=<IMS_FOLDER_ID>`
2. **[ops]** The same control-plane checks as dev, plus:
   - 3 custom roles;
   - the `allUsers` binding on frontend, api-gateway and dashboard is still present and **works from outside the
     company** (a signed-out browser on another network).
3. **[ops] Runtime:**
   - all **7 uptime checks** green;
   - prod sign-in with an approved test account; a tenant read; a student photo opens;
   - **sign in to the dashboard** with an allowlisted personal Google account from outside the company, and confirm the
     Google consent completes. Repeat this about 24 hours later, since an audience change can take that long;
   - 4 Scheduler relays run; push subscriptions drain; no new ERROR logs;
   - cross-project IAM equals the baseline: dev's registry readers, and `cost-analysis-collector@custoking-prod` in
     `gcloud projects get-iam-policy custoking-dev`. A prod **release** is not part of this window (§9).
4. **Soak 48 h.**

### 6.4 Phase 3: switch billing to the company account (minutes; after prod's soak)

1. **[owner]** link each project to the company billing account, dev first and then prod:
   `gcloud billing projects link custoking-dev --billing-account=<COMPANY_BILLING_ACCOUNT_ID>`, then the same for
   custoking-prod.
   - Charges up to the switch stay on the old account.
   - **Do not link to a closed account:** that disables billing and stops the project.
2. **[owner: `<COMPANY_ADMIN>`] Budgets come from Terraform, not by hand.**
   - A budget cannot move between billing accounts, so the apply **replaces** them: it deletes the budgets on the old
     account and creates new ones. Run `terraform plan` first. It must show only those replacements: 2 project budgets
     created, and the old account's 2 project budgets plus the trial-runway budget deleted.
   - Both project budgets (today ₹2,000 dev and ₹5,000 prod, on gross cost) are `google_billing_budget` resources. A
     hand-made copy plus an apply would give duplicate alerts.
   - In **both** observability tfvars (they are gitignored), set `billing_account_id = <COMPANY_BILLING_ACCOUNT_ID>`.
     In prod also set `manage_trial_runway_budget = false`; otherwise the apply recreates the account-wide trial budget.
   - Run `terraform apply` for the **dev root and the prod root**, right after the relink. Whoever applies needs Billing
     Account Administrator or Costs Manager on the company account (§5).
   - Check that `gcloud billing budgets list --billing-account=<COMPANY_BILLING_ACCOUNT_ID>` shows exactly the 2 project
     budgets, and that none is left on the old account for these projects.
   - That same apply also carries the already-pending observability fix.
3. **[owner] Console-only items:**
   - billing export to BigQuery for the company account, into `custoking-prod:billing_export`. Keep the old tables;
     they are history;
   - the "Pricing BigQuery Transfer" config. There are two duplicates today; keep one, on the new account.
4. **[ops] Repository changes (one PR, to `dev`):**
   - `spend_anomaly_alerts.tf:105,210`: the billing-export table name embeds the account ID;
   - `.github/workflows/gcp-cost-controls.yml`: change `BILLING_ACCOUNT_ID`. The countdown removal is already on `dev`
     (§0).
5. **[owner]** Decide the old billing account's future. It still funds the unrelated project (P7). Close it only when no
   project is linked.

### 6.5 Phase 4: clean-up (after billing is stable, about a week)

- [owner] Remove the four export/import allowances; revoke the temporary mover and policy-admin roles.
- [owner] Step down personal accounts as decided in P6. Move notification channels to company addresses.
- [owner] Transfer Drive folder ownership if decided.
- [ops] Update this document with what happened, the runbooks that name the old organization, and the deploy runbook's
  "where things live".
- Decide separately what to do with three old artefacts in custoking-prod (locations in the restricted notes):
  - the August 2026 database dumps in the prod migration bucket. Delete them or keep them deliberately;
  - the archived Terraform state;
  - the old billing history.

---

## 7. Validation matrix (each phase, against the Phase 0 baseline)

| Component | Pass | If not |
|---|---|---|
| Hierarchy | IMS folder; ID and number unchanged | Stop; check audit log; consider reverse move |
| Billing | Same account linked; billing enabled | Relink to an open account |
| IAM | Direct bindings and custom roles equal the baseline; company and ops accounts can act | Narrow grant at project level; never remove old access mid-change |
| Public access | Frontend, gateway, dashboard reachable from outside the company | Domain-restriction policy on the folder (§4): fix the policy, do not re-grant blindly |
| Cloud Run | Same revisions serving; no new errors | Check inherited policy denials (Policy Troubleshooter) |
| Cloud SQL | RUNNABLE; connections OK; no restore | Never restore for an access error |
| Scheduler / Pub/Sub | Relays run; no backlog growth | Check OIDC invoker grants; do not replay blindly |
| CI (WIF) | A dev deploy of a named commit succeeds, with the sign-in steps actually run | Check the provider condition and the folder's WIF policy |
| Prod CI (WIF) | **Not proven by the move:** no prod release runs in the window. The provider and service-account policies must equal the baseline. | The first prod release after the move is its real test; run it with the deploy runbook |
| Observability | Uptime checks green; logs and metrics flowing; a test alert arrives | Treat missing telemetry as a failure |
| Cross-project | Dev registry readers, the billing dataset ACL and the collector's dev grants equal the baseline | Folder or domain policy; restore the grant narrowly |
| Dashboard sign-in | An allowlisted personal account signs in from outside the company, at the move and about 24 h later | Check the OAuth client audience (External); do not chase IAP, which is off |

## 8. Rollback

- **Before a move:** do nothing; nothing has changed.
- **Move command fails:** the parent is unchanged. Keep the output, confirm health, fix the named blocker, and take a new
  go decision. Do not retry blindly.
- **Move succeeds but something breaks:**
  1. Find the exact denial. Most likely causes: an inherited policy from §4, or a lost inherited role.
  2. Fix it narrowly on the IMS folder or project.
  3. If that cannot restore service within the window, **move the project back**:
     - the run-as rule is the same: `<COMPANY_ADMIN>` only;
     - the command: `gcloud beta projects move <PROJECT> --organization=<SOURCE_ORG_ID>`;
     - it works only because Phase 0 step 5 set the reverse allowances, and §5 gave `<COMPANY_ADMIN>` Project Mover on
       the IMS folder and Project Creator on the old organization. Google: a rollback needs "the same IAM permissions
       and enforced policies as a new migration".
- **Billing switch problem:** `<COMPANY_ADMIN>` relinks the previous account. It is open, because it was upgraded
  today, and §5 gave `<COMPANY_ADMIN>` billing admin on it before Phase 3.
- **Never** restore the database or copy buckets because of an access or policy failure. An organization move does
  not touch data.

## 9. Kept separate on purpose

- **The prod promotion is on hold, and prod's deploy configuration is stale.**
  - Prod's last real release was 2026-09-26. Its Cloud Deploy targets predate the dedicated-runtime-role cutover, and
    3 prod invoker grants are missing (see the deploy runbook).
  - None of this is needed for, or changed by, the organization move.
  - Do not combine them. Do the promotion work before Phase 0 or after Phase 2, never inside a move window.
- **GitHub repository transfer** (if the company wants it under its own GitHub organization).
  - **What a transfer breaks.** Both WIF providers pin more than the owner ID (`274906704`): their conditions also pin
    `assertion.workflow_ref == 'custokingkr-dev/ims-v1/.github/workflows/<file>@refs/heads/<branch>'`. All 9
    service-account bindings are `principalSet`s on that `workflow_ref`. Prod promotion runs `cosign verify` with a
    certificate identity naming the repository. A transfer changes the owner and the owner name in all of these, so
    every GCP-authenticated workflow would be denied, and images signed before the transfer would be refused.
  - **Order:**
    1. Through the cicd Terraform (`github_repository`, `github_repository_owner_id`), **add** the new owner's
       `workflow_ref` clauses beside the old ones in both providers, and add a parallel `principalSet` binding for each
       of the 9 service accounts.
    2. Transfer the repository.
    3. Run a dev deploy, then produce a freshly signed dev build before any prod promotion.
    4. Remove the old clauses and bindings last.
    5. Update the scripts that hard-code `custokingkr-dev/ims-v1`, and the default in `infra/terraform/cicd/variables.tf`.
  - Re-check all 46 variables (19 repository, 12 `dev`, 15 `prod`) and both environments' required reviewers after the
    transfer.
- **Known defects found in the inventory.** Fix them on their own, not as part of the move:
  - cost jobs failing since 2026-11-03. The fix is merged but needs a `terraform apply`. Do it before Phase 0's
    freeze if you want those jobs as a runtime signal;
  - dead-letter topics without subscriptions;
  - the recovery-drill service account has no WIF binding;
  - the governance audit would use the dev WIF provider;
  - `migration-operator` is disabled but still used by two jobs;
  - three repository variables are never read.
- **Data residency.** Cloud SQL backups are stored in the `asia` multi-region, traces in `us`, and the billing archive in
  `us`. If India-only storage is required, that is a separate change; the move does not alter it.
- **Rebuild fallback.** Only if the organization move is impossible. Then the August procedure applies:
  `docs/GCP-SPLIT-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md` and `scripts/migration/*.ps1`. Note the known import bug:
  `Copy-MigrationData.ps1` builds a sanitized dump but imports the raw one.

## 10. Timeline (earliest; depends on P1–P7)

| Day | What |
|---|---|
| Sun 15 Nov | **Upgrade the billing account (§0)**; comment on #308 and leave it open. Owner answers P1–P7 |
| Mon 16 – Wed 18 Nov | Company organization, IMS folder, migration account, company billing account (P1–P5) |
| Thu 19 Nov | Phase 0 (2–3 h) |
| Fri 20 Nov | Phase 1: move dev (30 min plus checks) |
| Sat 21 – Mon 23 Nov | Dev soak |
| Tue 24 Nov, late evening IST | Phase 2: move prod (about 1 h) |
| Wed 25 – Thu 26 Nov | Prod soak |
| Fri 27 Nov | Phase 3: billing switch, budgets, export, PR |
| Week of 30 Nov | Phase 4: clean-up |

No parallel estate runs at any point, so the move adds no running cost.

## 12. Execution record (2026-11-15/16 IST)

**The destination differed from §3.** The owner created a **standalone** organization from a new Google account (no
company domain or directory), not a Workspace/Cloud Identity one. Consequences:
- There is no domain-restricted sharing. `iam.allowedPolicyMemberDomains` is allow-all, and the organization carries the
  same six secure-default constraints as the source, so the projects landed under identical policies. No IMS folder
  was needed: both projects sit at the organization root, as before.
- The operations account already held Owner, Organization Admin, Project Mover and Project Creator in **both**
  organizations. So it was the one account Google requires, and it ran every step. It also keeps inherited access in
  the destination, so nobody was locked out.
- The owner also created two empty projects in the new organization. They were not needed for a move, and were
  deleted (recoverable for 30 days) after their billing was unlinked.

**Steps and evidence.** Times are UTC, 15 Nov.

| Step | Result |
|---|---|
| Read-only before-snapshot of both projects | About 60 files each: IAM, policies, services, jobs, schedulers, Pub/Sub, SQL, buckets, secrets, service accounts, WIF, registry, uptime checks, alert policies (67 dev / 76 prod), pipelines, public endpoints |
| Org Policy Admin for the operations account at both organization roots; the four export/import allowances in both directions | Effective, verified |
| Canary: empty project created in the source, moved to the destination, moved back, deleted | Both directions succeeded; the destination's effective policies equal the source's |
| `gcloud asset analyze-move` for dev and prod | 0 blockers; the 4 expected warnings (inherited IAM, inherited policy, quotas, PAM), each with a disposition |
| **Dev moved at 18:36:48**, in 7 s | Project number unchanged; endpoints 200 throughout |
| Dev after-snapshot diff | Identical apart from the four export/import allowances |
| Dev runtime | No ERROR logs; all 5 async relays ran after the move; login round trip answers 401 to a bad password |
| Dev CI: `build-release.yml` dispatched with the baseline commit | "Successfully authenticated" in every build job; new revisions of 4 services deployed after the move (the other 3 already ran that build) |
| Prod: on-demand Cloud SQL backup before the move | SUCCESSFUL (daily backups and PITR also on) |
| **Prod moved at 19:04:27**, in 7 s | Project number unchanged; gateway 200, frontend 200, dashboard 302 at every check through the move |
| Prod after-snapshot diff | Identical apart from the four export/import allowances; frontend, api-gateway and dashboard still `allUsers` |
| Prod runtime | No ERROR logs; all 4 async relays ran after the move; **all 7 uptime checks passed every probe in the 10 minutes spanning the move**; login round trip 401; cross-project grants (dev registry readers, billing dataset ACL, collector grants) unchanged |

**Not done, and why.**
- **Billing switch blocked.** The new account's billing account refuses further project links with "Cloud billing quota
  exceeded". Its owner had linked two empty projects and dev was linked third. Unlinking all of them did not free a slot
  within the hour. Both projects therefore still bill to the old trial account, which must be upgraded, or the new
  account's quota raised through Google's form or by upgrading it, before the trial ends.
- **Prod CI not exercised.** No prod release ran: the promotion is on hold (§9). The prod WIF provider and its
  service-account bindings are unchanged in the diff.
- **Clean-up deliberately deferred** until the stabilization period ends (§6.5): the four export/import allowances, the
  Org Policy Admin grants, and the old organization's remaining roles. The allowances are what makes a move back
  possible.
- **Deviation:** the pre-move "fresh export, restore-checked" became an on-demand Cloud SQL backup. The database
  service agent has no write access to the 30-day-lifecycle snapshot bucket, and an organization move touches no data.

## 11. Sources

- Google Cloud: [project migration](https://docs.cloud.google.com/resource-manager/docs/project-migration),
  [perform a migration](https://docs.cloud.google.com/resource-manager/docs/perform-migration),
  [special cases](https://docs.cloud.google.com/resource-manager/docs/handle-special-cases),
  [migration checklist](https://docs.cloud.google.com/resource-manager/docs/project-migration-checklist),
  [org policies for migration](https://docs.cloud.google.com/resource-manager/docs/configure-org-policy),
  [analyze move](https://docs.cloud.google.com/resource-manager/docs/analyze-move),
  [secure-by-default organizations](https://docs.cloud.google.com/resource-manager/docs/secure-by-default-organizations),
  [domain-restricted sharing](https://docs.cloud.google.com/resource-manager/docs/organization-policy/restricting-domains),
  [change a project's billing account](https://docs.cloud.google.com/billing/docs/how-to/modify-project),
  [modify a billing account](https://docs.cloud.google.com/billing/docs/how-to/modify-billing-account),
  [free trial](https://docs.cloud.google.com/free/docs/free-cloud-features),
  [Free Trial terms](https://cloud.google.com/terms/free-trial), [Terms of Service](https://cloud.google.com/terms).
- This repository: `docs/SUPERSEDED-GCP-IN-PLACE-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md`. It is the August analysis of
  this same route, whose checklists §6 reuses. Also `docs/runbooks/notebook-tracker-deploy.md` (prod promotion state)
  and `.github/workflows/gcp-cost-controls.yml` (trial countdown).
