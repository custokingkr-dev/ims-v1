# Architecture Hardening Changelog

Date: 2026-06-16
Branch: architecture-hardening-local-dev

## Summary

This branch turns the earlier architecture review into concrete code and project changes. It keeps the application as a modular monolith, strengthens local development with Tilt/Compose, hardens authentication and tenant isolation, improves API contracts, splits frontend bundles, restores CI, and adds architecture documentation.

## Branch And Local Workflow

- Created branch `architecture-hardening-local-dev` from `master`.
- Kept the existing local Tilt cleanup on the new branch.
- Preserved local-only Compose/Tilt credentials for development.
- Restored the GitHub Actions / Cloud Build / Cloud Run deployment path.
- Added a CI guard that checks the GCP deployment workflow and Cloud Build configuration are present.

## Backend Runtime And Local Dev

### Changed

- Upgraded Spring Boot parent from `3.4.4` to `3.4.13`.
- Added backend Docker image healthcheck against `http://localhost:8080/actuator/health`.
- Installed `curl` in the backend runtime image for the healthcheck.
- Updated Compose so frontend waits for backend `service_healthy`.
- Set local Compose token lifetimes:
  - `APP_JWT_EXPIRATION_MS=900000`
  - `APP_REFRESH_TOKEN_EXPIRATION_MS=604800000`

### Benefit

The local stack now waits on real backend readiness instead of container start, which makes `tilt up` and `docker compose up` more predictable.

### Trade-off

The backend runtime image is slightly larger because of `curl`.

## Authentication And Session Security

### Changed

- Wired `auth_sessions` into login, refresh, and logout.
- Store SHA-256 token digests instead of raw access/refresh tokens.
- Added refresh-token rotation:
  - first refresh succeeds
  - old refresh token replay returns 401
  - rotated token replaces the previous digest in the same session row
- Logout deletes the matching refresh session and clears the cookie.
- Added JWT `jti` to access and refresh tokens to avoid duplicate token collisions during concurrent logins.
- Added refresh token type validation.
- Updated the JWT auth filter so refresh tokens cannot authenticate normal API requests.
- Reduced default access token lifetime to 15 minutes.

### Tests Added/Updated

- `AuthServiceTest`
  - login persists a session
  - refresh rotates the session
  - reused or revoked refresh token returns unauthorized
  - logout deletes the matching session
- `AuthIntegrationTest`
  - refresh replay is rejected
  - logout invalidates refresh session

### Verification

- Direct backend login: 200
- Frontend proxy login: 200
- Refresh first use: 200
- Old refresh replay: 401
- Backend tests: passed via Dockerized Maven

## Tenant Isolation

### Changed

- Added `TenantAccess` as the centralized school-scope resolver.
- Updated `TenantScopeService` to use active zone-school mappings.
- Deduplicated accessible school IDs for zone admins.
- Changed zone-admin behavior:
  - no requested school uses the first mapped school
  - requested mapped school is allowed
  - requested unmapped school returns 403
- Changed branch-user behavior:
  - no requested school resolves to branch school
  - own branch school is allowed
  - any other requested school returns 403
- Applied centralized tenant resolution to:
  - `WorkspaceService`
  - `StudentService`
  - `FeeService`
  - `AttendanceService`
  - `SupplyOrderService`
  - `FirefightingService`

### Security Fixes

- Fee assignment and payment now verify the target student is in the caller's school scope.
- Attendance day submission no longer locks records outside the caller's school scope.
- Supply and firefighting create/list/detail paths now resolve school access through `TenantAccess`.

### Tests Added

- `TenantAccessTest`
  - branch users cannot switch schools
  - zone admins can access only mapped schools
  - superadmins can remain global or select a school

## API Contracts

### Changed

Converted high-traffic request bodies from raw `Map<String,Object>` in controllers to typed DTOs:

- Attendance
  - `DailyAttendanceRequest`
  - `SubmitAttendanceDayRequest`
- Fees
  - `FeeAssignmentRequest`
  - `FeePaymentRequest`
  - `FeeReminderRequest`
- Firefighting
  - `FirefightingRequestCommand`
  - `FirefightingQuotationRequest`
  - `FirefightingDecisionRequest`
- Supply
  - `CatalogOrderRequest`
  - `OrderStatusUpdateRequest`
  - `OrderReturnRequest`
  - `AnnualPlanItemRequest`

### Benefit

The external API surface is clearer and validation happens earlier. The service layer can now be migrated away from maps one workflow at a time.

### Trade-off

Several service methods still accept maps internally. This branch intentionally avoids combining API typing with a large service rewrite.

## Workflow And State-Machine Hardening

### Changed

- Added server-side firefighting transition checks:
  - only `DRAFT` can be submitted
  - only `AWAITING_BURSAR` can be bursar-approved
  - only `AWAITING_PRINCIPAL` can be principal-approved
  - only `APPROVED` can be custoking-approved
  - only `CUSTOKING_APPROVED` can be fulfilled
  - terminal `REJECTED` and `FULFILLED` states reject further rejection attempts
- Restricted quotation add/update/remove to draft-state flows.

### Benefit

Invalid workflow jumps are rejected by the backend even if the frontend calls endpoints out of order.

### Trade-off

Some existing data with non-standard statuses may need cleanup before these endpoints are used against old production-like datasets.

## Frontend Performance

### Changed

- Ran `npm audit fix` without force to apply compatible security updates in `package-lock.json`.
- Converted protected route pages to `React.lazy`:
  - dashboard workspace
  - school management
  - zone management
- Converted workspace panels to lazy-loaded chunks:
  - students
  - fees
  - fee structure
  - attendance
  - catalog
  - firefighting panels
  - superadmin panels
- Added a stable workspace content fallback while panel chunks load.

### Verification

- `npm run build`: passed.
- Docker frontend image build: passed.
- Served Nginx assets include separate panel chunks.
- `http://localhost`: 200.

### Observed Build Output

- `UnifiedWorkspacePage` chunk: about 64 KB
- Separate panel chunks generated for catalog, fee structure, firefighting, attendance, students, and others.
- `xlsx` remains a large separate chunk at about 430 KB.

## Architecture Guardrails

### Changed

- Added Spring Modulith test dependency for module discovery groundwork.
- Added `ArchitectureGuardrailsTest`:
  - controllers must not import repositories or entities
  - DTOs must not import repositories, entities, services, or controllers
  - feature-domain packages must not depend on controllers, config, or security packages
- Added `ModulithDiscoveryTest` to ensure Spring Modulith can discover the application modules.

### Benefit

The repository now has automated checks for the architectural direction that can pass today and become stricter as legacy packages are migrated.

### Trade-off

The custom guardrails are intentionally lighter than full Modulith verification because the current package layout still has legacy root packages.

## CI And GCP Deployment

### Added

`.github/workflows/ci.yml` with:

- Backend job: `mvn -B -Pci verify`
- Frontend job: `npm ci`, `npm audit --audit-level=critical`, and `npm run build`
- GCP deployment-config job:
  - verifies `.github/workflows/deploy.yml`
  - verifies `cloudbuild.yaml`
  - verifies `deploy/gcp/README.md`
  - checks Cloud Build submission, synchronous deployment behavior, Cloud Run deploy markers, production API URL build arg, production cookie setting, and required Secret Manager bindings
- Container job:
  - `docker compose config`
  - backend image build
  - frontend image build

### Restored

- `.github/workflows/deploy.yml` manual deployment workflow.
- `cloudbuild.yaml` backend/frontend image build, Artifact Registry push, and Cloud Run deploy configuration.
- `deploy/gcp/README.md` production deployment notes.

### Deployment Fixes

- GitHub deploy now waits for Cloud Build completion instead of submitting asynchronously.
- Frontend Cloud Build now uses `VITE_API_BASE_URL=${_BACKEND_PUBLIC_URL}/api/v1` so the Cloud Run frontend calls the Cloud Run backend directly.
- Backend Cloud Run deploy now sets production CORS from `_FRONTEND_PUBLIC_URL`.
- Backend Cloud Run deploy now uses Secret Manager for `APP_JWT_SECRET`, `APP_AADHAR_SECRET`, database password, and Flyway password.
- Backend Cloud Run deploy now sets `APP_COOKIE_SECURE=true` and `APP_COOKIE_SAME_SITE=None` for separate Cloud Run service origins.
- Backend cookie SameSite is configurable through `APP_COOKIE_SAME_SITE`, while local development still defaults to `Strict`.

### Still Not Reintroduced

- local shell start/stop/reset scripts

## Documentation

### Added

- `docs/HLD-architecture-hardening.md`
- `docs/CHANGELOG-architecture-hardening.md`

## Verification Performed Locally

- Backend Docker compile/package: passed.
- Backend unit tests through Maven Docker image: passed.
- Frontend TypeScript/Vite build: passed.
- Docker Compose config validation: passed.
- Docker Compose stack: running.
- Backend actuator health: 200.
- Direct backend login: 200.
- Frontend proxy login: 200.
- Refresh rotation:
  - first refresh: 200
  - replay old refresh cookie: 401.
- Frontend Nginx root: 200.
- Frontend image contains lazy-loaded panel chunks.

## Known Follow-Ups

- Run full `mvn -Pci verify` on a host/CI runner where Testcontainers can access Docker.
- Review frontend dependency risk:
  - Vite/esbuild needs a breaking major upgrade to clear the remaining esbuild high advisory.
  - `xlsx` has no patched npm version for the remaining high advisories; consider replacing it or isolating it behind stricter import validation.
- Continue migrating services away from map-based internals.
- Break down `UnifiedWorkspacePage.tsx` further into hooks and smaller orchestration modules.
- Add session/device listing and targeted session revocation.
- Add login/account throttling backed by a distributed store for production.
- Add OpenTelemetry traces and operational dashboards.
- Revisit PostgreSQL RLS after tenant isolation integration tests are comprehensive.
