# Monolith Route Ownership Map

Date: 2026-06-26

This map tracks public `/api/v1/**` routes that were previously served through `backend/` and the extracted service that must own them before `backend/` can be deleted.

| Public path | Method | Current gateway target | Backend controller if any | Target service | Status | Required action |
|---|---:|---|---|---|---|---|
| `/api/v1/auth/**` | mixed | backend | `AuthController` | identity-service | service-owned | Route gateway directly to identity. |
| `/api/v1/rbac/**` | mixed | backend | `RbacController` | identity-service | service-owned | Route gateway directly to identity. |
| `/api/v1/users/**` | mixed | backend | `UserController` | identity-service | service-owned | Route gateway directly to identity. |
| `/api/v1/schools/**` | mixed | backend | `SchoolController`, `ModuleEntitlementController` | tenant-school-service | service-owned | Route gateway directly to tenant-school. |
| `/api/v1/zones/**` | mixed | backend | `ZoneController` | tenant-school-service | service-owned | Route gateway directly to tenant-school. |
| `/api/v1/classes/**` | GET | backend | `FeeCollectionController` | tenant-school-service/student-service | backend-proxy | Route class/section metadata to tenant-school; route roster to student-service with query rewrite. |
| `/api/v1/students/**` | mixed | backend | `StudentPhotoController`, `StudentImportController` | student-service | backend-proxy | Add legacy import/template compatibility and route directly to student-service. |
| `/api/v1/workspace/students` | POST | backend | `WorkspaceController` | student-service | backend-proxy | Route to student-service `POST /api/v1/students`. |
| `/api/v1/workspace` | GET | backend | `WorkspaceController` | reporting-service | backend-logic | Needs service-side compatibility workspace summary or frontend refactor. |
| `/api/v1/workspace/fees/record-payment` | POST | backend | `WorkspaceController` | fee-service | backend-proxy | Add or route to fee-service payment compatibility. |
| `/api/v1/workspace/timetable` | POST | backend | `WorkspaceController` | tenant-school-service | obsolete | Backend returned placeholder; route can return accepted placeholder only if frontend still calls it. |
| `/api/v1/workspace/staff` | POST | backend | `WorkspaceController` | tenant-school-service | service-owned | Route to tenant-school staff endpoint. |
| `/api/v1/workspace/firefighting` | POST | backend | `WorkspaceController` | firefighting-service | backend-proxy | Route to firefighting request create. |
| `/api/v1/attendance/**` | mixed | backend | `AttendanceController` | attendance-service | service-owned | Route gateway directly to attendance. |
| `/api/v1/fee-structure/**` | mixed | backend | `FeeStructureController` | fee-service | backend-proxy | Add public compatibility endpoints or rewrite to `/api/v1/fees/structure/**`. |
| `/api/v1/fee-assignments` | POST | backend | `FeeCollectionController` | fee-service | backend-proxy | Route to `/api/v1/fees/assignments`. |
| `/api/v1/payments` | POST | backend | `FeeCollectionController` | fee-service | backend-proxy | Route to `/api/v1/fees/payments`. |
| `/api/v1/fees/report` | GET | backend | `FeeCollectionController` | fee-service | backend-proxy | Route to `/api/v1/fees/reports/collection`. |
| `/api/v1/fees/overdue` | GET | backend | `FeeCollectionController` | fee-service | backend-proxy | Route to `/api/v1/fees/reports/overdue`. |
| `/api/v1/fees/send-reminders` | POST | backend | `FeeCollectionController` | fee-service | backend-proxy | Route to `/api/v1/fees/reminders/fee`. |
| `/api/v1/receipts/{paymentId}/pdf` | GET | backend | `FeeCollectionController` | fee-service | backend-logic | Add fee-service PDF receipt compatibility. |
| `/api/v1/supply/**` | mixed | backend | `SupplyController` | catalog-service | backend-proxy | Add public compatibility endpoints or rewrite to `/api/v1/catalog/**`. |
| `/api/v1/sa/orders/**` | mixed | backend | `SuperadminPortalController` | catalog-service | backend-proxy | Route to catalog order endpoints. |
| `/api/v1/sa/invoices/**` | mixed | backend | `SuperadminPortalController` | billing-service | backend-proxy | Route to billing `/api/v1/billing/sa/invoices/**`. |
| `/api/v1/sa/schools` | GET | backend | `SuperadminPortalController` | tenant-school-service | backend-proxy | Route to tenant-school school stats/list as required by UI. |
| `/api/v1/workflows/**` | mixed | backend | `WorkflowController` | workflow-service | backend-proxy | Route direct; legacy action aliases need rewrite to `/instances/{id}/...`. |
| `/api/v1/ff/**` | mixed | backend | `FirefightingController` | firefighting-service | service-owned | Route gateway directly to firefighting. |
| `/api/v1/audit-logs/**` | GET | backend | `AuditLogController` | audit-service | backend-proxy | Route or compatibility alias to audit events. |
| `/api/v1/notifications/**` | mixed | backend | notification controllers | notification-service | service-owned | Route gateway directly to notification. |
| `/api/v1/dashboard/**` | mixed | backend | dashboard/reporting controllers | reporting-service/student-service/catalog-service/fee-service | backend-proxy | Route reporting reads to reporting; write actions to owning service. |
| `/api/v1/command-centre/**` | mixed | backend | `CommandCenterController` | reporting-service | backend-proxy | Route to reporting command-center endpoints. |

Status counts at creation:

- `service-owned`: 9
- `backend-proxy`: 17
- `backend-logic`: 3
- `obsolete`: 1
- `unclear`: 0

Highest-risk deletion blockers:

- `/api/v1/workspace` composite payload.
- Fee receipt PDF endpoints.
- Any public route that relied on backend RBAC before services gained user-level authorization.
