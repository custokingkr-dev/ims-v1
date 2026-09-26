# Live messaging readiness — 26 September 2026

Status: **in progress; live delivery is not enabled or certified**. The user requested completion of live messaging and independent school acceptance after the [verified dev release](dev-release-acceptance-2026-09-26.md). The existing dry-run checks passed. They do not establish provider acceptance, recipient delivery, or safe retry after an uncertain provider response.

## Verified dev setup

Read-only checks at 16:55 UTC used `custoking-dev` and active Local Demo School, ID 1. The authenticated session was logged out with HTTP 204. Evidence is in ignored `artifacts/product-dev-release-2026-09-26/sender-readiness-20260926.json`; it contains configuration-presence flags, not destinations or credentials.

| Item | Observed state |
| --- | --- |
| Platform revision | `custoking-platform-service-dev-muilo6h2` |
| Provider | `logging` |
| Broadcast processing | `DRY_RUN`, worker ready, `canSend=false` |
| MSG91 credential | `msg91-auth-key-dev`, version 1 enabled and referenced by the runtime; provider authentication not yet verified |
| School sender | Persisted platform default resolved for school 1 |
| Email | From address and domain configured; provider verification not established; no email template configured |
| SMS | No template/flow configured |
| WhatsApp | No integrated number or template configured |
| Approved live test destination/channel | Awaiting the user's answer; existing synthetic `.invalid` destination cannot receive mail |

## Provider contract research

The current MSG91 [metadata contract](https://msg91.com/help/text-sms/custom-metadata-parameters-for-tracking-api-requests) describes CRQID/UUID as correlation fields returned in reports and webhooks. It specifies up to 80 characters and excludes colons. The older [webhook guide](https://msg91.com/help/webhooks/how-can-i-get-the-delivery-reports-on-my-webhook-url) gives a stricter 52-character alphanumeric limit. Raw internal event IDs such as `broadcast:...:SMS` are unsuitable. The SMS payload builder now derives a deterministic 51-character alphanumeric correlation from the first 192 bits of SHA-256, satisfying both formats without exposing the internal ID. Retries preserve it; a future submission ledger must persist its mapping. This change does not add provider idempotency or activate live sending.

MSG91's [error-code documentation](https://msg91.com/help/api/what-are-the-reason-for-error-codes-received-under-the-api-failed) describes code 311 as a same-number/same-content duplicate check within ten seconds. This does not establish a durable idempotency contract covering this application's retry window or a crash after provider acceptance.

The provider documents delivery callbacks for [SMS](https://msg91.com/help/webhooks/how-can-i-get-the-delivery-reports-on-my-webhook-url), [email](https://msg91.com/help/webhook-new/how-to-receive-email-delivery-reports-via-webhook-new), and [WhatsApp](https://msg91.com/help/webhook-new/how-to-receive-whatsapp-delivery-reports-via-webhook-new). Email reports distinguish queued, accepted, delivered and failed states. A send response alone must not become a delivery receipt. Callback authentication, field binding and replay behavior must be verified for the selected account and channel.

No documented durable provider idempotency guarantee was found in the reviewed official sources. This is a limit of the available evidence, not a claim that MSG91 can never offer an account-specific contract.

## Application work required before activation

The present adapter deliberately throws on live startup and live invocation; it performs no live HTTP submission. The broadcast worker admits only dry-run queues. These are implementation gates, not merely missing environment variables.

1. Establish the selected channel's sender/template and account eligibility. Preserve the reviewed recipient and current consent binding; caller-supplied variables must not replace destinations or provider metadata.
2. Implement an explicit submission lifecycle with a durable pre-submission record, payload fingerprint, provider correlation, provider request ID and separate delivery outcome. A provider HTTP request and the subsequent database commit cannot be made atomic by the existing inbox transaction.
3. Admit either a verified provider idempotency/reconciliation contract or a conservative single-submission path. In the latter, a timeout or crash after the durable submission claim becomes **UNKNOWN**, never an automatic resend. A crash before the HTTP call can also leave an unknown/missed message. There is no exactly-once claim; the support owner must reconcile ambiguous cases against provider evidence.
4. Implement authenticated report ingestion/readback and bind account, channel, request ID/correlation and destination to the original submission. Replay must be harmless; out-of-order or conflicting reports must not silently overwrite confirmed evidence. Do not label dry-run or accepted outcomes delivered.
5. Keep unrelated notifications and already approved dry-run rows isolated from the live pilot. A global provider switch must not send an existing queue or change its meaning.
6. Test concurrent replay, payload conflicts, timeout after acceptance, crash recovery, authenticated/forged/duplicate/out-of-order reports, current consent withdrawal and contact changes. Then deploy through the normal dev workflow and execute the bounded live test below.

## Bounded live acceptance

Use only the user's approved channel and destination. Record its controlled evidence reference and mask it in source-controlled reports. Sender verification and current permission to receive the test must precede submission. No existing guardian's contact or consent should be overwritten to make the test eligible.

| Case | Completion evidence |
| --- | --- |
| First submission | Exactly one clearly labeled test message; saved logical event, payload fingerprint, provider request ID and accepted/rejected/unknown result |
| Application replay | Original event/key returns the persisted result; provider-attempt count does not increase |
| Changed payload | Same key with different content is rejected before provider submission |
| Ambiguous timeout | Fault-injected test establishes no blind resend; live ambiguity uses provider report/reconciliation and remains UNKNOWN without sufficient proof |
| Receipt | Authenticated provider report bound to the original submission, plus the nominated recipient's observed receipt and timestamp |
| Withdrawal | Withdrawal before dispatch suppresses the queued recipient; zero provider submissions for that case |
| Cleanup | Test consent withdrawn where applicable, sessions logged out, temporary accounts disabled, pilot gate closed, evidence retained |

One successful channel certifies only that channel/sender/template combination. Email, SMS and WhatsApp require separate acceptance. An unconfirmed recipient, unknown delivery, missing authentication or unresolved duplicate leaves this item open.

## Provider clarification draft

This draft has **not** been sent to MSG91. An account owner can use it if public documentation does not settle the selected channel's contract:

> For our selected channel/API and account, does MSG91 support a client-controlled idempotency key? Please identify its scope, retention, same-key/same-body and same-key/different-body behavior. After an accepted request times out before its request ID reaches us, how can we find its authoritative result using client correlation, and when is absence conclusive? Please also confirm delivery-report authentication, retry/ordering guarantees, and how to distinguish provider acceptance from recipient delivery. CRQID correlation and the ten-second SMS duplicate check do not establish these guarantees for our retry window.

Independent human acceptance is tracked in [the school acceptance packet](school-acceptance-session-2026-09-26.md).
