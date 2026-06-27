# Production Hardening: Resilience & Operability Plan

Created: 2026-06-28

**Goal:** Close the locally-implementable, locally-testable Phase 7 (resilience &
operability) gaps from `docs/PRODUCTION_READINESS_OVERHAUL_PLAN.md` that remain after
inter-service timeouts and readiness/liveness probes were completed. Each phase is
implemented, tested, committed, then the next phase begins.

**Scope boundary:** This plan covers only code/config that can be verified locally with
unit tests and the existing audit gate. It explicitly does NOT touch production GCP
(Cloud Monitoring alert policies, Cloud Run autoscaling flags applied to live revisions,
per-service Cloud SQL DB users) or browser E2E (Playwright), which require environment
mutation or a running stack and are tracked separately.

## Already done (verified, not repeated here)
- Inter-service connect/read timeouts + 504 translation (`TenantSchoolClient`).
- Liveness/readiness probes enabled on all 12 Spring services (`actuator`, `probes.enabled`).

---

## Phase 1: Bounded retry for idempotent inter-service reads

The `TenantSchoolClient` now fails fast on timeout, but a single transient blip
(connection refused during a rolling deploy, a one-off 502/503/504) immediately surfaces
to the caller. Add a small bounded retry for the **idempotent GET** path only
(`school`, `zone`). Never retry the POST commands.

- Retry only on transient faults: `ResourceAccessException` (connect/read failure) and
  upstream 502/503/504.
- Do NOT retry on 4xx (including 404) — those are deterministic.
- Cap attempts (default 3 total) with a short fixed backoff; both tunable via config.
- Pure-Java retry loop (no new dependency) to keep the service lean.

Test: extend `TenantSchoolClientTest` with a real local `HttpServer` that fails the
first N times then succeeds (asserts eventual success), and a case that exhausts retries
(asserts the translated error). Run `identity-service` tests.

Exit: identity tests green; retries observable in the test.

---

## Phase 2: Standardized database connection-pool bounds

Each service uses the default HikariCP pool with no explicit bounds, so pool sizing is
implicit and inconsistent. Set explicit, env-tunable pool bounds (max pool size, min
idle, connection/validation timeouts) in every service `application.yml`, with sane
production defaults. Add `scripts/audit-service-datasource-pool.ps1` asserting every
Java service declares the pool bounds, and wire it into
`scripts/verify-microservice-migration.ps1` so the bound stays enforced.

Test: targeted compile/test for a couple of services; the new audit script; full
`verify-microservice-migration.ps1`.

Exit: pool audit passes; full verifier green.

---

## Phase 3: Final certification

- Run the full shared test catalog (`scripts/invoke-microservice-tests.ps1`).
- Run the full `scripts/verify-microservice-migration.ps1`.
- Append a dated progress-log entry to `PRODUCTION_READINESS_OVERHAUL_PLAN.md`.
- Produce a consolidated summary of all changes across the plan.

Exit: 14/14 test catalog green; all audits green.
