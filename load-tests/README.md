# Scale and Attendance Load Tests

These assets are restricted to `dev`/localhost and use the reserved school id range beginning at
`900000000`. They do not copy production PII.

## Safety and cost posture

- `scripts/invoke-scale-fixture.ps1` accepts only `dev`.
- Seed and cleanup require the explicit `-AllowScaleWrites` switch.
- The seed refuses to continue if a non-scale school occupies the reserved range.
- The Cloud Run job scales to zero and has no idle compute cost.
- Do not seed 300,000 students into the shared-core `db-f1-micro`. Temporarily use the
  `db-custom-4-7680` candidate shape for a 300-VU run, keep the test window bounded, and restore the
  cheaper shape after certification. Four vCPU passed the short burst but is not certified until the
  fixed full soak and mixed-read reruns pass. A two-vCPU shape is not certified at 300 VUs.
- Always run `Status` before a test. Run `Cleanup` after the final evidence run; when a failed run is
  under diagnosis, preserve the fixture only under an explicit handoff so the same data shape can be
  reused without reseeding.
- The fixture creates `scale-load-superadmin@custoking.local` with the documented local password
  `password` only while the dev fixture exists. Cleanup hard-deletes its sessions, role and user.
- The evening GCP cost-control workflow can stop dev Cloud SQL during a test window. Coordinate the
  bounded test with that schedule; do not disable the savings policy permanently.

## Fixture sizes

Single 10,000-student school:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action Seed -AllowScaleWrites -SchoolCount 1 -TotalStudents 10000 `
  -LargeSchoolStudents 10000 -OutputJson artifacts\scale-seed-10k.json
```

Fleet model with 100 schools, 300,000 students total, and one 10,000-student school:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action Seed -AllowScaleWrites -SchoolCount 100 -TotalStudents 300000 `
  -LargeSchoolStudents 10000 -OutputJson artifacts\scale-seed-300k.json
```

Read-only status/diagnostics and cleanup:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action Status -OutputJson artifacts\scale-status.json

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action Diagnostics -OutputJson artifacts\scale-diagnostics.json

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action Cleanup -AllowScaleWrites -OutputJson artifacts\scale-cleanup.json
```

`ScaleBacklogCleanup` is a narrower recovery action for a failed synthetic repeated-write test. It
requires `-AllowScaleWrites`, verifies the expected count of `SCALE-%` schools and refuses any
non-scale school in the reserved range, then removes only reporting-inbox and school-outbox rows for
those verified ids. It records before/deleted/after and outside-scope counts in PII-free evidence; it
does not delete schools, students, attendance rows, or reporting facts. Before using it, keep all
Scheduler jobs paused, verify the reporting Pub/Sub subscription has no undelivered messages, and
verify the relevant Cloud Run services are idle so a concurrent push/relay cannot recreate rows.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action ScaleBacklogCleanup -SchoolCount 100 -AllowScaleWrites `
  -OutputJson artifacts\scale-backlog-cleanup.json
```

The seed distributes students into sections of at most 40 across 12 synthetic classes, enables
`STUDENTS`, `ATTENDANCE`, and `REPORTS`, analyzes the affected tables, and emits exact counts.
`Diagnostics` is read-only and captures active PostgreSQL query shapes, wait events, blockers,
transaction ages, table/index statistics, exact scale-scope outbox/inbox counts, and the planner
shape for the school-core relay claim.

`QueryPlans` executes read-only `EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)` probes against the
300,000-student fixture and records whether enough multi-year attendance rows exist to call the
history result certified. A plan captured without at least 7,300,000 rows across at least 700 days
for the synthetic 10,000-student school is diagnostic evidence only, never long-history certification:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action QueryPlans -OutputJson artifacts\scale-query-plans.json
```

After the 300,000-student fleet exists, the guarded long-history action replaces attendance only
for reserved school `900000000` with 730 synthetic days: exactly 7,300,000 per-student records plus
182,500 section/day and reporting-fact rows. It refuses a non-synthetic school or a school that does
not contain exactly 10,000 students:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action LongHistorySeed -AllowScaleWrites `
  -OutputJson artifacts\scale-long-history-seed.json
```

## Attendance write workload

Use short-lived dev access tokens with `attendance:read` and `attendance:manage`. For a multi-hour
synthetic certification, `K6_LOGIN_EMAIL` and `K6_LOGIN_PASSWORD` let each VU renew its own synthetic
access token every 12 minutes; neither credentials nor tokens are written to evidence. The workload
reads each synthetic section register, then writes all students in one section request. It refuses
non-dev URLs, requires `ALLOW_SCALE_WRITES=1`, and refuses section ids outside the scale namespace.
It partitions sections into disjoint per-VU ownership so the harness does not manufacture lock
contention by writing the same register concurrently.

Example 100-VU stage using the containerized k6 client:

```powershell
$env:K6_ACCESS_TOKENS = '<token-1>,<token-2>,<token-3>'
docker run --rm `
  -e BASE_URL=https://custoking-api-gateway-dev-l7mhms5c2a-em.a.run.app `
  -e K6_ACCESS_TOKENS `
  -e ALLOW_SCALE_WRITES=1 `
  -e SCALE_SCHOOL_COUNT=100 `
  -e SCALE_TOTAL_STUDENTS=300000 `
  -e SCALE_LARGE_SCHOOL_STUDENTS=10000 `
  -e PEAK_VUS=100 -e RAMP_UP=2m -e HOLD=10m -e RAMP_DOWN=2m `
  -v "${PWD}\load-tests:/scripts:ro" `
  -v "${PWD}\artifacts:/results" `
  grafana/k6:2.0.0@sha256:a33a0cfdc4d2483d6b7a3a22e726a499ff2831a671a49239104cd34a9937523c `
  run --summary-export=/results/attendance-summary.json `
  /scripts/school-day-attendance-write.js
Remove-Item Env:K6_ACCESS_TOKENS
```

`K6_ACCESS_TOKEN` remains supported for a one-user smoke. Use `K6_ACCESS_TOKENS` for concurrent
stages because the gateway currently applies its 50-request/second, burst-100 limiter per raw bearer
token. Tokens are assigned deterministically by VU. Do not commit tokens or include them in exported
artifacts.

Repeat at 100, 200, and 300 VUs only when the prior stage passes. Stop if error rate reaches 1%,
attendance-write p95 reaches 1.5 seconds, Cloud SQL CPU remains at or above 80% for three fresh
one-minute samples, Cloud SQL Usage memory reaches 90%, database connections reach 140, or
tenant-isolation checks fail. The certification wrapper refuses more than 300 VUs.

Measured on 2026-08-11, `db-custom-2-7680` was rejected at 300 VUs after three CPU guardrail samples
reached 83.52%; its completed 200-VU application stage passed. The decisive 300-VU morning-burst
run passed on `db-custom-4-7680`: CPU 58.29%, Usage memory 48.42%, 99 connections, 0.0137% HTTP
failures (38 rate-limit responses and no 5xx), attendance-write p95 115.82 ms and p99 262.70 ms.
This is a bounded dev result, not permission to raise the 300-VU ceiling or skip production
observability.

The full 300-VU write soak on the same 4-vCPU shape did **not** pass. It stopped after 2h28m when
three consecutive Cloud SQL CPU samples reached 82.15%, 81.37%, and 82.26%. Application latency and
error gates were still passing: 2,405,050 HTTP requests, 121 failures (all 429, no 5xx), and
attendance-write p95/p99 350.45/626.16 ms. The test intentionally repeats section writes as a
sustained stress workload; it created more than 1.4 million pending synthetic attendance outbox rows
and must not be presented as a normal once-per-section school-day event rate.

## Mixed school-morning read workload

`school-day-mixed-read.js` rotates each VU through six authoritative GET routes: student page,
command-center dashboard, daily attendance summary, fee structure, fee defaulters, and attendance
summary report. At 300 VUs, three VUs are mapped to each of the 100 reserved schools; each VU issues
one flow per iteration instead of firing all six simultaneously. Login is per VU and refreshes every
12 minutes. Every flow has read thresholds of p95 under 800 ms and p99 under 2,000 ms plus tagged
2xx/4xx/5xx counters; any 4xx or 5xx makes the profile fail closed.

Preflight every route with one synthetic login before the full run. Then use the guarded wrapper:

```powershell
$env:K6_LOGIN_EMAIL = 'scale-load-superadmin@custoking.local'
$env:K6_LOGIN_PASSWORD = '<synthetic-fixture-password>'
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\invoke-dev-load-certification.ps1 `
  -Profile MixedMorning -PeakVus 300 -Hold 15m -AllowScaleWrites
Remove-Item Env:K6_LOGIN_EMAIL, Env:K6_LOGIN_PASSWORD
```

`-AllowScaleWrites` is retained as an explicit fixture-use acknowledgement in the common wrapper;
the mixed profile itself performs only GET requests plus login POSTs. Its 300 VUs model 300 active
browser sessions distributed over the 100 schools, not all 300,000 student records or a claim that
300,000 students are concurrently online.

The first full 300-VU mixed run failed with 13.0426% HTTP failures and overall p95/p99 of
55.015/59.998 seconds. Diagnosis found a daily-attendance-summary N+1 query (up to 250 extra count
queries for the largest school) and an outbox relay numeric-ID ordering alias that forced a large
scan/sort under the synthetic backlog. Both source fixes have focused tests, but the result remains a
failure until the patched dev deployment passes the identical profile. Do not weaken the thresholds.

## Tooling validation evidence

On 2026-08-10, the SQL was tested against PostgreSQL 16 after applying the real tenant-school,
student, attendance, and reporting migrations:

| Result | Value |
| --- | ---: |
| Schools | 100 |
| Students | 300,000 |
| Largest school | 10,000 |
| Sections | 7,576 |
| Local seed time | 13.51 s |
| Local database size after seed | 121 MB |
| Local cleanup time | 5.49 s |
| Reserved schools/students after cleanup | 0 / 0 |

These local figures validate generation and cleanup, not GCP production capacity.
