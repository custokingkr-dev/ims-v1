# Firefighting Per-User Permission Enforcement — Design

**Date:** 2026-07-08
**Status:** Approved for planning
**Services touched:** identity-service (access-token claims), api-gateway (claim → header), operations-service (TenantContext + approval guards), frontend (Approvals panel gate alignment).

One of the two firefighting follow-ups deferred from the module-fix work: enforce the caller's **actual permission codes** on firefighting approval decisions inside operations-service. Today every firefighting write endpoint calls `requireToken(token, "firefighting:write")`, which validates only the **internal service token** — the permission-code argument is decorative, so any gateway-authenticated user can drive approvals. (The other deferred item — true bursar≠principal separation of duties — remains deferred; this work uses a **single** `firefighting:approve` code per the scope decision.)

## Decisions (settled during brainstorming)
- **Propagation:** embed the user's permission codes in the JWT access token; the gateway forwards them as a header; operations reads them from `TenantContext`. No new runtime cross-service dependency (unlike the module-entitlement client). Chosen over an operations→identity per-request lookup.
- **SoD depth:** **single** `firefighting:approve` code gates both approval stages (bursar + principal) and reject. Distinct `approve-bursar`/`approve-principal` codes and a same-user block are **deferred**.
- **Scope:** enforce per-user permission only on the **approval decisions** — `approve-bursar`, `approve-principal`, `reject`. `approve-custoking` keeps `requireSuperAdmin()`. Create / read / submit / fulfill are unchanged (this follow-up is about approvals).
- **Rollout:** no auto-seed of the code onto roles. Superadmin always bypasses; non-superadmin approvers must be granted `firefighting:approve` via RBAC admin. Documented, not migrated.

---

## Data flow

```
identity (login + refresh): access token carries the user's permission codes
    claim `perms` (String[]), token `ver` bumped 2 → 3
  → gateway: verifyJwtLocally + principalFromClaims read `perms` (ver >= 3);
    inject header `x-authenticated-permissions` (comma-separated) with the other x-authenticated-* headers
  → operations TenantContextFilter: parse the header into TenantContext.permissions (Set<String>)
  → firefighting approval endpoints: requirePermission("firefighting:approve") (superadmin bypass)
```

Access tokens are short-lived (`APP_JWT_EXPIRATION_MS=900000` = 15 min) and the SPA calls `refreshToken()` on mount, so the whole active fleet re-mints ver-3 tokens within one access-token lifetime; no coordinated cutover needed.

---

## Part 1 — Identity: permission codes in the access token

- `AuthenticatedUserSnapshot` today carries id/name/email/role/branch/zone (no permissions). Extend the token-generation path so the access token includes the user's permission codes.
  - The codes are already loaded at login via `rbac.permissionCodes(user.getId())` (see `IdentityAuthService.responseFor`). Thread the same sorted list into `generateAccessToken` (either widen `AuthenticatedUserSnapshot` or pass the codes alongside it — plan picks the smaller touch), and add a `perms` claim (a JSON string array of codes).
  - Bump the access-token `ver` claim **2 → 3**. Keep everything else (`uid`, `sub`, `role`, `sid`, `zid`, `exp`) identical.
  - The **refresh** path must mint ver-3 tokens too (it re-issues an access token), so refreshed sessions also carry `perms`.
- Do **not** put permissions in the refresh token (it is opaque/rotating; only the access token is read by the gateway).
- Token size: permission lists are small (tens of short codes); a few hundred bytes. Acceptable for a header/cookie-free bearer used server-side.

## Part 2 — Gateway: claim → header

- `principalFromClaims` (server.js) currently maps `uid/sub/role/sid/zid` and requires `ver >= 2`. Extend it to read `perms` when `ver >= 3` (default to empty when absent), exposing `principal.permissions` (array).
- Where the gateway injects `x-authenticated-*` headers, add `x-authenticated-permissions` = the codes joined by `,` (omit / empty string when there are none). This header is part of the same trusted set the gateway strips from inbound requests and re-injects (it must be in the "gateway-controlled" header allowlist so a client can't spoof it — verify against the existing `x-authenticated-` handling).
- ver-2 tokens (pre-rollout) simply have no `perms` → header absent/empty.

## Part 3 — operations: TenantContext + approval guard

- `TenantContext` gains `Set<String> permissions` (immutable, never null — empty set when the header is absent). Update its constructor, the `record`/fields, `TenantContextFilter` (parse `x-authenticated-permissions`, split on `,`, trim, drop blanks), and every existing `new TenantContext(...)` call site + test to pass the set (empty where not relevant).
- Add `TenantContext.hasPermission(String code)` and a guard `TenantScope.requirePermission(String code)` mirroring `requireSuperAdmin()`:
  - **superadmin** (`TenantContext.get().isSuperAdmin()`) → allow (bypass).
  - permissions set **non-empty** and does **not** contain the code → `403` "You do not have permission to approve firefighting requests".
  - permissions set **empty or lacking the code** → **403** (fail closed). *(REVISED: the original design allowed an empty set as a transitional fallback; a security review found this a permanent fail-open — the gateway injects an empty header for permission-less users — so it was changed to fail closed. See commit `0fd0abd`. Accepted trade-off: non-superadmin approvers holding a pre-ver-3 token are denied for ≤15 min post-deploy until their access token refreshes; superadmin always bypasses.)*
- Apply `TenantScope.requirePermission("firefighting:approve")` in `FirefightingReadController` on: `approve-bursar` (:196), `approve-principal` (:207), `reject` (:228). Leave `approve-custoking` on `requireSuperAdmin()`. Leave create/quotations/submit/fulfill/vendor-paid as-is.

## Part 4 — Frontend: align the Approvals gate

- `FirefightingApprovalsPanel.tsx` currently gates the approve/reject actions on the ad-hoc `can('firefighting:write')`. Switch to the catalog constant `FIREFIGHTING_APPROVE` (`'firefighting:approve'`, already defined in `frontend/src/shared/permissions/permissions.ts`) so the FE gate and the new backend guard agree on one code. Custoking button stays superadmin-gated.

---

## Error handling
| Condition | HTTP | Message |
|---|---|---|
| non-superadmin with `perms` present but missing `firefighting:approve` hits approve-bursar/principal/reject | 403 | "You do not have permission to approve firefighting requests" |
| non-superadmin with empty/absent permissions (incl. pre-ver-3 token) hits an approval endpoint | 403 | fail closed (REVISED from transitional-allow; commit `0fd0abd`) |
| superadmin | (allow) | bypass |
| approve-custoking by non-superadmin | 403 | existing `requireSuperAdmin()` |

## Testing
- **identity:** the generated access token has `ver == 3` and a `perms` claim equal to the user's sorted permission codes; refresh also mints ver-3 with `perms`.
- **gateway (node --test):** `principalFromClaims` returns `permissions` for a ver-3 claim and `[]` for ver-2; the upstream request carries `x-authenticated-permissions` for ver-3 and not for ver-2; a client-supplied `x-authenticated-permissions` on the inbound request is stripped/overwritten (not trusted).
- **operations:** `requirePermission` → 403 when the set is non-empty and lacks the code; allow when it contains the code; allow for superadmin; allow when the set is empty (transitional). `TenantContextFilter` parses the CSV header. The three approval endpoints reject a non-permitted caller and allow a permitted one.
- **FE:** no tests (repo convention) — `npm run build` clean; the panel imports `FIREFIGHTING_APPROVE`.

## Files (indicative — plan pins exact lines)
**identity-service:** `application/IdentityAuthService.java` (thread perms), the JWT service (`generateAccessToken` + `perms` claim + `ver` 3), `AuthenticatedUserSnapshot` (if widened), tests.
**api-gateway:** `server.js` (`principalFromClaims` + header injection + trusted-header handling), `server.test.js`.
**operations-service:** `security/TenantContext.java` (+permissions), `security/TenantContextFilter.java` (parse header), `security/TenantScope.java` (`requirePermission`), `api/FirefightingReadController.java` (guard the three endpoints), all `new TenantContext(...)` call sites/tests, new guard tests.
**frontend:** `pages/workspace/panels/FirefightingApprovalsPanel.tsx` (gate on `FIREFIGHTING_APPROVE`).

## Deferred (unchanged)
- True separation of duties (distinct bursar/principal permission codes + same-user block) — needs role modeling; not built here.
