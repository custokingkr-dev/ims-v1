# Product Requirements Document  
## Custoking IMS — Institutional Management System  
### v2.0 · Ground-Up Architecture Design

---

> **Document Purpose:** Greenfield redesign specification for Custoking IMS, derived from the operational v1 system. Every module, API surface, data model, and cross-cutting concern is specified here. Use this PRD as the authoritative reference for any rewrite, new service extraction, or feature addition.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)  
2. [Product Vision & Strategic Goals](#2-product-vision--strategic-goals)  
3. [User Personas & Roles](#3-user-personas--roles)  
4. [System Context & Boundaries](#4-system-context--boundaries)  
5. [Platform Architecture (Greenfield Target)](#5-platform-architecture-greenfield-target)  
6. [Module Catalogue & Functional Requirements](#6-module-catalogue--functional-requirements)  
   - 6.1 Identity & Access (IAM)  
   - 6.2 School / Tenant Management  
   - 6.3 Zone & Geography Management  
   - 6.4 Student Lifecycle  
   - 6.5 Fee & Finance  
   - 6.6 Attendance  
   - 6.7 Supply Catalog & Orders  
   - 6.8 Firefighting (Safety Compliance)  
   - 6.9 Workflow Engine  
   - 6.10 Reporting & Analytics  
   - 6.11 Platform Administration (Superadmin Portal)  
   - 6.12 Notifications  
7. [Cross-Cutting Concerns](#7-cross-cutting-concerns)  
   - 7.1 Multi-Tenancy  
   - 7.2 RBAC — Dynamic Role-Based Access Control  
   - 7.3 Module Entitlements  
   - 7.4 Audit Trail  
   - 7.5 Security Hardening  
   - 7.6 Observability  
8. [Data Model — Canonical Schema](#8-data-model--canonical-schema)  
9. [API Design Specification](#9-api-design-specification)  
10. [Frontend Architecture](#10-frontend-architecture)  
11. [Infrastructure & Deployment](#11-infrastructure--deployment)  
12. [Migration Strategy from v1](#12-migration-strategy-from-v1)  
13. [Non-Functional Requirements](#13-non-functional-requirements)  
14. [Success Metrics & KPIs](#14-success-metrics--kpis)  
15. [Open Questions & Decisions Log](#15-open-questions--decisions-log)

---

## 1. Executive Summary

Custoking IMS is a **multi-tenant, B2B SaaS platform** for institutional (school) management. The platform is operated by Custoking (the platform provider) and subscribed to by schools. Each school is a **tenant**; Custoking staff are **platform administrators**.

The v1 system was evolved from a single-school operations tool into a multi-tenant platform over 13 incremental phases. This PRD specifies the **v2 ground-up design** that preserves all domain knowledge while eliminating the dual-package technical debt, completing domain-driven decomposition, and establishing clean module boundaries that can evolve independently.

### Scope in one sentence  
> A headless REST API platform (Spring Boot 3.4 + PostgreSQL) with a React 18 SPA frontend that enables school administrators to manage students, fees, attendance, supply orders, and safety compliance — with Custoking operating the platform across many schools simultaneously.

---

## 2. Product Vision & Strategic Goals

### Vision  
*"Give every school the operational backbone of a Fortune-500 enterprise, at SaaS pricing."*

### Strategic Goals

| # | Goal | Metric |
|---|------|--------|
| G1 | Multi-tenant isolation — no school sees another's data | Zero cross-tenant data leaks in pen-test |
| G2 | Dynamic RBAC — permissions managed via DB, not code | 100% endpoints covered by permission code |
| G3 | Module-based subscriptions | Entitlement gate on every module entry point |
| G4 | Observable & auditable | Every state change has an audit record |
| G5 | Developer velocity | New module ships in < 1 sprint with zero auth boilerplate |
| G6 | Compliance-ready | OWASP Top-10 addressed; full audit export |

---

## 3. User Personas & Roles

### Platform-Level Personas

| Persona | System Role | Description |
|---------|-------------|-------------|
| **Platform Admin** | `SUPERADMIN` | Custoking staff; can manage all schools, create zones, assign module entitlements, view platform-wide reports |
| **Zone Admin** | `ZONE_ADMIN` | Regional manager overseeing a cluster of schools; read-only cross-school analytics, can approve cross-zone supply orders |

### School-Level Personas

| Persona | System Role | Description |
|---------|-------------|-------------|
| **School Admin** | `ADMIN` | Principal / operations head; manages all school data, all module access |
| **Fee Clerk** | `OPERATIONS` | Data-entry staff; fee collection, attendance marking, limited student data |
| **Supply Coordinator** | `OPERATIONS` | Manages supply catalog orders, firefighting requests |
| **Accountant** | `OPERATIONS` | Fee reversal, payment reports |
| **Teacher** | `OPERATIONS` | Marks attendance for their sections |

> **Key decision:** Roles are coarse-grained (SUPERADMIN / ZONE_ADMIN / ADMIN / OPERATIONS). Fine-grained authorization is via **permission codes** assigned to roles in the `role_permissions` table. Never use role names in `@PreAuthorize`.

---

## 4. System Context & Boundaries

```
┌─────────────────────────────────────────────────────────┐
│                     EXTERNAL ACTORS                      │
│  School Admin  Fee Clerk  Teacher  Zone Admin  Platform  │
└──────────┬──────────┬────────┬────────┬──────────┬──────┘
           │          │        │        │          │
           ▼          ▼        ▼        ▼          ▼
┌─────────────────────────────────────────────────────────┐
│                    REACT 18 SPA                          │
│    Vite · TypeScript · TailwindCSS · React Query         │
│    In-memory JWT · Permission-aware feature flags        │
└─────────────────────────┬───────────────────────────────┘
                          │ HTTPS / REST+JSON
┌─────────────────────────▼───────────────────────────────┐
│               SPRING BOOT 3.4 API GATEWAY                │
│  JWT Auth Filter → Tenant Resolver → RBAC → Controller  │
│  Filter chain: Correlation → JWT → Tenant → Security    │
└──┬──────────┬──────────┬──────────┬──────────┬──────────┘
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
 Domain     Domain     Domain     Domain    Platform
 Services   Services   Services   Services  Services
(Students) (Fees)   (Supply)  (Firefight) (RBAC/Auth)
   │          │          │          │          │
   └──────────┴──────────┴──────────┴──────────┘
                          │
                ┌─────────▼─────────┐
                │   PostgreSQL 15    │
                │  (single schema,   │
                │  app-level tenant  │
                │  isolation via     │
                │  school_id FK)     │
                └────────────────────┘

External integrations (future):
  • SMS/Email gateway (notifications)
  • Payment gateway (Razorpay / PayU)
  • Aadhaar eKYC (student identity)
  • Google Cloud Storage (student photos, bulk import files)
```

### In-Scope for v2

- All 12 modules in §6  
- Single-region PostgreSQL  
- React SPA frontend  
- Docker-based deployment (Cloud Run / GKE)

### Out-of-Scope for v2

- Mobile native apps  
- Real-time websockets (use polling for now)  
- Multi-region / data residency  
- Third-party LMS integration

---

## 5. Platform Architecture (Greenfield Target)

### 5.1 Technology Choices

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Backend runtime | Java 21 + Spring Boot 3.4 | Virtual threads (Loom), structured concurrency, mature ecosystem |
| ORM | Spring Data JPA + Hibernate 6.6 | Existing expertise, schema migration via Flyway |
| Database | PostgreSQL 15 | JSONB for flexible config, native `pg_trgm` for search |
| DB Migrations | Flyway (versioned SQL) | Deterministic, auditable schema history |
| Auth | JWT (access 15 min + refresh 7 days) | Stateless; refresh stored in `auth_sessions` for revocation |
| Build | Maven + multi-stage Docker | Layer-cached, non-root appuser |
| Frontend | React 18 + TypeScript + Vite | Fast DX, type-safe |
| State / fetching | React Query v5 | Cache invalidation, optimistic updates |
| Styling | Tailwind CSS 3 | Utility-first, consistent design tokens |
| API docs | springdoc-openapi 2.8 | Auto-generated OpenAPI 3.1, JWT pre-configured |
| Metrics | Micrometer + Prometheus | Scrape-ready `/actuator/prometheus` |
| Logs | Logback (JSON in prod) | Structured logs for GCP Log Explorer |
| CI | GitHub Actions | Test → OWASP scan → Docker build |

### 5.2 Package Structure (Target)

```
com.custoking.ims
├── CustokingImsApplication.java
│
├── platform/                       # Superadmin / platform-level concerns
│   ├── controller/
│   ├── service/
│   └── dto/
│
├── auth/                           # Authentication & session management
│   ├── controller/AuthController
│   ├── service/AuthService
│   ├── security/
│   │   ├── JwtService
│   │   ├── JwtAuthFilter
│   │   ├── AppUserDetails
│   │   ├── AppUserDetailsService
│   │   └── LoginRateLimiter
│   └── dto/
│
├── iam/                            # Identity & Access Management (RBAC)
│   ├── controller/RbacController
│   ├── service/
│   │   ├── RbacService
│   │   ├── RbacAuditService
│   │   └── UserContextService
│   ├── entity/
│   │   ├── RoleEntity
│   │   ├── PermissionEntity
│   │   ├── UserRoleAssignmentEntity
│   │   └── RbacAuditLogEntity
│   ├── repo/
│   └── dto/
│
├── tenant/                         # Multi-tenancy
│   ├── TenantContext              (ThreadLocal scope)
│   ├── TenantScope                (record: schoolId, zoneId, isSuperadmin)
│   ├── TenantResolverFilter
│   ├── TenantScopeService
│   └── ModuleEntitlementService
│
├── school/                         # School & Zone management
│   ├── controller/
│   │   ├── SchoolController
│   │   └── ZoneController
│   ├── service/
│   │   ├── SchoolService
│   │   └── ZoneService
│   ├── entity/
│   │   ├── SchoolEntity
│   │   ├── SchoolClassEntity
│   │   ├── SchoolSectionEntity
│   │   ├── ZoneEntity
│   │   ├── ZoneSchoolMappingEntity
│   │   └── ZoneAdminAssignmentEntity
│   ├── repo/
│   └── dto/
│
├── student/                        # Student lifecycle
│   ├── controller/
│   │   ├── StudentController
│   │   └── StudentImportController
│   ├── service/
│   │   ├── StudentService
│   │   └── StudentImportService
│   ├── entity/
│   │   ├── StudentEntity
│   │   └── StaffMemberEntity
│   ├── repo/
│   └── dto/
│
├── fee/                            # Fee structures & collection
│   ├── controller/
│   │   ├── FeeStructureController
│   │   └── FeeCollectionController
│   ├── service/
│   │   ├── FeeService
│   │   └── FeeReportService
│   ├── entity/
│   │   ├── FeeItemEntity
│   │   ├── FeeBandEntity
│   │   ├── FeeAssignmentEntity
│   │   └── AcademicYearEntity
│   ├── repo/
│   └── dto/
│
├── payment/                        # Payment recording & reconciliation
│   ├── controller/PaymentController
│   ├── service/PaymentService
│   ├── entity/PaymentRecordEntity
│   ├── repo/
│   └── dto/
│
├── attendance/                     # Daily attendance
│   ├── controller/AttendanceController
│   ├── service/AttendanceService
│   ├── entity/AttendanceDailyEntity
│   ├── repo/
│   └── dto/
│
├── supply/                         # Supply catalog & orders
│   ├── controller/SupplyController
│   ├── service/SupplyOrderService
│   ├── entity/
│   │   ├── CatalogItemEntity
│   │   └── CatalogOrderEntity
│   ├── repo/
│   └── dto/
│
├── firefighting/                   # Safety compliance requests
│   ├── controller/FirefightingController
│   ├── service/FirefightingService
│   ├── entity/
│   │   ├── FirefightingRequestEntity
│   │   └── FirefightingQuotationEntity
│   ├── repo/
│   └── dto/
│
├── workflow/                       # Generic workflow engine
│   ├── controller/WorkflowController
│   ├── service/WorkflowService
│   ├── entity/
│   │   ├── WorkflowDefinitionEntity
│   │   ├── WorkflowStepEntity
│   │   ├── WorkflowInstanceEntity
│   │   └── WorkflowActionEntity
│   ├── repo/
│   └── dto/
│
├── planning/                       # Annual plan & budget
│   ├── controller/PlanningController
│   ├── service/PlanningService
│   ├── entity/
│   │   ├── AnnualPlanEntity
│   │   └── AnnualPlanItemEntity
│   ├── repo/
│   └── dto/
│
├── reporting/                      # Cross-module analytics
│   ├── controller/ReportController
│   ├── service/
│   │   ├── DashboardService
│   │   └── ReportExportService
│   └── dto/
│
├── audit/                          # Immutable audit trail
│   ├── AuditLogService
│   ├── AuditLogEntity
│   ├── AuditLogRepository
│   └── AuditLogController
│
├── notification/                   # Notifications (email/SMS)
│   ├── NotificationService
│   └── dto/
│
├── common/                         # Shared value objects & utilities
│   ├── domain/
│   │   ├── OrderStatus (enum)
│   │   ├── FirefightingRequestStatus (enum)
│   │   ├── StudentStatus (enum)
│   │   ├── PaymentStatus (enum)
│   │   ├── PaymentMethod (enum)
│   │   └── Module (enum — STUDENTS, FEES, ATTENDANCE, ORDERS, FIREFIGHTING, PAYMENTS)
│   ├── security/PermissionConstants
│   └── exception/
│       ├── TenantAccessException
│       ├── ModuleNotEntitledException
│       └── ResourceNotFoundException
│
└── config/                         # Spring configuration
    ├── SecurityConfig
    ├── WebConfig (CORS)
    ├── OpenApiConfig
    ├── DatabaseBootstrap
    ├── TenantDataSourceConfig
    ├── FilterConfig
    ├── ApplicationSecurityValidator
    └── logback-spring.xml
```

### 5.3 Request Lifecycle

```
HTTP Request
    │
    ▼
[1] RequestCorrelationFilter       → sets X-Request-Id MDC
    │
    ▼
[2] SecurityHeadersFilter          → X-Frame-Options, CSP, HSTS
    │
    ▼
[3] JwtAuthFilter                  → validates JWT, sets SecurityContext
    │                                 (loads AppUserDetails with permissions set)
    ▼
[4] TenantResolverFilter           → resolves schoolId from RBAC assignments
    │                                 sets TenantContext (ThreadLocal)
    ▼
[5] Spring Security RBAC check     → @PreAuthorize("@rbacService.hasPermission(...)")
    │
    ▼
[6] Controller                     → calls ModuleEntitlementService.requireModule(...)
    │
    ▼
[7] Domain Service                 → business logic, always scoped to TenantContext.get()
    │
    ▼
[8] Repository                     → SQL query with school_id = :schoolId predicate
    │
    ▼
[9] AuditLogService (async)        → writes audit record
    │
    ▼
HTTP Response + X-Request-Id header
```

---

## 6. Module Catalogue & Functional Requirements

---

### 6.1 Identity & Access Management (IAM)

**Purpose:** Authentication, session management, user lifecycle, dynamic RBAC.

#### 6.1.1 Authentication

| Feature | Requirement |
|---------|-------------|
| Login | Email + password (BCrypt cost 12). Returns access JWT (15 min) + refresh JWT (7 days). |
| Refresh | `POST /api/auth/refresh` with refresh token cookie. Issues new access token. Detects refresh token reuse (family invalidation). |
| Logout | Invalidates all sessions for the user (or single device). |
| Rate limiting | Max 5 failed logins per email per 15 minutes (in-memory bucket; Redis in v3). |
| JWT claims | `sub` (email), `userId` (Long), `iat`, `exp`. **No role claim** — permissions loaded from DB at auth time. |
| Session storage | `auth_sessions` table: `id`, `user_id`, `refresh_token_hash`, `created_at`, `expires_at`, `revoked`. |

#### 6.1.2 User Management

| Feature | Requirement |
|---------|-------------|
| Create user | Platform admin creates school users; school admin creates ops users |
| Soft delete | `deleted_at`, `deleted_by` on `app_users`. Deleted users cannot login. |
| Enable/disable | `active` flag. Inactive → 401 on login. |
| Password reset | Admin-triggered reset (generate temp token, email link). Self-service in v3. |
| Aadhaar linkage | `aadhar_enc` field: AES-256-GCM encrypted Aadhaar number. Key managed via env var. |

#### 6.1.3 Dynamic RBAC

**Core principle:** Permissions are data, not code. `@PreAuthorize` expressions reference permission codes only.

**Tables:**
- `roles` — `(id, code, name, description, active)`
- `permissions` — `(id, code, name, module, description)`
- `role_permissions` — `(role_id, permission_id)` M:M
- `user_role_assignments` — `(id, user_id, role_id, school_id, zone_id, valid_from, valid_until, active, granted_by, granted_at)`

**Permission check flow:**
1. At login, `AppUserDetailsService` loads all permission codes via `RbacService.loadPermissionsForUser(userId)` → stored on `AppUserDetails.permissions` (Set<String>).
2. `RbacService.hasPermission(authentication, code)` → fast-path in-memory set check; fallback DB query if set empty.
3. `@PreAuthorize("@rbacService.hasPermission(authentication, 'fee:collect')")` on every endpoint.

**Built-in permission codes (complete catalogue):**

```
Platform:
  platform:admin        — full platform access (superadmin equivalent)
  system:swagger        — view API docs (superadmin only)
  report:read           — cross-school reports

School:
  school:read           — view school list/detail
  school:create         — create new school
  school:update         — update school settings
  school:suspend        — suspend/reactivate school

Zone:
  zone:read             — view zones
  zone:create           — create zone
  zone:update           — update zone
  zone:assign-school    — add/remove school from zone

User:
  user:read             — list users
  user:create           — create user
  user:update           — update user
  user:disable          — disable user account

RBAC:
  role:assign           — assign role to user
  role:revoke           — revoke role from user
  role:disable          — disable a role
  permission:assign     — assign permission to role
  permission:revoke     — revoke permission from role

Students:
  student:read          — view student list
  student:create        — enrol new student
  student:update        — update student record
  student:import        — bulk import via CSV

Fees:
  fee:structure:read    — view fee items, bands
  fee:structure:write   — create/update fee items
  fee:collect           — record fee payment
  fee:reverse           — reverse a payment
  fee:report            — view fee reports

Attendance:
  attendance:read       — view attendance records
  attendance:mark       — mark/update attendance

Supply Orders:
  order:read            — view catalog and orders
  order:create          — place new supply order
  order:approve         — approve pending order
  order:reject          — reject order

Firefighting:
  firefighting:read     — view requests and quotations
  firefighting:create   — submit new firefighting request
  firefighting:approve  — approve quotation

Workflow:
  workflow:read         — view workflow instances
  workflow:act          — perform workflow action

Invoice / Billing:
  invoice:read          — view invoices
  invoice:create        — create invoice
  invoice:cancel        — cancel invoice

Payments:
  payment:read          — view payment records
  payment:create        — record payment

Audit:
  audit:read            — view audit log

Module Entitlement:
  entitlement:read      — view school entitlements
  entitlement:write     — grant/revoke module for school
```

#### 6.1.4 Role-Assignment Validity

`user_role_assignments.valid_from` and `valid_until` enforce time-bounded role grants. `isEffective()` on the entity checks `active && now >= valid_from && now <= valid_until`. All RBAC permission checks use `isEffective()` — not just `isActive()`.

---

### 6.2 School / Tenant Management

**Purpose:** Lifecycle management of schools (tenants) on the platform.

#### Functional Requirements

| # | Feature | Actor | Notes |
|---|---------|-------|-------|
| S1 | Create school | Platform admin | `short_code` must be globally unique. Auto-provision default roles + entitlements. |
| S2 | Update school | Platform admin, School admin | Name, city, state, contact info, class/section count. |
| S3 | Suspend school | Platform admin | Sets `active=false`. All school users get 401. |
| S4 | View school | Zone admin (for their zone schools), Platform admin | Returns school detail + active module entitlements. |
| S5 | List schools | Platform admin | With pagination, search by name/city. |
| S6 | Create school admin user | Platform admin | Creates `ADMIN` role assignment scoped to this school. |
| S7 | Configure classes & sections | School admin | Sets `configured_class_count`, `configured_section_count`. Auto-generates class and section entities. |
| S8 | Academic year management | Platform admin | Create/activate academic years (e.g., "2025-26"). |

#### School Entity (canonical)

```
schools:
  id BIGSERIAL PK
  name VARCHAR(255) NOT NULL
  short_code VARCHAR(50) NOT NULL UNIQUE
  city VARCHAR(255)
  state VARCHAR(255)
  contact_email VARCHAR(255)
  contact_phone VARCHAR(255)
  active BOOLEAN NOT NULL DEFAULT TRUE
  configured_class_count INTEGER
  configured_section_count INTEGER
  version BIGINT NOT NULL DEFAULT 0   ← optimistic lock
  created_at TIMESTAMPTZ NOT NULL
  created_by BIGINT FK app_users
  updated_at TIMESTAMPTZ
  updated_by BIGINT FK app_users
```

---

### 6.3 Zone & Geography Management

**Purpose:** Group schools into administrative regions ("zones") for zone-level admin oversight.

#### Functional Requirements

| # | Feature | Actor |
|---|---------|-------|
| Z1 | Create zone | Platform admin |
| Z2 | Assign school to zone | Platform admin |
| Z3 | Assign zone admin | Platform admin — creates `ZONE_ADMIN` role assignment |
| Z4 | View zone schools | Zone admin — sees only schools in their zone |
| Z5 | Zone analytics | Zone admin — aggregate stats across zone schools |
| Z6 | Remove school from zone | Platform admin |

#### Key Design Rules

- A school can belong to at most **one zone** at a time.
- A zone admin can have **multiple zones** (`zone_admin_assignments` is 1:N).
- Zone scope is derived at login from `user_role_assignments.zone_id`.

---

### 6.4 Student Lifecycle

**Purpose:** Manage student enrollment, profiles, class/section placement, and bulk import.

#### Functional Requirements

| # | Feature | Actor | Notes |
|---|---------|-------|-------|
| ST1 | Enrol student | School admin, Ops | Admission number, name, class, section, parent contacts, Aadhaar (encrypted) |
| ST2 | View student profile | Admin, Ops | Shows fee status, attendance summary, payment history |
| ST3 | Update student | Admin | Class/section change, contact update |
| ST4 | Soft-delete student | Admin | Sets `deleted_at`. Does not appear in lists. |
| ST5 | Bulk CSV import | Admin | Parse → validate → preview → confirm 2-step flow. Errors returned per row. |
| ST6 | Student photo upload | Admin | GCS signed URL flow; photo URL stored on student. |
| ST7 | Search students | Admin, Ops | By name, admission number, class, section. |
| ST8 | Fee status view | Ops | See outstanding balance per student. |

#### Student Entity (canonical)

```
students:
  id BIGSERIAL PK
  school_id BIGINT FK schools NOT NULL
  admission_no VARCHAR(100) NOT NULL
  full_name VARCHAR(255) NOT NULL
  school_class_id VARCHAR(255) FK school_classes
  section_id VARCHAR(255) FK school_sections
  father_name VARCHAR(255)
  mother_name VARCHAR(255)
  contact_phone VARCHAR(20)
  contact_email VARCHAR(255)
  aadhar_enc TEXT              ← AES-256-GCM encrypted
  dob DATE
  gender VARCHAR(10)
  fee_status VARCHAR(50)
  photo_url TEXT
  version BIGINT NOT NULL DEFAULT 0
  created_at TIMESTAMPTZ NOT NULL
  created_by BIGINT FK app_users
  deleted_at TIMESTAMPTZ
  deleted_by BIGINT FK app_users
  UNIQUE (school_id, admission_no)
```

#### Bulk Import Flow

```
POST /api/students/import/preview   → validate CSV, return ImportBatch{id, rows, errors}
POST /api/students/import/confirm   → confirm batch → persists all valid rows
GET  /api/students/import/{batchId} → status check
```

`import_batches` / `import_rows` tables track every import run for audit purposes.

---

### 6.5 Fee & Finance

**Purpose:** Define fee structures, assign fee plans to students, and track collections.

#### 6.5.1 Fee Structure

A **fee structure** is a set of `fee_items` with amounts. Fee items are templated; actual student obligation is created via `fee_assignments`.

**Fee item:** `(id, school_id, academic_year_id, name, amount, frequency, category)`  
**Fee band:** `(id, school_id, name, active, schedules_csv, discount_pct)` — discount bands applied at assignment.

#### 6.5.2 Fee Assignment

```
fee_assignments:
  id BIGSERIAL PK
  student_id BIGINT FK students NOT NULL
  academic_year_id VARCHAR FK academic_years NOT NULL
  school_id BIGINT FK schools NOT NULL
  net_payable BIGINT NOT NULL          ← total amount owed (paise)
  paid_amount BIGINT NOT NULL DEFAULT 0
  manual_discount DOUBLE PRECISION
  band_discount DOUBLE PRECISION
  status VARCHAR(50)                   ← PENDING, PARTIAL, PAID, WAIVED
  version BIGINT NOT NULL DEFAULT 0
  created_at TIMESTAMPTZ NOT NULL
  created_by BIGINT
```

#### 6.5.3 Fee Collection

Payment against a fee assignment. One student can have multiple payments.

#### Functional Requirements

| # | Feature | Actor |
|---|---------|-------|
| F1 | Define fee items | School admin |
| F2 | Create fee band | School admin |
| F3 | Assign fee plan to student | Admin, Ops |
| F4 | Record payment | Ops (fee clerk) |
| F5 | Reverse payment | Admin (with reason) |
| F6 | View student fee account | Admin, Ops |
| F7 | Outstanding fees report | Admin |
| F8 | Defaulter list (overdue 30/60/90 days) | Admin |
| F9 | Fee summary by class | Admin |

---

### 6.6 Attendance

**Purpose:** Daily per-student attendance tracking with class/section grouping.

#### Functional Requirements

| # | Feature | Actor |
|---|---------|-------|
| A1 | Mark attendance | Ops (teacher) — by class+section for a date |
| A2 | View attendance | Admin, Ops — calendar view per student or section |
| A3 | Attendance report | Admin — monthly summary, defaulter list |
| A4 | Edit attendance | Admin — correction with audit trail |
| A5 | Holiday calendar | Admin — mark non-school days |

#### Attendance Entity

```
attendance_daily:
  id BIGSERIAL PK
  student_id BIGINT FK students NOT NULL
  school_id BIGINT FK schools NOT NULL
  date DATE NOT NULL
  status VARCHAR(20) NOT NULL   ← PRESENT, ABSENT, HALF_DAY, HOLIDAY
  marked_by BIGINT FK app_users
  marked_at TIMESTAMPTZ
  UNIQUE (student_id, date)
```

---

### 6.7 Supply Catalog & Orders

**Purpose:** Schools browse a Custoking-managed catalog of supply items and place orders that flow through an approval workflow.

#### 6.7.1 Catalog (Platform-managed)

`catalog_items` are created/managed by Custoking superadmin only. Items have: title, subtitle, icon, order_type, sample_amount.

#### 6.7.2 Supply Orders

Schools place orders referencing catalog items or custom items. Orders go through a state machine:

```
DRAFT → SUBMITTED → APPROVED / REJECTED
         (zone admin or SA)
```

#### Functional Requirements

| # | Feature | Actor |
|---|---------|-------|
| O1 | Browse catalog | School admin, Ops |
| O2 | Place order | School admin |
| O3 | View my school's orders | School admin, Ops |
| O4 | Approve order | Zone admin, Platform admin |
| O5 | Reject order (with reason) | Zone admin, Platform admin |
| O6 | All orders view | Platform admin |
| O7 | Order fulfillment tracking | Platform admin (marks FULFILLED) |

#### Supply Order Entity

```
catalog_orders:
  id BIGSERIAL PK
  school_id BIGINT FK schools NOT NULL
  catalog_item_id BIGINT FK catalog_items
  title VARCHAR(255)
  quantity INTEGER
  amount BIGINT NOT NULL
  status VARCHAR(50)            ← DRAFT, SUBMITTED, APPROVED, REJECTED, FULFILLED
  notes TEXT
  rejection_reason TEXT
  ordered_by BIGINT FK app_users
  ordered_at TIMESTAMPTZ
  decided_by BIGINT FK app_users
  decided_at TIMESTAMPTZ
  version BIGINT NOT NULL DEFAULT 0
```

---

### 6.8 Firefighting (Safety Compliance)

**Purpose:** Schools submit firefighting equipment maintenance/installation requests; Custoking provides quotations and approval workflow.

#### State Machine

```
PENDING → QUOTATION_SENT → APPROVED / REJECTED
```

#### Functional Requirements

| # | Feature | Actor |
|---|---------|-------|
| FF1 | Submit request | School admin |
| FF2 | View school's requests | School admin, Ops |
| FF3 | Upload quotation | Platform admin |
| FF4 | Approve quotation | School admin |
| FF5 | Reject quotation | School admin |
| FF6 | All requests view | Platform admin |
| FF7 | Firefighting dashboard | School admin — status summary |

#### Firefighting Entities

```
firefighting_requests:
  code VARCHAR PK                      ← human-readable ID e.g. "FF-2025-001"
  school_id BIGINT FK schools NOT NULL
  title VARCHAR(255)
  description TEXT
  status VARCHAR(50)
  version BIGINT NOT NULL DEFAULT 0
  created_at TIMESTAMPTZ
  created_by BIGINT

firefighting_quotations:
  id BIGSERIAL PK
  request_code VARCHAR FK firefighting_requests
  amount BIGINT NOT NULL
  description TEXT
  document_url TEXT
  created_at TIMESTAMPTZ
```

---

### 6.9 Workflow Engine

**Purpose:** Generic, data-driven approval workflow applicable to supply orders, fee waivers, and future process types.

#### Design

- `workflow_definitions` — named process type (e.g., "SUPPLY_ORDER_APPROVAL", "FEE_WAIVER")
- `workflow_steps` — ordered steps per definition (e.g., ZONE_ADMIN_REVIEW → SA_REVIEW)
- `workflow_instances` — one per business object instance
- `workflow_actions` — each transition with actor, timestamp, decision, notes

#### Functional Requirements

| # | Feature |
|---|---------|
| W1 | Start workflow instance for an entity |
| W2 | Perform action (approve/reject/escalate) on current step |
| W3 | View pending actions for authenticated user |
| W4 | View history of a workflow instance |
| W5 | Callback mechanism when workflow completes (updates business entity status) |

---

### 6.10 Reporting & Analytics

**Purpose:** Cross-module dashboards and exportable reports.

#### School-Level Reports (for School Admin)

| Report | Description |
|--------|-------------|
| Fee collection summary | Total collected vs. outstanding by month/quarter |
| Class-wise fee summary | Outstanding by class |
| Defaulter list | Students with overdue > 30/60/90 days |
| Attendance summary | Monthly attendance % by class/section |
| Student roster | Full student list with status |
| Payment transaction log | All payments in date range |

#### Platform-Level Reports (for Superadmin)

| Report | Description |
|--------|-------------|
| Platform revenue | Total fees collected across all schools |
| School activation status | Active vs. suspended |
| Order volume | Supply orders by school/zone/month |
| Firefighting request volume | By status and region |
| User activity | Login frequency, active users |

#### Dashboard API

`GET /api/dashboard` returns `DashboardStats`:  
- student count, staff count  
- fee collection (today, month, year)  
- attendance rate (today)  
- pending orders count  
- pending firefighting requests count  
- pending workflow actions for current user  

---

### 6.11 Platform Administration (Superadmin Portal)

**Purpose:** Custoking-internal operations across all schools.

#### Functional Requirements

| # | Feature |
|---|---------|
| P1 | School management (CRUD, suspend) |
| P2 | Module entitlement management |
| P3 | Global catalog management |
| P4 | Platform-wide supply order approval |
| P5 | Superadmin invoice management (B2B billing to schools) |
| P6 | Zone configuration |
| P7 | Cross-school audit log search |
| P8 | Platform revenue dashboard |

#### Superadmin Invoice Entity

```
superadmin_invoices:
  id BIGSERIAL PK
  school_id BIGINT FK schools
  amount BIGINT NOT NULL
  description TEXT
  status VARCHAR(50)          ← DRAFT, SENT, PAID
  issued_at TIMESTAMPTZ
  due_at TIMESTAMPTZ
  paid_at TIMESTAMPTZ
```

---

### 6.12 Notifications

**Purpose:** Event-driven communication to school users via email and (future) SMS.

#### Events That Trigger Notifications

| Event | Recipient |
|-------|-----------|
| Supply order approved | School admin |
| Supply order rejected | School admin |
| Firefighting quotation uploaded | School admin |
| Student fee overdue (>30 days) | School admin (daily batch) |
| New school created | School admin (welcome email) |
| Password reset | User |
| Role assignment changed | User |

#### Implementation (v2)

- `notification_events` table: `(id, event_type, target_user_id, payload_json, status, created_at, sent_at)`
- `NotificationService` processes events asynchronously via Spring `@Async`
- Email via SMTP (JavaMailSender). SMS via future integration.

---

## 7. Cross-Cutting Concerns

---

### 7.1 Multi-Tenancy

**Strategy:** Application-level tenant isolation via `school_id` FK column on every tenant-scoped table. No row-level security (RLS disabled — V117 migration).

**Enforcement:**
1. `TenantResolverFilter` resolves `schoolId` from the authenticated user's active RBAC assignment.
2. `TenantContext` (ThreadLocal) stores `TenantScope(schoolId, zoneId, isSuperadmin)`.
3. All domain service methods query with `WHERE school_id = TenantContext.get()`.
4. Platform admins have `schoolId = null`; their requests are not scoped. `ModuleEntitlementService.requireModule(null, module)` is a no-op for platform admins.

**Non-obvious rules:**
- `TenantContext.get()` returns null for superadmins — never assume non-null in service code.
- `isPlatformAdmin()` reads from `TenantContext.getScope().isSuperadmin()` — not a DB query.
- Role strings are never used to determine tenant scope — only RBAC assignment's `school_id` / `zone_id`.

---

### 7.2 RBAC — Full Specification

See §6.1.3 for the permission code catalogue.

**Implementation rules:**

1. **Never** use `hasRole()` or `hasAnyRole()` in `@PreAuthorize`. Always use `@rbacService.hasPermission(authentication, 'code')`.
2. **Never** check `user.getRole()` in business logic. Use `userContext.isPlatformAdmin()` or permission check.
3. Permission set loaded at login, cached on `AppUserDetails.permissions`. DB fallback if empty.
4. `RbacService.hasAnyPermission(auth, codes...)` for endpoints requiring one of many permissions.
5. `RbacService.hasAllPermissions(auth, codes...)` for compound requirements.
6. Role assignment validity: `isEffective()` enforced at auth time and re-checked in service layer for sensitive operations.
7. RBAC audit log (`rbac_audit_log`) records every grant/revoke/disable/expiry change.

---

### 7.3 Module Entitlements

Schools subscribe to modules. Modules not entitled to a school → 403 with `MODULE_NOT_ENTITLED` error code.

**Modules:**
```java
enum Module {
    STUDENTS, FEES, ATTENDANCE, ORDERS, FIREFIGHTING, PAYMENTS
}
```

**Table:**
```
school_module_entitlements:
  id BIGSERIAL PK
  school_id BIGINT FK schools NOT NULL
  module VARCHAR(50) NOT NULL
  enabled BOOLEAN NOT NULL DEFAULT TRUE
  enabled_by BIGINT FK app_users
  enabled_at TIMESTAMPTZ
  UNIQUE (school_id, module)
```

**Enforcement pattern:**
```java
// In every write method of a module controller:
moduleService.requireModule(TenantContext.get(), Module.STUDENTS);
```

`requireModule(null, module)` → no-op (platform admin bypass).

---

### 7.4 Audit Trail

Every state-changing operation writes to `audit_log`. **Immutable** — no update/delete on this table.

**Schema:**
```
audit_log:
  id BIGSERIAL PK
  event_type VARCHAR(100) NOT NULL
  actor_user_id BIGINT
  actor_email VARCHAR(255)
  school_id BIGINT
  entity_type VARCHAR(100)
  entity_id VARCHAR(255)
  detail JSONB
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  request_id VARCHAR(100)          ← from X-Request-Id
```

**Usage pattern:**
```java
auditLogService.record(b -> b
    .action("STUDENT_CREATED")
    .userId(actor.userId())
    .schoolId(schoolId)
    .entityType("Student")
    .entityId(student.getId().toString())
    .detail("admissionNo", admissionNo)
    .build());
```

**Retention:** 7 years (compliance). Archive to cold storage after 1 year.

---

### 7.5 Security Hardening

| Control | Implementation |
|---------|---------------|
| Authentication | JWT (RSA-256 signed). No role in JWT — userId only. |
| Secret validation | `ApplicationSecurityValidator` — startup fails if JWT or Aadhaar secret < 32 chars |
| Password strength | BCrypt cost 12. Min 8 chars enforced at registration. |
| Rate limiting | Login: 5 attempts / 15 min per email. In-memory bucket. |
| CSRF | Disabled (JWT stateless); SameSite=Strict cookie for refresh token. |
| Security headers | `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Strict-Transport-Security`, `Content-Security-Policy`, `Referrer-Policy` |
| Input validation | `@Valid` + Bean Validation on all request DTOs. |
| SQL injection | JPA only; no native queries without parameterization. |
| Aadhaar encryption | AES-256-GCM, unique IV per record, key from env. |
| Swagger in prod | Disabled by default (`SWAGGER_ENABLED=false`). Protected by `system:swagger` permission when enabled. |
| CORS | `WebConfig` allows only configured `FRONTEND_ORIGIN`. |
| Dependency audit | OWASP dependency-check in CI — fails on CVSS ≥ 7. |

---

### 7.6 Observability

#### Logging

- **Development:** Human-readable logback pattern with color.
- **Production:** JSON format (`logback-spring.xml`): `timestamp`, `level`, `logger`, `message`, `requestId` (MDC), `userId` (MDC), `schoolId` (MDC), `traceId`.
- `RequestLoggingFilter` adds MDC values at request start; clears at request end.

#### Metrics (Prometheus / Micrometer)

| Metric | Type |
|--------|------|
| `ims.login.success` | Counter |
| `ims.login.failure` | Counter (tag: reason) |
| `ims.fee.collected.amount` | Counter (tag: schoolId) |
| `ims.supply.order.created` | Counter (tag: schoolId) |
| `ims.student.enrolled` | Counter (tag: schoolId) |
| HTTP request duration | `http.server.requests` (Spring auto) |
| DB connection pool | HikariCP metrics (auto) |

#### Health

- `/actuator/health` — liveness + DB health (`DatabaseHealthIndicator`)
- `/actuator/prometheus` — metrics scrape (protected by `actuator:read` permission)

#### Alerting (future)

- Login failure rate > 50/min → alert
- DB health DOWN → alert
- Error rate > 1% → alert

---

## 8. Data Model — Canonical Schema

### Entity Relationship Overview

```
schools ──< students ──< fee_assignments ──< payment_records
   │              │
   │         attendance_daily
   │
   ├──< catalog_orders ──> catalog_items
   │
   ├──< firefighting_requests ──< firefighting_quotations
   │
   ├──< app_users ──< user_role_assignments ──> roles ──< role_permissions ──> permissions
   │
   ├──< school_module_entitlements
   │
   ├──< zones (via zone_school_mappings)
   │
   └──< annual_plan_entries ──< annual_plan_items
```

### Complete Table List

| Table | Domain | Tenant-Scoped |
|-------|--------|---------------|
| `schools` | School | No (parent) |
| `school_classes` | School | No (shared) |
| `school_sections` | School | No (shared) |
| `academic_years` | School | No (shared) |
| `zones` | Zone | No |
| `zone_school_mappings` | Zone | No |
| `zone_admin_assignments` | Zone | No |
| `app_users` | IAM | Yes (branch_id) |
| `auth_sessions` | IAM | No |
| `roles` | RBAC | No |
| `permissions` | RBAC | No |
| `role_permissions` | RBAC | No |
| `user_role_assignments` | RBAC | Yes (school_id) |
| `rbac_audit_log` | RBAC | No |
| `school_module_entitlements` | Entitlement | Yes |
| `students` | Student | Yes |
| `import_batches` | Student | Yes |
| `import_rows` | Student | Yes |
| `fee_items` | Fee | Yes |
| `fee_bands` | Fee | Yes |
| `fee_assignments` | Fee | Yes |
| `payment_records` | Payment | Yes |
| `attendance_daily` | Attendance | Yes |
| `catalog_items` | Supply | No (platform) |
| `catalog_orders` | Supply | Yes |
| `supply_orders` | Supply | Yes |
| `firefighting_requests` | Firefighting | Yes |
| `firefighting_quotations` | Firefighting | Yes |
| `workflow_definitions` | Workflow | No |
| `workflow_steps` | Workflow | No |
| `workflow_instances` | Workflow | Yes |
| `workflow_actions` | Workflow | Yes |
| `annual_plan_entries` | Planning | Yes |
| `annual_plan_items` | Planning | Yes |
| `audit_log` | Audit | Yes |
| `superadmin_invoices` | Platform | Yes |
| `notification_events` | Notification | Yes |

### Key Index Strategy

```sql
-- Tenant isolation (most critical)
CREATE INDEX idx_students_school_id ON students(school_id);
CREATE INDEX idx_fee_assignments_school_id ON fee_assignments(school_id);
CREATE INDEX idx_payment_records_school_id ON payment_records(school_id);
CREATE INDEX idx_attendance_daily_school_id ON attendance_daily(school_id);
CREATE INDEX idx_catalog_orders_school_id ON catalog_orders(school_id);

-- Common query patterns
CREATE INDEX idx_students_admission_no ON students(school_id, admission_no);
CREATE INDEX idx_attendance_date ON attendance_daily(school_id, date);
CREATE INDEX idx_fee_assignments_student_year ON fee_assignments(student_id, academic_year_id);
CREATE INDEX idx_audit_log_school_created ON audit_log(school_id, created_at DESC);
CREATE INDEX idx_user_role_assignments_user ON user_role_assignments(user_id, active);

-- Soft delete (exclude deleted rows from most queries)
CREATE INDEX idx_students_active ON students(school_id, deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_app_users_active ON app_users(email) WHERE deleted_at IS NULL;
```

---

## 9. API Design Specification

### 9.1 Base URL & Versioning

```
https://api.custoking.com/api/v1/...
```

Version in URL path. Breaking changes → new version. Non-breaking → same version.

### 9.2 Response Envelope

**Success:**
```json
{
  "data": { ... },
  "meta": {
    "page": 1,
    "size": 20,
    "total": 150,
    "requestId": "req-abc123"
  }
}
```

**Error (GlobalExceptionHandler):**
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "admissionNo must not be blank",
    "requestId": "req-abc123",
    "timestamp": "2026-05-20T10:30:00Z",
    "fields": [
      { "field": "admissionNo", "message": "must not be blank" }
    ]
  }
}
```

**Error codes:**
```
VALIDATION_ERROR         400
RESOURCE_NOT_FOUND       404
ACCESS_DENIED            403
TENANT_ACCESS_DENIED     403
MODULE_NOT_ENTITLED      403
RATE_LIMIT_EXCEEDED      429
INTERNAL_ERROR           500 (includes requestId for log correlation)
```

### 9.3 Endpoint Catalogue

#### Auth
```
POST   /api/auth/login
POST   /api/auth/refresh
POST   /api/auth/logout
POST   /api/auth/logout-all
GET    /api/auth/me
```

#### Schools
```
GET    /api/schools                  [school:read]
POST   /api/schools                  [school:create]
GET    /api/schools/{id}             [school:read]
PUT    /api/schools/{id}             [school:update]
POST   /api/schools/{id}/suspend     [school:suspend]
POST   /api/schools/{id}/activate    [school:suspend]
POST   /api/schools/{id}/admin       [school:create + user:create]
GET    /api/schools/{id}/entitlements [entitlement:read]
```

#### Users & RBAC
```
GET    /api/users                    [user:read]
POST   /api/users                    [user:create]
GET    /api/users/{id}               [user:read]
PUT    /api/users/{id}               [user:update]
POST   /api/users/{id}/disable       [user:disable]
POST   /api/users/{id}/enable        [user:disable]

GET    /api/rbac/roles               [role:assign]
POST   /api/rbac/assign              [role:assign]
DELETE /api/rbac/revoke/{assignmentId} [role:revoke]
GET    /api/rbac/my-permissions      (any authenticated)
```

#### Module Entitlements
```
GET    /api/entitlements/{schoolId}  [entitlement:read]
POST   /api/entitlements/{schoolId}/enable  [entitlement:write]
POST   /api/entitlements/{schoolId}/disable [entitlement:write]
```

#### Zones
```
GET    /api/zones                    [zone:read]
POST   /api/zones                    [zone:create]
GET    /api/zones/{id}               [zone:read]
PUT    /api/zones/{id}               [zone:update]
POST   /api/zones/{id}/schools       [zone:assign-school]
DELETE /api/zones/{id}/schools/{schoolId} [zone:assign-school]
```

#### Students
```
GET    /api/schools/{schoolId}/students         [student:read]
POST   /api/schools/{schoolId}/students         [student:create]
GET    /api/schools/{schoolId}/students/{id}    [student:read]
PUT    /api/schools/{schoolId}/students/{id}    [student:update]
DELETE /api/schools/{schoolId}/students/{id}    [student:update]
POST   /api/schools/{schoolId}/students/import/preview  [student:import]
POST   /api/schools/{schoolId}/students/import/confirm  [student:import]
```

#### Fees
```
GET    /api/schools/{schoolId}/fee-items        [fee:structure:read]
POST   /api/schools/{schoolId}/fee-items        [fee:structure:write]
PUT    /api/schools/{schoolId}/fee-items/{id}   [fee:structure:write]

GET    /api/schools/{schoolId}/fee-assignments  [fee:structure:read]
POST   /api/schools/{schoolId}/fee-assignments  [fee:structure:write]
GET    /api/schools/{schoolId}/fee-assignments/{id} [fee:structure:read]

POST   /api/schools/{schoolId}/payments         [fee:collect]
GET    /api/schools/{schoolId}/payments         [fee:report]
POST   /api/schools/{schoolId}/payments/{id}/reverse [fee:reverse]
```

#### Attendance
```
GET    /api/schools/{schoolId}/attendance       [attendance:read]
POST   /api/schools/{schoolId}/attendance       [attendance:mark]
PUT    /api/schools/{schoolId}/attendance/{id}  [attendance:mark]
GET    /api/schools/{schoolId}/attendance/report [attendance:read]
```

#### Supply Orders
```
GET    /api/catalog                             [order:read]
GET    /api/schools/{schoolId}/orders           [order:read]
POST   /api/schools/{schoolId}/orders           [order:create]
GET    /api/schools/{schoolId}/orders/{id}      [order:read]
POST   /api/orders/{id}/approve                 [order:approve]
POST   /api/orders/{id}/reject                  [order:reject]
GET    /api/orders                              [platform:admin] all orders
```

#### Firefighting
```
GET    /api/schools/{schoolId}/firefighting     [firefighting:read]
POST   /api/schools/{schoolId}/firefighting     [firefighting:create]
GET    /api/schools/{schoolId}/firefighting/{code} [firefighting:read]
POST   /api/firefighting/{code}/quotation       [platform:admin]
POST   /api/firefighting/{code}/approve         [firefighting:approve]
POST   /api/firefighting/{code}/reject          [firefighting:approve]
GET    /api/firefighting                        [platform:admin] all requests
```

#### Workflow
```
GET    /api/workflows/pending           [workflow:read]
GET    /api/workflows/{instanceId}      [workflow:read]
POST   /api/workflows/{instanceId}/act  [workflow:act]
```

#### Dashboard & Reports
```
GET    /api/dashboard                   (any authenticated)
GET    /api/reports/fees                [fee:report]
GET    /api/reports/attendance          [attendance:read]
GET    /api/reports/students            [student:read]
```

#### Audit
```
GET    /api/audit-log                   [audit:read]
GET    /api/audit-log/{id}              [audit:read]
```

---

## 10. Frontend Architecture

### 10.1 Technology Stack

| Layer | Choice | Notes |
|-------|--------|-------|
| Runtime | React 18 + TypeScript 5 | |
| Build | Vite 5 | Fast HMR, ESM |
| Routing | React Router v6 | Nested routes for workspace panels |
| Data fetching | React Query v5 | Stale-while-revalidate, mutation + cache invalidation |
| Styling | Tailwind CSS 3 | Design token layer via CSS variables |
| State | React Context (auth only) + React Query (server state) | No Redux |
| Forms | React Hook Form + Zod | Type-safe validation |
| Tables | TanStack Table v8 | Headless, sortable, paginated |

### 10.2 Auth & Token Strategy

- **Access token:** Stored in **memory only** (`useRef` inside AuthContext). Never in localStorage or sessionStorage.
- **Refresh token:** HttpOnly, Secure, SameSite=Strict cookie. Server manages.
- **Auto-refresh:** Interceptor calls `/api/auth/refresh` when access token expires. Deduplication: one inflight refresh at a time.
- **401 handling:** All 401 responses → clear auth context → redirect to login.
- **Permissions:** Loaded from `/api/auth/me` at login, stored in `AuthContext.permissions: string[]`. `usePermissions()` hook: `can(code)`, `canAny(codes)`, `canAll(codes)`.

### 10.3 Route & Page Structure

```
/login                              → LoginPage
/                                   → (protected) AppLayout
  /dashboard                        → DashboardPage
  /workspace                        → UnifiedWorkspacePage
    /workspace/students             → StudentsPanel
    /workspace/students/add         → AddStudentPanel
    /workspace/students/import      → BulkImportPanel
    /workspace/fees                 → FeesPanel
    /workspace/fees/structure       → FeeStructurePanel
    /workspace/attendance           → AttendancePanel
    /workspace/orders               → CatalogPanel / AdminOrdersPanel
    /workspace/firefighting         → FirefightingDashboardPanel
    /workspace/firefighting/new     → FirefightingNewPanel
    /workspace/planning             → PlanningPanel
    /workspace/staff                → StaffPanel
  /approvals                        → ApprovalsPage
  /schools                          → SchoolManagementPage (SA only)
  /zones                            → ZoneManagementPage (SA only)
  /users                            → UsersPage
  /reports                          → ReportsPage
  /audit-log                        → AuditLogPage (SA only)
  /sa-portal                        → Superadmin Portal (SA only)
    /sa-portal/schools              → SaSchoolsPanel
    /sa-portal/orders               → SaAllOrdersPanel
    /sa-portal/invoices             → SaInvoicesPanel
    /sa-portal/revenue              → SaRevenuePanel
    /sa-portal/erp                  → SaErpPanel
    /sa-portal/catalog              → SaCatalogPanel
```

### 10.4 Key Frontend Patterns

#### Permission Gate Component
```tsx
<PermissionGate permission="student:create">
  <Button onClick={handleAdd}>Add Student</Button>
</PermissionGate>
```

#### Feature Hook Pattern
```tsx
// Each domain has a useXxxFeature hook that encapsulates queries + mutations
const { students, isLoading, enrolStudent } = useStudentFeature(schoolId);
```

#### Error Boundary
```tsx
<ErrorBoundary fallback={<ErrorFallback />}>
  <FeaturePanel />
</ErrorBoundary>
```

#### Optimistic Updates (React Query)
```tsx
useMutation({
  mutationFn: recordPayment,
  onMutate: async (vars) => {
    await queryClient.cancelQueries(['feeAssignment', vars.assignmentId]);
    // snapshot + optimistic update
  },
  onError: (err, vars, ctx) => queryClient.setQueryData(..., ctx.prev),
  onSettled: () => queryClient.invalidateQueries(['feeAssignment']),
})
```

---

## 11. Infrastructure & Deployment

### 11.1 Container Strategy

```dockerfile
# Backend: multi-stage
FROM eclipse-temurin:21-jdk AS build
...
FROM eclipse-temurin:21-jre
RUN adduser --system --no-create-home appuser
USER appuser
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

### 11.2 Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DATABASE_URL` | Yes | JDBC URL |
| `DATABASE_USERNAME` | Yes | DB user |
| `DATABASE_PASSWORD` | Yes | DB password |
| `JWT_SECRET` | Yes | ≥32 chars; HMAC-SHA256 signing key |
| `AADHAR_ENCRYPTION_KEY` | Yes | ≥32 chars; AES-256 key |
| `FRONTEND_ORIGIN` | Yes | Allowed CORS origin |
| `SUPERADMIN_EMAIL` | Yes | Bootstrap superadmin email |
| `SUPERADMIN_PASSWORD` | Yes | Bootstrap superadmin password |
| `SWAGGER_ENABLED` | No | Default `false` in prod |
| `SPRING_PROFILES_ACTIVE` | No | `prod` in production |
| `MAIL_HOST` | No | SMTP host for notifications |

### 11.3 CI/CD Pipeline

```yaml
# .github/workflows/ci.yml
jobs:
  test:
    - mvn test -Pcoverage (JaCoCo; fail if < 80% line coverage)
    - mvn dependency-check:check (OWASP; fail if CVSS ≥ 7)
  
  frontend:
    - npm ci && npm run build
    - npm run type-check
  
  docker:
    - docker build -t ims-backend .
    - docker run (smoke test: /actuator/health)
  
  deploy: (main branch only)
    - Push image to GCR
    - gcloud run deploy / kubectl apply
```

### 11.4 Database Operations

- **Migrations:** Flyway versioned SQL. Checksums validated at startup.
- **Backup:** Daily pg_dump to GCS. 30-day retention.
- **Connection pooling:** HikariCP, max 20 connections, min-idle 5.

---

## 12. Migration Strategy from v1

### 12.1 Package Consolidation

The v1 system has a dual-package problem: old `service/*` alongside new domain packages. Migration:

1. **Keep old services** as the authoritative implementation (they work and are battle-tested).
2. **Remove/rename** new domain service files that duplicate Spring bean names.
3. **Gradually move** functionality into domain packages as new features are added.
4. **Controller wiring:** All controllers use old services for now; new feature additions use new domain services.

### 12.2 Entity Migration Risks

| Entity | Known Non-obvious API |
|--------|----------------------|
| `StudentEntity` | PK is `Long id`, field is `admissionNo` (not admissionNumber) |
| `FirefightingRequestEntity` | PK is `String code` (not id) |
| `FeeAssignmentEntity` | `netPayable`/`paidAmount` in paise (long), no `getAmount()` |
| `PaymentRecordEntity` | No `status`, no `reconciliationStatus` fields |

### 12.3 Database Migration Numbering

Current highest: V125. New migrations start at V126+. Follow pattern:
- V126 — next feature
- V127 — etc.

**Never modify existing migrations.** Add new ones.

### 12.4 Zero-Downtime Deployment

1. Deploy Flyway migration (backward-compatible column additions).
2. Deploy new backend image (reads new + old columns).
3. Verify health check.
4. (Optional) Deploy migration to populate new columns from old data.

---

## 13. Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Performance** | P95 API latency < 500ms for list endpoints with 1000 students |
| **Performance** | Dashboard loads < 2s end-to-end |
| **Availability** | 99.5% uptime SLA for school hours (6am–8pm IST) |
| **Scalability** | Support 500 schools, 100,000 students, 1M audit records without index regression |
| **Security** | OWASP Top-10 addressed; annual pen-test |
| **Compliance** | Aadhaar data encrypted at rest; audit log 7-year retention |
| **Maintainability** | JaCoCo line coverage ≥ 80% for service layer |
| **Operability** | Zero-downtime deploys; Flyway migrations must be backward-compatible |
| **DX** | New endpoint with RBAC + audit + entitlement check in < 30 min for any developer |

---

## 14. Success Metrics & KPIs

### Business Metrics

| Metric | Target (Year 1) |
|--------|----------------|
| Schools onboarded | 100 |
| Students managed | 50,000 |
| Monthly active users | 300 |
| Fee collections recorded | ₹10 Cr/month |

### Platform Health Metrics

| Metric | Target |
|--------|--------|
| API error rate | < 0.5% |
| P95 latency | < 500ms |
| Uptime | > 99.5% |
| Test coverage | > 80% service layer |
| OWASP findings (CVSS≥7) | 0 |
| Cross-tenant data leaks | 0 |

### Developer Velocity

| Metric | Target |
|--------|--------|
| Time to add new module | < 1 sprint |
| Time to add new permission | < 30 min |
| CI pipeline duration | < 10 min |
| Build success rate on main | > 95% |

---

## 15. Open Questions & Decisions Log

| # | Question | Status | Decision |
|---|----------|--------|----------|
| OQ1 | Should fees use `BigDecimal` or `long` (paise)? | **Decided** | `long` in paise — avoids floating-point errors, simpler DB storage |
| OQ2 | Single DB vs. per-tenant DB? | **Decided** | Single DB, application-level isolation. Row-level security disabled (V117). |
| OQ3 | JWT role claim? | **Decided** | No role in JWT. Permissions loaded from DB at login. Avoids stale permission issues. |
| OQ4 | Refresh token in cookie vs. response body? | **Decided** | HttpOnly cookie (CSRF-safe with SameSite=Strict) |
| OQ5 | Access token in localStorage vs. memory? | **Decided** | Memory only — XSS protection |
| OQ6 | Should RBAC be per-school or global? | **Decided** | Per school — `user_role_assignments.school_id` scopes the assignment |
| OQ7 | RLS vs. app-level isolation? | **Decided** | App-level (V117 disables RLS). RLS caused complexity with Flyway + SUPERUSER requirements. |
| OQ8 | Module entitlement: feature flag or hard gate? | **Decided** | Hard gate — `requireModule()` throws 403 immediately |
| OQ9 | Real-time notifications (WebSocket)? | **Open** | Currently polling. WebSocket in v3. |
| OQ10 | Multi-region data residency? | **Open** | Out of scope for v2 |
| OQ11 | Payment gateway integration? | **Open** | Razorpay integration planned for v2.1 |
| OQ12 | Should annual plans / timetable be separate modules? | **Open** | Currently in workspace; may need entitlement gate |
| OQ13 | Should reports be a microservice? | **Open** | Monolith for now; extract if report generation > 30s |
| OQ14 | Staff management module depth? | **Open** | Currently light (StaffMemberEntity only). Payroll out of scope. |

---

## Appendix A — Permission Code Reference

See §6.1.3 for the complete permission code catalogue organized by domain.

## Appendix B — Flyway Migration History

| Version | Description |
|---------|-------------|
| V1 | Baseline schema |
| V3 | Performance indexes |
| V4 | Row-level security (later disabled by V117) |
| V5 | Audit log table |
| V6 | Supply/firefighting indexes |
| V99 | BCrypt rehash |
| V100–V107 | Audit log schema fixes |
| V108 | Sequence-backed ID generation |
| V109 | Phase-5 DB improvements (version columns, soft-delete) |
| V110 | Operations role + zone fields |
| V111–V112 | RBAC tables + seed data |
| V113 | Zone tables |
| V114 | Tenant isolation indexes |
| V115–V116 | Workflow tables + seed definitions |
| V117 | Disable RLS; use app-level isolation |
| V118–V122 | Permission additions and RBAC refinements |
| V123 | School module entitlements |
| V124 | RBAC audit log |
| V125 | Swagger permission |

## Appendix C — Key Architectural Decisions (ADRs)

### ADR-001: Single Spring Boot Monolith
**Context:** Need to ship fast; team is small.  
**Decision:** Single deployable JAR. Domain packages within the monolith provide logical separation.  
**Consequence:** Extract services (students, fees) as microservices when team grows.

### ADR-002: Dynamic RBAC via DB, Not Spring Roles
**Context:** Customers need custom roles without code changes.  
**Decision:** `role_permissions` table drives all authorization. `@PreAuthorize` uses permission codes.  
**Consequence:** DB query at auth time; mitigated by in-memory permission set on `AppUserDetails`.

### ADR-003: Application-Level Multi-Tenancy
**Context:** Row-level security (RLS) caused issues with Flyway migrations (requires SUPERUSER) and was not adding meaningful security beyond app-level enforcement.  
**Decision:** Disable RLS (V117). All queries include `WHERE school_id = ?` explicitly.  
**Consequence:** Developer discipline required. `TenantContext` must be set before every DB access.

### ADR-004: Fees in Paise (long), Not BigDecimal
**Context:** Financial amounts with fractional rupees cause floating-point precision bugs.  
**Decision:** Store all monetary amounts as `long` in paise (1 rupee = 100 paise). Display layer divides by 100.  
**Consequence:** Max representable amount ≈ ₹92 trillion — sufficient.

---

*Document maintained by Custoking Engineering. Last updated: 2026-05-20.*  
*For questions, contact: abhishek.iitkgp16@gmail.com*
