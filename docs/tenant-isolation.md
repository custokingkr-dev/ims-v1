# Tenant Isolation

Custoking IMS is a **multi-tenant** platform where each school is an isolated tenant.
Isolation is enforced **entirely at the application layer** — one shared PostgreSQL database
is used and every query is scoped by `school_id`.

---

## Isolation model

```
HTTP request
  └─ JwtAuthFilter            (validates token, loads permissions)
       └─ TenantResolverFilter (derives schoolId from RBAC → sets TenantContext)
            └─ @PreAuthorize   (permission check via RbacService)
                 └─ Controller  (calls moduleService.requireModule + service)
                      └─ Service / Repository
                           └─ WHERE school_id = TenantContext.get()
```

### TenantContext (ThreadLocal)

```java
// Reading the current tenant in a service
Long schoolId = TenantContext.get();   // null for platform admins

// Always clear on request completion (done by filter)
TenantContext.clear();
```

`TenantContext` is a `ThreadLocal<Long>` holding the current `school_id`.
It is **null** when the authenticated user is a platform admin (`isSuperadmin() == true`);
such users bypass all tenant filters.

### TenantResolverFilter

Derives the `schoolId` by inspecting the user's effective `user_role_assignments`.
If a request header `X-School-Id` is provided by an admin, it is validated against the
user's allowed schools before being honoured.

### TenantDataSourceConfig

On every connection borrow from HikariCP, the filter sets:

```sql
SET app.current_school_id = '<schoolId>';
```

This Postgres session variable is retained so that any future re-enablement of
Row-Level Security (RLS) policies can read it without further filter changes.

---

## Data isolation guarantees

| Layer | Mechanism |
|-------|-----------|
| RBAC | `user_role_assignments.school_id` scopes what a user can access |
| Service layer | All service methods call `TenantContext.get()` before querying |
| Repository layer | Every `@Query` / derived query includes `school_id = :schoolId` |
| Platform admin | Bypasses filters; used only for cross-tenant admin tasks |

### Module entitlement check

Beyond tenant isolation, each school subscribes to individual modules.
Every module-gated endpoint must call:

```java
moduleService.requireModule(TenantContext.get(), Module.STUDENTS);
// throws ModuleNotEntitledException (403) if the school hasn't subscribed
```

`requireModule(null, module)` is a no-op — platform admins bypass entitlement checks.

---

## PostgreSQL RLS — current status and future path

Row-Level Security was **disabled** in migration `V117` while the application-layer model
was hardened.  The session variable `app.current_school_id` is already written on every
request to make re-enablement straightforward.

**To re-enable RLS (future Phase 2):**

1. Create a new migration (V126+) that:
   - Calls `ALTER TABLE <table> ENABLE ROW LEVEL SECURITY`
   - Creates a policy:
     ```sql
     CREATE POLICY tenant_isolation ON <table>
       USING (school_id = current_setting('app.current_school_id')::bigint);
     ```
2. Grant the `ims_app` DB user `SELECT / INSERT / UPDATE / DELETE` but **not** `BYPASSRLS`.
3. Ensure `FLYWAY_USERNAME` (the migration user) has `BYPASSRLS` or is a superuser so
   migrations can insert seed data across all tenants.
4. Test with `AbstractIntegrationTest` — Testcontainers will spin up a fresh DB.

**Do not re-enable RLS** until integration tests covering cross-tenant isolation
(i.e., asserting that a user from school A cannot see school B records) are in place.

---

## Security boundaries

| Boundary | Enforced? | Mechanism |
|----------|-----------|-----------|
| Cross-school data reads | ✅ Yes | `WHERE school_id = TenantContext.get()` in all queries |
| Cross-school writes | ✅ Yes | Same; plus `forbidSuperAdmin()` guard on mutation endpoints |
| Module entitlement | ✅ Yes | `moduleService.requireModule(...)` at controller entry |
| DB-level RLS | ❌ Disabled | See above — Phase 2 |
| DB schema isolation (separate schemas/DBs) | ❌ Not implemented | Shared schema by design |

---

## What the tenant cannot affect

- JWT signing key — shared platform secret, not per-tenant
- RBAC roles and permissions — administered by SUPERADMIN or ZONE_ADMIN
- Other tenants' data — query scoping prevents cross-tenant access
- Platform APIs (`/api/v1/zones`, `/api/v1/schools` management) — protected by `platform:admin`

---

## Known risks and mitigations

| Risk | Mitigation |
|------|-----------|
| `TenantContext` not cleared after request | `TenantResolverFilter` calls `TenantContext.clear()` in a `finally` block |
| Virtual threads reusing ThreadLocals | Spring Boot 3.4 + JDK 21 virtual threads use a new `ThreadLocal` per virtual thread; no leak risk |
| Platform admin misuse | `forbidSuperAdmin()` guard prevents platform admins from mutating school ERP data |
| Missing `school_id` predicate in a new query | Code review checklist item in CONTRIBUTING.md |
