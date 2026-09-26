# Password recovery and shared request allowances

Implemented in the September 26 follow-up. Apply identity migration `V8__shared_quotas_and_password_reset.sql` before the matching identity service revision. Existing accounts and sessions begin at credential version zero.

## Recovery behavior

The browser uses `/reset-password` and the canonical `GET /api/v1/auth/password-reset/capabilities`, `POST .../request`, and `POST .../confirm` APIs. These are public at the gateway, which supplies the private identity service credential. Direct peer calls fail closed without that credential.

Request responses are generic for unknown, disabled and eligible accounts. Eligible requests commit a durable delivery intent before returning HTTP 202; the response never waits for SMTP. No reset secret is stored in the intent. Workers create 256-bit random tokens, store only SHA-256 digests, and send the link to the account's stored email. Tokens expire after 30 minutes. Mail contains a fixed configured HTTPS origin/path and a fragment token, so the secret is absent from HTTP access logs and referrers. The SPA removes that fragment and holds the token only in memory. Refreshing the page requires reopening the original link.

SMTP requires authentication, STARTTLS, hostname verification, and bounded connect/read/write timeouts. The worker leases at most ten intents per drain, retries up to three attempts, rejects intents older than 30 minutes, and fences mutations to the current attempt and unexpired lease. A transport failure emits only the event name `password_reset_delivery_failed`, with no recipient or token. SMTP acceptance is not proof of inbox delivery. A lost SMTP response can lead to a duplicate recovery email; all tokens become unusable after any one succeeds.

Confirmation locks the token/account, updates the password, advances the credential version, consumes the token, and records `PASSWORD_RESET_COMPLETED` in one transaction. Access introspection and refresh compare session and account versions. This also invalidates a concurrent old-password login that commits a session after the reset. Administrator resets and changes to the normalized account email advance the version too; old-address links cannot reset the transferred account.

Passwords require at least 12 Unicode code points and at most 72 UTF-8 bytes, matching the installed password encoder's input bound. The frontend validates matching entries and this byte limit. It preserves failures for recovery, never stores passwords, and directs an uncertain confirmation response to try signing in before requesting another link.

## Activation gates

Recovery defaults **off**. No SMTP secrets were present in the inspected dev project, and no recovery email was sent during local verification. SSO is explicitly deferred by the user's choice.

Configure these through the established release/config reconciliation process, using Secret Manager for the SMTP password:

| Setting | Required value |
| --- | --- |
| `IDENTITY_PASSWORD_RESET_ENABLED` | `true` only after the following checks |
| `IDENTITY_PASSWORD_RESET_WORKER_READY` | `true` only after verifying an always-allocated identity worker with at least one running instance |
| `IDENTITY_PASSWORD_RESET_URL` | `https://<approved-frontend-origin>/reset-password`, without query, fragment or credentials |
| `IDENTITY_PASSWORD_RESET_FROM` | Authorized sender address |
| `IDENTITY_PASSWORD_RESET_SMTP_HOST`, `..._PORT` | SMTP submission endpoint; port defaults to 587 |
| `IDENTITY_PASSWORD_RESET_SMTP_USER`, `..._PASSWORD` | Authenticated SMTP credentials |
| `IDENTITY_PASSWORD_RESET_DELIVERY_DELAY_MS` | Defaults to 5000 |

On September 26 dev identity had `minScale=0` and CPU throttling enabled. Scheduled work cannot guarantee timely execution in that configuration. The application rejects recovery-enabled startup unless worker readiness is explicitly acknowledged; the flag does not provision or prove the infrastructure. Change the runtime through deployment reconciliation and verify execution during idle traffic before setting it. Account recovery by an administrator remains available while email recovery is disabled.

Before enabling for a school, verify a synthetic account on the approved frontend origin: inbox delivery, expiry, replay rejection, previous-session rejection, worker restart/retry, and disabled-account behavior. Use the selected dev cohort, Local Demo School (school ID 1), without changing any existing person's password for a test.

## Shared limits

The gateway retains its cheap per-replica token bucket. Identity adds database-coordinated budgets, using atomic UPSERT and database time; new access tokens and additional gateway replicas do not grant extra user allowance. Each counter commits independently of the subsequent operation so failed login attempts still count. Request failures consume allowance as well as successes.

| Scope | Allowance |
| --- | --- |
| Login, whole fleet | 300/minute; `IDENTITY_QUOTA_LOGIN_PER_MINUTE` |
| Login, normalized account | 10/15 minutes |
| Reset request, fleet / normalized account | 50/minute / 3/hour |
| Reset confirmation, fleet / token fingerprint | 120/minute / 6/minute |
| Ordinary reads, user / school | 900/minute / 5000/minute |
| Ordinary writes, user / school | 180/minute / 1000/minute |
| Import mutations, user / school | 10/minute / 30/minute |
| Export requests, user / school | 30/minute / 60/minute |

General user read/write limits can be tuned with `IDENTITY_QUOTA_USER_READS_PER_MINUTE` and `IDENTITY_QUOTA_USER_WRITES_PER_MINUTE`; nonpositive configuration fails startup. These are initial operational limits, not certified throughput or contractual school entitlements. Windows reset when the prior window expires; they are not sliding-window quotas. Allowance can be spent near both sides of a window boundary. Account identifiers are HMAC fingerprints, not plaintext email addresses. Expired counters/tokens are cleaned in bounded batches; old delivery intents are retained for seven days before cleanup.

The gateway supplies the decoded request path, HTTP method and an optional school ID from the route/query. Identity charges a requested school only if the current principal is its member, assigned operator, or superadmin; arbitrary IDs cannot spend another school's allowance. Otherwise the current home school applies. Operations whose target appears only in the body remain covered by the user and home-school allowance. Downstream tenant authorization still decides access. This accounting hint does not grant a school permission.

HTTP 429 and a bounded `Retry-After` propagate through the gateway. Quota database failures fail closed as an upstream failure rather than silently granting allowance. Introspection plus shared counters adds database work; local measurements are not production capacity certification. Run the guarded dev workload certification with reviewed budgets before promoting at school-day traffic.

## Evidence

PostgreSQL integration checks cover concurrent quota consumption, expiry, failed-login accounting, reset replay, two simultaneous links, old access/refresh rejection, administrator reset, email transfer, and expired/reclaimed worker leases. Browser tests cover mobile/desktop recovery, secret removal, retry and keyboard focus, with axe checks. Delivery uses synthetic/mock mail in tests; real SMTP inbox delivery remains an external activation gate.
