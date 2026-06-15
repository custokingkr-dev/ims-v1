# Custoking IMS — Demo Guide

Quick setup and demo flow for investor presentations.

---

## Start the app

```bash
# 1. Start PostgreSQL (Docker)
docker compose up postgres -d

# 2. Start backend
cd backend
APP_JWT_SECRET=demo-secret-key-minimum-32-characters \
APP_AADHAR_SECRET=demo-aadhar-key1 \
SUPERADMIN_PASSWORD=Admin@123456 \
DEMO_ADMIN_PASSWORD=Admin@123456 \
APP_BOOTSTRAP_USERS=true \
./mvnw spring-boot:run

# 3. Start frontend (new terminal)
cd frontend
npm ci
npm run dev
# → Open http://localhost:5173
```

> On first run, `APP_BOOTSTRAP_USERS=true` seeds 3 demo schools, 188 students, 13 FF requests, and 6 catalog orders automatically.

---

## Demo Credentials

| User | Email | Password | Notes |
|------|-------|----------|-------|
| Platform Admin | `superadmin@custoking.com` | `Admin@123456` | Full platform access, all schools |
| Demo School Admin | `admin@demo.custoking.com` | `Admin@123456` | Custoking Demo School, Hyderabad |
| Greenwood Admin | `admin@greenwood.custoking.com` | `Admin@123456` | Greenwood Academy, Bengaluru |
| Sunrise Admin | `admin@sunrise.custoking.com` | `Admin@123456` | Sunrise International, Mumbai |

> Passwords are set via `SUPERADMIN_PASSWORD` and `DEMO_ADMIN_PASSWORD` env vars. The values above are for local dev only — never use these in production.

---

## Seeded Demo Data

| School | Students | Orders | FF Requests |
|--------|----------|--------|-------------|
| Custoking Demo School (Hyderabad) | 80 | 6 | 9 (Draft/Pending/Approved/Fulfilled) |
| Greenwood Academy (Bengaluru) | 48 | — | 2 (DRAFT + AWAITING_BURSAR) |
| Sunrise International (Mumbai) | 60 | — | 2 (FULFILLED + AWAITING_PRINCIPAL) |

---

## Investor Demo Script (15 min)

### Act 1 — School Admin view (5 min)
1. Login as **Demo School Admin** (`admin@demo.custoking.com`)
2. **Command Center** loads — Priority Queue shows live action cards derived from DB
3. **Students** — 80 students with search, filter by class/section
4. **Fee Collections** — fee summary, overdue count, payment history
5. **School Orders → School Orders** — 6 catalog orders in various statuses

### Act 2 — Urgent Procurement workflow (5 min)
6. **Urgent Procurement → Request Pipeline** — 4-column kanban: Draft / Quotes submitted / Approved / Fulfilled
7. **New Request** — show the 4-step workflow: Raise → Quotes → Finance Review → Admin Approval
8. Click a card in "Quotes submitted" → **Approval Queue** — approve through Finance Review then Admin Approval
9. Click any request title → show the **approval timeline modal** (full audit trail)

### Act 3 — Platform Admin view (5 min)
10. Logout → Login as **Platform Admin** (`superadmin@custoking.com`)
11. **School accounts** — 3 schools, order value YTD, platform GMV stats
12. **Request Pipeline** — urgent requests from all 3 schools combined
13. **Approve & Fulfill** — move an APPROVED request to Custoking Fulfilment, then Mark as Delivered
14. Show that **school isolation works**: each school admin only sees their own data

---

## Key Differentiators to Emphasize

- **Multi-tenant RBAC**: ADMIN for School A cannot access School B data (enforced in backend, not frontend)
- **Transparent 3-stage approval**: Finance Review → Admin Approval → Custoking Fulfilment — full audit trail
- **Real-time Command Center**: Action cards computed from live DB state (pending approvals, overdue fees, submitted orders)
- **Single GST invoice**: Custoking aggregates all school procurement into one invoice per cycle
- **Module entitlements**: Schools subscribe to modules (Students, Fees, Orders, Urgent Procurement) — Platform Admin controls access

---

## If Something Goes Wrong

| Symptom | Fix |
|---------|-----|
| Login fails | Check `SUPERADMIN_PASSWORD` / `DEMO_ADMIN_PASSWORD` env vars |
| "No active academic year" | Set `APP_BOOTSTRAP_USERS=true` and restart backend once |
| Command Center shows mock cards | Backend `/api/v1/dashboard/command-centre` returning error — check logs |
| School not found error | Verify seed data: `APP_BOOTSTRAP_USERS=true` on first run only |
| 3 schools not visible | Ensure all env vars are set and backend started fresh with `APP_BOOTSTRAP_USERS=true` |
