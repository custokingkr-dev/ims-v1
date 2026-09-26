# Live messaging readiness - 26 September 2026

Status: **the dedicated EMAIL path is implemented, deployed to dev and checked with live sending disabled; provider delivery is not certified**. Updated 27 September 2026 (IST), using the 26 September UTC release evidence below. Dev dry-run acceptance is recorded in [the release evidence](dev-release-acceptance-2026-09-26.md). Provider verification, a reachable verified dev test inbox, delivery evidence and independent school acceptance remain outstanding.

## Implemented

The dedicated broadcast path uses a fixed, verified email sender/domain/template and school/destination-hash allowlists. It resolves the approved guardian again immediately before submission, including current consent and contact binding. The template receives only HTML-escaped `title` and `message` variables; caller input cannot replace destinations, sender, template or provider metadata.

A durable reservation records the event, canonical request hash, destination/sender hashes and stable correlation before provider I/O. Submission runs outside the database transaction and makes one bounded HTTP attempt. A timeout, ambiguous response or crash leaves an unresolved outcome; it never permits a blind resend. This deliberately trades possible missed messages for avoiding uncertain duplicate sends. It is not an exactly-once delivery guarantee.

The [MSG91 email send contract](https://docs.msg91.com/email/send-email) returns a queued acceptance ID. **ACCEPTED is not DELIVERED.** The separate callback requires the gateway's service credential and an environment-specific webhook secret, then binds correlation, provider ID, destination and sender to existing evidence. Duplicate reports are harmless; conflicting reports require reconciliation. Unknown bindings receive the same generic acknowledgement without creating evidence.

The generic notification inbox remains `logging`/dry-run. Its MSG91 adapter still blocks `MSG91_DRY_RUN=false`. Live SMS, OTP and WhatsApp are outside this implementation. Existing dry-run approvals cannot become live submissions merely through a configuration change.

## Evidence and remaining gates

| Area | Current evidence / remaining work |
| --- | --- |
| Dev dry run | School 1 capabilities remain `DRY_RUN`, `canQueue=true`, `canSend=false`; retained broadcasts remain dry-run with zero delivered |
| Application tests | PR 294's final [CI 36260887510](https://github.com/custokingkr-dev/ims-v1/actions/runs/36260887510) passed 345 platform tests (including the scheduled-time regression), 88 gateway tests, 397 frontend unit tests and 109 browser checks. [CodeQL 36260887293](https://github.com/custokingkr-dev/ims-v1/actions/runs/36260887293) passed |
| Provider access | One read-only MSG91 template-list request returned HTTP 401. Chrome's MSG91 session is signed out. Account/API access must be restored and verified; an enabled Secret Manager reference alone does not establish provider authorization |
| Sender/template | Earlier platform sender settings exist, but the new dedicated fixed sender/domain/template must be separately verified and configured |
| EMAIL release | Application PR [294](https://github.com/custokingkr-dev/ims-v1/pull/294) merged at `a07c2fa73a4bc8731b2d41015f31f65624bfdb8c`; [CD 36261225626](https://github.com/custokingkr-dev/ims-v1/actions/runs/36261225626) succeeded. Platform ready revision: `custoking-platform-service-dev-muipmx2l`. Dev backup `1790446335149` completed successfully before deployment. Configuration PR [295](https://github.com/custokingkr-dev/ims-v1/pull/295) and dev reconciliation [36260733995](https://github.com/custokingkr-dev/ims-v1/actions/runs/36260733995) preceded the release |
| Latest frontend release | PR [296](https://github.com/custokingkr-dev/ims-v1/pull/296), source `1da0dc1d99f839bdd6b24aeb6565bb85d7728f63`, deployed successfully through [CD 36262889154](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262889154). Frontend revision `custoking-frontend-dev-00050-snx` is ready with 100% traffic and returned HTTP 200 at 18:42:26 UTC; gateway was UP at 18:42:36 UTC. Signing and approval completed at 18:42:56 UTC |
| Deployed callback checks | Missing/wrong callback credentials returned 401. A validly authenticated, unknown synthetic callback and its replay returned 202 twice while live sending stayed disabled. This checks endpoint authentication/replay acknowledgement, not an actual MSG91 report or message delivery |
| Database proof | Read-only job `ims-q-dev-xj2hr` at 18:22:28 UTC confirms notification V12 succeeded, school 1 has zero live submissions/reports after the unknown callbacks, and both prior broadcasts remain `DRY_RUN_COMPLETE` with approval/dispatch modes `DRY_RUN`. Both live tables have RLS enabled; `app_rt` is non-superuser/non-bypass, has SELECT/INSERT but no table-wide UPDATE/DELETE/TRUNCATE, cannot update `request_sha256`, and can update `delivery_status` |
| Natural Scheduler | Five natural `Google-Cloud-Scheduler` requests returned 200 on the new platform revision, once per minute from 18:26 through 18:30 UTC. The latest took 0.109 seconds. This proves request-driven execution, not live delivery or capacity |
| Login observation | Before PR 296, the first gateway login returned 200 in 33.621 seconds; a repeat took 0.483 seconds. These are two observations, not capacity certification. The login-only 60-second timeout fix is now deployed after successful [CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262566669), [CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262566385) and CD. A school participant's actual login acceptance remains NOT ASSESSED |
| Recipient | The user authorized choosing dev school/recipients, and school 1 is selected. No reachable, verified dev test inbox has been established; retained `.invalid` fixtures cannot receive mail. A receiving account and observed receipt are needed to complete the evidence |
| School acceptance | A nominated school representative and support owner must complete [the acceptance packet](school-acceptance-session-2026-09-26.md) |

The ignored artifacts under `artifacts/product-dev-release-2026-09-26/` are `live-release-dev-verification.json` (`completed=true`, ready revision/image, authenticated checks and logout 204), `live-postdeploy-database-proof.json`, and `live-postdeploy-scheduler-proof.json`. No actual provider submission or external message was sent. No provider sender/template approval or human school sign-off is claimed.

The latest frontend release evidence is in `artifacts/product-dev-release-2026-09-26/release-evidence-dev-1da0dc1d/`: approved OCI digest `sha256:eae2fa6e48dddb91cb7cacab4996b6687427c1b9d8952dfbcae6db24f0882206`, runnable digest `sha256:08cd8a33f358322d3eeebb61c65fc545b497273f153f54345244844ed74dd920`, and source ID `487cf15398ffc37b1e78f8968dfff4696597ce878e7200c84d64b01ecca2927b`.

The dev prerequisite `broadcast-live-webhook-token-dev` version 1 is provisioned in `custoking-dev`, with secret-level access for the platform runtime identity. Before production rollout, provision `broadcast-live-webhook-token-prod` in `custoking-prod` with the same narrow access. The manifest references this secret even while live sending is disabled. Stage requires its own secret if deployed. Keep the initial rollout disabled. See [production setup](../MSG91-PRODUCTION-SETUP.md) for exact environment variables, callback JSON/header setup and controlled activation order.

## Bounded live acceptance

After provider verification and establishment of a reachable dev test inbox within the existing authorization, admit school 1 and one verified email hash through the governed configuration workflow. Use a reviewed live draft and clearly labeled message. Do not overwrite another guardian's contact or consent to make a test eligible.

| Case | Required evidence |
| --- | --- |
| First submission | One logical event and provider attempt, saved hashes/correlation/provider receipt, accepted/rejected/unknown result |
| Replay / changed content | Original result preserved without another provider call; changed content cannot reuse the reservation |
| Delivery report | Callback matches the submission; named recipient confirms observed receipt and time |
| Withdrawal | Consent withdrawn before dispatch suppresses the recipient with no provider attempt |
| Uncertain outcome | Fault-injection coverage passes locally; any actual ambiguity stays unresolved until provider evidence settles it |
| Cleanup | Withdraw test consent where applicable, close the pilot gate, log out, retain evidence and disable temporary accounts |

The official [email CRQID contract](https://msg91.com/help/email/how-to-send-the-crqid-via-email-api) provides correlation, not documented durable idempotency. Reconcile an UNKNOWN or conflicting result with the account owner and provider; never delete/reset the reservation or create a replacement broadcast to force a retry. Account-specific callback fields and provider ID correspondence must be verified during the bounded acceptance, because public documentation alone does not certify this account's setup.

One successful email test covers only the accepted account/sender/template/recipient scope. It does not establish independent school use, SMS/WhatsApp readiness, support capacity or business value.
