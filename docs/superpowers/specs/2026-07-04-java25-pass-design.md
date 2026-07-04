# Java 21 → 25 Pass — Design (Task 2.4, final Phase-2 item)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 2, Task 2.4 — the Java runtime bump, deferred until after the Spring Boot 4.0 rollout (now complete: all 5 services on SB 4.0.7 / Java 21 in prod).

**Scope:** move all 5 Java services from Java 21 → **Java 25** (JVM runtime + compile target + CI + Docker images), staying on Spring Boot 4.0.7. The Node api-gateway and React frontend are unaffected (not Java). **Pure version bump — no application code changes** (the only code touch permitted is a dependency-version fix if the canary surfaces a Java-25 tooling incompatibility).

## Decisions (locked during brainstorming)

1. **Canary CI-first, then full bump** (two PRs). PR 1 bumps only CI `java-version` to 25 (poms/Dockerfiles stay 21) to run every service's existing tests on the JVM 25 runtime and isolate the tooling risk. PR 2 bumps the 5 poms `<java.version>` and 5 Dockerfiles (build + runtime images) to 25.
2. **Incremental prod deploy** per service after PR 2 merges (matches the SB 4.0 rollout). Mixed Java-21/25 fleet during deploy is safe (independent services). Rollback = redeploy the prior Cloud Run revision.
3. **Stay on Spring Boot 4.0.7** — SB 4.0 has first-class Java 25 support; no framework change.

## Background — current state (measured) & Java 25 facts

- **5 poms** at `<java.version>21</java.version>` (the SB parent property that drives `maven.compiler.release` = source/target). All 5 use the Mockito javaagent in `maven-surefire-plugin` (`-javaagent:…/mockito-core-${mockito.version}.jar`).
- **5 Dockerfiles**, each two-stage: build `maven:3.9.9-eclipse-temurin-21`, runtime `eclipse-temurin:21-jre`.
- **CI:** `.github/workflows/ci.yml:56` and `.github/workflows/whole-application-validation.yml:54` set `java-version: 21` (a single shared value across the service matrix — cannot canary one service on 25 while others stay 21).
- **Local:** **JDK 25.0.3 LTS is installed** at `C:\Program Files\Java\jdk-25.0.3` (verified `java -version` → 25.0.3+9-LTS). JDK 21.0.11 remains at `C:\Program Files\Java\jdk-21.0.11`. So local `mvn test` on Java 25 is now possible (tighter loop than CI-only).
- **Image tags:** `eclipse-temurin:25-jre` and `25-jdk` exist (Temurin 25.0.3 GA). The maven build-image tag (`maven:<ver>-eclipse-temurin-25`) is confirmed at execution via `docker pull` — `maven:3.9.9-eclipse-temurin-21` is the current tag; the temurin-25 equivalent may be `maven:3.9.9-eclipse-temurin-25` or a newer maven patch (e.g. `maven:3.9-eclipse-temurin-25`). Pin a tag that resolves.

## The primary risk

**Bytecode tooling on JVM 25.** Java 25 emits/loads class-file **major version 69**. The Mockito javaagent + its ByteBuddy backend, and Testcontainers, must support Java 25. SB 4.0.7's BOM manages Mockito/ByteBuddy versions and already targets Java 25, so the managed versions *should* be Java-25-ready — but this is exactly what the canary (PR 1) validates by running every suite (incl. the Mockito-agent and Testcontainers integration tests) on JVM 25. If a suite fails with a ByteBuddy/Mockito "unsupported class file major version 69" (or similar), the fix is a version bump of the offending tool (prefer letting the SB BOM manage it; else an explicit `<mockito.version>` / `<byte-buddy.version>` property override) — applied in PR 1, once, for all services.

## Components

### PR 1 — Canary (CI java-version only)
- Change `java-version: 21` → `25` in `.github/workflows/ci.yml` and `.github/workflows/whole-application-validation.yml`.
- Nothing else. poms stay `<java.version>21</java.version>` (code still compiles to Java 21 bytecode), Dockerfiles stay temurin-21.
- **Effect:** CI's `setup-java` provisions JDK 25 and runs `mvn test` for all 5 services on the JVM 25 runtime — proving the Mockito-agent / ByteBuddy / Testcontainers tooling runs on Java 25 while the build target is unchanged.
- **Gate:** all `service-test (*)` jobs green on CI. If a tooling incompat surfaces, fix the tool version here (BOM-managed bump or explicit property) and re-run until green.
- Local pre-check (optional, now possible): run one representative service's suite locally on JDK 25 (`JAVA_HOME=…jdk-25.0.3`) before pushing.

### PR 2 — Full bump (poms + Dockerfiles)
- **5 poms:** `<java.version>21</java.version>` → `25` (makes javac target Java 25 / class-file 69).
- **5 Dockerfiles:** build stage `maven:3.9.9-eclipse-temurin-21` → `maven:<confirmed>-eclipse-temurin-25`; runtime `eclipse-temurin:21-jre` → `eclipse-temurin:25-jre`.
- **Gate:** CI green (now compiling *to* Java 25 on JVM 25) + all 5 `docker-build (*)` jobs succeed (the in-container maven-temurin-25 build compiles + packages, and the temurin-25-jre runtime image assembles).
- Local validation: build+test each (or a representative subset) on JDK 25 locally, plus a docker boot of one service, before/after the PR.

### Deploy (per service, after PR 2 merges)
- `gh workflow run deploy.yml … deploy_services=<svc> run_direct_smoke=false` for each of the 5, in a low-risk order (billing → operations → identity → platform → school-core).
- After each: `Ready=True` + health, and an authenticated route returns real data through the gateway (school-core: the full 5-schema data path + admin RLS-scope check `/students` 200 / `/schools` 403).
- Rollback per service = redeploy the prior revision.

## Per-service / per-PR shape

- PR 1 = one CI change, validated by CI (+ optional local JDK-25 spot check). One commit.
- PR 2 = one coordinated commit touching 5 poms + 5 Dockerfiles, validated by local JDK-25 build/test + CI + docker-build, then incremental deploy.
- No per-service branches this time (unlike the SB4 rollout) — the change is a uniform version bump with a shared CI gate, so two focused PRs are the right granularity. (If PR 2's CI/docker-build fails for a specific service, fix that service in the same PR.)

## Success criteria

- PR 1: all CI `service-test` jobs green on JDK 25 (tooling proven on JVM 25).
- PR 2: all 5 poms + 5 Dockerfiles on Java 25; CI green (compiling to Java 25); all `docker-build` jobs succeed; local `mvn test` green on JDK 25 for the services checked.
- Deploy: each service `Ready=True`, health UP, authenticated routes return real data; school-core RLS scope intact (`/students` 200 scoped, `/schools` 403).
- `grep <java.version>` shows `25` in all 5 poms; no `temurin-21` / `21-jre` remain in Dockerfiles; no `java-version: 21` remains in CI.

## Testing

Behavior-preserving; each service's existing suite is the oracle (unchanged test counts). No new tests — this is a runtime/target bump. The canary specifically exercises the Mockito-agent + Testcontainers paths on JVM 25. Do not weaken/skip any test to pass on Java 25; a genuine failure is a tooling-version fix, not a test change.

## Rollback / risk

- PR 1 is CI-only (no artifact/deploy change) — trivially revertable.
- PR 2's prod exposure is per-service and incremental; redeploy the prior Cloud Run revision for any service that misbehaves. The mixed Java-21/25 fleet during the deploy window is safe.
- If a Java-25 tooling issue is unresolvable without a risky change, stop and report — Java 21 remains fully working (revert the PRs).

## Non-goals (YAGNI)

Spring Boot 4.1; GraalVM native / AOT; the Node gateway and React frontend; any application code change beyond a forced tooling-version bump; changing `maven-surefire` config beyond a version property if required.
