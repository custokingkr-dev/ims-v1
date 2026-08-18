# Production Migration Plan — `custoking` to `custoking-prod`

**Prepared:** 2026-08-18, after `custoking-dev` was migrated, verified and left running.
**Supersedes for execution:** the procedural parts of `GCP-CUTOVER-RUNBOOK-2026-08-18.md`.
**Companions:** `GCP-MIGRATION-DATA-LEDGER-PROD-2026-08-18.md` (measured data),
`GCP-SOURCE-DELETION-CONTINUITY-2026-08-18.md` (what dies with the project),
`DRIVE-OAUTH-REBUILD-PLAN.md` (the last tether to the source project).

This is not a theoretical plan. Every step below was executed against `custoking-dev` first, and the
failures it produced are already fixed in the scripts. **Thirteen defects surfaced in the rehearsal, and
every one of them would otherwise have hit production.**

The destination was then built and loaded ahead of the window, which produced **two more that the
rehearsal could not have found** — they live in the ways production genuinely differs from dev. Rehearsing
on dev is necessary and it is not sufficient; build production early enough that its own defects have
somewhere to surface other than the window.

---

## 1. What is different about production

| | dev (done) | prod (this plan) |
| --- | --- | --- |
| Cloud SQL | `db-f1-micro`, no backups | **`db-g1-small`, backups + PITR + deletion protection** |
| Source database | was STOPPED, had to be started | already RUNNABLE |
| Data volume | 0.5 MB photos, tiny database | **146 MB database, 69 MB across 1,175 photo objects** |
| Release path | direct Cloud Run deploy | **Cloud Deploy release and rollout** |
| GitHub environment | no reviewers | **required reviewers** |
| Live sessions | irrelevant | **378 unexpired, so the JWT secret must transfer** |
| Pub/Sub | complete | **incomplete at source, see section 6** |
| Traffic | nobody watching | real users, and the URL changes |

---

## 2. The order that must not be reversed

**Pin dev explicitly before touching any repository-level variable.**

`build-images` declares no `environment:`, so it resolves **repository-level** variables, while the deploy
jobs resolve **environment-level** ones. The dev migration handled this by pinning prod first. Doing prod
now inverts it: repository-level variables currently point at `custoking-dev`, so changing them without
pinning dev first would drag dev into the wrong project.

Before anything else, confirm the dev environment pins all four of `GCP_PROJECT_ID`,
`GCP_PROJECT_NUMBER`, `WORKLOAD_IDENTITY_PROVIDER` and `ARTIFACT_REGISTRY_PROJECT_ID`. They already do.
Verify it, do not assume it.

---

## 3. The Artifact Registry decision, made concrete

Production never builds. It promotes by resolving a `dev-approved-<sourceId>` tag, so it must read the
registry that the dev branch builds into, which is now `custoking-dev`.

- keep `ARTIFACT_REGISTRY_PROJECT_ID = custoking-dev` at repository level;
- set it explicitly on the **prod** environment to `custoking-dev` as well;
- grant `custoking-prod`'s Cloud Deploy deployer and release identity `roles/artifactregistry.reader` on
  `projects/custoking-dev/locations/asia-south2/repositories/custoking`.

That makes `custoking-dev` a build project which production reads from. It is a deliberate, read-only
coupling. The alternative, copying digests at promotion time, reintroduces the egress line that was
eliminated at some cost unless the copy runs inside `asia-south2`.

Running services are unaffected if that registry is ever unavailable; only new deployments are.

---

## 4. Before the window — no user impact

- [ ] `Invoke-DestinationBuild.ps1 -Environment prod`, stages 10 to 90. Creates APIs, VPC and Private
      Service Access, Cloud SQL, buckets, identities, secret shells, Artifact Registry and Pub/Sub. It is
      idempotent and safe to re-run.
- [ ] `Copy-MigrationSecrets.ps1 -Environment prod`, then again with `-Verify`. **All must report
      identical.** `jwt-secret-prod` above all: regenerating it signs out 378 live sessions.
- [ ] Apply `infra/terraform/cicd` with a `custoking-prod.tfvars` setting `environments = ["prod"]`,
      `enable_dev_identities = false`, and `billing_export_dataset = ""` until the billing account is
      reachable.
- [ ] Apply `deploy/gcp/observability` against `custoking-prod` with `manage_compliance_logging = true`
      and **two** notification channels. The source has one, which fails its own runbook's requirement.
- [ ] Copy the seven live production image digests with `docker buildx imagetools create` — registry to
      registry, no pull to any workstation, digest verified after each copy.
- [ ] Pre-sync photos with the exclusions in section 5, and re-sync right up to the freeze.
- [ ] Create `ims-app-rt-prod` and the other operational jobs.
- [ ] **Rehearse the data move end to end into the destination, then reset the database.** Dev proved the
      import can fail in a way that leaves the database empty; see that here, not at cutover.
- [ ] Prove destination backups, PITR and an **independent restore drill**. Once `custoking` is deleted
      this is the only recovery path that exists.
- [ ] Compute the fourteen destination URLs as `<service>-<prod-project-number>.asia-south2.run.app` and
      wire them into the Cloud Deploy target parameters. They are knowable before anything is deployed,
      which is what removes the bootstrap ordering problem for the gateway's thirteen upstreams.
- [ ] Publish the new URL to users along with the switch time.

---

## 5. The data copy set, from the measured ledger

| Set | Objects | Action |
| --- | --- | --- |
| Photos referenced by `students.photo_url` | 881 | **copy, and verify key, size and CRC32C exactly** |
| Import workbooks (`workbook_object_key`) | 5 | copy |
| Other `schools/*/student-imports` objects | about 139 | copy if present; do not fail on absence |
| `temporary/photo-imports/` | 122 | **do not copy** — transient and lifecycle-managed |
| Superseded, unreferenced photos | 17 | **do not copy** |
| Legacy numeric-prefix objects | 11 | **do not copy** |

`Copy-MigrationData.ps1` excludes `^temporary/.*|^students/.*|^student-imports/.*`. The trailing `.*` on
each alternative is load-bearing: `gcloud storage rsync --exclude` matches the **whole object name**, so
the earlier prefix-only form silently excluded nothing from `temporary/` (defect 15).

### Three integrity rules that prevent a false NO-GO

1. **Scope the photo gate to `students.photo_url` only.** `photo_import_rows.source_object_key` is
   populated on 811 rows, but its objects are lifecycle-deleted by design. A naive "every key must exist"
   check reports hundreds of missing objects and fails a perfectly healthy migration.
2. **Compare source to destination, never to an invariant.** `dim_student` holds 1,256 rows against 1,257
   students because five students are soft-deleted and four stale dimension rows predate the projection
   handling deletions. A gate asserting `dim_student == students` fails on healthy data.
3. **Use exact `count(*)`, never `n_live_tup`.** During the ledger work the estimate reported
   `dim_section` as 379 against a real 376.
4. **Run one whole-database digest on both sides, with the identical query.** The per-relation table in the
   ledger is a curated selection, so matching it proves nothing about the relations it omits. Run this on
   source and destination and compare the three values — it covers every user relation, not a chosen subset:

   ```sql
   SELECT count(*) AS relations, sum(c) AS rows,
          md5(string_agg(t || ':' || c, ',' ORDER BY t)) AS digest
   FROM (SELECT schemaname || '.' || relname AS t,
                (xpath('/row/c/text()', query_to_xml(
                   format('select count(*) as c from %I.%I', schemaname, relname),
                   false, true, '')))[1]::text::bigint AS c
         FROM pg_stat_user_tables) s;
   ```

   Measured on `custoking-prod` after the pre-load: **107 relations, 38,438 rows,
   digest `dc8555e71be3c55f90eb98a6f86067f1`.** That figure is the *pre-load* state and will move when the
   frozen dump is re-imported at T0; what must match is source against destination at that moment, both
   sides queried the same way. Note it is 107 relations, not the ledger's 66 — the difference is exactly
   why the digest, and not the curated table, is the gate.

### Restore drill — executed 2026-08-18, PASSED

Backups being *configured* is not evidence they are *restorable*. A point-in-time clone of
`custoking-db-prod` was taken and verified before the window, then deleted. PITR was used deliberately over
a plain backup restore because it exercises the write-ahead-log chain as well as the backup file.

| Check | Destination | Restored clone | Verdict |
| --- | --- | --- | --- |
| relations / rows / digest | 107 / 38,438 / `dc8555e7…` | 107 / 38,438 / `dc8555e7…` | **identical** |
| policies / RLS tables / schemas | 66 / 66 / 13 | 66 / 66 / 13 | identical |
| `appuser`, `app_rt` roles | present, `app_rt` NOBYPASSRLS | present, `app_rt` NOBYPASSRLS | identical |
| students / users / sessions / schools | — | 1,257 / 49 / 382 / 11 | matches the ledger |

Two things worth carrying into the window. **A clone inherits deletion protection**, so the drill instance
cannot be removed until `--no-deletion-protection` is applied first — budget for that rather than
discovering it while trying to clean up. And roles survive a clone even though they do **not** survive
`pg_dump`; the recovery path and the migration path therefore differ precisely on the thing most likely to
be forgotten.

---

## 6. Fix production's own gaps while rebuilding it

The source production environment is missing things dev has. Create them in `custoking-prod` rather than
faithfully reproducing the gap:

- `ims-reporting-dead-letter-v1-prod` topic, and a dead-letter policy on the reporting push subscription;
- `ims-notification-service-push-prod` subscription — production has the topic but **no subscription at
  all**, so notification events currently have nowhere to go;
- `ims-notification-push-prod` service account, which does not exist in the source.

Stage 80 of the build script already provisions all three.

---

## 7. The window

Target **5 to 10 minutes** of unavailability. Users stay signed in, because JWTs carry no issuer, audience
or URL, and `auth_sessions` migrates with the database.

### T−30, no user impact
1. Take an on-demand Cloud SQL backup of `custoking-db-prod`.
2. Export a dump to a bucket **in `custoking-prod`, not in `custoking`**, so the last copy survives the
   source project's deletion. Grant the source instance's service account write on it first.
3. Confirm outboxes are 0 pending and 0 dead, and the inbox is fully `PROCESSED`.
4. Confirm destination services are healthy and warm at `min-instances=1`.

### T0 — freeze
5. **Remove `allUsers`** from `custoking-frontend-prod` and `custoking-api-gateway-prod`. This is the
   atomic switch; there is no DNS layer. It propagates in seconds and eliminates split-brain writes by
   construction.
6. Pause source Pub/Sub subscriptions.
7. Re-confirm the outboxes are drained.
8. Capture the frozen source ledger: exact row counts, Flyway state, and the 881 photo keys.

### T+2 — data
9. Export the frozen database, **sanitize the dump** (defect 2 below), then import.
10. Create `appuser` with the transferred `db-password-prod` value.
11. Run `create-app-rt-role.sql` **before the import** so `app_rt` exists, and **again after** to apply
    schema grants. The dump carries GRANT and RLS statements naming `app_rt`.
12. Final incremental photo sync.

### T+5 — verify before opening
13. Recompute the destination ledger and compare with the frozen source ledger under the section 5 rules.
14. Photo gate: every `students.photo_url` key present, with matching size and CRC32C.
15. **Any mismatch stops the cutover.** Do not open; restore source routing instead.

### T+7 — open
16. Grant `allUsers` on the destination frontend and gateway.
17. Enable the destination Pub/Sub subscriptions.
18. Run `smoke-gateway-health`, then `smoke-gateway-routes` — expect 31 of 31.
19. **Log in as a real existing user with their existing password.** That single check proves BCrypt
    verification, the transferred JWT secret, `app_rt` database access and RBAC all at once.
20. Exercise a school-scoped role action to prove `user_role_assignments` and RLS survived. Checking the
    coarse `app_users.role` column alone would not catch a failure here.
21. Fetch a student photo through the service, which proves Cloud Storage end to end.
22. Publish the new URL.

### T+10 onward
23. Restore `min-instances=0`.
24. Watch 5xx rate, latency, SQL CPU and connections, outbox age, dead-letter depth, and auth failures.
25. Leave `custoking` stopped but intact for the agreed notice period.

---

## 8. The fifteen defects, and the two that only production could produce

All are already fixed in the scripts or the repository. They are listed so that a failure at two in the
morning is recognised rather than debugged. Rows 1–13 came from the dev rehearsal; rows 14–15 came from
building `custoking-prod` itself and **could not have been caught on dev at any level of diligence**.

| # | Defect | Where it bites |
| --- | --- | --- |
| 1 | Cloud SQL now defaults to **ENTERPRISE_PLUS**, which rejects shared-core tiers | instance creation; `db-g1-small` fails identically |
| 2 | **The import rolls back entirely** on trailing `ALTER DEFAULT PRIVILEGES` and leaves the database empty | data load; stripping them is lossless |
| 3 | **Service-to-service invoker IAM** missing, so every API call is a bare Cloud Run 403 while each service looks healthy | after deploy |
| 4 | `metadata.namespace` carries the source project number and Cloud Run rejects the apply | service deploy |
| 5 | The rollback identity needs **`artifactregistry.reader` and `actAs`**, masked before because dev rolled back as the shared account | discovered *after* the traffic decision is taken |
| 6 | The photo bucket and Drive folder id must be **deploy parameters**, not derived from the environment name | Cloud Deploy otherwise renders the old project's bucket |
| 7 | `render-clouddeploy-targets.ps1` hardcoded `@custoking.iam` in its validation | rejects every destination target |
| 8 | Workflows must forward **`GCP_PROJECT_NUMBER`** and `STUDENT_PHOTO_BUCKET` | rendering fails |
| 9 | Cloud SQL reports RUNNABLE while an operation is still settling, giving **409** | export or import immediately after starting an instance |
| 10 | Service-account creation is **rate limited per minute** | 429 partway through a batch |
| 11 | PowerShell 5.1 raises **`NativeCommandError`** on redirected native stderr | scripts throw on successful commands |
| 12 | **CRLF** line splitting silently produced zero secrets *and reported success* | secret transfer |
| 13 | **`gcloud` consumes stdin inside shell loops**, so only the last item processes | any bulk loop; add `</dev/null` |
| 14 | **Production only.** The captured `traffic` block pins `revisionName` to a **source** revision that does not exist in the destination, so routing fails with *"Revision does not exist or is deleted"* while the container is perfectly healthy. The same capture also pins `spec.template.metadata.name`, so a failed first attempt can never be superseded — every retry collides with the same dead revision. | service deploy; **structurally invisible on dev**, which deploys directly with `latestRevision: true` while prod is deployed by Cloud Deploy, which pins revisions explicitly |
| 15 | **Production only.** `gcloud storage rsync --exclude` matches against the **whole object name**, not a prefix, so `^temporary/` silently excluded nothing while `^students/` and `^student-imports/` worked. 122 transient objects were copied. Each alternative needs a trailing `.*`. | photo copy; dev's bucket had no populated `temporary/` tree, so the broken alternative never had anything to fail against |

Two further traps worth holding in mind. Terraform here authenticates with an access token because there
is no Application Default Credentials on the workstation, and that token expires after about an hour — a
stale one reads as a 401 against state, which looks alarmingly like state loss but is not. And
`gcloud deploy targets list --format=json` misreports; use the REST API.

---

## 9. Rollback

Available **only until step 16**, and only because the source is intact.

1. Do not grant `allUsers` on the destination.
2. Restore `allUsers` on the source frontend and gateway.
3. Re-enable the source Pub/Sub subscriptions.
4. Verify source health and re-run the source ledger.
5. No user wrote to the destination, so there is nothing to reconcile.

After step 16 this becomes reconciliation rather than rollback. **Prefer extending the freeze to opening
on an ambiguous result.**

---

## 10. After production is stable

- [ ] **Re-enable `gcp-cost-controls`.** It is currently disabled so that dev's database survives the
      migration window; left off, dev runs 24/7 indefinitely.
- [ ] Point `DEV_CLOUDSQL_INSTANCE` and `DEV_GCP_PROJECT_ID` at whichever dev instance should be stopped.
- [ ] Share the production Drive root folder with `ims-school-core-prod@custoking-prod...` and set
      `STUDENT_PHOTO_IMPORT_CREDENTIAL_MODE=service-account`. This is proven in dev: existing folders are
      reused rather than duplicated, and it removes the OAuth client, the verification requirement and the
      CASA assessment entirely.
- [ ] Retire the six `student-photo-import-drive-oauth-*` secrets.
- [ ] Delete the remaining old-dev orphans in `custoking`: 20 secrets, 10 service accounts, the old photo
      bucket, and `custoking-db-dev`.
- [ ] Export anything that must outlive the project — the **locked** `_Required` audit bucket at 400 days,
      `custoking-compliance-india` at 180 days, and the BigQuery billing export. **Locked buckets still
      die with the project.**
- [ ] Only then begin deleting `custoking`, treating its roughly 30-day pending window as the final
      safety net.
