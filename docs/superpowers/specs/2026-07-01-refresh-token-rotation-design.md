# Design — Refresh-Token Rotation + Reuse Detection (Phase 1, Task 1.6)

> Review ID: `SEC-P1-1`. Parent program plan: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` (Task 1.6).
> Scope: identity-service only. Independent of the tenant-isolation work (1.1–1.5).
> Status: **approved design** → next step is the bite-sized implementation plan.

## Problem

identity-service already issues a short-lived access JWT plus a longer-lived refresh JWT, and persists a hashed copy of each in `auth_sessions`. On `refresh()` it already *rotates* — it overwrites the session's single `refresh_token_hash` with the new token's hash. But this overwrite model has no **reuse detection**: once a refresh token is rotated, its hash is gone from the table, so replaying an old (already-rotated) refresh token is indistinguishable from presenting a logged-out or expired token — both just 401 for that one request. A stolen refresh token that an attacker has already rotated stays valid; when the legitimate user later presents their now-stale token they simply get 401, and nothing signals the theft.

The RFC-6819 / OAuth refresh-token-rotation-with-reuse-detection pattern fixes this: each login opens a token **family**; every refresh retires the presented token and issues a new one in the same family; replaying a **retired** token is a theft signal that revokes the entire family.

## Goals / Non-goals

**Goals**
- Persist a row per **issued** refresh token (not one overwritten row) with a `family_id` (login lineage) and a `status` (`ACTIVE`/`ROTATED`/`REVOKED`).
- On refresh: rotate an `ACTIVE` token (retire it `ROTATED`, issue a new `ACTIVE` token in the same family).
- On replay of a non-`ACTIVE` token (`ROTATED`/`REVOKED`): **revoke the whole family** and audit the event.
- Logout revokes the presented token's family.
- Existing live sessions keep working across the deploy (backfill).
- Keep the access-token TTL unchanged (~15 min); refresh TTL unchanged.

**Non-goals (deferred / out of scope)**
- Cross-device session management UI / "log out everywhere" button (not this task; the family-only blast radius is deliberate).
- Revoking *all* of a user's families on reuse (rejected — a single stale-token replay must not log the user out on every device).
- Rotating/second-guessing the **access** token (it stays a stateless short-lived JWT; reuse detection is a refresh-token concern).
- Sliding/absolute session lifetime changes, device fingerprinting, IP binding.

## Decisions (resolved during brainstorming)

1. **Storage = family + per-token status.** `auth_sessions` becomes one row per issued refresh token. `family_id` groups a login's rotation chain; `status` ∈ {`ACTIVE`, `ROTATED`, `REVOKED`}. This is the standard pattern and detects multi-step replay (an attacker replaying any retired token in the chain).
2. **Reuse blast radius = the family only.** Revoking one family leaves the user's other logins (other devices/browsers) working. Contains the breach without a global logout.
3. **Reuse of any non-`ACTIVE` token triggers revocation.** Presenting a `ROTATED` or `REVOKED` token whose row exists = replay → revoke the family. A token whose hash is not in the table at all (forged / already-swept expired) → generic 401, nothing to revoke.

## Schema — `V2__refresh_token_families.sql` (identity-service)

`auth_sessions` today: `id` (PK, VARCHAR), `user_id` (FK), `access_token_hash` (unique), `refresh_token_hash` (unique), `created_at`, `expires_at`.

Changes (forward Flyway, run as `appuser`):
```sql
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS family_id VARCHAR(64);
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMPTZ;

-- Backfill existing live sessions so they survive the deploy: each becomes its own
-- single-token ACTIVE family (id doubles as family_id).
UPDATE identity.auth_sessions SET family_id = id     WHERE family_id IS NULL;
UPDATE identity.auth_sessions SET status = 'ACTIVE'  WHERE status IS NULL;

ALTER TABLE identity.auth_sessions ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE identity.auth_sessions ALTER COLUMN status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_identity_auth_sessions_family ON identity.auth_sessions(family_id);
```
- `refresh_token_hash` stays unique — every issued token is distinct, so appended rows never collide.
- `access_token_hash` is retained per row (minimal change); it is not load-bearing for reuse detection.
- The backfill is deterministic (no NULLs possible afterward), so a single migration is safe; the runbook still pre-checks `count(*) WHERE family_id IS NULL` = 0 before `SET NOT NULL`.

## Components (identity-service)

### 1. `AuthSessionEntity` + `AuthSessionRepository`
- Add fields `familyId` (String), `status` (String; or a `RefreshTokenStatus` enum persisted as string), `rotatedAt` (OffsetDateTime).
- Repository methods: `findByRefreshTokenHash(hash)` (exists); add `List<AuthSessionEntity> findByFamilyId(String familyId)` (to revoke a family) and a bulk revoke — either iterate-and-save or a `@Modifying` `UPDATE ... SET status='REVOKED' WHERE family_id = :familyId` for efficiency.

### 2. `IdentityAuthService` — the flow

`login(...)` → `issueSession(user, existingSession=null)` creates a **new family**: `familyId = UUID`, one `ACTIVE` row with the new access+refresh hashes.

`refresh(rawRefreshToken)`:
1. Validate the refresh JWT (signature, type, expiry) — existing logic.
2. `session = findByRefreshTokenHash(digest(raw))`:
   - **empty** → `401 Invalid refresh token` (nothing to revoke).
   - `status == ACTIVE` → **rotate**: set `session.status = ROTATED`, `session.rotatedAt = now`, save; create a NEW `AuthSessionEntity` (new `id`, same `familyId`, `status = ACTIVE`, new hashes, new `expiresAt`), save; return new tokens.
   - `status in {ROTATED, REVOKED}` → **REUSE DETECTED**: revoke the family (`UPDATE ... status='REVOKED' WHERE family_id = session.familyId`), write an audit row, throw `401 Refresh token reuse detected — session revoked`.
3. (Idempotent: replaying a token from an already-revoked family re-revokes/no-ops and 401s.)

`logout(rawRefreshToken)`: look up the row; if present, revoke its whole family (`status='REVOKED' WHERE family_id = ...`) rather than deleting the single row. (A forged/absent token → no-op, still 200.)

Cleanup: extend the existing `sessions.deleteByExpiresAtBefore(now)` sweep (already called on login) to remove expired rows regardless of status, so retired/revoked rows age out with their expiry.

### 3. Audit
On reuse detection, write an entry to the existing `identity.rbac_audit_log` (or the service's audit path) capturing user id, family id, timestamp, action `REFRESH_TOKEN_REUSE_DETECTED`. This is the security signal an operator can alert on.

## Data flow

```
login → new family_id (UUID), ACTIVE row {access_hash, refresh_hash, expires}
refresh(valid ACTIVE token):
   old row → ROTATED (rotated_at=now)
   new row → ACTIVE (same family_id, new hashes/expiry)
   → return new access + refresh
refresh(ROTATED/REVOKED token in family F):   # theft signal
   UPDATE auth_sessions SET status=REVOKED WHERE family_id=F
   audit REFRESH_TOKEN_REUSE_DETECTED
   → 401 (family dead; that session must re-login)
refresh(unknown hash): → 401 (nothing revoked)
logout(token in family F): revoke family F → 401 on any later F token
other families (other logins/users) unaffected throughout
```

## Error handling

| Case | Response | Side effect |
|---|---|---|
| Valid ACTIVE refresh | 200 + new tokens | rotate (old ROTATED, new ACTIVE) |
| Retired (ROTATED/REVOKED) token replay | 401 "reuse detected" | revoke family + audit |
| Unknown/forged/expired-swept hash | 401 "invalid refresh token" | none |
| Malformed/expired JWT | 401 "invalid refresh token" | none |
| Logout (valid token) | 200 | revoke family |
| Logout (absent token) | 200 | none |

## Testing strategy

- **Mockito unit tests on `IdentityAuthService`** (no DB; repository mocked):
  - `rotate`: ACTIVE refresh → returns new tokens; old row saved ROTATED; a new ACTIVE row saved with the same `familyId`.
  - `reuse_oneStep`: replay the just-rotated token (now ROTATED) → family revoked (bulk update invoked with that `familyId`), 401.
  - `reuse_multiStep`: rotate twice, replay the first (oldest) token → family revoked, 401.
  - `logout_revokesFamily`: logout → family revoked; a subsequent refresh with any family token → 401.
  - `familyIsolation`: revoking family A does not touch family B (assert the revoke query is scoped to A's `familyId`).
  - `unknownToken`: unknown hash → 401, no revoke/audit.
  - `auditOnReuse`: reuse path writes the audit entry.
- **Testcontainers migration test** (model on existing identity or the tenant-key migration tests): apply migrations; assert `auth_sessions.family_id` and `status` are `NOT NULL`; seed a pre-migration-style row (via `target` before V2) with NULL family/status, run V2, assert it is backfilled to `family_id = id`, `status = 'ACTIVE'`.
- Existing auth/login tests stay green (login now also sets `family_id`/`status`).

## Rollout

Forward Flyway `V2` (identity-service's next version). The migration backfills existing rows to single-token `ACTIVE` families, so sessions issued before the deploy keep refreshing (their first refresh rotates them into a proper family). App change ships with `V2`. Rollback: `ALTER TABLE ... DROP COLUMN` is destructive to the new tracking, so rollback = redeploy the prior app image and leave the columns (harmless if unused); the runbook notes this.

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Backfill leaves NULL family/status → `SET NOT NULL` fails | Deterministic backfill (`family_id=id`, `status='ACTIVE'`) covers every row; runbook pre-checks zero NULL |
| Benign stale-token replay (slow client, double-submit) revokes a family spuriously | Only a token whose row exists as ROTATED/REVOKED triggers revoke; a client that keeps its current ACTIVE token rotates normally. Accepted: a genuine retired-token replay is treated as compromise by design |
| Rotated rows accumulate | The existing expiry sweep now removes expired rows of any status |
| Concurrent refresh race (two requests with the same ACTIVE token) | The second sees the row already ROTATED → treated as reuse → family revoked. Acceptable (double-use of one token is itself suspicious); note as known behavior, revisit with row-locking only if it causes real friction |
| access_token_hash unique index vs appended rows | Each rotation issues a fresh access token → distinct hash; no collision |

## Open items (deferred, not blocking)

- Concurrent-refresh grace (a short reuse window / row lock) if legitimate double-submits prove common in practice.
- A "sessions / sign out everywhere" management surface (would build on `family_id`).
- Moving the audit signal onto the shared audit-service event envelope (currently the local `rbac_audit_log`).
