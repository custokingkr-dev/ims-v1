# MSG91 production setup

The implemented live path is a **dedicated, bounded EMAIL broadcast pilot**. It is not enabled by default and has not completed live provider/recipient or independent school acceptance. The generic notification inbox remains `logging`/dry-run; keep `MSG91_DRY_RUN=true`. Its existing adapter deliberately refuses live mode. SMS, OTP and WhatsApp do not inherit this pilot's readiness.

## Provision before the next rollout

The platform manifest now references `broadcast-live-webhook-token-${env}` even when live sending is disabled. Provision an independent, cryptographically random secret of at least 32 characters, with an enabled version, in each environment before deploying that manifest:

| Project | Secret | Only runtime accessor |
| --- | --- | --- |
| `custoking-dev` | `broadcast-live-webhook-token-dev` | `ims-platform-dev@custoking-dev.iam.gserviceaccount.com` |
| `custoking-prod` | `broadcast-live-webhook-token-prod` | `ims-platform-prod@custoking-prod.iam.gserviceaccount.com` |

Grant `roles/secretmanager.secretAccessor` at the individual secret, not project scope. Do not grant this new secret to the gateway, frontend, other services or `allUsers`. An authorized account operator copies its value privately into MSG91's custom webhook header; never place it in source, command arguments, URLs or logs. Stage needs its own secret and platform accessor if deployed. Provisioning is a prerequisite, not an assertion that these cloud changes have been completed.

Existing references must also resolve: `msg91-auth-key-${env}` for the platform, `notification-status-token-${env}` shared by the gateway/platform for internal service authentication, and `broadcast-policy-token-${env}` for current school recipient policy. Use the exact environment/project on every operator command.

## Configuration

These are the actual environment names in [platform application.yml](../services/platform-service/src/main/resources/application.yml). Nonsecret live values are governed by Cloud Deploy target parameters and the platform manifest; avoid temporary `gcloud run services update --set-env-vars` drift.

| Environment variable | Initial rollout / activation requirement |
| --- | --- |
| `NOTIFICATION_DELIVERY_PROVIDER` | Keep `logging` for the generic inbox |
| `MSG91_DRY_RUN` | Keep `true`; it is not the live pilot switch |
| `MSG91_AUTH_KEY` | Platform secret reference `msg91-auth-key-${env}` |
| `BROADCAST_DISPATCH_MODE` | Retain dev `DRY_RUN`, stage/prod `OFF` initially; `LIVE` only for an explicitly admitted pilot |
| `BROADCAST_WORKER_READY` | `true` only after authenticated request-driven Scheduler/policy readiness is verified |
| `BROADCAST_POLICY_TOKEN` | Existing private policy secret reference |
| `BROADCAST_LIVE_ENABLED` | `false` initially in every target; `true` only for the accepted pilot scope |
| `BROADCAST_LIVE_CHANNEL` | `EMAIL`; other live channels are unsupported |
| `BROADCAST_LIVE_SENDER_VERIFIED` | `false` until provider sender/domain verification is evidenced |
| `BROADCAST_LIVE_SCHOOL_IDS` | Empty initially; comma-separated admitted positive school IDs |
| `BROADCAST_LIVE_DESTINATION_SHA256` | Empty initially; comma-separated lowercase SHA-256 hashes of trimmed, lowercase approved email addresses |
| `BROADCAST_LIVE_WEBHOOK_TOKEN` | Secret reference `broadcast-live-webhook-token-${env}` |
| `BROADCAST_LIVE_EMAIL_SENDER` | Exact verified sender email; no school-specific sender fallback |
| `BROADCAST_LIVE_EMAIL_SENDER_NAME` | Reviewed fixed sender display name |
| `BROADCAST_LIVE_EMAIL_DOMAIN` | Verified provider domain, exactly matching the sender email's domain |
| `BROADCAST_LIVE_EMAIL_TEMPLATE_ID` | Reviewed fixed MSG91 email template ID |
| `BROADCAST_LIVE_EMAIL_TEMPLATE_VERIFIED` | `false` until the exact template is reviewed and renders correctly |
| `NOTIFICATION_STATUS_TOKEN` | Platform service credential; the gateway uses its `NOTIFICATION_SERVICE_TOKEN` to inject the matching header |

Verify MSG91 account eligibility, sender/domain DNS requirements and template configuration with the account owner. The template has only `title` and `message` variables, both HTML-escaped by the application. Check the rendered text, line breaks and escaping in the provider's preview. No recipient-supplied variables, raw HTML, attachments, CC/BCC or caller-selected sender/template are supported. Existing `MSG91_EMAIL_*` generic sender settings and sender-profile rows do not configure this dedicated path.

The transport fixes the endpoint to the [official email send API](https://docs.msg91.com/email/send-email). `MSG91_EMAIL_ENDPOINT` and `MSG91_TIMEOUT` do not override its endpoint or its three-second connection / ten-second overall bounds. It sends one recipient per request and caps the request/response at 64/16 KiB. [Transport details](runbooks/msg91-live-email-contract.md) describe validation and uncertainty handling.

## Configure the callback

In the same MSG91 account, choose **Email → Webhook (New) → On Report Received**. Set the callback to the environment's public gateway origin plus the exact path:

```text
https://<environment-gateway-origin>/api/v1/notifications/provider-reports/msg91/email
```

Use `POST`, `Content-Type: application/json`, and this exact six-field JSON object. These mappings use the provider's [documented customizable email report placeholders](https://msg91.com/help/webhook-new/how-to-receive-email-delivery-reports-via-webhook-new):

```json
{
  "correlationId": "{{crqid}}",
  "providerMessageId": "{{requestId}}",
  "destination": "{{recipient}}",
  "sender": "{{sender}}",
  "status": "{{eventName}}",
  "occurredAt": "{{statusUpdatedAt}}"
}
```

Add the custom header `X-MSG91-Webhook-Token` with this environment's private secret value. The gateway injects `X-Notification-Service-Token` itself; do not expose that internal token to MSG91 or include a user access token. Platform requires both credentials. A callback secret belongs to one environment/account configuration and must not be reused across environments.

All six values must be strings; no extra fields or batch arrays are accepted, and the body limit is 32 KiB. The supported status values are `Queued`, `Accepted`, `Delivered`, and `Failed` (case-insensitive). `occurredAt` must include a timezone. Do not subscribe opened/clicked/unsubscribed events to this endpoint. During provider acceptance, verify `requestId` equals the send response's `data.unique_id`, `crqid` matches the application's correlation, and sender/recipient/timestamp are populated as configured. Any mismatch keeps the pilot uncertified; do not weaken the binding to force a pass.

The send body puts case-sensitive `CRQID` inside the individual recipient, per the [email correlation contract](https://msg91.com/help/email/how-to-send-the-crqid-via-email-api); callback `crqid` maps it back. A generic HTTP 202 `{ "accepted": true }` acknowledges callback handling, including unknown bindings. It is not proof of recipient delivery. Read the stored broadcast outcome and obtain the nominated recipient's observed receipt. Duplicate reports preserve evidence; contradictions require reconciliation. Receipt ingestion remains available after live sending is disabled.

## Controlled rollout and acceptance

1. Provision the required secrets and verify their scoped access in **both dev and production before the next rollout**. Keep all live parameters disabled/empty. Apply target/renderer configuration through its reconciliation workflow, then deploy the service-manifest/application release through the normal promotion path.
2. Verify that the serving revision stays in its intended OFF/DRY_RUN state, that the private Scheduler reaches the platform drain, and that existing synthetic dry-run results remain dry-run. Request-based Cloud Run CPU is supported through authenticated Scheduler calls; do not rely on idle background execution.
3. Obtain the approved live email recipient and school scope, current consent, verified sender/domain/template, and exact callback settings. Begin with one destination hash and one school. Activate only that reviewed scope through governed parameters.
4. Create a fresh live draft, review the eligible audience and explicit live approval fingerprint, and send one clearly labeled test notice. Persisted provider acceptance is **ACCEPTED**, not **DELIVERED**. Require a bound delivery report plus recipient observation, replay without an extra attempt, and withdrawal-before-dispatch suppression.
5. Close the live gate after the bounded test, retain reports/reservations, withdraw test consent where applicable, and complete the [school acceptance packet](product/school-acceptance-session-2026-09-26.md). Production promotion requires its own readiness and school decision; successful dev automation is insufficient.

An UNKNOWN, stale SUBMITTING or REPORT_CONFLICT outcome must be reconciled against the original provider evidence. Never reset/delete its reservation, blindly resend, or create another broadcast merely to bypass uncertainty. No documented durable MSG91 idempotency guarantee is assumed. Track remaining approval and evidence in [live messaging readiness](product/live-messaging-readiness-2026-09-26.md).