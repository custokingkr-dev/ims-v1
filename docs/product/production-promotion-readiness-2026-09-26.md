# Production promotion readiness - 26 September 2026

Status: **NOT READY for promotion**. The user has authorized eventual production continuation after acceptance. The synthetic dev rehearsal passed; live recipient delivery and independent school acceptance remain unproved. This assessment used read-only GitHub and GCP metadata plus repository inspection at approximately 17:20 UTC. No production deployment, configuration, IAM, secret, database or application mutation was performed.

## Release under review

| Evidence | Observed state |
| --- | --- |
| Production branch | `main` at `c1d15601ebfbcd5cede287a0e1d31c00bb089cc3` |
| Development branch | `dev` at `d92ae6fc5b51a85e49e5a34ac9a9ae7dab8fda8c` |
| Branch comparison | Dev is 25 commits ahead and 4 behind main. The four main-only commits are previous promotion merges. The local comparison contains 309 changed files across all seven services, frontend, contracts, migrations and deployment configuration. GitHub's comparison file response stopped at 300 and is not the full count. |
| Current work | [PR 294](https://github.com/custokingkr-dev/ims-v1/pull/294), draft into dev, head `6c925d66e51e452a62ab9c7b139b6a67881e328a`. All non-skipped checks on that head passed. Further live-provider/UI changes were still uncommitted during this assessment; those check results do not validate subsequent work. |
| Verified dev application release | [CD 36252157938](https://github.com/custokingkr-dev/ims-v1/actions/runs/36252157938), source `86c5a945`, deployed all seven services. |
| Verified dev private-workflow release | [CD 36254443940](https://github.com/custokingkr-dev/ims-v1/actions/runs/36254443940), source `d4f19b46`, deployed four affected services. |
| Latest dev workflow | [CD 36256958772](https://github.com/custokingkr-dev/ims-v1/actions/runs/36256958772), source `d92ae6fc`, was a successful documentation-only run. Its release/build jobs were skipped; it is not a newer application deployment. |

[Dev acceptance](dev-release-acceptance-2026-09-26.md) records the owned synthetic admission, attendance replay, 100-paise payment/receipt replay and conflict, procurement replay, private document access/deletion, annual confirmation, dry-run broadcast and consent withdrawal. It includes successful migration/privilege verification and natural Scheduler execution. It establishes technical results in dev only. Synthetic transactions do not establish a school's adoption, real revenue, margin or recipient delivery.

## Actual GitHub gates

Read-only repository/environment APIs established the following:

- Both `main` and `dev` currently report `protected: false`; repository rulesets are empty. The current token has push access but not admin or maintain access. A repository administrator must apply the reviewed branch rules; successful checks alone do not prevent an unchecked merge or direct push.
- The `prod` environment permits only `main` and has required reviewers `bagrodiashubham` and `custokingkr-dev`. Self-review prevention is false. The environment review is a real release/configuration approval gate; no approval for a future run is claimed here.
- PR CI requires a promotion PR into `main` to originate from `dev`. Preserve that route and resolve any divergence in a reviewed PR. Do not cherry-pick the current feature branch directly into production.
- The release workflow requires prod dispatch from `main` and verifies that the selected SHA belongs to that branch. Production resolves the source's dev-approved image digest, verifies the dev workflow signature, copies that digest to the production registry, and applies the exact-digest security and runtime checks. It does not build a substitute production image when the approved digest is missing.
- All seven production Cloud Deploy targets set `requireApproval: false`. The release workflow advances canaries automatically through 5%, 25%, 50% and stable. The GitHub environment review must not be described as a separate human approval at every canary phase.

The production environment variables identify `custoking-prod`, project number `182609177023`, production release/configuration identities and the production staging bucket. `ARTIFACT_REGISTRY_PROJECT_ID=custoking-dev` is intentional: promotion reads the approved development image and copies its digest into the production registry. The repository variables API returned 404 with this token, so inherited variables such as workload identity and the photo bucket were not independently enumerated. Verify the final rendered configuration rather than interpreting that response as missing variables. GitHub environment secrets numbered zero; GCP workload identity and Secret Manager references are used instead.

## Production prerequisites observed

All cloud reads explicitly selected `custoking-prod`; the workstation's default project was not used or changed. Secret values, ordinary recipient destinations and database contents were not read.

| Area | Current evidence | Required before promotion/activation |
| --- | --- | --- |
| Existing runtime | All seven Cloud Run services have a ready current revision serving 100% traffic. Their images are immutable digests in the production registry. All observed services have minimum instances 0 and CPU throttling enabled. | Capture the pre-release revision/digest inventory and verify the new release, routes and critical workflows after rollout. Readiness metadata is not an end-to-end application test. |
| Broadcast policy credential | `broadcast-policy-token-prod` was absent from the 26 production secret metadata records. Current production revisions do not reference it. The promoted school-core and platform manifests require this secret reference. | Create the dedicated production credential through the reviewed secret path and grant access only to the two required runtime identities. Verify an enabled version and each service's access without printing its value. **Broadcast OFF does not remove this Cloud Run startup dependency.** |
| Live email callback credential | New application manifests also reference `broadcast-live-webhook-token-prod`; it was absent in the inspected inventory. The dev prerequisite was subsequently provisioned with only the platform runtime accessor. | Run the reviewed `scripts/configure-live-broadcast-prerequisites.ps1` metadata plan for the explicit production project, then provision before rollout. This secret is required even while live sending remains disabled. Configure the MSG91 custom callback header privately before activation. |
| Private quotation storage | No quotation bucket exists among the eight production buckets, and no `quotationDocumentRuntime` project custom role was found. The promoted operations manifest names `custoking-prod-quotation-documents`. | Provision the private production bucket with uniform bucket-level access and public access prevention. Review the four-permission runtime role (`storage.buckets.get`, `storage.objects.create`, `storage.objects.get`, `storage.objects.delete`) and bind it only on this bucket to the operations runtime identity. Verify grants without replacing existing policies. |
| Policy peer authorization | The new feature needs platform-to-school-core policy access in addition to its dedicated credential. Existing production IAM for this new path was not independently verified. | Verify narrowly scoped Cloud Run invocation for the platform identity and the scoped policy token. Do not widen service or bucket access to fix a failed check. |
| Scheduled workers | Enabled minute jobs already exist in `asia-south1` for operations relay and platform drain, using the production Scheduler service account and each current hashed Cloud Run URL/audience. School-core and billing jobs also exist. No jobs were returned in `asia-south2`. | Reuse and reconcile the existing jobs rather than create duplicates in a new region. Verify target, OIDC audience, invoker IAM and natural successful execution on the new revisions. Configuration alone does not prove execution. |
| Messaging defaults | Current platform is `logging` with `MSG91_DRY_RUN=true`. The reviewed production target defaults are broadcast `OFF` and worker-ready false. `msg91-auth-key-prod` exists as metadata; its provider validity was not tested. | Keep these defaults for a non-live release. A live pilot needs separately reviewed school/channel allowlisting, sender/account eligibility, current consent, report authentication and the approved recipient test. A global provider switch must not send old dry-run rows or unrelated queues. |
| Reset email | No `PASSWORD_RESET_ENABLED`/worker-ready settings or `SPRING_MAIL_HOST` were present in the current identity revision. No SMTP/reset-named secret was in the inspected metadata. | Retain administrator-assisted recovery until the approved SMTP configuration and delivery/expiry/replay/worker evidence exist. Always-on worker requirements must be reconciled explicitly; scale-to-zero metadata cannot prove idle processing. |
| Database and backup | `custoking-db-prod` is RUNNABLE, PostgreSQL 16, `db-g1-small`, zonal. Backups and point-in-time recovery are enabled; retention is 14 backups and 7 days of transaction logs. The latest three inspected backups were successful; the latest was `1790368200000`, completed 25 September at 22:22 UTC. | Obtain a fresh release backup and a reviewed rollback/restore plan. Run read-only production migration/data preflights with verified visibility before applying the seven existing new migrations and any additional live-ledger migration. Dev's clean receipt counts do not prove production-wide uniqueness or visibility. Verify post-migration history, RLS and exact runtime grants. |

The existing Scheduler paths `/api/v1/internal/outbox/relay` and `/api/v1/internal/async/drain` are guarded by Cloud Run IAM. Their controllers do not use the application `InternalCallerAuthenticator`; the separate delivery-command OIDC audience/peer allowlist must not be confused with these Scheduler paths. Verify the actual job audience against the target service and successful request evidence.

## Configuration reconciliation is required

The read-only classifier was run against `origin/main` to `origin/dev` with `-Environment prod -ForceAll`. It reported all seven services, `deployment_config_changed=true`, and `deployment_reconciliation_required=true`. The triggering paths are `deploy/clouddeploy/targets-prod.yaml` and `scripts/render-clouddeploy-targets.ps1`.

The current [release workflow](../../.github/workflows/build-release.yml) skips service-test/build/release jobs when reconciliation is required, even with `force_full_deploy=true`. Re-running the same merge commit after reconciliation does not change the classifier's result. [Configuration reconciliation](../../.github/workflows/reconcile-deployment-config.yml) is a separate main-only, prod-environment-reviewed workflow that applies targets/pipelines and creates no release or rollout.

Use the following sequence when the acceptance gates are closed:

1. Finalize the exact dev source and its tests, deploy the approved digests, and retain live/school acceptance evidence for that version. Review the full promotion diff, including all migrations and new startup dependencies.
2. Prepare and verify production prerequisites, backup, data/privilege preflight, rendered configuration and rollback inventory. Provisioning is a separate reviewed mutation; this assessment did not perform it.
3. Promote through a reviewed `dev`-to-`main` PR. If that push contains target/renderer changes, expect the release workflow to stop at the reconciliation gate. Run the production configuration-reconciliation workflow with its required reviewer.
4. Use a reviewed subsequent commit, promoted through dev, whose own release comparison contains no target/pipeline/renderer change, then dispatch the production workflow with its exact `commit_sha` and `force_full_deploy=true`. Manual dispatch compares that SHA to its first parent; verify this comparison locally first. A source-identical documentation/evidence commit can preserve the tested image source IDs. Do not bypass the classifier or invent approved image tags.
5. Complete the production environment review, observe the serial rollout and security gates, verify all seven intended digests/revisions and run the agreed bounded smoke checks. Verify natural Scheduler execution and truthful disabled/live capability states. Keep live activation separate until its exact production prerequisites and approval are satisfied.

For an alternative staged configuration promotion, keep the configuration-only and application/manifest release commits separate from the beginning, with the same reconciliation and digest checks. In either sequence, confirm the final workflow's actual comparison rather than assuming a green no-deployment run changed runtime state.

The existing [release operator runbook](../runbooks/release-operator.md) still mentions a manual `apply_deployment_config` input and automatic target reconciliation that are not present in the current workflow. Use the workflow definitions above as the authoritative contract and correct that documentation before an operator follows it for production.

## Remaining acceptance decisions

| Gate | Evidence still required |
| --- | --- |
| Live messaging | Final live implementation and migration tests, deployed dev source/digest, an explicitly approved real destination/channel, verified sender/account eligibility, one provider submission, authenticated bound receipt plus recipient observation, harmless replay/conflict handling, conservative UNKNOWN handling and withdrawal with zero provider submissions. Current dry-run/logging outcomes prove none of the live-delivery claims. See [live readiness](live-messaging-readiness-2026-09-26.md). |
| Independent school acceptance | A nominated school representative and support owner, the cases they actually performed, assistance and defects, and their dated scope-specific decision. [The session packet](school-acceptance-session-2026-09-26.md) currently says NOT ASSESSED. The assistant cannot supply this sign-off. |
| Capacity and operating cost | An approved bounded measurement window and verified isolated fixture. The latest documented dev gross cost exceeded its guard; no load test or budget override was run. The production database tier is not capacity certification. Any limited pilot must state its unmeasured capacity and agreed operating scope. |
| Operational ownership | Named release/rollback and support owners, the accepted monitoring/response path, and how UNKNOWN provider outcomes are reconciled without blind resubmission. Business adoption, willingness to pay and realized margin remain separately measured outcomes. |
| Repository enforcement | Administrator-applied branch protection/rules, or a clearly recorded release decision acknowledging the currently absent enforcement. The prod environment's review remains required. |

This is a point-in-time readiness assessment, not a release approval. Refresh the PR head, branch comparison, checks, rendered config, runtime inventory and external acceptance evidence after ongoing changes settle. No production database-wide cleanliness, SMTP delivery, provider delivery, capacity result or school approval is inferred from absent evidence.

## Verification limits and process state

Evidence came from GitHub branch/compare/PR/environment APIs, explicit-project GCP service/secret/bucket/role/Scheduler/SQL-backup metadata, local git comparisons, the release classifier and the linked source-controlled acceptance record. No production database rows or provider credentials were accessed. IAM bindings for the new production policy/storage paths and inherited repository variables remain to be verified by the release operator.

All inspection commands launched for this assessment completed. The process check found no workspace-referencing Maven, Java, acceptance-runner or gcloud process owned by this subtask. The observed workspace Node processes were agent tool servers, which were left running. Other agents were still implementing the live work; this statement does not imply their work or tests were finished.
