# RBAC — Role-Based Access Control

> **Auth philosophy:** All authorization is **permission-based**, not role-based.
> Business logic must never call `hasRole()` or inspect `user.getRole()`.
> The `role` column on `app_users` is a display label only.

---

## Overview

```
User  →  user_role_assignments  →  roles  →  role_permissions  →  permissions
```

At login every permission code the user holds is loaded into `AppUserDetails.permissions`
(a `Set<String>`), so permission checks are O(1) in-memory lookups — no DB hit per request.

---

## Entities

| Table | Purpose |
|-------|---------|
| `app_users` | Accounts; `role` column is **display-only** |
| `roles` | Named role definitions (`name`, `scope_type`, `active`) |
| `role_permissions` | Many-to-many join between roles and permissions |
| `permissions` | Permission codes; each row has a `code` (e.g. `student:read`) and a `description` |
| `user_role_assignments` | Assigns a role to a user, scoped to an optional `school_id` or `zone_id`, with `valid_from` / `valid_until` bounds |

An assignment is **effective** when:

```java
UserRoleAssignmentEntity.isEffective()
// active && validFrom <= now && validUntil >= now
```

---

## Platform vs School roles

`user_role_assignments.scope_type` is one of:

| Scope | Meaning |
|-------|---------|
| `PLATFORM` | Assignment grants permissions across all tenants |
| `ZONE` | Assignment is scoped to a specific zone (and its schools) |
| `SCHOOL` | Assignment is scoped to a single school |

Platform admins (`scope_type = PLATFORM`) have `TenantScope.isSuperadmin() == true` and bypass tenant filters.

---

## Seeded roles

The following roles are created by Flyway migrations V112 / V118.
To grant a new role access to a permission, add a row to `role_permissions` in a new migration (V126+) — no Java change required.

| Role constant | Display name | Scope | Purpose |
|---------------|-------------|-------|---------|
| `SUPERADMIN` | Super Admin | PLATFORM | Full platform access |
| `ZONE_ADMIN` | Zone Admin | ZONE | Zone + school oversight |
| `ADMIN` | School Admin | SCHOOL | Full school administration |
| `OPERATIONS` | Operations | SCHOOL | Day-to-day school ops |
| `ACCOUNTANT` | Accountant | SCHOOL | Fees, payments, invoices |
| `TEACHER` | Teacher | SCHOOL | Students, attendance, timetable |
| `VIEWER` | Viewer | SCHOOL | Read-only |

---

## Permission codes

Codes follow the format `resource:action`.

| Domain | Codes |
|--------|-------|
| Platform | `platform:admin` |
| Workspace | `workspace:access` |
| School | `school:read` · `school:create` · `school:update` · `school:admin_manage` |
| Zone | `zone:read` · `zone:manage` · `zone:assign_school` |
| Student | `student:read` · `student:create` · `student:update` · `student:delete` · `student:import` |
| Attendance | `attendance:read` · `attendance:manage` |
| Fee | `fee:read` · `fee:collect` · `fee:reverse` · `fee_structure:manage` |
| Order | `order:read` · `order:create` · `order:update` · `order:approve` · `order:fulfill` |
| Firefighting | `firefighting:read` · `firefighting:create` · `firefighting:update` · `firefighting:approve` · `firefighting:fulfill` |
| Payment | `payment:read` · `payment:create` |
| Invoice | `invoice:read` · `invoice:create` · `invoice:cancel` |
| Customer | `customer:read` · `customer:create` |
| Report | `report:read` |
| Audit | `audit:read` |
| User | `user:read` · `user:create` · `user:update` · `user:disable` · `user:reset_password` |
| Timetable | `timetable:read` · `timetable:manage` |
| Staff / HR | `staff:read` · `staff:manage` |
| Annual plan | `plan:read` · `plan:manage` |
| Workflow | `workflow:read` · `workflow:act` |
| Role | `role:read` · `role:create` · `role:update` · `role:assign` · `role:revoke` · `role:disable` |
| Permission | `permission:read` · `permission:assign` · `permission:revoke` |
| Notification | `notification:read` · `notification:send` |
| System | `system:actuator` |

The canonical Java constants live in
`backend/src/main/java/com/custoking/ims/common/domain/PermissionConstants.java`.

---

## Using permissions in code

### Backend — controller method

```java
@GetMapping("/students")
@PreAuthorize(PermissionConstants.STUDENT_READ)   // ← always use the constant
public List<StudentDto> listStudents() { ... }
```

**Never write raw SpEL strings in `@PreAuthorize`.**
Add new constants to `PermissionConstants.java` first.

### Backend — service / domain check

For checks below the controller (rare), inject `RbacService` directly:

```java
if (!rbacService.hasPermission(authentication, "student:import")) {
    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
}
```

### Frontend — React hook

```tsx
const { can, canAny } = usePermissions();

// Single permission
if (can('student:create')) { ... }

// Any of several permissions
if (canAny(['order:approve', 'order:fulfill'])) { ... }
```

Permissions are fetched at login from `GET /api/v1/auth/me` and stored in `AuthContext`.

---

## Adding a new permission (checklist)

1. Add the SQL row to the `permissions` table in a **new Flyway migration** (V126+).
2. Assign it to the appropriate role(s) in the same migration via `role_permissions`.
3. Add a Java constant to `PermissionConstants.java`.
4. Annotate the controller method with `@PreAuthorize(PermissionConstants.YOUR_CONST)`.
5. If the endpoint is module-gated, add `moduleService.requireModule(...)` in the controller.
6. Write a `ControllerSecurityTest` case proving both the happy path and the 403 case.

---

## Rate limiting

Login attempts are rate-limited in `LoginRateLimiter` (5 failures / 15 min per email, in-memory).
There is currently no per-API rate limiting beyond this.
