# Spring Boot 4.0 Spike — Findings & GO/NO-GO (identity-service)

**Spike branch:** `phase2-sb4-spike-identity` · **Target:** Spring Boot `4.0.7` on Java `21` (Java 25 explicitly out of scope — see below)

## Outcome: **GO**

`identity-service` was migrated from Spring Boot `3.5.16` → `4.0.7` on Java 21 with **pom-only
changes** — zero edits to entities, Flyway migrations, `application.yml`, or business logic.
Result: compiles clean, **64/64 tests green (exact match of the pre-migration baseline)**, and the
packaged application **boots and reports healthy** (`Started IdentityServiceApplication`, SB banner
`v4.0.7`, Hibernate `7.2.19.Final`, Flyway migrations validated, `/actuator/health` → `UP`).

The upgrade is mechanically well-understood: every break found has a known, narrow, additive fix.
Recommend proceeding to plan the rollout to the other 4 domain services.

---

## What changed in identity (all in `services/identity-service/pom.xml`; no source/entity/migration/yml touched)

1. **Parent bump:** `spring-boot-starter-parent` `3.5.16` → `4.0.7`. `<java.version>` left at `21`.
2. **Testcontainers artifactId rename** — SB 4.0.7's BOM pulls the Testcontainers **2.0.5** BOM,
   which renamed its Maven artifacts:
   `org.testcontainers:postgresql` → `testcontainers-postgresql`,
   `org.testcontainers:junit-jupiter` → `testcontainers-junit-jupiter` (groupId unchanged, no
   explicit version — still BOM-managed). Java package names (`org.testcontainers.containers.*`)
   are unchanged — imports needed no edits.
3. **jjwt / Jackson 3 — the crux, resolved as strategy (a): pin Jackson 2 explicitly.**
   SB 4.0's BOM manages Jackson **3** (`tools.jackson.core:*`) and has nothing to say about
   Jackson **2** (`com.fasterxml.jackson.core:*`). `jjwt-jackson:0.12.5` (identity's token
   serializer) needs Jackson 2. Because the two majors live under disjoint Maven
   groupIds/package namespaces, they coexist on the classpath with **no conflict at all** —
   Maven simply resolves jjwt-jackson's own declared transitive Jackson-2 version
   (confirmed 2.21.4) with no version-mediation surprise. Tests passed with *zero* pom change.
   Rather than leave that coexistence to implicit transitive resolution, an explicit pin was
   added for reproducibility:
   ```xml
   <dependency>
     <groupId>com.fasterxml.jackson.core</groupId>
     <artifactId>jackson-databind</artifactId>
     <version>2.18.2</version>
   </dependency>
   ```
   Strategies (b) newer-jjwt and (c) jjwt-gson swap were not attempted — (a) was clean on the
   first pass and adds no new dependency (jjwt-jackson already required a Jackson-2 databind
   transitively either way).

   **Refinement to apply during rollout**, since this pin gets replicated to every jjwt-using
   service:
   - Mark the pin `scope=runtime` — identity code never imports Jackson 2 directly, only jjwt's
     internal serializer needs it on the runtime classpath.
   - Resolve a version skew: only `jackson-databind` was pinned (to `2.18.2`), while jjwt itself
     transitively pulls Jackson-2 `2.21.4` for `jackson-core`/`jackson-annotations`. Either (i)
     drop the explicit pin entirely and let jjwt's self-consistent transitive set resolve on its
     own (simplest — no conflict was ever observed), or (ii) if an explicit pin is preferred for
     reproducibility, pin all three Jackson-2 artifacts via `com.fasterxml.jackson:jackson-bom` at
     one version rather than a single artifact. Rollout should verify which is cleaner before
     replicating.
4. **SB 4.0 auto-configuration modularization — the biggest, most consequential finding.**
   SB 4.0 split per-technology auto-configuration out of the old monolithic
   `spring-boot-autoconfigure` / `spring-boot-starter-web` into separate opt-in modules:
   - **`org.springframework.boot:spring-boot-flyway`** — `FlywayAutoConfiguration` moved here.
     Without it, Flyway silently does **not** run at context startup (even though `flyway-core`
     is on the classpath and works fine when invoked directly, e.g. in plain-JUnit migration
     tests) — Hibernate's `ddl-auto: validate` then fails with `missing table [app_users]`
     because the auto-wired "flyway bean must run before entityManagerFactory" dependency was
     never registered. **Every Flyway-using service (all 5) needs this dependency.**
   - **`org.springframework.boot:spring-boot-restclient`** — `RestClientAutoConfiguration` moved
     here. Without it, `RestClient.Builder` is not available for injection, breaking any
     collaborator built on `RestClient` (identity's `TenantSchoolClient` hit this once Flyway was
     fixed and the context progressed further). **Any service making cross-service HTTP calls via
     `RestClient` needs this.**

   Both fixes are additive `<dependency>` blocks with no explicit version (BOM-managed) and
   required no code changes.

---

## Deprecations cleared (Task 1)

**None found or fixed.** identity-service's own source was already clean on 3.5.16 — no
`WebSecurityConfigurerAdapter`, `antMatchers`, `authorizeRequests`, deprecated `RestTemplateBuilder`
methods, or `@SuppressWarnings("deprecation")` anywhere in main or test code; the security config
already used the modern lambda DSL. The one `javax.crypto.SecretKey` import in `JwtService.java` is
JDK JCA (never part of the Jakarta EE namespace migration) and is not a deprecation. Any
deprecations surfaced by the 4.0 bump itself were compile/runtime breaks, not lingering 3.5-era
deprecated-API usage, and are all covered above.

---

## Test + boot evidence

- **Baseline (3.5.16):** `Tests run: 64, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
- **Post-migration (4.0.7):** `Tests run: 64, Failures: 0, Errors: 0, Skipped: 0` — BUILD SUCCESS.
  Exact match, per-class breakdown identical (compat controllers, controllers, tenant scoping,
  RBAC, user directory validation, auth-service rotation/reuse-commit, tenant-school client,
  auth-session migration, JWT service, tenant scope — 11 classes, 64 tests).
- **Local Docker boot** (in-container Maven build, `maven:...-temurin-21` → `eclipse-temurin:21-jre`,
  unchanged Dockerfile):
  ```
  :: Spring Boot ::                (v4.0.7)
  ... Started IdentityServiceApplication in 7.681 seconds ...
  ... Successfully validated 3 migrations ...
  ... Schema "identity" is up to date. No migration necessary.
  HHH000001: Hibernate ORM core version 7.2.19.Final
  ```
  `curl http://localhost:8083/actuator/health` → `{"status":"UP", ...}` (db, diskSpace, liveness,
  ping, readiness, ssl all `UP`).

---

## Per-service rollout effort estimate

Sizing based on inspecting each service's `pom.xml` and source tree read-only during this spike
(no changes made to any service besides identity).

| Service | Size | Reasoning |
|---|---|---|
| **billing-service** | **S** | Uses Flyway (needs `spring-boot-flyway`) and Testcontainers in tests (artifactId rename). No jjwt (only identity mints tokens). No direct `RestClient`/Jackson usage found. Same mechanical parent-bump + two dependency additions as identity, no code changes expected. |
| **operations-service** | **S** | Same profile as billing: Flyway (`spring-boot-flyway`) + Testcontainers rename. No jjwt, no direct `RestClient` or `com.fasterxml.jackson` hits found. Mechanical. |
| **platform-service** | **L** | Flyway + Testcontainers rename, **plus** direct `RestClient` usage (`Msg91NotificationDeliveryProvider` — needs `spring-boot-restclient`), **plus** the largest direct-Jackson footprint: 5 main-source files import `com.fasterxml.jackson.*` (`PubSubPushController`, `ReportingPubSubPushController`, `NotificationDeliveryService`, `NotificationInboxProcessor`, `Msg91NotificationDeliveryProvider`) plus matching tests. These need real code migration — `com.fasterxml.jackson` → `tools.jackson` imports/API, verifying JSON (de)serialization shape is unchanged, and accounting for Jackson 3's "auto-register all classpath modules" behavior (a behavior change vs. Jackson 2's opt-in module registration, worth a characterization pass on notification/PubSub payloads). This is genuine code work, not just pom edits. |
| **school-core-service** | **L** | Flyway + Testcontainers rename, plus 2 main-source files with direct Jackson usage (`Json.java`, `StudentReadRepository`) needing the same `tools.jackson` migration (smaller surface than platform, but still real code, not pom-only). **Additionally** carries a custom multi-schema Flyway setup (`SchoolCoreFlywayConfig`, 5 hand-declared `Flyway` `@Bean`s backing the RLS/tenant-key integration tests across attendance/catalog/fee/student schemas) that must be re-validated once `spring-boot-flyway`'s auto-configuration is in play — risk that the custom bean wiring interacts with (or is now redundant with / conflicts with) the new auto-configured `Flyway` bean and its `entityManagerFactory` dependency edge. This needs deliberate re-verification, not just a dependency add, and no jjwt-comparable "known good pattern" from identity to lean on here. |

No service other than identity uses `jjwt` (confirmed via pom search) — the jjwt/Jackson-2 pin
decision does not need replicating anywhere else in the rollout.

**Recommended rollout order (easy → hard):** billing-service → operations-service →
platform-service → school-core-service. Billing and operations are expected to be near-identical,
low-risk repeats of identity's mechanical fix (parent bump + 2 dependency additions + Testcontainers
rename). Platform and school-core should go last because they carry genuine code changes
(Jackson 3 migration) and, for school-core, a custom Flyway configuration that needs careful
re-validation rather than a drop-in dependency fix. Doing the two easy services first also
re-confirms the "Flyway/RestClient module-split" fix generalizes before spending effort on the two
harder services.

---

## Risks / unknowns for the rollout

- **Jackson 3 auto-registration behavior** (platform, school-core): Jackson 3's `ObjectMapper`
  auto-registers all classpath modules by default, a change from Jackson 2's more opt-in module
  registration. Needs a characterization pass on notification/PubSub/reporting JSON payloads to
  confirm no field-shape or (de)serialization behavior drifts silently.
- **school-core's custom multi-schema Flyway beans**: not exercised at all during this spike
  (identity has no analogous construct). The interaction between `SchoolCoreFlywayConfig`'s 5
  explicit `Flyway` beans and the newly-required `spring-boot-flyway` auto-configuration module is
  an open question — could double-migrate, could deadlock on bean ordering, or could simply work;
  this is the single biggest unknown in the whole rollout and should be investigated early in that
  service's task, not assumed safe.
- **jjwt pin refinement** (identity only, but worth finalizing before it's held up as the
  reference pattern): the scope/version-skew cleanup described above should be resolved and
  re-verified before treating it as "the" template, even though no other service currently needs it.
- **Hibernate 7 / dialect edge cases**: this spike only exercised identity's schema (2 tables,
  simple mappings). Other services' entity mappings, custom types, or dialect-specific SQL were not
  exercised under Hibernate 7 and could surface new issues not seen here.
- **Testcontainers 2.0.5**: only the artifactId rename was needed for identity's usage
  (`PostgreSQLContainer`, `@Testcontainers`, `@Container`, `DockerClientFactory`). Other services'
  Testcontainers usage (school-core's RLS/tenant-key integration tests in particular) should be
  re-checked for any other 1.x → 2.0 API surface changes beyond the artifact rename, since this
  spike didn't need to touch container-usage code itself.
- **No behavior/perf regression testing was in scope for this spike** — only "compiles, unit/
  integration suite green, boots and reports healthy" was verified. Broader smoke/perf testing
  should still run per-service during the actual rollout, per the standard checklist in `DEVELOPMENT_GUIDE.md`.

---

## Java 25 pass — explicitly deferred, separate follow-on

Java 21 was kept unchanged throughout this spike (`<java.version>21</java.version>`), by design —
the spike isolates the Spring Boot 4.0 upgrade from any JDK bump. A follow-on Java 21 → 25 pass is
unblocked once SB 4.0 is rolled out, and is its own separate piece of work:
- Dockerfiles: `temurin-21` (build + runtime stages) → `temurin-25` (or whatever LTS-equivalent tag
  is chosen) across all 5 services + build stages.
- CI: `.github/workflows/ci.yml` `java-version` matrix entries, `21` → `25`.
- Each service's `pom.xml` `<java.version>` (or `<maven.compiler.release>`), `21` → `25`.

This should get its own spec/plan and is explicitly **not** part of this GO decision — the GO here
covers Spring Boot 4.0.7 on Java 21 only.

---

## Non-goals confirmed unaffected

No Java 25, no other services touched, no production deployment, no Spring Boot 4.1 or native-image
work was attempted or is implied by this GO — those remain out of scope until separately planned.
