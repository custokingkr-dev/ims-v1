# API Contracts and Client Migration

Last reconciled: 2026-08-26 against the generated route inventory and identity authentication sources.

The API gateway route inventory remains the repository-wide ownership and compatibility-usage contract. It
describes every Spring mapping and gateway matcher, but it does not describe payload schemas. OpenAPI is now
being introduced one bounded public workflow at a time so contract review and client migration can proceed
without deleting compatibility routes.

## First OpenAPI slice: identity authentication

`contracts/openapi/identity-auth.v1.openapi.json` is the authoritative OpenAPI 3.1 contract for the three
canonical browser authentication operations:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

The contract includes login and principal payloads, nullable tenant/zone fields, the HttpOnly refresh-cookie
security scheme, and success/error responses. Internal `POST /api/v1/auth/introspect` is deliberately absent:
browser code must never receive or model the identity-service token required by that route.

`scripts/generate-openapi-typescript-client.js` validates every contracted operation against the checked-in
controller inventory before generating `frontend/src/generated/identityAuthApi.ts`. The generated client
accepts the frontend's configured Axios instance, uses paths relative to its `/api/v1` base URL, and returns
typed response bodies. The login, silent-refresh and logout call sites now use this generated client through
the existing shared Axios instance. That preserves the configured `/api/v1` base URL, `withCredentials`,
authorization header injection, single-flight refresh behavior, auth-endpoint retry suppression, in-memory
access-token lifecycle, local session marker, and best-effort logout cleanup.

Regenerate both route and payload contracts from the API gateway directory:

```powershell
npm run contracts:generate
```

CI and local gateway tests use the non-writing freshness gate:

```powershell
npm run contracts:check
```

The gate fails when the OpenAPI path no longer exists on the named canonical controller, an operation ID is
missing or duplicated, or the checked-in TypeScript output differs from the contract.

## Remaining migration boundary

OpenAPI coverage is not yet repository-wide. Add one service workflow per reviewed batch, generate its typed
client, migrate callers, and retain compatibility aliases while their gateway telemetry is non-zero. Route
deletion still requires the agreed observation window and separate approval; a generated client is not
evidence that an alias has no consumers.
