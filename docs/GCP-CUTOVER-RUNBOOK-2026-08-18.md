# Custoking Cutover Runbook — Minimal-Downtime Migration to `custoking-dev` / `custoking-prod`

**Prepared:** 2026-08-18 IST
**Premise:** `custoking` will be deleted afterwards. **Data lost in this migration is not recoverable.**
**Accepted:** the production URL changes. No DNS layer is introduced.
**Target:** user-visible unavailability of **5–10 minutes**, with users remaining signed in.
**Companions:** `GCP-SPLIT-PROJECT-MIGRATION-PLAN-2026-08-18.md` (what to build),
`GCP-MIGRATION-DATA-LEDGER-PROD-2026-08-18.md` (data baseline),
`GCP-SOURCE-DELETION-CONTINUITY-2026-08-18.md` (what dies with the project).

---

## 1. Final "nothing is amiss" sweep

The complete data surface was enumerated against the live project. **There is nothing else.**

| Store | State | Migration treatment |
| --- | --- | --- |
| Cloud SQL PostgreSQL | prod `custoking_prod`, **146 MB** actual; dev stopped | export / import |
| Cloud Storage | prod photos **69 MB / 1,175 objects**; dev 0.53 MB / 18 | object copy, scoped set |
| BigQuery | `billing_export` only — **cost history, not application data** | export only if cost history matters |
| Secret Manager | 44 secrets, 1 enabled version each | value transfer ceremony |
| Google Drive | external; folders owned by an account, not the project | survives; only the OAuth client must be rebuilt |
| Pub/Sub | 5 subscriptions, **0 undelivered on every one** | recreate; nothing stranded |
| **Firestore / Datastore** | **none** — Firestore API not enabled | n/a |
| **Memorystore / Redis** | **none** — API not enabled | n/a |
| **Spanner, Bigtable** | **none** — APIs not enabled | n/a |
| Compute Engine | **no instances, no disks** | n/a |

Corroborating state, all verified: every schema's Flyway history is exactly repository + 1 (zero drift);
all three outboxes are 0 pending / 0 dead and the reporting inbox is 100% `PROCESSED`; **zero broken photo
references** (881 keys, all objects present). The only known defects are the two pre-existing ones in the
data ledger, both to be fixed *before* cutover.

### 1.1 Three properties that make this cutover unusually safe

1. **Users stay signed in.** JWTs carry only `role`, `uid`, `sid`, `zid`, `perms`, `ver`, `ops_schools` and
   the email subject — **no issuer, audience, or URL**. With `jwt-secret-prod` value-transferred and
   `auth_sessions` arriving in the dump, existing access tokens (15 min) and refresh tokens (7 days)
   validate unchanged in the destination. No forced re-login.
2. **No CORS or origin allowlist exists to update.** The frontend's nginx proxies `/api/v1/` server-side to
   `$API_UPSTREAM`, and the SPA calls the relative path `/api/v1`. The browser only ever talks to one
   origin, so there is no cross-origin configuration anywhere — confirmed by the absence of any CORS config
   in the gateway. The URL change therefore has no configuration blast radius.
3. **No inbound external integrations.** MSG91 is outbound-only and is not even live in production
   (`MSG91_DRY_RUN=true`, `NOTIFICATION_DELIVERY_PROVIDER=logging`). Nothing external calls in, so nothing
   external needs re-pointing.

---

## 2. Why not continuous replication

Database Migration Service with CDC would give near-zero downtime, but it requires
`cloudsql.logical_decoding` (and pglogical) on the **source**, and those are **restart-required flags**.
Enabling them costs a production restart *before* the migration begins — roughly the same outage as the
entire cutover below, for a 146 MB database that dumps and restores in two to four minutes.

**Recommendation: do not use DMS.** Pre-stage everything, then take one short, well-rehearsed write freeze.
The complexity of CDC is not justified at this data size, and every additional moving part is a new way to
lose data that cannot be recovered.

## 3. The switch mechanism, in the absence of DNS

There is no DNS layer, so there is no natural atomic switch — and two live environments would risk
split-brain writes from anyone still on the old URL.

**Use Cloud Run invoker IAM as the switch.** Only `frontend` and `api-gateway` are public
(`roles/run.invoker` → `allUsers`); the five backend services are already private and service-account
scoped.

- **Close the old environment:** remove `allUsers` from `custoking-frontend-prod` and
  `custoking-api-gateway-prod`. The old site becomes instantly unreachable — no writes possible.
- **Open the new environment:** add `allUsers` to the destination frontend and gateway.

IAM changes propagate in seconds, are trivially reversible, and require no deploy. This is the closest
thing to an atomic cutover available here, and it eliminates split-brain by construction.

---

## 4. Pre-cutover — completed days in advance

Nothing in this section happens in the outage window.

- [ ] **Drive OAuth client rebuilt** in the destination organization, re-consented, fresh refresh token
      stored. *Longest lead time — starts first; restricted scopes may need Google verification.*
- [ ] Destination billing access resolved; budgets and alert recipients exist.
- [ ] Both destination projects fully built per the migration plan (APIs, VPC + PSA, identities, WIF,
      Artifact Registry with the 7 live digests, buckets, Cloud SQL, secrets, Pub/Sub, Cloud Deploy,
      observability, **at least two notification channels**).
- [ ] `ARTIFACT_REGISTRY_PROJECT_ID` introduced at **repository level only**; every `|| 'custoking'`
      fallback converted to a hard failure.
- [ ] Destination Cloud Run services deployed at the exact production digests, **`allUsers` NOT yet
      granted**, and warmed with `min-instances=1` for the window.
- [ ] Destination URLs computed from the deterministic `<service>-<PROJECT_NUMBER>.<region>.run.app` form
      and wired into the Cloud Deploy target parameters (13 gateway `*_UPSTREAM` + frontend `API_UPSTREAM`).
- [ ] **Two pre-existing data defects repaired** — the `dim_student` off-by-one, and the 28 dead objects
      (17 superseded + 11 legacy-prefix) excluded or deleted. Do this now so the cutover comparison is
      unambiguous.
- [ ] **Full rehearsal**: import a copy of production into the destination, run the complete ledger, run
      `smoke-gateway-health`, `smoke-gateway-routes`, `smoke-microservice-features`,
      `test-application-logical-e2e`, and a Drive photo import. Then **reset the destination database** by
      the approved procedure.
- [ ] **Independent restore drill passes on the destination.** After deletion this is the only recovery
      path that will exist.
- [ ] Photos pre-synced to the destination bucket (copy only the in-scope set; exclude `temporary/` and the
      28 dead objects). Re-sync daily so the final delta is seconds.
- [ ] Cost-control schedule (`gcp-cost-controls.yml`, `30 14 * * *`) **suspended** for the window.
- [ ] New URL communicated to all 17 active users with the switch time.

---

## 5. The outage window

**Target: 5–10 minutes.** Times are cumulative from T0.

### T−15 — final safety net, no user impact
1. Take an **on-demand Cloud SQL backup** of `custoking-db-prod`.
2. Export a full dump to a bucket **in `custoking-prod`, not in `custoking`** — grant the source instance's
   service account write access on the destination bucket first. *The final export must live somewhere that
   survives the source project's deletion.*
3. Confirm outboxes are 0 pending / 0 dead and the inbox is fully `PROCESSED`.
4. Confirm all destination services are healthy and warm.

### T0 — freeze (writes stop here)
5. **Remove `allUsers`** from `custoking-frontend-prod` and `custoking-api-gateway-prod`.
6. Pause source Pub/Sub subscriptions and any relay.
7. Re-confirm outboxes drained — a final check that nothing was in flight at the moment of freeze.
8. Capture the **frozen source ledger**: exact row counts for all tables, Flyway state, the 881
   `students.photo_url` keys.

### T+2 — final data movement
9. Export the frozen production database and import into the empty destination database.
10. Create the `appuser` role with the transferred `db-password-prod` value.
11. Run `scripts/create-app-rt-role.sql` with the transferred `app-rt-password-prod` value, creating
    `app_rt` as `NOBYPASSRLS`. **The dump does not carry roles — skipping this breaks every service.**
12. Final incremental photo sync (seconds; almost everything is already there).

### T+5 — verify before opening
13. Recompute the destination ledger and compare to the frozen source ledger: **exact counts** (never
    `n_live_tup`), Flyway parity, and the photo-key set.
14. Photo integrity gate: every `students.photo_url` key must exist, matching size and CRC32C.
    **Scope the gate to `photo_url` only** — `photo_import_rows.source_object_key` dangles by design and
    would otherwise report hundreds of false failures.
15. Any mismatch → **stop**. Do not open. Reopen the source instead (section 7).

### T+7 — open
16. **Grant `allUsers`** on the destination frontend and gateway.
17. Enable destination Pub/Sub subscriptions.
18. Run `smoke-gateway-health`, `smoke-gateway-routes`, then one reversible write via
    `smoke-production-write-paths`.
19. Sign in as an existing user **with their existing password**, and confirm an already-issued token still
    works.
20. Exercise one school-scoped role action (SCHOOL_ADMIN or TEACHER) to prove
    `user_role_assignments` and RLS scoping survived — the coarse `app_users.role` column alone would not
    catch a failure here.
21. Publish the new URL.

### T+10 onward
22. Restore `min-instances=0`.
23. Watch 5xx rate, latency, SQL CPU/connections, outbox age and DLQ depth, auth failures, and gross cost.
24. Leave the source project **stopped but intact** for the agreed notice period.

---

## 6. What can go wrong, and the tell

| Failure | Tell | Response |
| --- | --- | --- |
| `app_rt` role missing or wrong password | Every service fails to connect at startup | Re-run the role script; this is step 11 |
| RLS not active because services connected as `appuser` | Tenant-scoped reads return **too many** rows | Stop immediately — this is a tenant-isolation breach, not a performance issue |
| JWT secret not transferred | All users forced to re-login | Not data loss; decide in advance whether acceptable |
| Photo gate fails on hundreds of objects | Almost certainly `source_object_key`, which dangles by design | Re-scope the gate to `photo_url`; do not abort on this alone |
| Row-count mismatch of 1–3 on reporting dimensions | Likely `n_live_tup` estimates | Re-check with exact `count(*)` before calling it a failure |
| Someone still on the old URL | Writes to the old database | Prevented by construction — `allUsers` was removed at T0 |

---

## 7. Abort

Abort is available **only until step 16**, and only because the source is still intact.

1. Do not grant `allUsers` on the destination.
2. Restore `allUsers` on the source frontend and gateway.
3. Re-enable source Pub/Sub subscriptions.
4. Verify source health and re-run the source ledger.
5. Nothing was written to the destination by users, so there is nothing to reconcile.

**After step 16, abort becomes reconciliation, not rollback** — any destination write must be merged back
deliberately. Prefer extending the freeze over opening on an ambiguous result.

---

## 8. Deletion — deliberately not part of cutover

Do **not** delete `custoking` in this window. Before deletion, separately:

- run the agreed notice period with the source stopped but intact;
- prove the destination's own backups and an independent restore drill;
- export anything that must outlive the project — the locked `_Required` audit bucket (400 days), the
  `custoking-compliance-india` logs (180 days), and the BigQuery billing export. **Locked buckets still die
  with the project.**

Deletion then has a ~30-day recoverable pending window. Treat that as the last safety net, not a formality.
