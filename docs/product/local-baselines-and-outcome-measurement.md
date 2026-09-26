# Local baselines and outcome measurement

## Measured authentication cost

On 2026-09-26 the opt-in `AuthoritativeAuthLocalBenchmarkTest` sent 80 real localhost HTTP introspection requests after 10 warmups, with four concurrent callers against disposable PostgreSQL 16. It exercised session lookup, current permission resolution, response serialization, and two shared quota increments per successful request. All 80 measured requests succeeded; the test also verified all 90 school-quota charges including warmup.

| Local measurement | Result |
| --- | ---: |
| Median request | 25.65 ms |
| p95 request | 49.71 ms |
| Slowest request | 76.49 ms |
| Measured batch elapsed | 576.34 ms |

Reproduce with `./mvnw.cmd -pl services/identity-service -Dtest=AuthoritativeAuthLocalBenchmarkTest -Dreadiness.benchmark=true test --no-transfer-progress`. Output contains one `IMS_LOCAL_AUTH_BASELINE` JSON record without account/session secrets. The benchmark creates and removes its own PostgreSQL test container.

This isolates authentication and database cost by replacing the external telemetry span exporter. An initial attempt without that isolation waited for a nonexistent local collector and was discarded. The result excludes gateway, cloud network, cold starts, production data volume, actual exporter latency, and password verification. It has no asserted latency SLA and is not development or production capacity certification.

## Existing performance coverage and limits

The repository already has an opt-in 10,000-student onboarding certification that executes twenty 500-row preview/confirmation transactions and verifies replay/reconciliation. It was not rerun during this bounded pass while another task owned the school-core test process. `load-tests/school-day-attendance-write.js`, `school-day-mixed-read.js`, and `staff-workload-arrival.js` exercise authenticated attendance and mixed reads; query-plan scripts cover student search and attendance history. Their fleet/history certification requires the designated synthetic fixture and guarded dev/localhost services. No cloud load, shared fixture seeding, or cloud scaling was performed here.

Fee-report regression coverage checks batched schedule reads for 21 students in four SELECTs. That establishes query-count behavior, not a latency claim. Spreadsheet/client measurements are tracked separately from these backend and authentication probes. Local functional test durations must not be relabeled as production throughput.

The separate frontend probe measured the real import handler up to mocked preview HTTP on Node 24.15/Windows, with one warmup and five samples: 500-row XLSX median 25.10 ms (20.16–35.05), 500-row CSV median 16.96 ms (14.90–22.16), and 500 rows plus 20 tiny embedded PNGs median 21.39 ms (13.54–28.69). A 501-row file was rejected without HTTP. Reproduction lives in `frontend/performance`; evidence is `artifacts/product-followup-2026-09-26/frontend-spreadsheet-baseline.json`. These figures exclude browser paint, real photo decoding, and backend latency.

## Receipt migration preflight

Run `scripts/sql/fee-payment-integrity-preflight.sql` through an existing, explicitly selected authorized PostgreSQL connection:

```powershell
psql -X --dbname='<approved connection alias>' --file=scripts/sql/fee-payment-integrity-preflight.sql
```

Do not put connection passwords in command arguments or captured output. The script uses a read-only transaction, a 15-second statement timeout, and aggregate counts only. It works before and after fee migration V10 and reports duplicate receipt groups, cross-school collision groups, legacy collision markers, idempotency duplicates, migration objects, and sequence permission. A restricted RLS connection is explicitly reported as incomplete; zero visible collisions from that connection does not certify the database. Existing duplicate receipts are preserved by V10 and need payment-ID lookup, not renumbering.

Validation: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/tests/fee-payment-integrity-preflight-test.ps1` passes against an isolated PostgreSQL fixture before and after the actual V10 migration, including cross-school historical collisions, inherited owner privileges, non-inherited membership and hidden rows under forced RLS. The first development execution (`ims-q-dev-rhlzz`) conservatively reported incomplete visibility because the check overlooked inherited ownership. Metadata inspection confirmed that `appuser` inherits the table owner's privileges and the table does not force RLS. The corrected read-only execution (`ims-q-dev-hk4n5`, 26 September 2026 15:17 UTC) therefore reports complete database visibility: four payments, zero duplicate groups, zero missing school/receipt values, and V10 objects absent. The execution-only SQL override did not change the diagnostic job template or application data. A successful predeployment Cloud SQL backup followed the reviewed preflight (backup `1790435759457`).

## Product outcome snapshot

`scripts/sql/product-outcome-baseline.sql` is an explicitly offline analytical script. It intentionally crosses service schemas and must never be wired into an application request or used to relax application database boundaries. Run only through an existing authorized analytical connection to the consolidated database:

```powershell
psql -X --dbname='<approved analytical connection alias>' -v from_date=2026-09-01 -v through_date=2026-09-30 --file=scripts/sql/product-outcome-baseline.sql
```

The window is inclusive in Asia/Kolkata calendar dates. Each output line is aggregate JSON. The script rejects missing sources and incomplete RLS visibility, runs read-only with a 30-second statement timeout, and emits no student, user, school, receipt, or reset-token identifiers. Save dated output under controlled operational evidence storage and compare the same definitions and observation window over time.

| Measurement | Definition and interpretation |
| --- | --- |
| Import reconciliation | Previewed rows, completed batch insert/skip totals, count mismatches, and completion durations. Fewer surviving linked students can reflect later permanent deletion and requires investigation. |
| Attendance | Mark counts and completeness within registers that exist. This cannot measure missing expected operating days or sections without a school calendar denominator. |
| Approval aging | Current approval queue count and age since request creation; this includes draft time because submission time is not stored. |
| Fulfillment | Dated completions in the window, duration from Custoking approval, and fulfilled records missing timestamps. |
| School fee collections | Recorded payment amounts in the window and current unpaid assignment balances. They are distinct from platform revenue. |
| Platform invoice collections | Canonical payment entries separated from `LEGACY` paid-status mirrors, whose dates come from invoices. Current noncancelled invoice balances are shown separately. |

Bank reconciliation, refunds/net cash, recognized revenue, margin, attributable support cost, and success without operator assistance cannot be established by these records alone. The output marks unavailable outcome evidence rather than estimating it. Monetary amounts retain their stored INR minor units (paise): divide by 100 to display rupees. Current balances and backlog are snapshots at execution time, not end-of-window historical reconstructions.

Validation: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/tests/product-outcome-baseline-test.ps1` passes six source-based measurement sections against disposable fixtures. Fixtures preserve the queried production column names/types and test mismatches, missing attendance marks, approval/fulfillment intervals, and legacy cash separation. They are semantic query tests, not actual school outcome measurements. Real baseline values remain an authorized operational collection step.
