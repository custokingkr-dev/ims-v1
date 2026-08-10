# Scale and Attendance Load Tests

These assets are restricted to `dev`/localhost and use the reserved school id range beginning at
`900000000`. They do not copy production PII.

## Safety and cost posture

- `scripts/invoke-scale-fixture.ps1` accepts only `dev`.
- Seed and cleanup require the explicit `-AllowScaleWrites` switch.
- The seed refuses to continue if a non-scale school occupies the reserved range.
- The Cloud Run job scales to zero and has no idle compute cost.
- Do not seed 300,000 students into the shared-core `db-f1-micro`. Temporarily use at least
  `db-custom-2-7680`, run the bounded test window, clean the fixture, and restore the cheaper shape.
- Always run `Status` before and `Cleanup` after the test, even when k6 fails.
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

The seed distributes students into sections of at most 40 across 12 synthetic classes, enables
`STUDENTS`, `ATTENDANCE`, and `REPORTS`, analyzes the affected tables, and emits exact counts.
`Diagnostics` is read-only and captures active PostgreSQL query shapes, wait events, blockers and
transaction ages for contention analysis.

`QueryPlans` executes read-only `EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)` probes against the
300,000-student fixture and records whether enough multi-year attendance rows exist to call the
history result certified. A plan captured without at least 7,300,000 rows across at least 700 days
for the synthetic 10,000-student school is diagnostic evidence only, never long-history certification:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-scale-fixture.ps1 `
  -Action QueryPlans -OutputJson artifacts\scale-query-plans.json
```

## Attendance write workload

Use short-lived dev access tokens with `attendance:read` and `attendance:manage`. The workload
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

Repeat at 100, 300, and 500 VUs only when the prior stage passes. Stop if error rate reaches 1%,
attendance-write p95 reaches 1.5 seconds, Cloud SQL CPU remains above 80%, connections reach 70%, or
tenant-isolation checks fail.

Measured on 2026-08-10, the two-vCPU dev database passed the clean sustained 300-VU stage but crossed
80% CPU at 500 VUs; 500 VUs is therefore a stop boundary, not an approved operating target for that
database shape.

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
