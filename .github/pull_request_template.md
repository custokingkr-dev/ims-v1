## Description

<!-- What does this PR do? Why? Link the issue/ticket if applicable. -->

Closes #

---

## Type of change

- [ ] Bug fix
- [ ] New feature / endpoint
- [ ] Refactor (no behaviour change)
- [ ] Database migration
- [ ] Documentation / config only
- [ ] Security fix

---

## Checklist

### All PRs
- [ ] CI is green (backend-test, db-migration-test, frontend-build, secret-scan)
- [ ] No secrets, passwords, or tokens committed

### Backend changes
- [ ] `@PreAuthorize` uses a `PermissionConstants.*` constant (no raw SpEL strings)
- [ ] Every repository query that touches tenant data includes `WHERE school_id = ...`
- [ ] `moduleService.requireModule(...)` called on module-gated controller entry-points
- [ ] `@Valid` on all request DTOs
- [ ] Audit log written for every state-changing operation
- [ ] Unit test added/updated
- [ ] Integration test added/updated (covers both success and 403 cases)

### Database migrations
- [ ] Migration version is V126+ (never re-uses an existing number)
- [ ] Existing migration files are **not modified**
- [ ] New permission row added to `permissions` table (if endpoint introduces a new permission)
- [ ] Permission assigned to appropriate role(s) in `role_permissions`
- [ ] Tested against a fresh database with `mvn flyway:migrate`

### Frontend changes
- [ ] All CSS classes use the `ck-` prefix
- [ ] No hard-coded colors — uses `:root` CSS variables
- [ ] Actions gated behind `usePermissions().can('permission:code')`
- [ ] No `any` types without an explanatory comment
- [ ] `npm run build` passes with zero errors

---

## Testing

<!-- Describe how you tested this change. Include steps to reproduce or verify. -->

---

## Screenshots (if UI change)

<!-- Before / after screenshots or screen recording. -->
