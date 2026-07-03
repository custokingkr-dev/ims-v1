# Spring Boot 4.0 Spike (identity-service) — Design (Task 2.4, spike phase)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 2, Task 2.4 (`P1-5`/`P0-2`) — "Java 21→25 + Spring Boot 3.5→4.0 spike … validated on one service, then rolled out."

**Scope of THIS spec:** the **spike only** — migrate `identity-service` from Spring Boot 3.5.16 to **4.0.7 while staying on Java 21**, validate on a branch, and produce a go/no-go for the rollout. The 4-service rollout and the Java 21→25 pass are **out of scope** here and get their own spec/plan after the go/no-go gate.

## Decisions (locked during brainstorming)

1. **Axis isolation:** do Spring Boot 3.5→4.0 **on Java 21 first** (change one axis at a time). Java 21→25 is a separate later pass. SB 4.0 supports Java 17–25, so SB 4.0 + Java 21 is a supported intermediate combo, and no new JDK install is needed for the spike (only JDK 21 is installed locally).
2. **Spike service = `identity-service`** — standalone (no cross-service coupling) yet exercises the trickiest real risk (jjwt-jackson on Jackson 2), plus JPA, Flyway, validation, BCrypt, and Testcontainers.
3. **Spike-only scope with a go/no-go gate** — validate on a branch (build + tests + local boot), produce a breaks-inventory + effort estimate + go/no-go recommendation, then **pause for the user's decision** before planning the rollout. A hard blocker is a legitimate **no-go** output, not a failure.
4. **Target version = Spring Boot 4.0.7** (final, fully-patched 4.0-line release; not 4.1 — one minor jump at a time).

## Background — SB 4.0 facts that bind this migration

- **Java baseline** Java 17+; Java 25 is first-class. We hold at **Java 21** for the spike.
- **Jackson 3** is SB 4.0's preferred JSON library: group/package rename `com.fasterxml.jackson` → `tools.jackson` (except `jackson-annotations`, which keeps `com.fasterxml.jackson.annotation`). Jackson now auto-registers **all** classpath modules. Jackson 2 config properties (`JsonParser.Feature`) are replaced by `spring.jackson.json.read.*`.
- **Jakarta EE 11 baseline** → **Hibernate 7.x**, Bean Validation 3.1, Tomcat 11. Hibernate 7's SQM can generate different SQL than Hibernate 6 (a risk only for exact-SQL-asserting tests or index-shape-tuned queries).
- **Recommended path:** go through SB 3.5 first and eliminate **all** deprecation warnings, then migrate to 4.0. identity is already on 3.5.16.

## Current state of identity-service (measured)

- `pom.xml`: parent `spring-boot-starter-parent:3.5.16`, `<java.version>21</java.version>`.
- Spring-managed starters: `web`, `actuator`, `data-jpa`, `validation`. Plus `spring-security-crypto` (BCrypt only — **no** `spring-boot-starter-security`, **no** `SecurityFilterChain`/`@EnableWebSecurity`).
- Explicitly-versioned deps: `jjwt-api`/`jjwt-impl`/`jjwt-jackson` **0.12.5**, `logstash-logback-encoder` **8.0**.
- Spring-managed (unpinned): `postgresql`, `flyway-core`, `flyway-database-postgresql`, Testcontainers (`postgresql`, `junit-jupiter`), `spring-boot-starter-test`.
- **No direct `com.fasterxml.jackson` usage in identity's main code** (verified — the codebase's Jackson usage is in platform/school-core, not identity). identity's Jackson exposure is entirely **transitive via jjwt-jackson**.
- **2 `@Entity` classes** (`app_users`, `auth_sessions`); **no tests assert exact SQL**.
- 2 servlet filters (`RequestCorrelationFilter`, `TenantContextFilter`); Flyway V1+V2 migrations; existing suite ≈ 64 tests incl. Testcontainers integration tests.
- `maven-surefire-plugin` sets `-javaagent:…/mockito-core-${mockito.version}.jar` (SB-managed `${mockito.version}`) to load Mockito as an agent — forward-compatible with newer JDKs.

## The migration surface (expected work, by risk)

### 1. Deprecation sweep (do first, on 3.5.16)
Build identity on 3.5.16 surfacing deprecation warnings; fix them so the 4.0 bump starts clean. Expected to be small (Phase 0 already modernized to 3.5). Any that can't be trivially fixed are noted in the inventory.

### 2. Parent bump 3.5.16 → 4.0.7
Change the parent version; keep `<java.version>21</java.version>`. Rebuild and resolve compile/dependency-management breaks (removed APIs, relocated auto-config classes, changed managed versions). Jakarta EE 11 / Hibernate 7 / Bean Validation 3.1 arrive transitively via the starters.

### 3. jjwt-jackson vs Jackson 3 — THE crux
SB 4.0 brings Jackson 3 (`tools.jackson`); `jjwt-jackson 0.12.5` is built on Jackson 2 (`com.fasterxml.jackson.databind`). SB 4.0's BOM no longer manages Jackson 2, so this must be resolved explicitly. Try, in order, and keep the first that yields a clean build + green JWT tests:
- **(a) Coexist:** keep `jjwt-jackson`, explicitly pin a Jackson 2 `jackson-databind` (+ `jackson-core`) version so JJWT's serializer resolves; Jackson 2 (`com.fasterxml.jackson`) and Spring's Jackson 3 (`tools.jackson`) live side-by-side on the classpath.
- **(b) Newer jjwt:** if a jjwt release supports Jackson 3, upgrade jjwt and drop the pin.
- **(c) Swap serializer:** replace `jjwt-jackson` with **`jjwt-gson`** (Gson-based, no Jackson dependency) — the cleanest end state if (a)/(b) are messy. JWT create/parse behavior is unchanged; only the serialization backend differs.

The chosen strategy and its rationale are the single most valuable output for the rollout (identity's jjwt path informs every service that uses jjwt).

### 4. Hibernate 7 / Jakarta EE 11 / Bean Validation 3.1
Small surface (2 entities, standard mappings, `@Valid` DTOs). The existing Testcontainers integration tests (real Postgres boot + JPA round-trips + Flyway) are the safety net. Fix any mapping/validation break they surface. No exact-SQL tests to churn.

### 5. Logging & misc dependency bumps
Bump `logstash-logback-encoder` if SB 4.0's Logback requires it; adjust any other explicitly-pinned dep the 4.0 BOM now conflicts with. Keep the Mockito `-javaagent` argLine (still valid).

### 6. NOT touched in the spike
Dockerfile base images (stay `temurin-21`), CI `java-version` (stays 21), the other 4 services, prod deploy. Those belong to the Java-25 pass and the rollout.

## Method (phases)

1. **Branch + baseline:** create `phase2-sb4-spike-identity`; build + test identity on 3.5.16 to confirm a green starting point and capture deprecation warnings.
2. **Deprecation sweep:** fix captured deprecations on 3.5.16; commit.
3. **Parent bump:** 3.5.16 → 4.0.7; iterate compile fixes; commit when it compiles.
4. **jjwt/Jackson-3 resolution:** apply strategy (a)→(b)→(c) until the JWT unit tests pass; commit the chosen approach.
5. **Green the suite:** run the full identity suite (unit + Testcontainers integration); fix Hibernate-7/validation/config breaks until all pass with pristine output; commit.
6. **Local boot:** build the jar and boot the service locally (container or `java -jar` against a local Postgres) to prove the assembled app starts on 4.0.7, not just the test context; capture the startup log evidence.
7. **Inventory + go/no-go:** write `docs/SB4-SPIKE-FINDINGS.md` — what broke, how it was fixed, the jjwt decision, dep bumps, a per-category effort estimate for the rollout (identity + the Jackson-3-heavy platform/school-core, informed by this spike), and a clear **GO** or **NO-GO** recommendation with reasoning. Commit.

## Deliverables

- `identity-service` on Spring Boot 4.0.7 (Java 21): **compiles, full suite green, boots locally.**
- `docs/SB4-SPIKE-FINDINGS.md`: breaks-inventory + jjwt decision + rollout effort estimate + **go/no-go**.
- A validated branch (`phase2-sb4-spike-identity`). **Not merged/deployed** until the user reviews the go/no-go (the gate).

## Success criteria (the gate)

- `mvn -f services/identity-service/pom.xml test` is **BUILD SUCCESS**, all ≈64 tests green, output pristine (no new warnings introduced by the migration that we can reasonably remove).
- The packaged app **boots** on 4.0.7 locally and serves `/actuator/health` (or equivalent liveness).
- The findings doc contains a definitive **GO** or **NO-GO** with the jjwt decision and a rollout effort estimate.

Reaching a well-documented **NO-GO** (e.g., an unresolvable dependency) is a **successful spike** — it prevents a costly blind rollout.

## Testing

The migration is behavior-preserving; the existing identity suite is the oracle. No new feature tests. Add a test only if a break requires characterizing changed behavior (e.g., if a Jackson-3 serialization difference alters a JWT claim's JSON representation, add an assertion pinning the intended shape). The Testcontainers integration tests must stay green — they are the real-Postgres/JPA/Flyway safety net for Hibernate 7.

## Rollback / risk

- The spike lives on its own branch and is **not deployed**; rollback = abandon the branch. Prod is untouched.
- If phase 4 (jjwt) or phase 5 (suite) exposes a hard blocker, stop and write it up as a **NO-GO** with specifics — do not force a workaround that would be unsafe to roll out.

## Non-goals (YAGNI)

Java 25; Dockerfile/CI changes; the other 4 services; prod deployment; Spring Boot 4.1; GraalVM native / AOT; Jackson-config tuning beyond what's needed to make identity build and pass.
