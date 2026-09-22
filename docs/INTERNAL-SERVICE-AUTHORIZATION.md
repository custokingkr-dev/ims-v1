# Internal Service Authorization Matrix

Extracted services are private runtime boundaries. Cloud Run IAM is the transport boundary in production, and service tokens are the application-level defense-in-depth boundary for gateway and peer-service routes. Every protected gateway or peer-service controller route must declare an internal route scope at the token guard.

## What each layer proves

Cloud Run IAM proves only that the caller is *some* invoker of the service, and the API gateway is
an invoker of every backend. A service token proves only that the request passed through a hop
that holds the token, and the gateway injects each service's token on every request it proxies.
Neither layer can therefore distinguish a peer service from a user request the gateway relayed.
Since 2026-09-22 (see `SECURITY-REVIEW-2026-09-22.md`) two further controls close that gap:

- **Gateway deny.** The gateway refuses, before authentication, any request whose rewritten
  upstream path is under `/api/v1/internal` or `/api/v1/pubsub`, on canonical and diagnostic-alias
  routes alike (`isInternalUpstreamPath` in `services/api-gateway/server.js`). Those surfaces are
  never reachable through the gateway, whatever token it carries.
- **Caller identity.** Where a surface must be reachable only by one specific principal, the
  controller verifies the Google-signed OIDC bearer that Cloud Run and Pub/Sub attach: signature,
  audience in `SERVICE_OIDC_AUDIENCES` (this service's own URLs), and `email` in a per-surface
  allow-list. platform-service implements this in `security/CallerIdentity` with two entry points:
  `PubSubPushAuthenticator` for `/api/v1/pubsub/**` when `*_PUBSUB_REQUIRE_SHARED_TOKEN=false`
  (allow-list `PUBSUB_PUSH_OIDC_SERVICE_ACCOUNTS`), and `InternalCallerAuthenticator` for
  `/api/v1/internal/notifications/deliveries` when `INTERNAL_OIDC_REQUIRE=true` (allow-list
  `INTERNAL_OIDC_SERVICE_ACCOUNTS`, the school-core runtime account). Both fail closed when their
  allow-list or the audience set is empty.

Request bodies never carry the caller's identity: audit ingest binds non-superadmin principals to
the gateway-asserted user, school and the server clock; the tenant-unscoped
`POST /api/v1/notifications/logs` route no longer exists.

Four request-driven maintenance endpoints are deliberately IAM-only: the school-core, operations,
and billing outbox relays plus the platform async drain. They are unreachable through the API
gateway (see the gateway deny above) and accept only a Google-signed OIDC request from the
dedicated Scheduler identity with service-level `roles/run.invoker`. The boundary audit allowlists
the exact controller, single method, internal route, Scheduler OIDC configuration, and absence from
the gateway; adding another method fails the gate.

## Scope Rules

- Read-only routes use `<service>:read`.
- Mutating routes use `<service>:write`.
- Async ingestion routes use `<service>:ingest`.
- Specialized control-plane routes use an explicit narrower scope.
- Public identity routes (`login`, `refresh`, `logout`) are not internal service routes.

## Current Scopes

The scope names preserve the logical domain boundary even where multiple domains are merged into one runtime service.

| Logical domain | Runtime service | Scopes |
| --- | --- | --- |
| attendance | `school-core-service` | `attendance:read`, `attendance:write` |
| audit | `platform-service` | `audit:ingest`, `audit:read` |
| billing | `billing-service` | `billing:read`, `billing:write` |
| catalog | `school-core-service` | `catalog:read`, `catalog:write` |
| fee | `school-core-service` | `fee:read`, `fee:write` |
| firefighting | `operations-service` | `firefighting:read`, `firefighting:write` |
| identity | `identity-service` | `identity:read`, `identity:write`, `identity:introspect` |
| notification | `platform-service` | `notification:read`, `notification:write`, `notification:ingest`, `notification:deliver`, `notification:status:read` |
| reporting | `platform-service` | `reporting:read`, `reporting:write` |
| student | `school-core-service` | `student:read`, `student:write` |
| tenant-school | `school-core-service` | `tenant-school:read`, `tenant-school:write` |
| workflow | `operations-service` | `workflow:read`, `workflow:write` |

## Guardrail

Run this before promoting service-boundary changes:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit-service-authorization-boundaries.ps1
```

The audit fails if a controller:

- omits a token guard on a mapped protected endpoint;
- uses a generic `requireToken(token)` or `requireValidToken(token)` guard;
- permits requests when the configured service token is blank;
- loses the gateway, compose, or Cloud Run manifest Secret Manager token wiring.
- changes an IAM-only maintenance mapping, exposes it through the gateway, or loses its OIDC
  Scheduler/`run.invoker` wiring.
