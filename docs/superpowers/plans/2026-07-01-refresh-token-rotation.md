# Refresh-Token Rotation + Reuse Detection Implementation Plan (Phase 1, Task 1.6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give identity-service RFC-style refresh-token rotation with reuse detection: each login opens a token *family*; every refresh retires the presented token and issues a new one in the same family; replaying a retired token revokes the whole family and is audited.

**Architecture:** Evolve `auth_sessions` from one-overwritten-row-per-session to one-row-per-issued-refresh-token, tagged with a `family_id` (login lineage) and a `status` (`ACTIVE`/`ROTATED`/`REVOKED`). `IdentityAuthService.refresh` rotates an `ACTIVE` token (retire → issue new in same family) and, on a non-`ACTIVE` replay, revokes the family + audits. Logout revokes the family. Existing rows are backfilled to single-token `ACTIVE` families so live sessions survive the deploy.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring Data JPA (`AuthSessionRepository`), Spring `JdbcClient` (audit insert), Flyway, Postgres 15/16, Testcontainers (`org.testcontainers:postgresql`+`:junit-jupiter`, BOM-managed — ADD to identity pom), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-07-01-refresh-token-rotation-design.md`

## Global Constraints

- **Storage:** one `auth_sessions` row per issued refresh token; `family_id VARCHAR(64) NOT NULL` (login lineage), `status VARCHAR(16) NOT NULL` ∈ {`ACTIVE`,`ROTATED`,`REVOKED`}, `rotated_at TIMESTAMPTZ` nullable. `refresh_token_hash` stays UNIQUE.
- **Status values** are the exact strings `ACTIVE` / `ROTATED` / `REVOKED` (used in JPQL literal + Java). Define them as `public static final String` constants on `AuthSessionEntity` and reference them everywhere — no bare string literals in the service.
- **Rotate** (refresh with an `ACTIVE`, unexpired, user-matching token): set the presented row `ROTATED` + `rotated_at=now`, INSERT a new `ACTIVE` row with the SAME `family_id` and fresh hashes/expiry; return the new tokens.
- **Reuse** (refresh with a row whose `status != ACTIVE`): revoke the whole family (`status='REVOKED' WHERE family_id=?`), write a `REFRESH_TOKEN_REUSE_DETECTED` audit row, throw `401`. A refresh-token hash NOT in the table → generic `401`, nothing revoked.
- **Blast radius = the family only** (never all of the user's families).
- **Logout** revokes the presented token's family (not a single-row delete). Absent/blank token → no-op, no error.
- **Login** starts a NEW family (`family_id = UUID`), one `ACTIVE` row.
- **TTLs unchanged:** access `900000ms` (~15 min), refresh `604800000ms` (7 days). Do NOT touch JwtService TTLs.
- **Backfill is deterministic:** existing rows → `family_id = id`, `status = 'ACTIVE'`. No NULLs remain before `SET NOT NULL`.
- Forward-only Flyway; identity-service's next version is **V2** (current: only `V1__identity_schema.sql`). Migrations run as `appuser`.
- Token hashing stays SHA-256 hex via the existing `tokenDigest(...)`. Existing Mockito/login tests stay green (adapt only where the changed message/behavior requires).

---

### Task 1: Schema V2 + entity/repository — family & status columns (with migration test)

**Files:**
- Create: `services/identity-service/src/main/resources/db/migration/V2__refresh_token_families.sql`
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/AuthSessionEntity.java`
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/AuthSessionRepository.java`
- Modify: `services/identity-service/pom.xml` (Testcontainers test deps + surefire UTC)
- Test: `services/identity-service/src/test/java/com/custoking/ims/identityservice/persistence/AuthSessionMigrationTest.java`

**Interfaces (produced — used by Task 2):**
- `AuthSessionEntity`: constants `ACTIVE`/`ROTATED`/`REVOKED` (String); fields+accessors `familyId:String`, `status:String`, `rotatedAt:OffsetDateTime`.
- `AuthSessionRepository.findByFamilyId(String):List<AuthSessionEntity>`; `revokeFamily(String familyId):int` (`@Modifying` sets status=REVOKED for the family).

- [ ] **Step 1: Migration `V2__refresh_token_families.sql`**
```sql
-- Refresh-token rotation with reuse detection: auth_sessions becomes one row per
-- issued refresh token, grouped by family_id (login lineage) with a lifecycle status.
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS family_id VARCHAR(64);
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE identity.auth_sessions ADD COLUMN IF NOT EXISTS rotated_at TIMESTAMPTZ;

-- Backfill existing live sessions so they survive the deploy: each becomes its own
-- single-token ACTIVE family (id doubles as family_id).
UPDATE identity.auth_sessions SET family_id = id    WHERE family_id IS NULL;
UPDATE identity.auth_sessions SET status = 'ACTIVE' WHERE status IS NULL;

ALTER TABLE identity.auth_sessions ALTER COLUMN family_id SET NOT NULL;
ALTER TABLE identity.auth_sessions ALTER COLUMN status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_identity_auth_sessions_family ON identity.auth_sessions(family_id);
```

- [ ] **Step 2: Add fields + status constants to `AuthSessionEntity`**

Add to the class (after `expiresAt`), plus getters/setters matching the existing style:
```java
    public static final String ACTIVE = "ACTIVE";
    public static final String ROTATED = "ROTATED";
    public static final String REVOKED = "REVOKED";

    @Column(name = "family_id", nullable = false, length = 64)
    private String familyId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "rotated_at")
    private OffsetDateTime rotatedAt;

    public String getFamilyId() { return familyId; }
    public void setFamilyId(String familyId) { this.familyId = familyId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(OffsetDateTime rotatedAt) { this.rotatedAt = rotatedAt; }
```

- [ ] **Step 3: Add repository methods**

In `AuthSessionRepository` (add imports `java.util.List`, `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`):
```java
    java.util.List<AuthSessionEntity> findByFamilyId(String familyId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
        "update AuthSessionEntity s set s.status = com.custoking.ims.identityservice.persistence.AuthSessionEntity.REVOKED where s.familyId = :familyId")
    int revokeFamily(@org.springframework.data.repository.query.Param("familyId") String familyId);
```
(If the constant reference in JPQL is awkward for the JPA provider, use the literal `'REVOKED'` in the query string — it must equal `AuthSessionEntity.REVOKED`. Prefer the literal `'REVOKED'` for reliability and add a comment that it must match the constant.)

- [ ] **Step 4: Add Testcontainers deps + surefire UTC to `services/identity-service/pom.xml`**

Add (test scope, no version — BOM-managed) `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter`, and `-Duser.timezone=UTC` to the surefire `argLine` (preserve any existing mockito javaagent) — copy the exact form from `services/student-service/pom.xml`.

- [ ] **Step 5: Write the migration test `AuthSessionMigrationTest.java`**

Testcontainers (model container/Flyway setup on `services/catalog-service/src/test/java/com/custoking/ims/catalogservice/persistence/CatalogTenantKeyMigrationTest.java`; schema `identity`). Two methods:
- `columns_areNotNull`: full migrate; assert `information_schema.columns` shows `family_id` and `status` are `is_nullable='NO'`.
- `backfill_assignsFamilyAndActive`: fresh container, Flyway `target('1')`; seed an app_user (`INSERT INTO identity.app_users(...)` — read `V1__identity_schema.sql` for its NOT NULL columns; e.g. `full_name,email,password_hash,role,created_at`) and an `auth_sessions` row with the V1 columns only (`id='sess-1', user_id=<that user>, access_token_hash='a', refresh_token_hash='r', created_at=now(), expires_at=now()+1day`); run Flyway to `target('2')`; assert the row now has `family_id='sess-1'` and `status='ACTIVE'`.

- [ ] **Step 6: Run** `./mvnw.cmd -q -f services/identity-service/pom.xml test -Dtest=AuthSessionMigrationTest` (Docker up) → PASS; then full `…/identity-service/pom.xml test` → green.

- [ ] **Step 7: Commit**
```bash
git add services/identity-service/src/main/resources/db/migration/V2__refresh_token_families.sql \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/AuthSessionEntity.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/AuthSessionRepository.java \
        services/identity-service/pom.xml \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/persistence/AuthSessionMigrationTest.java
git commit -m "feat(identity): auth_sessions family_id + status columns for refresh-token rotation"
```

---

### Task 2: Rotation, reuse detection, logout + audit (IdentityAuthService)

**Files:**
- Create: `services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/AuthAuditRepository.java`
- Modify: `services/identity-service/src/main/java/com/custoking/ims/identityservice/application/IdentityAuthService.java`
- Test: `services/identity-service/src/test/java/com/custoking/ims/identityservice/application/IdentityAuthServiceRotationTest.java`

**Interfaces:**
- Consumes: Task 1's `AuthSessionEntity` constants/fields + `AuthSessionRepository.revokeFamily`.
- Produces: `AuthAuditRepository.recordRefreshTokenReuse(Long userId, String email, String familyId)`.

- [ ] **Step 1: Create `AuthAuditRepository`** (JdbcClient insert into `identity.rbac_audit_log`)

Read `services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/RbacCommandRepository.java` `logAudit(...)` (~line 291) for the exact insert shape + the table's NOT NULL columns (read `rbac_audit_log` in `V1__identity_schema.sql`). Then:
```java
package com.custoking.ims.identityservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Records refresh-token reuse (a theft signal) into the identity audit log. */
@Repository
public class AuthAuditRepository {

    private final JdbcClient jdbc;

    public AuthAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void recordRefreshTokenReuse(Long userId, String email, String familyId) {
        jdbc.sql("""
                INSERT INTO identity.rbac_audit_log (event_type, actor_user_id, actor_email, permission_codes, created_at)
                VALUES ('REFRESH_TOKEN_REUSE_DETECTED', :userId, :email, :familyId, now())
                """)
            .param("userId", userId)
            .param("email", email)
            .param("familyId", familyId)
            .update();
    }
}
```
(Adjust the column list to the table's actual NOT NULL set — if `rbac_audit_log` has other NOT NULL columns without defaults, include them; `permission_codes` is TEXT and reused here to carry the family id. Match `RbacCommandRepository.logAudit`'s column handling.)

- [ ] **Step 2: Wire `AuthAuditRepository` into `IdentityAuthService`**

Add the field + constructor param (alongside `sessions`, `rbac`, etc.):
```java
    private final AuthAuditRepository authAudit;
    // constructor: add `AuthAuditRepository authAudit` param and `this.authAudit = authAudit;`
```

- [ ] **Step 3: Refactor `issueSession` to always append a row in a given family**

Replace the existing `issueSession(AppUserEntity user, AuthSessionEntity existingSession)` with:
```java
    private LoginResult issueSession(AppUserEntity user, String familyId) {
        AuthenticatedUserSnapshot snapshot = snapshot(user);
        String accessToken = jwtService.generateAccessToken(snapshot);
        String refreshToken = jwtService.generateRefreshToken(snapshot);
        AuthSessionEntity session = new AuthSessionEntity();
        session.setId(UUID.randomUUID().toString());
        session.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        session.setFamilyId(familyId);
        session.setStatus(AuthSessionEntity.ACTIVE);
        session.setUser(user);
        session.setAccessTokenHash(tokenDigest(accessToken));
        session.setRefreshTokenHash(tokenDigest(refreshToken));
        session.setExpiresAt(OffsetDateTime.ofInstant(jwtService.extractExpiration(refreshToken).toInstant(), ZoneOffset.UTC));
        sessions.save(session);
        return new LoginResult(refreshToken, responseFor(user, accessToken));
    }
```

- [ ] **Step 4: `login` starts a new family**

In `login(...)`, change the final line `return issueSession(user, null);` to:
```java
        return issueSession(user, UUID.randomUUID().toString());
```

- [ ] **Step 5: Rewrite `refresh` with rotation + reuse detection**

Replace the body of `refresh(String rawRefreshToken)` (keep the JWT-validation prologue as-is through the `catch` block) so that after obtaining `email`:
```java
        AuthSessionEntity session = sessions.findByRefreshTokenHash(tokenDigest(rawRefreshToken))
                .orElseThrow(() -> unauthorized("Invalid refresh token"));

        // Reuse detection: a replay of a retired/revoked token is a theft signal.
        if (!AuthSessionEntity.ACTIVE.equals(session.getStatus())) {
            sessions.revokeFamily(session.getFamilyId());
            authAudit.recordRefreshTokenReuse(session.getUser().getId(), email, session.getFamilyId());
            throw unauthorized("Refresh token reuse detected - session revoked");
        }
        if (session.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw unauthorized("Refresh token expired");
        }
        AppUserEntity user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> unauthorized("Invalid refresh token"));
        if (!Objects.equals(session.getUser().getId(), user.getId())) {
            throw unauthorized("Invalid refresh token");
        }
        // Rotate: retire the presented token, issue a new one in the same family.
        session.setStatus(AuthSessionEntity.ROTATED);
        session.setRotatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessions.save(session);
        return issueSession(user, session.getFamilyId());
```

- [ ] **Step 6: `logout` revokes the family**

Replace `logout`'s body's `.ifPresent(sessions::delete);` with:
```java
        sessions.findByRefreshTokenHash(tokenDigest(rawRefreshToken))
                .ifPresent(s -> sessions.revokeFamily(s.getFamilyId()));
```

- [ ] **Step 7: Write `IdentityAuthServiceRotationTest.java` (Mockito)**

Mock `AppUserRepository`, `AuthSessionRepository`, `RbacLookupRepository`, `PasswordEncoder`, `JwtService`, `AuthAuditRepository`. A helper builds a valid refresh path: stub `jwtService.extractUsername(raw)`→email, `isRefreshToken`→true, `isTokenValid`→true, `extractExpiration`→a future date, `generateAccessToken`/`generateRefreshToken`→dummy strings, `users.findByEmailIgnoreCase`→the user (id 7). Build `AuthSessionEntity` fixtures with a set `familyId`/`status`/`user`/`expiresAt`. Tests:
```java
// rotate: ACTIVE token → old row ROTATED, a NEW ACTIVE row saved in same family, tokens returned
@Test void refresh_activeToken_rotatesWithinFamily() {
    // session ACTIVE, familyId "F", expires future, user id 7
    // findByRefreshTokenHash → that session
    // act: refresh(raw)
    // assert: session.getStatus()==ROTATED; captured saved new session has status ACTIVE & familyId "F";
    //         revokeFamily NEVER called; authAudit NEVER called
}
@Test void refresh_rotatedToken_reuseDetected_revokesFamily() {
    // session status ROTATED, familyId "F"
    // act+assert: throws ResponseStatusException 401 "reuse detected";
    //   verify sessions.revokeFamily("F"); verify authAudit.recordRefreshTokenReuse(7, email, "F")
}
@Test void refresh_revokedToken_reuseDetected() { /* status REVOKED → same as above */ }
@Test void refresh_unknownHash_401_noRevoke() {
    // findByRefreshTokenHash → empty → 401 "Invalid refresh token"; verify revokeFamily NEVER, audit NEVER
}
@Test void refresh_expiredActive_401_noRevoke() { /* ACTIVE but expiresAt past → 401 expired; no revoke/audit */ }
@Test void logout_revokesFamily() {
    // findByRefreshTokenHash → session familyId "F"; logout(raw) → verify revokeFamily("F")
}
@Test void login_startsNewFamily() {
    // findByEmailIgnoreCase→user, passwordEncoder.matches→true; login → captured saved session status ACTIVE, familyId non-null
}
```
Use `ArgumentCaptor<AuthSessionEntity>` to assert the saved new-row `status`/`familyId`. Use `verify(sessions, never()).revokeFamily(any())` / `verify(authAudit, never())...` for the no-revoke cases. (Family isolation is guaranteed by `revokeFamily` being scoped to a single `familyId` — assert the exact family arg.)

- [ ] **Step 8: Adapt any existing auth test** — search `services/identity-service/src/test` for an existing `IdentityAuthService`/refresh/login test. If one asserts the old `"Refresh token was already used or revoked"` message or the overwrite behavior, update it to the new message/append behavior (do not weaken assertions). If none exists, note that in the report.

- [ ] **Step 9: Run** `./mvnw.cmd -q -f services/identity-service/pom.xml test -Dtest=IdentityAuthServiceRotationTest` → PASS; then full `…/identity-service/pom.xml test` → green.

- [ ] **Step 10: Commit**
```bash
git add services/identity-service/src/main/java/com/custoking/ims/identityservice/persistence/AuthAuditRepository.java \
        services/identity-service/src/main/java/com/custoking/ims/identityservice/application/IdentityAuthService.java \
        services/identity-service/src/test/java/com/custoking/ims/identityservice/application/IdentityAuthServiceRotationTest.java
git commit -m "feat(identity): refresh-token rotation + reuse detection (revoke family + audit)"
```

---

### Task 3: Runbook note + tracker + verification

**Files:**
- Modify: `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` (Task 1.6 status)
- Modify: `ARCHITECTURE_REVIEW.md` (SEC-P1-1 note)

- [ ] **Step 1: Update the program tracker** — under Task 1.6, add `Status (2026-07-01): done` referencing this plan; note: refresh tokens now rotate per-family with reuse detection (replaying a retired token revokes the family + audits `REFRESH_TOKEN_REUSE_DETECTED`); access TTL unchanged (~15 min); existing sessions backfilled to ACTIVE families. Append only.

- [ ] **Step 2: Update `ARCHITECTURE_REVIEW.md`** — near the `SEC-P1-1` entry (grep), append a dated (2026-07-01) note summarizing the rotation + reuse-detection behavior, the family-only blast radius, and the audit signal. Append only; do not rewrite.

- [ ] **Step 3: Verification** — Docker up:
```bash
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services/identity-service/pom.xml test
```
Expected: green, including `AuthSessionMigrationTest` (Testcontainers) and `IdentityAuthServiceRotationTest`. (identity-service also needs `APP_JWT_SECRET`≥32 chars + `APP_AADHAR_SECRET`≥16 chars set to boot — the migration/unit tests don't boot the full context, but if any @SpringBootTest exists, export those.)

- [ ] **Step 4: Commit**
```bash
git add docs/superpowers/plans/2026-06-28-architecture-remediation-program.md ARCHITECTURE_REVIEW.md
git commit -m "docs(ops): refresh-token rotation + reuse detection — tracker + architecture-review (Task 1.6)"
```

---

## Self-Review

**Spec coverage:**
- family_id + status per-issued-token storage → Task 1 (migration + entity). ✓
- rotate ACTIVE (retire + append new in family) → Task 2 Steps 3–5. ✓
- reuse of non-ACTIVE → revoke family + audit + 401 → Task 2 Steps 1,5. ✓
- family-only blast radius → `revokeFamily(familyId)` scoped query (Task 1 Step 3) + tests assert exact family. ✓
- logout revokes family → Task 2 Step 6. ✓
- login starts new family → Task 2 Step 4. ✓
- backfill existing rows to ACTIVE family → Task 1 Step 1 + migration test Step 5. ✓
- access/refresh TTL unchanged → Global Constraints (JwtService untouched). ✓
- audit `REFRESH_TOKEN_REUSE_DETECTED` → Task 2 Step 1. ✓
- unknown/expired token → 401 no revoke → Task 2 Step 5 + tests. ✓
- Testcontainers migration test (backfill proof) → Task 1 Steps 4–5. ✓

**Placeholder scan:** full SQL, entity fields, repository query, refactored service methods, audit repo, and the seven Mockito test intents (with the capture/verify approach) are given. The one implementer-resolved detail is the exact NOT NULL column set of `rbac_audit_log` for the audit insert (Task 2 Step 1 says read the table + `logAudit` and match) — named, not vague.

**Type/name consistency:** `AuthSessionEntity.ACTIVE/ROTATED/REVOKED` (String) used in the entity, `revokeFamily` JPQL literal, and the service; `issueSession(AppUserEntity, String familyId)` signature consistent between login (Step 4) and refresh (Step 5); `AuthAuditRepository.recordRefreshTokenReuse(Long,String,String)` defined Task 2 Step 1, called Step 5; repository `findByFamilyId`/`revokeFamily` defined Task 1 Step 3. Migration V2 = identity's next version.

## Risks

- **rbac_audit_log NOT NULL columns** beyond the ones inserted → Task 2 Step 1 says read the table + match `logAudit`; if a required column lacks a default, include it.
- **JPQL literal vs constant drift** (`'REVOKED'` must equal `AuthSessionEntity.REVOKED`) → Task 1 Step 3 comments this.
- **Existing session/login test** asserting old message/behavior → Task 2 Step 8 adapts it.
- **Concurrent double-refresh** (same ACTIVE token twice) → second sees ROTATED → family revoked (accepted per spec; not a code defect, a documented behavior).
- **identity boot secrets** for any @SpringBootTest → Task 3 Step 3 notes the env vars.
