# Final Review Fixes Report — TenantContext Branch

## Fix A — applyResolvedSchool helpers hardened (9 total)

All helpers now wrap `Long.valueOf(String.valueOf(...))` in `try/catch (NumberFormatException)` throwing `ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId")` instead of propagating a 500.

| File | Return type |
|------|-------------|
| `services/student-service/.../StudentReadController.java` | void |
| `services/student-service/.../StudentWorkspaceCompatibilityController.java` | void |
| `services/attendance-service/.../AttendanceReadController.java` | Long (variant) |
| `services/fee-service/.../FeeReadController.java` | void |
| `services/catalog-service/.../CatalogReadController.java` | void |
| `services/catalog-service/.../CatalogPublicCompatibilityController.java` | void |
| `services/workflow-service/.../WorkflowReadController.java` | void |
| `services/firefighting-service/.../FirefightingReadController.java` | void |
| `services/firefighting-service/.../FirefightingPublicCompatibilityController.java` | void |

## Fix B — TenantScopeTest copied to 4 services

- `services/attendance-service/.../security/TenantScopeTest.java` (package attendanceservice)
- `services/fee-service/.../security/TenantScopeTest.java` (package feeservice)
- `services/catalog-service/.../security/TenantScopeTest.java` (package catalogservice)
- `services/workflow-service/.../security/TenantScopeTest.java` (package workflowservice)

## New 400 test

Added `malformedSchoolId_inRequestBody_returns400` to `StudentTenantScopingTest`:
POST `/api/v1/students` with body `{"schoolId":"abc"}` under SUPERADMIN context -> HTTP 400; createStudent never called.

## Per-service test results

| Service | Tests run | Failures | Result |
|---------|-----------|----------|--------|
| student-service | 28 | 0 | BUILD SUCCESS |
| attendance-service | 16 | 0 | BUILD SUCCESS |
| fee-service | 29 | 0 | BUILD SUCCESS |
| catalog-service | 18 | 0 | BUILD SUCCESS |
| workflow-service | 14 | 0 | BUILD SUCCESS |
| firefighting-service | 30 | 0 | BUILD SUCCESS |
