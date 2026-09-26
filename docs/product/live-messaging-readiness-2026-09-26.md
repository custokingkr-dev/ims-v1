# Live messaging readiness - 26 September 2026

Status: **the dedicated EMAIL path is implemented and locally tested; live delivery is not enabled or certified**. Dev dry-run acceptance is recorded in [the release evidence](dev-release-acceptance-2026-09-26.md). Provider verification, an explicitly approved live recipient, delivery evidence and independent school acceptance remain outstanding.

## Implemented

The dedicated broadcast path uses a fixed, verified email sender/domain/template and school/destination-hash allowlists. It resolves the approved guardian again immediately before submission, including current consent and contact binding. The template receives only HTML-escaped `title` and `message` variables; caller input cannot replace destinations, sender, template or provider metadata.

A durable reservation records the event, canonical request hash, destination/sender hashes and stable correlation before provider I/O. Submission runs outside the database transaction and makes one bounded HTTP attempt. A timeout, ambiguous response or crash leaves an unresolved outcome; it never permits a blind resend. This deliberately trades possible missed messages for avoiding uncertain duplicate sends. It is not an exactly-once delivery guarantee.

The [MSG91 email send contract](https://docs.msg91.com/email/send-email) returns a queued acceptance ID. **ACCEPTED is not DELIVERED.** The separate callback requires the gateway's service credential and an environment-specific webhook secret, then binds correlation, provider ID, destination and sender to existing evidence. Duplicate reports are harmless; conflicting reports require reconciliation. Unknown bindings receive the same generic acknowledgement without creating evidence.

The generic notification inbox remains `logging`/dry-run. Its MSG91 adapter still blocks `MSG91_DRY_RUN=false`. Live SMS, OTP and WhatsApp are outside this implementation. Existing dry-run approvals cannot become live submissions merely through a configuration change.

## Evidence and remaining gates

| Area | Current evidence / remaining work |
| --- | --- |
| Dev dry run | Verified synthetic run, zero delivered; this is not provider acceptance or receipt evidence |
| Application tests | Full platform suite: 344 passed. Subsequent scheduled-time regression: 18 repository tests passed (14 live ledger, 4 existing dispatch). Gateway: 88 passed. Frontend: 44 targeted tests, 3 mocked browser checks, TypeScript and production build passed |
| Credentials | Dev read-only check found an enabled `msg91-auth-key-dev` reference; account authentication has not been verified with a live submission |
| Sender/template | Earlier platform sender settings exist, but the new dedicated fixed sender/domain/template must be separately verified and configured |
| Deployment | Configuration PR [295](https://github.com/custokingkr-dev/ims-v1/pull/295) merged; dev reconciliation [36260733995](https://github.com/custokingkr-dev/ims-v1/actions/runs/36260733995) passed. Live remains disabled. Application PR 294 still requires CI and dev rollout |
| Recipient | No approved live email destination has been supplied; retained `.invalid` fixtures cannot receive mail |
| School acceptance | A nominated school representative and support owner must complete [the acceptance packet](school-acceptance-session-2026-09-26.md) |

The dev prerequisite `broadcast-live-webhook-token-dev` version 1 is provisioned in `custoking-dev`, with secret-level access for the platform runtime identity. Before production rollout, provision `broadcast-live-webhook-token-prod` in `custoking-prod` with the same narrow access. The manifest references this secret even while live sending is disabled. Stage requires its own secret if deployed. Keep the initial rollout disabled. See [production setup](../MSG91-PRODUCTION-SETUP.md) for exact environment variables, callback JSON/header setup and controlled activation order.

## Bounded live acceptance

After provider verification and explicit recipient approval, admit one school and one email hash through the governed configuration workflow. Use a separately reviewed live draft and clearly labeled message. Do not overwrite another guardian's contact or consent to make a test eligible.

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
