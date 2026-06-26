# Internal Service Authorization Matrix

Extracted services are private runtime boundaries. Cloud Run IAM is the transport boundary in production, and service tokens are the application-level defense-in-depth boundary. Every protected service controller route must declare an internal route scope at the token guard.

## Scope Rules

- Read-only routes use `<service>:read`.
- Mutating routes use `<service>:write`.
- Async ingestion routes use `<service>:ingest`.
- Specialized control-plane routes use an explicit narrower scope.
- Public identity routes (`login`, `refresh`, `logout`) are not internal service routes.

## Current Scopes

| Service | Scopes |
| --- | --- |
| `attendance-service` | `attendance:read`, `attendance:write` |
| `audit-service` | `audit:ingest`, `audit:read` |
| `billing-service` | `billing:read`, `billing:write` |
| `catalog-service` | `catalog:read`, `catalog:write` |
| `fee-service` | `fee:read`, `fee:write` |
| `firefighting-service` | `firefighting:read`, `firefighting:write` |
| `identity-service` | `identity:read`, `identity:write`, `identity:introspect` |
| `notification-service` | `notification:read`, `notification:write`, `notification:ingest`, `notification:status:read` |
| `reporting-service` | `reporting:read`, `reporting:write` |
| `student-service` | `student:read`, `student:write` |
| `tenant-school-service` | `tenant-school:read`, `tenant-school:write` |
| `workflow-service` | `workflow:read`, `workflow:write` |

## Guardrail

Run this before promoting service-boundary changes:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/audit-service-authorization-boundaries.ps1
```

The audit fails if a controller:

- omits a token guard on a mapped protected endpoint;
- uses a generic `requireToken(token)` or `requireValidToken(token)` guard;
- permits requests when the configured service token is blank;
- loses the gateway, compose, or Cloud Build Secret Manager token wiring.
