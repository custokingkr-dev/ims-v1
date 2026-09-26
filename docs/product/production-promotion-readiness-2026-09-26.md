# Production promotion readiness - 26 September 2026

Status: **NOT READY for promotion**. The user has authorized eventual production continuation after acceptance. The synthetic dev rehearsal passed and the dedicated EMAIL implementation has since deployed to dev with live sending disabled; live recipient delivery and independent school acceptance remain unproved. Updated 27 September 2026 (IST). Production metadata below remains the read-only snapshot from approximately 17:20 UTC on 26 September unless stated otherwise. No production deployment, configuration, IAM, secret, database or application mutation was performed.

## Release under review

| Evidence | Observed state |
| --- | --- |
| Production branch | `main` at `c1d15601ebfbcd5cede287a0e1d31c00bb089cc3` |
| Development branch | `dev` at `1da0dc1d99f839bdd6b24aeb6565bb85d7728f63` after PR 296; its frontend release succeeded |
| Branch comparison | At these exact heads, dev is 34 commits ahead and 4 behind main, with 335 changed files. Main's tree equals the merge-base tree, so this comparison indicates no content conflict. Nine SQL migrations are added, with no historical migration files modified; see the inventory below. Refresh the comparison if either head changes |
| Current work | [PR 294](https://github.com/custokingkr-dev/ims-v1/pull/294) and [PR 296](https://github.com/custokingkr-dev/ims-v1/pull/296) are merged and deployed to dev. The login-only 60-second timeout fix passed exact-head [CI 36262566669](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262566669), [CodeQL 36262566385](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262566385) and [CD 36262889154](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262889154) |
| Implementation CI | PR 294's [final CI 36260887510](https://github.com/custokingkr-dev/ims-v1/actions/runs/36260887510) passed 345 platform tests, 88 gateway tests, 397 frontend unit tests and 109 browser checks. [CodeQL 36260887293](https://github.com/custokingkr-dev/ims-v1/actions/runs/36260887293) passed |
| Verified dev application release | [CD 36252157938](https://github.com/custokingkr-dev/ims-v1/actions/runs/36252157938), source `86c5a945`, deployed all seven services. |
| Verified dev private-workflow release | [CD 36254443940](https://github.com/custokingkr-dev/ims-v1/actions/runs/36254443940), source `d4f19b46`, deployed four affected services. |
| Verified EMAIL release | [CD 36261225626](https://github.com/custokingkr-dev/ims-v1/actions/runs/36261225626), source `a07c2fa73a4bc8731b2d41015f31f65624bfdb8c`, succeeded. Platform ready revision: `custoking-platform-service-dev-muipmx2l` |
| Latest verified frontend release | [CD 36262889154](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262889154), source `1da0dc1d99f839bdd6b24aeb6565bb85d7728f63`, succeeded. Revision `custoking-frontend-dev-00050-snx` is ready with 100% traffic and HTTP 200 at 18:42:26 UTC; gateway was UP at 18:42:36 UTC. Signing and approval completed at 18:42:56 UTC |
| Dev pre-release backup | `1790446335149` was SUCCESSFUL before deployment; this is dev backup evidence, not a new production backup |
| Deployed dev checks | `artifacts/product-dev-release-2026-09-26/live-release-dev-verification.json` records `completed=true`: school 1 remains `DRY_RUN` (`canQueue=true`, `canSend=false`), retained broadcasts stay dry-run, missing/wrong callback credentials receive 401, and an authenticated unknown synthetic callback receives 202 twice. No actual provider send occurred |
| Post-deploy database proof | Read-only execution `ims-q-dev-xj2hr` at 18:22:28 UTC verifies notification V12, zero school 1 live submissions/reports, and both previous broadcasts as `DRY_RUN_COMPLETE` with approval/dispatch modes `DRY_RUN`. Both live tables have RLS; `app_rt` is non-superuser/non-bypass, has SELECT/INSERT but no table-wide UPDATE/DELETE/TRUNCATE, cannot update `request_sha256`, and can update `delivery_status`. Artifact: `live-postdeploy-database-proof.json` |
| Natural Scheduler proof | Five `Google-Cloud-Scheduler` requests returned 200 on `custoking-platform-service-dev-muipmx2l`, once per minute from 18:26 through 18:30 UTC; latest latency 0.109 seconds. Artifact: `live-postdeploy-scheduler-proof.json`. This is execution evidence, not a capacity or delivery result |

[Dev acceptance](dev-release-acceptance-2026-09-26.md) records the owned synthetic admission, attendance replay, 100-paise payment/receipt replay and conflict, procurement replay, private document access/deletion, annual confirmation, dry-run broadcast and consent withdrawal. It includes successful migration/privilege verification and natural Scheduler execution. It establishes technical results in dev only. Synthetic transactions do not establish a school's adoption, real revenue, margin or recipient delivery.

Before PR 296, the first gateway login returned 200 after 33.621 seconds; a repeat took 0.483 seconds. These two measurements do not certify latency or capacity. PR 296's CI passed 401 frontend tests across 72 files and 109 browser checks; the timeout fix is now deployed, but the school participant's login acceptance remains NOT ASSESSED. The verification artifacts named above are retained under `artifacts/product-dev-release-2026-09-26/`.

The latest frontend's `release-evidence-dev-1da0dc1d/` folder records approved OCI digest `sha256:eae2fa6e48dddb91cb7cacab4996b6687427c1b9d8952dfbcae6db24f0882206`, runnable digest `sha256:08cd8a33f358322d3eeebb61c65fc545b497273f153f54345244844ed74dd920`, and source ID `487cf15398ffc37b1e78f8968dfff4696597ce878e7200c84d64b01ecca2927b`. Deployment, signing and approval evidence do not replace human acceptance.

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
| Database and backup | `custoking-db-prod` is RUNNABLE, PostgreSQL 16, `db-g1-small`, zonal. Backups and point-in-time recovery are enabled; retention is 14 backups and 7 days of transaction logs. The latest three inspected backups were successful; the latest was `1790368200000`, completed 25 September at 22:22 UTC. | Obtain a fresh release backup and a reviewed rollback/restore plan. Run read-only production migration/data preflights with verified visibility for all nine source-added migrations listed below; determine pending versions from actual production Flyway history. Dev's clean receipt counts do not prove production-wide uniqueness or visibility. Verify post-migration history, RLS and exact runtime grants. |

The existing Scheduler paths `/api/v1/internal/outbox/relay` and `/api/v1/internal/async/drain` are guarded by Cloud Run IAM. Their controllers do not use the application `InternalCallerAuthenticator`; the separate delivery-command OIDC audience/peer allowlist must not be confused with these Scheduler paths. Verify the actual job audience against the target service and successful request evidence.

### Source-added migration inventory

The exact `main`-to-`dev` comparison above adds these nine SQL files. All are additions; none changes a historical migration. This is source scope, not evidence that each version is pending in the production database.

| Schema | Added migration |
| --- | --- |
| `identity` | `V8__shared_quotas_and_password_reset.sql` |
| `firefighting` | `V12__private_quotation_documents.sql` |
| `firefighting` | `V13__procurement_creation_replays.sql` |
| `notification` | `V11__broadcast_recipient_queue.sql` |
| `notification` | `V12__live_broadcast_submission_evidence.sql` |
| `catalog` | `V23__wholesale_has_no_upload_and_billbook_notes.sql` |
| `catalog` | `V24__annual_plan_confirmations.sql` |
| `fee` | `V10__payment_integrity.sql` |
| `tenant_school` | `V28__zone_admin_reference_reads.sql` |

Catalog V23 is also new relative to production source and updates product-form metadata for wholesale uploads and billbook notes; it must be included in the migration review. The earlier seven-plus-live-ledger count omitted this file.

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

The [release operator runbook](../runbooks/release-operator.md) has been corrected against the workflows and classifier. It now documents separate configuration reconciliation, zero application releases from that workflow, a qualifying post-reconciliation service release, the actual manual inputs, `dev`-to-`main` promotion and production Environment review. The nonexistent `apply_deployment_config` input is no longer presented as available; neither `force_full_deploy` nor re-running the same configuration commit bypasses the classifier.

## Remaining acceptance decisions

| Gate | Evidence still required |
| --- | --- |
| Live messaging | Implementation/tests, migration/grant proof and disabled-live dev rollout are complete. The read-only MSG91 template API returned 401 and Chrome's MSG91 session is signed out. Dev school/recipient selection is already authorized and school 1 is selected, but no reachable verified dev test inbox has been established; retained fixtures are `.invalid`. Restore provider access, verify sender/domain/template eligibility, establish the receiving account, then retain one bounded submission, authenticated bound delivery report plus observed receipt, and withdrawal with zero provider submissions. Synthetic callback 202 and dry-run/logging outcomes prove none of the live-delivery claims. See [live readiness](live-messaging-readiness-2026-09-26.md). |
| Independent school acceptance | A nominated school representative and support owner, the cases they actually performed, assistance and defects, and their dated scope-specific decision. [The session packet](school-acceptance-session-2026-09-26.md) currently says NOT ASSESSED. The assistant cannot supply this sign-off. |
| Capacity and operating cost | An approved bounded measurement window and verified isolated fixture. The latest documented dev gross cost exceeded its guard; no load test or budget override was run. The production database tier is not capacity certification. Any limited pilot must state its unmeasured capacity and agreed operating scope. |
| Operational ownership | Named release/rollback and support owners, the accepted monitoring/response path, and how UNKNOWN provider outcomes are reconciled without blind resubmission. Business adoption, willingness to pay and realized margin remain separately measured outcomes. |
| Repository enforcement | Administrator-applied branch protection/rules, or a clearly recorded release decision acknowledging the currently absent enforcement. The prod environment's review remains required. |

This is a point-in-time readiness assessment, not a release approval. Refresh the PR head, branch comparison, checks, rendered config, runtime inventory and external acceptance evidence after ongoing changes settle. No production database-wide cleanliness, SMTP delivery, provider delivery, capacity result or school approval is inferred from absent evidence.

## Verification limits and process state

Evidence came from GitHub branch/compare/PR/environment APIs, explicit-project GCP service/secret/bucket/role/Scheduler/SQL-backup metadata, local git comparisons, the release classifier and the linked source-controlled acceptance record. No production database rows or provider credentials were accessed. IAM bindings for the new production policy/storage paths and inherited repository variables remain to be verified by the release operator.

The original read-only inspection commands completed without leaving subtask-owned Maven, Java, acceptance-runner or gcloud processes; agent tool servers were left running. This historical process check is not a current process inventory. Subsequent dev release, database and Scheduler verification, including the successful PR 296 frontend deployment, are recorded above. No production mutation or human acceptance is implied by that progress.
