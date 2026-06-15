# Custoking IMS — Investor Demo Checklist

Quick reference for running the investor demo. All flows below work end-to-end against the seeded demo data.

---

## Demo Credentials

| Role | Email | Password env var |
|------|-------|-----------------|
| Platform Admin (Custoking) | `superadmin@custoking.com` | `SUPERADMIN_PASSWORD` |
| School Admin (Demo School) | `admin@demo.custoking.com` | `DEMO_ADMIN_PASSWORD` |
| School Admin (Greenwood Academy) | `admin@greenwood.custoking.com` | `DEMO_ADMIN_PASSWORD` |
| School Admin (Sunrise International) | `admin@sunrise.custoking.com` | `DEMO_ADMIN_PASSWORD` |

> All school admin users are created automatically on startup when `DEMO_ADMIN_PASSWORD` and `APP_BOOTSTRAP_USERS=true` are set.

---

## Pre-demo Setup

- [ ] `APP_BOOTSTRAP_USERS=true` (first run only; set to `false` after seed)
- [ ] `DEMO_ADMIN_PASSWORD` env var is set
- [ ] Backend is running and `/actuator/health` returns `{"status":"UP"}`
- [ ] Frontend dev server or production build is accessible
- [ ] DB has seed data: 188 students across 3 schools, 13 urgent procurement requests, 6 catalog orders

---

## Flow 1 — School Admin View (Primary Demo)

**Login as:** `admin@demo.custoking.com`

- [ ] **Command Center loads** — Priority Queue shows real action cards derived from DB state
- [ ] **Students panel** — 80 students visible, search + filter works
- [ ] **Fee Collections** — fee summary shows collected/outstanding amounts
- [ ] **Supply OS → School Orders** — 6 catalog orders listed with status
- [ ] **Urgent Procurement → Request Pipeline** — pipeline board shows 4 columns (Draft / Quotes submitted / Approved / Fulfilled)
- [ ] **Urgent Procurement → New Request** — create a new urgent request, fill form, submit
- [ ] **Urgent Procurement → Approval Queue** — approve the submitted request through Finance Review and Admin Approval
- [ ] **Logout** cleanly redirects to login page

---

## Flow 2 — Platform Admin View (Investor Overlay)

**Login as:** `superadmin@custoking.com`

- [ ] **Platform Admin badge** visible in topbar
- [ ] **Operations → Order approvals** — supply orders from all schools
- [ ] **Urgent Procurement → Request Pipeline** — all schools' requests visible
- [ ] **Urgent Procurement → Approve & Fulfill** — move an approved request to "Move to Fulfilment", then "Mark as Delivered"
- [ ] **Schools → School accounts** — 3 schools (Demo, Greenwood, Sunrise) listed with student count and order value
- [ ] **Analytics → Revenue** — placeholder shown (coming soon)

---

## Flow 3 — Urgent Procurement End-to-End (Key Differentiator)

Demonstrate the 3-stage approval workflow:

1. **School Admin creates request** → status: `DRAFT`
2. **School Admin adds quotations** (2–3 vendors) → status: `AWAITING BURSAR`
3. **School Admin approves Finance Review** → status: `AWAITING PRINCIPAL`
4. **School Admin approves Admin Approval** → status: `APPROVED`
5. **Platform Admin moves to fulfilment** → status: `CUSTOKING APPROVED`
6. **Platform Admin marks delivered** → status: `FULFILLED`

Each transition shows the full audit timeline in the tracking modal (click any request title).

---

## Known Demo Data

| Entity | Count |
|--------|-------|
| Schools | 3 (Demo School, Greenwood Academy, Sunrise International) |
| Students | 188 (80 + 48 + 60) |
| Staff | 2 |
| Catalog orders | 6 (Demo School only) |
| Urgent procurement requests | 13 (9 Demo + 2 Greenwood + 2 Sunrise) |
| Fee bands | 3 |

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Login fails for demo admin | Check `DEMO_ADMIN_PASSWORD` env var is set |
| Command Center shows mock data | Backend `/api/v1/dashboard/command-centre` may be returning an error — check logs |
| "School not found" on workspace load | `TenantContext` not set — check `TenantResolverFilter` in logs |
| Seed data missing | Set `APP_BOOTSTRAP_USERS=true` and restart backend once |
| CORS error in browser | Ensure frontend proxy is configured (`/api → localhost:8080`) |
