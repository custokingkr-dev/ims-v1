# Gateway Local JWT Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the per-request `POST /api/v1/auth/introspect` call from the API gateway's hot path by verifying the HS512 access token locally, while preserving the `x-authenticated-*` identity headers downstream services and RLS depend on.

**Architecture:** Identity enriches the access token with `uid`/`sid`/`zid`/`ver:2` claims at issue time; the gateway verifies the HS512 signature locally with the shared `APP_JWT_SECRET` (using Node's built-in `crypto`, no new dependency) and builds the principal from claims — no network call. Introspection is kept as a fallback for un-enriched legacy tokens (rollout window) and as the flag-off path. A `GATEWAY_LOCAL_JWT_VERIFY` env flag is an instant rollback lever.

**Tech Stack:** Node.js (plain `http`, zero deps) gateway; Java 21 / Spring Boot 3.5.16 identity-service; JJWT (`io.jsonwebtoken`); `node --test`; JUnit/Mockito.

**Spec:** `docs/superpowers/specs/2026-07-04-gateway-local-jwt-verification-design.md`

## Global Constraints

- **Do not break the public `/api/v1/**` contract**; the 401 response body stays `{message:"Unauthorized"}`.
- **The gateway must remain dependency-free** — no `package.json`, no npm installs; use only Node built-ins (`crypto`). Dockerfile is unchanged.
- **Signing stays HS512 (symmetric)**; the gateway holds `APP_JWT_SECRET` (Secret Manager secret name `jwt-secret`).
- **Access-token TTL is unchanged** (900 000 ms / 15 min). Revocation window of ≤ TTL is accepted.
- **`school_id` must keep flowing** to upstreams via `x-authenticated-school-id` so RLS/BOLA gates stay green.
- **Claim names are exactly:** `uid` (userId, Long), `sid` (schoolId = branchId, Long, omitted when null), `zid` (zoneId, Long, omitted when null), `ver` (integer `2`). Subject (`sub`) stays the email. `role` unchanged.
- **Enforce-mode + RLS gates must stay green**: `node --test server.test.js` and identity `mvn test` per change.
- Branch: `phase2-gateway-local-jwt` (already created off `main`; the design spec is already committed there).

## File Structure

- `services/identity-service/src/main/java/com/custoking/ims/identityservice/security/JwtService.java` — **modify** `generateAccessToken` to add the enrichment claims. (Refresh token + `token()` helper + `claims()` unchanged.)
- `services/identity-service/src/test/java/com/custoking/ims/identityservice/security/JwtServiceTest.java` — **create**; proves the access token carries `uid`/`sid`/`zid`/`ver:2` and handles the null-school (superadmin) case.
- `services/api-gateway/server.js` — **modify**: add `verifyJwtLocally`, `principalFromClaims`, `authenticate`, the two config constants; replace the `introspect(...)` call at the auth check with `authenticate(...)`; export the new functions. (`introspect` kept as-is.)
- `services/api-gateway/server.test.js` — **modify**: add tests for the three new functions.
- `cloudbuild.yaml` — **modify** the gateway deploy step: add `GATEWAY_LOCAL_JWT_VERIFY=enabled` env and `APP_JWT_SECRET=jwt-secret:latest` secret.
- `docker-compose.yml` — **modify** the `api-gateway` service env: add `APP_JWT_SECRET` (matching identity's local value) and `GATEWAY_LOCAL_JWT_VERIFY: enabled`.

---

### Task 1: Identity — enrich the access token

**Files:**
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/security/JwtService.java` (method `generateAccessToken`, lines 36-40)
- Test: `services/identity-service/src/test/java/com/custoking/ims/identityservice/security/JwtServiceTest.java` (create)

**Interfaces:**
- Consumes: `AuthenticatedUserSnapshot(Long id, String fullName, String email, String role, Long branchId, String branchName, Long zoneId, String zoneName)` — already passed to `generateAccessToken`.
- Produces: an access token whose claims include `role`, `uid` (Long), `sid` (Long, only when `branchId != null`), `zid` (Long, only when `zoneId != null`), `ver` (int `2`), and `sub` = email. This is what the gateway's `verifyJwtLocally`/`principalFromClaims` (Task 2) read.

- [ ] **Step 1: Write the failing test**

Create `services/identity-service/src/test/java/com/custoking/ims/identityservice/security/JwtServiceTest.java`:

```java
package com.custoking.ims.identityservice.security;

import com.custoking.ims.identityservice.application.AuthenticatedUserSnapshot;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "test-jwt-secret-at-least-32-characters-long";
    private final JwtService jwtService = new JwtService(SECRET, 900_000L, 604_800_000L);

    private AuthenticatedUserSnapshot user(Long id, String role, Long branchId, Long zoneId) {
        return new AuthenticatedUserSnapshot(id, "Full Name", "user@example.com", role, branchId, "Branch", zoneId, "Zone");
    }

    @Test
    void accessTokenCarriesEnrichmentClaims() {
        String token = jwtService.generateAccessToken(user(42L, "ADMIN", 7L, 3L));
        Claims claims = jwtService.claims(token);

        assertEquals("user@example.com", claims.getSubject());
        assertEquals("ADMIN", claims.get("role"));
        assertEquals(42L, ((Number) claims.get("uid")).longValue());
        assertEquals(7L, ((Number) claims.get("sid")).longValue());
        assertEquals(3L, ((Number) claims.get("zid")).longValue());
        assertEquals(2, ((Number) claims.get("ver")).intValue());
    }

    @Test
    void superadminTokenOmitsSchoolAndZoneButKeepsVersion() {
        String token = jwtService.generateAccessToken(user(1L, "SUPERADMIN", null, null));
        Claims claims = jwtService.claims(token);

        assertEquals(2, ((Number) claims.get("ver")).intValue());
        assertEquals(1L, ((Number) claims.get("uid")).longValue());
        assertNull(claims.get("sid"));
        assertNull(claims.get("zid"));
    }

    @Test
    void refreshTokenIsUnchanged() {
        String token = jwtService.generateRefreshToken(user(42L, "ADMIN", 7L, 3L));
        Claims claims = jwtService.claims(token);

        assertEquals("refresh", claims.get("type"));
        assertNull(claims.get("uid"));
        assertNull(claims.get("sid"));
        assertTrue(claims.get("ver") == null);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\identity-service\pom.xml -Dtest=JwtServiceTest test
```
Expected: FAIL — `accessTokenCarriesEnrichmentClaims` and `superadminTokenOmitsSchoolAndZoneButKeepsVersion` fail with `NullPointerException`/assertion on `claims.get("uid")` (claim absent).

- [ ] **Step 3: Write minimal implementation**

In `JwtService.java`, replace `generateAccessToken` (lines 36-40):

```java
    public String generateAccessToken(AuthenticatedUserSnapshot user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.role());
        claims.put("uid", user.id());
        if (user.branchId() != null) {
            claims.put("sid", user.branchId());
        }
        if (user.zoneId() != null) {
            claims.put("zid", user.zoneId());
        }
        claims.put("ver", 2);
        return token(user.email(), claims, expirationMs);
    }
```

Leave `generateRefreshToken`, `token(...)`, and `claims(...)` unchanged.

- [ ] **Step 4: Run test to verify it passes**

```
.\mvnw.cmd -f services\identity-service\pom.xml -Dtest=JwtServiceTest test
```
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full identity test suite (nothing else regressed)**

```
.\mvnw.cmd -f services\identity-service\pom.xml test
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/security/JwtService.java services/identity-service/src/test/java/com/custoking/ims/identityservice/security/JwtServiceTest.java
git commit -m "feat(identity): enrich access token with uid/sid/zid/ver claims (Task 2.3)"
```

---

### Task 2: Gateway — dependency-free HS512 verifier + principal mapper

**Files:**
- Modify: `services/api-gateway/server.js` (add two functions near the existing `introspect` at line 270; add both to `module.exports` at lines 529-547)
- Test: `services/api-gateway/server.test.js` (add a test block)

**Interfaces:**
- Produces:
  - `verifyJwtLocally(token: string, secret: string, nowSeconds: number) → claimsObject | null` — returns the decoded JWT payload if the HS512 signature is valid, `alg === 'HS512'`, and `exp`/`nbf` pass; else `null`.
  - `principalFromClaims(claims: object) → { userId, email, role, branchId, zoneId } | null` — returns the principal (same shape `introspect` returns) when `claims.ver >= 2`; `null` otherwise (signals fallback).
- Consumes (Task 3): both functions are called by `authenticate`.

- [ ] **Step 1: Write the failing test**

In `services/api-gateway/server.test.js`, add `const crypto = require('node:crypto');` near the top requires, add `verifyJwtLocally` and `principalFromClaims` to the destructured `require('./server')` import (lines 27-43), and append this block at the end of the file:

```js
// --- Local JWT verification (Task 2.3) ---

function signHS512(payload, secret, header = { alg: 'HS512', typ: 'JWT' }) {
  const h = Buffer.from(JSON.stringify(header)).toString('base64url');
  const p = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.createHmac('sha512', secret).update(`${h}.${p}`).digest('base64url');
  return `${h}.${p}.${sig}`;
}

const JWT_SECRET = 'test-jwt-secret-at-least-32-characters-long';
const NOW = 1_000_000;
const enrichedClaims = { sub: 'a@b.com', role: 'ADMIN', uid: 42, sid: 7, zid: 3, ver: 2, exp: NOW + 900 };

test('verifyJwtLocally accepts a valid enriched HS512 token', () => {
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const claims = verifyJwtLocally(token, JWT_SECRET, NOW);
  assert.equal(claims.uid, 42);
  assert.equal(claims.ver, 2);
});

test('verifyJwtLocally rejects a tampered signature', () => {
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const tampered = `${token.slice(0, -2)}xx`;
  assert.equal(verifyJwtLocally(tampered, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects a token signed with a different secret', () => {
  const token = signHS512(enrichedClaims, 'some-other-secret-key-32-characters-x');
  assert.equal(verifyJwtLocally(token, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects an expired token', () => {
  const token = signHS512({ ...enrichedClaims, exp: NOW - 1 }, JWT_SECRET);
  assert.equal(verifyJwtLocally(token, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects alg none and alg RS256', () => {
  const none = signHS512(enrichedClaims, JWT_SECRET, { alg: 'none', typ: 'JWT' });
  const rs = signHS512(enrichedClaims, JWT_SECRET, { alg: 'RS256', typ: 'JWT' });
  assert.equal(verifyJwtLocally(none, JWT_SECRET, NOW), null);
  assert.equal(verifyJwtLocally(rs, JWT_SECRET, NOW), null);
});

test('verifyJwtLocally rejects a malformed token', () => {
  assert.equal(verifyJwtLocally('a.b', JWT_SECRET, NOW), null);
  assert.equal(verifyJwtLocally('not-a-token', JWT_SECRET, NOW), null);
});

test('principalFromClaims maps an enriched claim set', () => {
  assert.deepEqual(principalFromClaims(enrichedClaims), {
    userId: 42, email: 'a@b.com', role: 'ADMIN', branchId: 7, zoneId: 3,
  });
});

test('principalFromClaims returns null for un-enriched (ver<2 / absent) claims', () => {
  assert.equal(principalFromClaims({ sub: 'a@b.com', role: 'ADMIN' }), null);
  assert.equal(principalFromClaims({ ...enrichedClaims, ver: 1 }), null);
});

test('principalFromClaims yields null branchId/zoneId when sid/zid absent', () => {
  const p = principalFromClaims({ sub: 's@b.com', role: 'SUPERADMIN', uid: 1, ver: 2 });
  assert.equal(p.branchId, null);
  assert.equal(p.zoneId, null);
});
```

- [ ] **Step 2: Run test to verify it fails**

```
cd services/api-gateway
node --test server.test.js
```
Expected: FAIL — `verifyJwtLocally is not a function` (not yet exported/defined).

- [ ] **Step 3: Write minimal implementation**

In `services/api-gateway/server.js`, add `const crypto = require('crypto');` under the existing requires (after line 4). Then add these two functions immediately above `async function introspect(` (line 270):

```js
// Verify an HS512 JWT locally with the shared secret — no network call.
// Returns the decoded claims, or null on any failure. `nowSeconds` is injectable for tests.
function verifyJwtLocally(token, secret, nowSeconds) {
  if (typeof token !== 'string' || typeof secret !== 'string' || !secret) return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [headerB64, payloadB64, sigB64] = parts;
  let header;
  let payload;
  try {
    header = JSON.parse(Buffer.from(headerB64, 'base64url').toString('utf8'));
    payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  // Enforce HS512 only — rejects "none" and algorithm-confusion attacks.
  if (!header || header.alg !== 'HS512') return null;
  const expected = crypto.createHmac('sha512', secret).update(`${headerB64}.${payloadB64}`).digest('base64url');
  const provided = Buffer.from(sigB64);
  const expectedBuf = Buffer.from(expected);
  if (provided.length !== expectedBuf.length) return null;
  if (!crypto.timingSafeEqual(provided, expectedBuf)) return null;
  if (typeof payload.exp === 'number' && nowSeconds >= payload.exp) return null;
  if (typeof payload.nbf === 'number' && nowSeconds < payload.nbf) return null;
  return payload;
}

// Map verified JWT claims to the same principal shape introspect() returns.
// Returns null for un-enriched tokens (ver < 2) so the caller falls back to introspection.
function principalFromClaims(claims) {
  if (!claims || typeof claims.ver !== 'number' || claims.ver < 2) return null;
  return {
    userId: claims.uid ?? null,
    email: claims.sub ?? null,
    role: claims.role ?? null,
    branchId: claims.sid ?? null,
    zoneId: claims.zid ?? null,
  };
}
```

Add both to `module.exports` (the object at lines 529-547), e.g. after `stringOrEmpty,`:

```js
  verifyJwtLocally,
  principalFromClaims,
```

- [ ] **Step 4: Run test to verify it passes**

```
node --test server.test.js
```
Expected: PASS (all new tests + existing tests green).

- [ ] **Step 5: Commit**

```bash
git add services/api-gateway/server.js services/api-gateway/server.test.js
git commit -m "feat(gateway): dependency-free HS512 local verifier + principal mapper (Task 2.3)"
```

---

### Task 3: Gateway — `authenticate()` wiring + config flag + replace introspect call

**Files:**
- Modify: `services/api-gateway/server.js` (add config constants after line 8; add `authenticate` above `introspect`; replace the call at line 197; export `authenticate`)
- Test: `services/api-gateway/server.test.js` (add a test block)

**Interfaces:**
- Consumes: `verifyJwtLocally`, `principalFromClaims` (Task 2), and the existing `introspect(req, requestId)`.
- Produces: `authenticate(req, requestId, opts?) → principal | null`, where `opts` (for tests) may override `{ localVerify, secret, introspect, now }`. The request handler calls `authenticate(req, requestId)`.

- [ ] **Step 1: Write the failing test**

In `services/api-gateway/server.test.js`, add `authenticate` to the destructured `require('./server')` import, and append:

```js
// --- authenticate() dispatch (Task 2.3) ---

function reqWithToken(token) {
  return { headers: token ? { authorization: `Bearer ${token}` } : {} };
}

test('authenticate uses local claims for an enriched token and does NOT introspect', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 999 }; };
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const principal = await authenticate(reqWithToken(token), 'req-1', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal.userId, 42);
  assert.equal(principal.branchId, 7);
});

test('authenticate falls back to introspection for a valid un-enriched token', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 5, branchId: 8 }; };
  const legacy = signHS512({ sub: 'a@b.com', role: 'ADMIN', exp: NOW + 900 }, JWT_SECRET);
  const principal = await authenticate(reqWithToken(legacy), 'req-2', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 1);
  assert.equal(principal.userId, 5);
});

test('authenticate returns null (no introspection) for a bad-signature token', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 1 }; };
  const bad = `${signHS512(enrichedClaims, JWT_SECRET).slice(0, -2)}xx`;
  const principal = await authenticate(reqWithToken(bad), 'req-3', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal, null);
});

test('authenticate always introspects when local verify is disabled', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 77 }; };
  const token = signHS512(enrichedClaims, JWT_SECRET);
  const principal = await authenticate(reqWithToken(token), 'req-4', {
    localVerify: false, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 1);
  assert.equal(principal.userId, 77);
});

test('authenticate returns null and does not introspect when no bearer token is present', async () => {
  let calls = 0;
  const introspectStub = async () => { calls += 1; return { userId: 1 }; };
  const principal = await authenticate(reqWithToken(null), 'req-5', {
    localVerify: true, secret: JWT_SECRET, introspect: introspectStub, now: NOW,
  });
  assert.equal(calls, 0);
  assert.equal(principal, null);
});
```

- [ ] **Step 2: Run test to verify it fails**

```
cd services/api-gateway
node --test server.test.js
```
Expected: FAIL — `authenticate is not a function`.

- [ ] **Step 3: Write minimal implementation**

In `services/api-gateway/server.js`, add config constants after line 8 (`const CLOUD_RUN_AUTH = ...`):

```js
// Task 2.3: verify the access token locally instead of introspecting per request.
// 'disabled' forces pure introspection (instant rollback lever, no redeploy).
const LOCAL_JWT_VERIFY = (process.env.GATEWAY_LOCAL_JWT_VERIFY || 'enabled').toLowerCase() !== 'disabled';
const APP_JWT_SECRET = process.env.APP_JWT_SECRET || '';
let warnedMissingJwtSecret = false;
```

Add `authenticate` immediately above `async function introspect(` (line 270):

```js
// Resolve the caller's principal. Prefers local HS512 verification; falls back to
// introspection for un-enriched legacy tokens or when local verify is off/misconfigured.
// `opts` overrides are for deterministic unit tests.
async function authenticate(req, requestId, opts = {}) {
  const localVerify = opts.localVerify !== undefined ? opts.localVerify : LOCAL_JWT_VERIFY;
  const secret = opts.secret !== undefined ? opts.secret : APP_JWT_SECRET;
  const introspectFn = opts.introspect || introspect;
  const now = opts.now !== undefined ? opts.now : Math.floor(Date.now() / 1000);

  const auth = req.headers.authorization || '';
  const match = /^Bearer\s+(.+)$/i.exec(auth);
  if (!match) return null;

  if (localVerify && secret) {
    const claims = verifyJwtLocally(match[1], secret, now);
    if (!claims) return null; // bad signature / expired / wrong alg → 401, no fallback
    const principal = principalFromClaims(claims);
    if (principal) return principal; // enriched token → no network call
    return introspectFn(req, requestId); // valid but un-enriched → fall back
  }
  if (localVerify && !secret && !warnedMissingJwtSecret) {
    warnedMissingJwtSecret = true;
    console.warn('gateway.localjwt: GATEWAY_LOCAL_JWT_VERIFY enabled but APP_JWT_SECRET unset; using introspection');
  }
  return introspectFn(req, requestId);
}
```

Replace the auth call at line 197 — change:

```js
        const principal = await introspect(req, requestId);
```
to:
```js
        const principal = await authenticate(req, requestId);
```

Add `authenticate,` to `module.exports`.

- [ ] **Step 4: Run test to verify it passes**

```
node --test server.test.js
```
Expected: PASS (all tests green).

- [ ] **Step 5: Commit**

```bash
git add services/api-gateway/server.js services/api-gateway/server.test.js
git commit -m "feat(gateway): authenticate() prefers local verify, introspection as fallback (Task 2.3)"
```

---

### Task 4: Deploy config — wire `APP_JWT_SECRET` + flag into cloudbuild and compose

**Files:**
- Modify: `cloudbuild.yaml` (gateway deploy step — env line 185, secrets line 186)
- Modify: `docker-compose.yml` (`api-gateway` service env, after line 220)

**Interfaces:**
- Consumes: `GATEWAY_LOCAL_JWT_VERIFY` and `APP_JWT_SECRET` read by `server.js` (Task 3). Secret Manager secret name is `jwt-secret` (the same one identity uses at `cloudbuild.yaml:120`).

- [ ] **Step 1: Add the env flag to the gateway `--set-env-vars` in `cloudbuild.yaml`**

At line 185, append `,GATEWAY_LOCAL_JWT_VERIFY=enabled` to the end of the gateway `--set-env-vars` value (immediately before the closing `"`), so it ends `...NOTIFICATION_UPSTREAM=$${PLATFORM_URL},GATEWAY_LOCAL_JWT_VERIFY=enabled"`.

- [ ] **Step 2: Add the secret to the gateway `--set-secrets` in `cloudbuild.yaml`**

At line 186, append `,APP_JWT_SECRET=jwt-secret:latest` to the end of the gateway `--set-secrets` value (before the closing `"`), so it ends `...NOTIFICATION_SERVICE_TOKEN=notification-status-token:latest,APP_JWT_SECRET=jwt-secret:latest"`.

- [ ] **Step 3: Add both to the compose gateway env**

In `docker-compose.yml`, in the `api-gateway` service `environment:` block, add after line 220 (`GATEWAY_CLOUD_RUN_AUTH: never`):

```yaml
      APP_JWT_SECRET: "local-dev-jwt-secret-key-32-chars-minimum"
      GATEWAY_LOCAL_JWT_VERIFY: enabled
```

(The value must match identity's `APP_JWT_SECRET` at `docker-compose.yml:41` so local tokens verify.)

- [ ] **Step 4: Validate both files parse**

```
cd D:/Projects/ims-v1
python -c "import yaml; yaml.safe_load(open('docker-compose.yml')); yaml.safe_load(open('cloudbuild.yaml')); print('yaml OK')"
```
Expected: `yaml OK`. If Docker is available also run `docker compose config -q` (expected: no output, exit 0).

- [ ] **Step 5: Grep-verify the wiring landed on the gateway step (not identity)**

```
grep -n "GATEWAY_LOCAL_JWT_VERIFY=enabled\|APP_JWT_SECRET=jwt-secret:latest" cloudbuild.yaml
```
Expected: both matches are within the `custoking-api-gateway` deploy block (around lines 185-186), not the identity block (line 120 already has `APP_JWT_SECRET=jwt-secret:latest` for identity — that one is expected and unchanged).

- [ ] **Step 6: Commit**

```bash
git add cloudbuild.yaml docker-compose.yml
git commit -m "chore(deploy): wire APP_JWT_SECRET + GATEWAY_LOCAL_JWT_VERIFY into gateway (Task 2.3)"
```

---

### Task 5: Rollout & verification (operational — no unit test)

**Files:** none (deploy + verify). Run after Tasks 1-4 are merged to `main` and CI is green.

**Interfaces:** Consumes the deployed enriched identity + gateway. This is the spec's "Rollout" and "Post-deploy verification" sections.

- [ ] **Step 1: Deploy identity first (enriched tokens)**

```bash
gh workflow run deploy.yml --ref main -f environment=production -f deploy_services=identity-service -f run_direct_smoke=false
```
Wait for the run to conclude `success` and the service to reach `Ready=True`.

- [ ] **Step 2: Verify fresh tokens are enriched**

Log in via the gateway as a test superadmin and decode the access token's payload:

```bash
GW="https://custoking-api-gateway-xkv7oenbna-em.a.run.app"
TOKEN=$(curl -s -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
  -d '{"email":"e2e-superadmin@local.test","password":"password"}' | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")
echo "$TOKEN" | cut -d. -f2 | python -c "import sys,base64,json;p=sys.stdin.read().strip();print(json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4))))"
```
Expected: payload includes `'ver': 2`, `'uid': ...`, `'role': 'SUPERADMIN'` (superadmin: no `sid`).

- [ ] **Step 3: Deploy the gateway**

```bash
gh workflow run deploy.yml --ref main -f environment=production -f deploy_services=api-gateway -f run_direct_smoke=false
```
Wait for `success` + `Ready=True`.

- [ ] **Step 4: Re-run the authenticated end-to-end probe (data still flows)**

```bash
TOKEN=$(curl -s -X POST "$GW/api/v1/auth/login" -H "Content-Type: application/json" \
  -d '{"email":"e2e-superadmin@local.test","password":"password"}' | python -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")
for p in "/api/v1/schools" "/api/v1/students?schoolId=4" "/api/v1/fees/bands?schoolId=4"; do
  echo "$p -> $(curl -s -o /dev/null -w '%{http_code}' "$GW$p" -H "Authorization: Bearer $TOKEN")"
done
```
Expected: all `200` (real data), matching pre-change behavior — confirms `school_id` still flows and RLS still returns rows.

- [ ] **Step 5: Confirm introspection volume dropped (the success signal)**

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="custoking-identity-service" AND textPayload:"/api/v1/auth/introspect"' --freshness=10m --limit=20 --format='value(timestamp)' | wc -l
```
Expected: near-zero introspect hits after the gateway rollout (vs. one per authenticated request before). If something is wrong, set the rollback lever: redeploy the gateway with `GATEWAY_LOCAL_JWT_VERIFY=disabled` (or patch the env) to return to pure introspection.

---

## Self-Review

**Spec coverage:**
- Component 1 (enrich token) → Task 1. ✓
- Component 2 (verifier + mapper) → Task 2. ✓
- Component 3 (authenticate path, no-fallback-on-bad-sig, fallback on legacy, flag-off) → Task 3. ✓
- Component 4 (config: `APP_JWT_SECRET` secret, flag, compose, no Dockerfile change) → Task 4. ✓
- Rollout + post-deploy verification (introspect volume drop, e2e probe, rollback lever) → Task 5. ✓
- Non-goals honored: no RS256/JWKS, no permission forwarding, TTL unchanged, `render-nginx.sh` untouched, gateway stays dep-free (crypto is a Node built-in). ✓

**Type/name consistency:** claim names `uid`/`sid`/`zid`/`ver` and principal keys `userId`/`email`/`role`/`branchId`/`zoneId` match between Task 1 (producer), Task 2 (`principalFromClaims`), and Task 3 (`authenticate`). `authenticate(req, requestId, opts)` opts shape (`localVerify`/`secret`/`introspect`/`now`) is consistent between Task 3's implementation and its tests. `verifyJwtLocally(token, secret, nowSeconds)` signature matches all call sites.

**Placeholder scan:** none — every code step shows complete code and exact commands.
