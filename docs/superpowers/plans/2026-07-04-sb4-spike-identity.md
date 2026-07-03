# Spring Boot 4.0 Spike (identity-service) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate `identity-service` from Spring Boot 3.5.16 to 4.0.7 while staying on Java 21, validate it on a branch (build + full test suite + local boot), and produce a breaks-inventory + go/no-go recommendation for the 4-service rollout.

**Architecture:** A phased, discover-and-fix migration on branch `phase2-sb4-spike-identity`. Because a major-version upgrade breaks in ways only the compiler/tests reveal, each task is a **procedure with hard entry/exit gates and exact commands**, not pre-written code. The existing ~64-test identity suite (incl. Testcontainers integration) is the behavior oracle.

**Tech Stack:** Java 21, Maven (root `mvnw.cmd`), Spring Boot 3.5.16 → 4.0.7, JJWT 0.12.5, Flyway, Hibernate (6→7), Jakarta EE 11, Testcontainers, Docker Compose (local Postgres).

> **⚠️ This is a SPIKE, not feature work.** Tasks 2–4 contain discover-and-fix loops: run a command, read the errors, apply the smallest fix, re-run. The plan states the *strategy* and the *known* edits (pom versions, the jjwt options) but cannot pre-list every compile error. A well-documented **NO-GO** is a successful outcome, not a failure — do not force an unsafe workaround to reach GREEN.

## Global Constraints

- **Stay on Java 21** for this entire spike (`<java.version>21</java.version>` unchanged). No JDK 25. SB 4.0 supports Java 17–25, so 4.0.7 + Java 21 is a supported combo.
- **Target = Spring Boot 4.0.7** exactly (final 4.0-line patch; not 4.1).
- **Scope = identity-service only.** Do NOT touch the other 4 services, Dockerfiles, CI workflows, or deploy prod.
- **Behavior-preserving migration** — the existing test suite is the oracle; do not weaken/delete tests to get green. Add a test only to characterize a genuinely changed behavior.
- **Branch `phase2-sb4-spike-identity`** already exists (off `main`) with the design spec committed. Do all work there.
- **Maven on Windows/PowerShell** (set JDK first): `$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"` then `.\mvnw.cmd -f services\identity-service\pom.xml <goal>`.
- **jjwt resolution order:** try (a) coexist-with-pinned-Jackson-2 → (b) newer jjwt supporting Jackson 3 → (c) swap to `jjwt-gson`. Keep the first that yields a clean build + green JWT tests.

## File Structure

- `services/identity-service/pom.xml` — the primary edit surface: parent version (Task 2), jjwt/Jackson deps (Task 3), any other explicitly-pinned dep bumps (Tasks 2/4).
- `services/identity-service/src/main/java/**` — only files the compiler/tests force us to change (removed/relocated APIs, deprecations). Expected small; identity has no direct Jackson usage and no Spring Security config.
- `services/identity-service/src/main/resources/application.yml` — only if a property was renamed/removed in 4.0 (e.g. Jackson or actuator property changes).
- `services/identity-service/src/test/java/**` — only if a test breaks on a legitimately-changed behavior (characterize, don't weaken).
- `docs/SB4-SPIKE-FINDINGS.md` — **created in Task 6**: the breaks-inventory + jjwt decision + rollout effort estimate + GO/NO-GO.
- `$CLAUDE_JOB_DIR/tmp/` (or a scratch dir) — capture build logs (`baseline.txt`, etc.); **not committed**.

---

### Task 1: Baseline green + deprecation sweep (still on 3.5.16)

**Goal:** Prove a green starting point and clear 3.5 deprecations before bumping (SB's recommended path).

**Entry:** on branch `phase2-sb4-spike-identity`, identity at parent 3.5.16.
**Exit:** identity builds + all tests green on 3.5.16; known-fixable deprecation warnings resolved; the exact baseline test count recorded.

- [ ] **Step 1: Confirm the baseline suite is green and record the test count**

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\identity-service\pom.xml clean test | Tee-Object "$env:TEMP\sb4-baseline.txt"
```
Expected: `BUILD SUCCESS`. Record the `Tests run: N` total (the oracle count for later tasks — expected ≈64).

- [ ] **Step 2: Surface deprecation warnings**

```
.\mvnw.cmd -f services\identity-service\pom.xml clean compile "-Dmaven.compiler.showDeprecation=true" "-Dmaven.compiler.showWarnings=true" | Select-String -Pattern "deprecat|WARNING"
```
Read the list. These are Spring/Java API deprecations in identity's own code.

- [ ] **Step 3: Fix each deprecation on 3.5.16**

For each deprecation, apply the smallest fix (replace the deprecated call with its documented replacement — Spring Javadoc names it). Do NOT change behavior. If a deprecation originates in a library (not identity's code), note it and leave it — it will be resolved by the 4.0 bump or recorded in findings.

- [ ] **Step 4: Re-run tests to confirm still green after deprecation fixes**

```
.\mvnw.cmd -f services\identity-service\pom.xml clean test
```
Expected: `BUILD SUCCESS`, same test count as Step 1, deprecation warnings reduced.

- [ ] **Step 5: Commit (only if any code changed; otherwise skip)**

```bash
git add services/identity-service
git commit -m "chore(identity): clear SB 3.5 deprecations before SB 4.0 bump (Task 2.4 spike)"
```
If no deprecations needed fixing, record that fact for the findings doc and proceed (no commit).

---

### Task 2: Bump parent to 4.0.7 + resolve compile breaks

**Goal:** Get identity to **compile** on Spring Boot 4.0.7 (main + test sources), resolving removed/relocated APIs and dependency-management conflicts. JWT tests may still fail here (Task 3 owns jjwt).

**Entry:** Task 1 exit met.
**Exit:** `test-compile` succeeds on 4.0.7 (compilation only; test execution can still fail).

- [ ] **Step 1: Bump the parent version**

In `services/identity-service/pom.xml`, change the parent (lines 5-10) version:
```xml
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>
```
Leave `<java.version>21</java.version>` unchanged.

- [ ] **Step 2: Attempt compile; capture the break list**

```
.\mvnw.cmd -f services\identity-service\pom.xml clean test-compile "-U" | Tee-Object "$env:TEMP\sb4-compile.txt"
```
Expected on first run: FAILURE. Read `$env:TEMP\sb4-compile.txt` for `[ERROR]` lines — removed classes, relocated auto-config, changed method signatures, or unresolved managed versions.

- [ ] **Step 3: Discover-and-fix loop until it compiles**

For each compile error, apply the minimal fix:
- **Relocated/renamed Spring API** → update the import/call to the 4.0 replacement (consult the [SB 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) for the specific class).
- **Removed managed version** for an explicitly-pinned dep → set an explicit version or the 4.0-BOM-provided one.
- **`logstash-logback-encoder 8.0` conflict** with SB 4.0's Logback → bump to the version compatible with SB 4.0's Logback (check Maven Central; set the version in the existing dependency).
- Do NOT touch the jjwt dependencies in this task even if they cause *compile* issues in test JWT code — if jjwt blocks compilation, note it and move its resolution to Task 3 (bump only what's needed to reach a compile). If jjwt genuinely blocks `test-compile`, apply the Task 3 Option (a) pin now as the minimal unblock and note it.

Re-run Step 2's command after each fix.

- [ ] **Step 4: Confirm compile-clean**

```
.\mvnw.cmd -f services\identity-service\pom.xml clean test-compile
```
Expected: `BUILD SUCCESS` (compilation only).

- [ ] **Step 5: Commit**

```bash
git add services/identity-service/pom.xml services/identity-service/src
git commit -m "build(identity): compile on Spring Boot 4.0.7 (Java 21) — parent bump + API fixes (Task 2.4 spike)"
```

---

### Task 3: Resolve jjwt-jackson vs Jackson 3 (the crux)

**Goal:** Make the JWT create/parse path work under SB 4.0's Jackson 3, using the cleanest of three strategies. Success = the JWT-focused tests pass.

**Entry:** Task 2 exit met (compiles on 4.0.7).
**Exit:** `JwtServiceTest` (and any other JWT-touching test) passes; the chosen strategy is committed and noted for the findings doc.

Background: SB 4.0 uses **Jackson 3** (`tools.jackson`); `jjwt-jackson 0.12.5` is built on **Jackson 2** (`com.fasterxml.jackson.databind`), which the 4.0 BOM no longer manages. The JWT tests to watch: `JwtServiceTest` (created in Task 2.3) and `IdentityAuthService*` tests that mint/parse tokens.

- [ ] **Step 1: Run the JWT tests to see the current failure**

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.11'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\identity-service\pom.xml "-Dtest=JwtServiceTest" test | Tee-Object "$env:TEMP\sb4-jjwt.txt"
```
Read the failure (typically `ClassNotFoundException`/`NoClassDefFoundError` for a `com.fasterxml.jackson.databind` class, or JJWT failing to locate a serializer).

- [ ] **Step 2: Strategy (a) — coexist with a pinned Jackson 2**

In `services/identity-service/pom.xml`, keep the three `jjwt-*` deps and add explicit Jackson 2 databind + core so JJWT's serializer resolves (Jackson 2's `com.fasterxml.jackson` package coexists with Spring's Jackson 3 `tools.jackson`):
```xml
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.18.2</version>
        </dependency>
```
(`jackson-databind` pulls the matching `jackson-core`/`jackson-annotations` 2.x transitively. If a version mismatch surfaces, pin `jackson-core` 2.18.2 too.) Re-run Step 1's command. If GREEN → go to Step 5.

- [ ] **Step 3: Strategy (b) — newer jjwt (only if (a) is unclean)**

Check Maven Central for a `jjwt` release newer than 0.12.5 that targets Jackson 3. If one exists, bump all three `jjwt-api`/`jjwt-impl`/`jjwt-jackson` to it and remove the Step 2 Jackson-2 pin. Re-run Step 1's command. If GREEN and cleaner than (a) → go to Step 5. If no such release exists, skip to Step 4.

- [ ] **Step 4: Strategy (c) — swap to jjwt-gson (only if (a)/(b) are unclean)**

Replace the `jjwt-jackson` dependency with `jjwt-gson` (same jjwt version), removing the Step 2 Jackson-2 pin:
```xml
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-gson</artifactId>
            <version>0.12.5</version>
            <scope>runtime</scope>
        </dependency>
```
JJWT auto-detects the Gson serializer on the classpath; `JwtService` code is unchanged (it uses the `Jwts` builder/parser API, not Jackson directly). Re-run Step 1's command. Expected: GREEN.

- [ ] **Step 5: Run the full JWT-touching test set to confirm no regression**

```
.\mvnw.cmd -f services\identity-service\pom.xml "-Dtest=JwtServiceTest,IdentityAuthServiceRotationTest,IdentityAuthServiceReuseCommitIntegrationTest" test
```
Expected: `BUILD SUCCESS`, all pass. (These cover mint/parse/rotation/reuse — the full JWT surface.)

- [ ] **Step 6: Commit the chosen strategy**

```bash
git add services/identity-service/pom.xml
git commit -m "build(identity): JWT serialization works under SB4 Jackson 3 via <chosen strategy> (Task 2.4 spike)"
```
Replace `<chosen strategy>` with the actual one, e.g. "jjwt-gson swap" or "pinned Jackson 2 coexistence".

---

### Task 4: Green the full suite (Hibernate 7 / validation / runtime fixes)

**Goal:** All ~64 identity tests pass on 4.0.7, output pristine. This exercises Hibernate 7 (Testcontainers integration tests boot real Postgres + JPA + Flyway), Bean Validation 3.1, and actuator/context wiring.

**Entry:** Task 3 exit met (JWT tests green).
**Exit:** full identity suite `BUILD SUCCESS`, same test count as Task 1 baseline, no new warnings we can reasonably remove.

- [ ] **Step 1: Run the full suite; capture failures**

```
.\mvnw.cmd -f services\identity-service\pom.xml clean test | Tee-Object "$env:TEMP\sb4-suite.txt"
```
Read failures. Likely categories: Hibernate 7 mapping/dialect differences; Bean Validation 3.1 message/behavior; an actuator or Jackson-3 property in `application.yml`; a Testcontainers/context-boot issue.

- [ ] **Step 2: Discover-and-fix loop**

For each failure, apply the minimal, behavior-preserving fix:
- **Renamed property** in `application.yml` (Jackson `spring.jackson.json.read.*`, or an actuator property) → rename to the 4.0 key per the migration guide.
- **Hibernate 7 mapping/SQL difference** on an entity → adjust the mapping annotation to preserve the prior schema/behavior (the Flyway schema is authoritative; make the entity match it). Do not change Flyway migrations.
- **Bean Validation 3.1** message/annotation change surfaced by a `@Valid` test → align the assertion or annotation to the intended contract.
- If a test asserts a Jackson-serialized JSON shape that Jackson 3 legitimately changed, **characterize** the intended shape (pin it) rather than deleting the test.
Re-run Step 1's command after each fix.

- [ ] **Step 3: Confirm full green with the baseline count**

```
.\mvnw.cmd -f services\identity-service\pom.xml clean test
```
Expected: `BUILD SUCCESS`; `Tests run: N` equals the Task 1 baseline count; no failures/errors.

- [ ] **Step 4: Commit**

```bash
git add services/identity-service
git commit -m "test(identity): full suite green on Spring Boot 4.0.7 (Hibernate 7 / validation fixes) (Task 2.4 spike)"
```

---

### Task 5: Local boot verification (the assembled jar runs, not just the test context)

**Goal:** Prove the packaged application boots on 4.0.7 against a real Postgres and serves health — SB 4.0 can restructure the fat jar, so a green test context is necessary but not sufficient.

**Entry:** Task 4 exit met.
**Exit:** the identity container/jar boots on 4.0.7 and `/actuator/health` returns `{"status":"UP"}`; startup log captured for the findings doc.

- [ ] **Step 1: Start a local Postgres**

```
docker compose up -d postgres
```
Expected: the `postgres` container is healthy (`docker compose ps`).

- [ ] **Step 2: Build + boot identity via compose (uses the real Dockerfile — temurin-21, unchanged)**

```
docker compose up -d --build identity-service
```
(If identity is profile-gated, add the needed profile, e.g. `docker compose --profile core up -d --build identity-service`.) This rebuilds the image with SB 4.0.7 (an extra signal that the in-Docker Maven build also works) and boots it with compose's env wiring (`APP_JWT_SECRET`, DB creds, etc.).

- [ ] **Step 3: Confirm it booted healthy**

```
docker compose logs --tail=40 identity-service
```
Expected: `Started IdentityServiceApplication` with no stack trace; Flyway applied migrations; no `APPLICATION FAILED TO START`.

Then hit health (identity listens on 8080 in-container; use its mapped host port from `docker compose ps` — commonly 8083):
```
curl -s http://localhost:8083/actuator/health
```
Expected: `{"status":"UP"}` (or the actuator health JSON with status UP).

- [ ] **Step 4: Capture boot evidence + tear down**

Save the `Started IdentityServiceApplication ...` log line and the health response into `$env:TEMP\sb4-boot.txt` for the findings doc. Then:
```
docker compose down
```
No commit (no source change in this task).

---

### Task 6: Breaks-inventory + GO/NO-GO writeup

**Goal:** Produce the spike's decision artifact so the rollout can be planned (or 2.4 deferred) from facts, not guesses.

**Entry:** Tasks 2–5 complete (identity green + boots on 4.0.7) — OR a hard blocker was hit (a NO-GO is still written up here).
**Exit:** `docs/SB4-SPIKE-FINDINGS.md` committed with a definitive GO or NO-GO.

- [ ] **Step 1: Create `docs/SB4-SPIKE-FINDINGS.md`**

Write the document with these sections (fill from the captured logs `sb4-baseline/compile/jjwt/suite/boot.txt` and the commits):
- **Outcome:** GO or NO-GO (one line, up top).
- **What changed in identity** — parent version; the **jjwt/Jackson-3 decision** (which of a/b/c, and why); any Jackson-2 pin or `logstash-logback-encoder` bump; every source/`application.yml` edit with its reason (removed/relocated API, renamed property, Hibernate 7 mapping).
- **Deprecations cleared** (Task 1) and any left to libraries.
- **Test result:** baseline count vs post-migration count (must match); boot evidence (the `Started …` line + health).
- **Rollout effort estimate** — per remaining service (billing, operations, platform, school-core), calling out: platform + school-core each carry direct Jackson-3 code migration (7 files total: `tools.jackson` import/API renames + the "all modules auto-register" behavior), school-core's custom multi-schema Flyway config to re-validate under Hibernate 7, and that every jjwt-using service reuses identity's chosen strategy. Give a rough size (S/M/L) per service with the reasons.
- **Risks/unknowns for the rollout** and the recommended rollout order (easy→hard: billing → operations → identity-merge-in? no — identity done → platform/school-core last).
- **Java 25 pass** — note it as the separate follow-on (Dockerfiles `temurin-21`→`25`, CI `java-version` 21→25, `<java.version>`), unblocked once SB 4.0 is in.

- [ ] **Step 2: Commit**

```bash
git add docs/SB4-SPIKE-FINDINGS.md
git commit -m "docs: SB 4.0 spike findings + GO/NO-GO for identity-service (Task 2.4 spike)"
```

- [ ] **Step 3: STOP — go/no-go gate**

Do **not** open a PR, merge, deploy, or start the rollout. Report the GO/NO-GO and the findings summary to the controller/user for the decision. The rollout (other 4 services) and the Java 25 pass get their own spec/plan only after an explicit GO.

---

## Self-Review

**Spec coverage:**
- Deprecation sweep (spec §Method 1) → Task 1. ✓
- Parent bump 3.5.16→4.0.7 + compile fixes (spec §Method 3 / §Surface 2) → Task 2. ✓
- jjwt/Jackson-3 resolution a→b→c (spec §Surface 3, the crux) → Task 3. ✓
- Hibernate 7 / validation / full suite green (spec §Surface 4, §Method 5) → Task 4. ✓
- Local boot (spec §Method 6, success criteria) → Task 5. ✓
- Breaks-inventory + effort estimate + GO/NO-GO + pause gate (spec §Deliverables, §Method 7) → Task 6. ✓
- Constraints honored: Java stays 21; identity-only; no Dockerfile/CI/prod; behavior-preserving (suite is oracle); SB 4.0.7 exact. ✓
- Non-goals respected: no Java 25, no other services, no prod deploy, no 4.1/native. ✓

**Placeholder scan:** The `<chosen strategy>` in Task 3 Step 6 and the Task 6 doc contents are *intentionally* filled at runtime (spike discovers them) — every *command* and *known* edit (parent version, jjwt option pom snippets, Jackson-2 pin, test names, boot commands) is concrete. No lazy "TODO/handle errors" placeholders.

**Consistency:** Branch name `phase2-sb4-spike-identity`, target `4.0.7`, Java `21`, and the jjwt strategy order (a→b→c) are consistent across all tasks and the spec. Test names (`JwtServiceTest`, `IdentityAuthServiceRotationTest`, `IdentityAuthServiceReuseCommitIntegrationTest`) match Task 2.3 + refresh-rotation work.
