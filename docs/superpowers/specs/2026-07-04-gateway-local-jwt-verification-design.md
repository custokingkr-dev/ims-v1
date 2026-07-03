# Gateway Local JWT Verification — Design (Task 2.3)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 2, Task 2.3 (`P1-4`).

**Goal:** Remove the per-request `POST /api/v1/auth/introspect` call from the API gateway's hot path by verifying the access token locally, while preserving the exact `x-authenticated-*` identity headers that downstream services and Row-Level Security depend on.

## Background — what is true today

- The access token is **HS512 (symmetric)**, signed by identity-service with the shared secret `APP_JWT_SECRET` (Secret Manager secret name `jwt-secret`). There is **no** asymmetric key / JWKS endpoint. (`services/identity-service/.../security/JwtService.java` — `Keys.hmacShaKeyFor`, `.signWith(key)`.)
- The access token carries **only** `role` + subject (`email`), plus `jti`/`iat`/`exp`. It does **not** contain `userId`, `schoolId`, or `zoneId`.
- Introspection **enriches from the database**: `IdentityAuthService.introspect(token)` parses the token for the email, verifies signature+expiry, then `users.findByEmailIgnoreCase(email)`, checks `user.isDisabled()`, and returns a principal with `userId`, `email`, `role`, `branchId` (=schoolId), `zoneId`.
- The gateway (`services/api-gateway/server.js`) calls `introspect()` on **every** authenticated request (`server.js:197`) and forwards `x-authenticated-user-id/email/role/school-id/zone-id` to the upstream. **Permissions are not forwarded** — domain services rely on role + tenant scope + RLS and do not fetch permissions per request. Permissions are therefore out of scope for this task.
- The gateway has **zero runtime npm dependencies**: its Dockerfile is `FROM node:20-alpine`, copies only `server.js`, and runs `node server.js`. There is no `package.json`.
- The deployed gateway is the Node `server.js`. `services/api-gateway/render-nginx.sh` is a separate, unused artifact for this path and is **not** touched.
- `AuthenticatedUserSnapshot` (passed to `JwtService.generateAccessToken`) already carries `id`, `email`, `role`, `branchId`, `zoneId` — so enrichment needs no new data source.

## Decisions (locked)

1. **Signing scheme: keep HS512, gateway verifies locally with the shared secret.** The gateway is added as a holder of `APP_JWT_SECRET`. Rationale: the gateway is already fully trusted — it holds every per-service token and stamps the `x-authenticated-*` identity headers every service trusts — so holding the signing secret adds little marginal risk. RS256/JWKS is deferred as possible Phase-6 hardening.
2. **Revocation posture: accept the ~15-min window.** Local verification drops the per-request `user.isDisabled()` check; a disabled user's existing access token keeps working until it expires (≤ access TTL, currently 900 000 ms / 15 min). Logout is unaffected — it already only revokes the refresh-token family; introspection never looked up the session table for access tokens. The access TTL is **unchanged** by this task.

## Architecture

Two coordinated changes plus config:

```
Login/refresh (identity)
  └─ JwtService.generateAccessToken  ──►  access token now carries: role, uid, sid, zid, ver=2
                                          (subject = email; refresh token unchanged)

Every authenticated request (gateway)
  └─ authenticate(req)
       ├─ no Bearer ───────────────────────────────► 401
       ├─ local verify ON + secret present:
       │     verifyJwtLocally(token)
       │       ├─ bad signature / expired / wrong alg ─► 401  (hard fail, NO fallback)
       │       ├─ valid + ver>=2 ─► principalFromClaims  (NO network call — the win)
       │       └─ valid + ver<2/absent (legacy) ─► introspect()  (rollout fallback)
       └─ local verify OFF or secret missing ─► introspect()      (current behavior)
```

### Component 1 — Identity: enrich the access token

`JwtService.generateAccessToken(AuthenticatedUserSnapshot user)` adds claims:

| Claim | Source | Notes |
|---|---|---|
| `role` | `user.role()` | already present |
| `uid` | `user.id()` | new |
| `sid` | `user.branchId()` | new; **null for superadmin** (no school) — omit or null |
| `zid` | `user.zoneId()` | new; nullable |
| `ver` | literal `2` | new; enrichment marker the gateway keys off |

Subject stays `email`. The **refresh token is unchanged** (`generateRefreshToken` keeps `role` + `type=refresh`). Introspection (`IdentityAuthService.introspect`) is **unchanged** and continues to enrich from the DB, serving the gateway's fallback path.

`null` claim handling: `sid`/`zid` may be null (superadmin / unzoned). The gateway must treat an absent-or-null `sid` as "no school scope" (empty `x-authenticated-school-id`), exactly as the introspect principal does today for a superadmin.

### Component 2 — Gateway: dependency-free HS512 verifier

New functions in `server.js`, using only the built-in `crypto` module (no new deps):

- `verifyJwtLocally(token, secret, now)` → returns the decoded claims object or `null`:
  1. Split into `header.payload.signature`; reject if not exactly 3 segments.
  2. base64url-decode the header JSON; require `alg === 'HS512'` (rejects `none`, `RS256`, and algorithm-confusion). Reject any other `alg`.
  3. Recompute `HMAC-SHA512(header + '.' + payload, secret)`, base64url-encode, and compare to the provided signature with `crypto.timingSafeEqual` (length-guarded).
  4. base64url-decode the payload JSON.
  5. Enforce `exp` (and `nbf` if present) against `now` (seconds).
  6. Return the claims; any failure → `null`. (`now` is a parameter for deterministic tests.)
- `principalFromClaims(claims)` → the same shape `introspect` returns:
  `{ userId: claims.uid, email: claims.sub, role: claims.role, branchId: claims.sid ?? null, zoneId: claims.zid ?? null }`.
  Returns `null` when `claims.ver` is `< 2` or absent (signals the fallback path).

### Component 3 — Gateway: auth path

New `authenticate(req, requestId)` replaces the direct `introspect(req, requestId)` call in the request handler (`server.js:197`):

- Extract the Bearer token; none → `null`.
- If `LOCAL_JWT_VERIFY` is enabled **and** `APP_JWT_SECRET` is set:
  - `claims = verifyJwtLocally(token, secret, nowSeconds())`.
  - `claims === null` (bad signature / expired / wrong alg) → return `null` (→ 401). **No fallback** — a validly-signed token is required for the fallback path, and introspection would reject a bad token anyway; falling back would just add latency to a guaranteed 401.
  - `claims` valid: `principal = principalFromClaims(claims)`; if non-null → return it (no network). If null (legacy un-enriched token, still validly signed) → `return introspect(req, requestId)`.
- Else (flag disabled or secret missing) → `return introspect(req, requestId)`.

The existing `introspect()` function is **kept as-is** for the fallback and the flag-off path.

### Component 4 — Config & deploy

- New gateway environment:
  - `APP_JWT_SECRET` — from Secret Manager `jwt-secret:latest` (the same secret identity uses).
  - `GATEWAY_LOCAL_JWT_VERIFY` — `enabled` (default) | `disabled`. `disabled` forces pure introspection with no redeploy → **instant rollback lever**.
- `cloudbuild.yaml`: add `APP_JWT_SECRET=jwt-secret:latest` to the gateway `--set-secrets` and `GATEWAY_LOCAL_JWT_VERIFY=enabled` to its env.
- `docker-compose.yml`: set `APP_JWT_SECRET` (matching identity's value) and the flag on the gateway service for the local stack.
- **No Dockerfile change** (dependency-free).
- Fail-open on misconfig: if the flag is enabled but `APP_JWT_SECRET` is unset, the gateway logs a warning once and falls back to introspection for all requests (does not fail closed — introspection is the current, secure, working path; failing closed would needlessly break prod on a config slip).

## Rollout

1. Deploy enriched **identity-service**. Verify a fresh login's access token decodes to include `ver:2`, `uid`, `sid`, `zid`.
2. Deploy **gateway** with `GATEWAY_LOCAL_JWT_VERIFY=enabled` + `APP_JWT_SECRET`.
3. Tokens issued in the ≤15-min window before step 1 lack `ver` → gateway auto-falls-back to introspection until they expire. No coordinated cutover needed; order is safe either way (if the gateway ships first, every token is un-enriched → it introspects → identical to today).
4. **Rollback:** set `GATEWAY_LOCAL_JWT_VERIFY=disabled` on the gateway.

## Error handling

- Bad/absent/expired/wrong-alg token with local verify on → `401 {message:"Unauthorized"}` (unchanged response contract).
- Local verify enabled but secret missing → warn once, introspect (fail-open to current behavior).
- Introspection network failure on the fallback path → same as today (`introspect` returns `null` → 401).
- `alg` other than `HS512` (incl. `none`) → rejected in `verifyJwtLocally` (no algorithm-confusion / none-alg bypass).

## Testing

- **Gateway** (`node --test`, `server.test.js`): `verifyJwtLocally` — valid enriched HS512 token → claims; tampered signature → null; expired (`exp` in past) → null; `alg:none` and `alg:RS256` headers → null; malformed (≠3 segments) → null. `principalFromClaims` — maps fields; `ver<2`/absent → null; null `sid`/`zid` → null branchId/zoneId. `authenticate` — enriched token uses local path with a fetch stub that must **not** be called; legacy token (valid sig, no `ver`) calls the introspect stub; flag `disabled` always calls introspect.
- **Identity** (`mvn test`, `JwtServiceTest`): generated access token contains `uid`/`sid`/`zid`/`ver:2`; superadmin snapshot (`branchId == null`) → token still has `ver:2` and a null/absent `sid`; refresh token unchanged (no `uid`/`sid`/`zid`, still `type:refresh`).
- **Regression gates (must stay green):** the enforce-mode + RLS + BOLA suites — `school_id` still flows to the upstream via `x-authenticated-school-id`, so tenant isolation is unchanged. Run `node --test server.test.js` and identity `mvn test` per change; the BOLA/enforce gate at the phase boundary.

## Post-deploy verification

- Re-run the authenticated end-to-end probe (login as a test superadmin, confirm `schools`/`students`/`fees` still return data through the gateway).
- Confirm identity `/auth/introspect` request volume drops sharply in logs/metrics after the gateway rollout (the primary success signal).

## Non-goals (YAGNI)

RS256/JWKS migration; forwarding permission codes through the gateway; changing the access-token TTL; session-table-based access-token revocation (denylist); modifying `render-nginx.sh`.
