# Spring Boot 4.0 Rollout (4 services) — Design (Task 2.4, rollout phase)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 2, Task 2.4 — "…then rolled out." Follows the identity spike (GO): `docs/SB4-SPIKE-FINDINGS.md`.

**Scope:** migrate the remaining 4 Java services — **billing, operations, platform, school-core** — from Spring Boot 3.5.16 to **4.0.7 while staying on Java 21**, one PR per service in 2 risk-ordered waves, deploying each to prod incrementally. identity is already on 4.0.7 in `main` (PR #37) but not yet deployed to prod. The **Java 21→25 pass is out of scope** (separate later effort).

## Decisions (locked during brainstorming)

1. **Per-service PRs, 2 waves.** Wave 1 (mechanical): billing → operations. Wave 2 (code migration): platform → school-core. Each service branched, validated, PR'd, and merged independently — cleanest per-service rollback; the easy services confirm the recipe generalizes before the hard ones.
2. **Incremental prod deploy.** After each service's PR merges + CI is green, deploy that service's SB4 image to prod, then verify. Start by deploying the already-merged **identity**. Mixed SB4/SB3.5 fleet during the rollout is safe (services are independent — own schema, HTTP-only cross-service). Rollback = redeploy the prior Cloud Run revision (or flip nothing — just redeploy previous image tag).
3. **Stay on Java 21** (SB 4.0.7 + Java 21 is a supported combo). Java 25 is a later pass.

## The recipe (proven on identity, `docs/SB4-SPIKE-FINDINGS.md`)

Applied per service, sized by the measured per-service fingerprint:

| Service | parent→4.0.7 | +`spring-boot-flyway` | Testcontainers rename | +`spring-boot-restclient` | Jackson-3 code | Size |
|---|---|---|---|---|---|---|
| **billing** | ✓ | ✓ | — (no TC) | — | — | **XS** |
| **operations** | ✓ | ✓ | ✓ | — | — | **S** |
| **platform** | ✓ | ✓ | ✓ | ✓ (1 caller) | **5 files** | **L** |
| **school-core** | ✓ | ? (investigate — see Wave 2) | ✓ | — | **2 files** + custom Flyway re-validation | **L** |

- **`spring-boot-flyway`** — SB 4.0 moved `FlywayAutoConfiguration` into this module; without it Flyway doesn't run at startup → `ddl-auto: validate` fails (`missing table …`). All 4 use Flyway.
- **Testcontainers artifactId rename** — SB 4.0.7 pulls the Testcontainers **2.0.5** BOM: `org.testcontainers:postgresql` → `testcontainers-postgresql`, `org.testcontainers:junit-jupiter` → `testcontainers-junit-jupiter` (groupId unchanged; Java packages `org.testcontainers.*` unchanged). Applies to operations, platform, school-core. **billing has no Testcontainers** → skip.
- **`spring-boot-restclient`** — SB 4.0 moved `RestClientAutoConfiguration` here; needed only by services injecting `RestClient.Builder`. Only **platform** (its Msg91 provider) qualifies.
- **Jackson-3 code migration** — SB 4.0 renamed Jackson `com.fasterxml.jackson` → `tools.jackson` (except `jackson-annotations`). platform (5 main files: PubSub push controllers, notification delivery/inbox, Msg91 provider) and school-core (2 main files: `Json.java`, `StudentReadRepository`) directly use `com.fasterxml.jackson.*` and need import/API renames + awareness that Jackson 3 auto-registers all classpath modules.
- **No jjwt** in any of the 4 → the identity Jackson-2 pin does not replicate.

## Per-service task shape (the unit of work)

Each service is one PR built as:
1. **Branch** `phase2-sb4-<service>` off `main`.
2. **Baseline green** on 3.5.16 — record the service's test count (oracle).
3. **Apply the recipe** for that service (pom edits; + code migration for platform/school-core).
4. **Full suite green** on 4.0.7 — the Testcontainers integration tests are the Hibernate-7 + Flyway-module safety net; test count matches baseline. Behavior-preserving (Flyway migrations authoritative; don't weaken tests). For Jackson-migrated files, characterize any JSON-shape difference rather than deleting a test.
5. **Local boot** — packaged app boots on 4.0.7, `/actuator/health` UP.
6. **PR + CI green** → **merge to main**.
7. **Deploy to prod** (`gh workflow run deploy.yml … deploy_services=<service> run_direct_smoke=false`) → confirm `Ready=True` + health; then an authenticated route returns real data (RLS/enforce intact) using the e2e test creds (`e2e-superadmin@local.test` / `password`).

## Wave 1 — mechanical

- **Deploy identity first** (already merged, most-validated) as the initial prod SB4 proof: deploy → verify health + an authenticated route (schools/students still return data via the gateway).
- **billing**: parent + `spring-boot-flyway` only. No TC/Jackson/RestClient. Expect pom-only, suite green, boot, merge, deploy, verify a billing route (e.g. `/api/v1/sa/invoices` returns 200/expected).
- **operations**: + Testcontainers rename. Verify a workflow/firefighting route post-deploy.

Wave 1 also **re-confirms the recipe generalizes** (Flyway/RestClient module split, TC rename) before the harder services.

## Wave 2 — real code migration

- **platform**:
  - pom recipe + `spring-boot-restclient`.
  - Rename `com.fasterxml.jackson` → `tools.jackson` across the 5 files; adjust any Jackson-2-specific API (e.g. `ObjectMapper` config, module registration) to the Jackson-3 equivalent.
  - **Characterization pass**: Jackson 3 auto-registers all classpath modules (vs Jackson 2's opt-in) — verify notification/PubSub/reporting JSON (de)serialization shape is unchanged; add/adjust assertions to pin the intended shape where Jackson 3 legitimately differs. Don't weaken tests.
  - Suite green (incl. the Pub/Sub push + notification tests), boot, merge, deploy, verify reporting/notification routes.
- **school-core**:
  - pom recipe (parent + TC rename) + Jackson-3 in the 2 files. **Whether `spring-boot-flyway` is needed at all is part of the investigation** — unlike identity (which relied on auto-configured Flyway), school-core drives Flyway itself via `SchoolCoreFlywayConfig`'s 5 `@Bean(initMethod="migrate")` instances, so it may not depend on `FlywayAutoConfiguration`.
  - **Investigate-then-fix the custom Flyway config** (`SchoolCoreFlywayConfig`, 5 hand-declared multi-schema `Flyway` `@Bean`s for the RLS/tenant-key integration tests). Determine the SB 4.0 interaction and pick the cleanest working state:
    - (a) **Do not add `spring-boot-flyway`** — the custom beans already run migrations directly; if the suite (esp. the multi-schema integration tests) is green without it, that's the minimal end state.
    - (b) If some path needs the auto-config present, add `spring-boot-flyway` and rely on Spring Boot's Flyway auto-config being `@ConditionalOnMissingBean(Flyway.class)` so it backs off in the presence of the custom beans (verify this holds in SB 4.0).
    - (c) If the auto-config does not back off and conflicts, exclude `FlywayAutoConfiguration` (`@SpringBootApplication(exclude=…)` / `spring.autoconfigure.exclude`), keeping the custom beans as the sole drivers.
    Do NOT change the Flyway migrations or the multi-schema behavior. This is the spike's flagged biggest unknown — discover-and-fix with a clear writeup, validated by the multi-schema integration tests staying green.
  - Suite green (incl. the multi-schema RLS integration tests), boot, merge, deploy, verify schools/students/fees/supply routes.

## Success criteria (per service)

- `mvn -f services/<svc>/pom.xml test` BUILD SUCCESS, test count == that service's 3.5.16 baseline, output pristine.
- Packaged app boots on 4.0.7, `/actuator/health` UP.
- CI green on the PR; merged to main.
- Prod deploy `Ready=True`; health UP; an authenticated route returns real data (no 5xx; RLS/enforce intact).

## Testing

Behavior-preserving migration; each service's existing suite is the oracle. Add tests only to characterize genuinely-changed Jackson-3 serialization shapes (platform/school-core). The Testcontainers integration tests must stay green (real-Postgres/JPA/Flyway/RLS safety net) — especially school-core's multi-schema tests, which validate the custom Flyway config under SB 4.0.

## Rollback / risk

- Each service is an independent PR + independent Cloud Run deploy. If a prod deploy misbehaves, redeploy the prior revision/image for that one service — the rest of the fleet is unaffected.
- If platform's Jackson-3 migration or school-core's Flyway config proves unsafe, stop that service's PR and report — the already-migrated services stay fine (mixed fleet is safe).
- The mixed SB4/SB3.5 fleet during rollout is explicitly safe: services share no framework runtime, only HTTP `/api/v1/**` (version-agnostic) and per-service schemas.

## Non-goals (YAGNI)

Java 25 (separate later pass — Dockerfiles temurin-21→25, CI java-version 21→25, `<java.version>`); the Node gateway and React frontend (unaffected by a Java framework bump); Spring Boot 4.1; GraalVM native/AOT; any behavior change beyond what the migration forces.
