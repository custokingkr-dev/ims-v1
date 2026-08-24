# Privacy Technical Controls Evidence — 24 August 2026

Status: technical controls passed; legal/policy approval remains open.
Repository: `custokingkr-dev/ims-v1`
Evidence contains synthetic counts and a digest only. It contains no student rows or sensitive filenames.

## Repository/export boundary scan

Executed at 23 August 2026 20:46 UTC:

```powershell
./scripts/audit-repository-data-boundaries.ps1
./scripts/audit-repository-export-history.ps1
```

Results:

| Surface | Result |
| --- | --- |
| Current tracked sensitive export paths | 0 |
| Current tracked files above 10 MiB | 0 |
| Local/origin branch refs inspected | 7 |
| Sensitive paths in branch trees | 0 |
| Sensitive paths in all reachable Git objects/history | 0 |
| GitHub releases / release assets inspected | 0 / 0 |
| Sensitive GitHub release assets | 0 |

The scanners fail closed and report counts only. They do not print a matching path because a filename can
itself contain personal information. Both checks are required by the PR `privacy-technical-controls` job.

## Synthetic export and verified-deletion drill

Executed at 23 August 2026 20:49 UTC:

```powershell
./scripts/invoke-privacy-export-erasure-drill.ps1
```

The drill started a disposable PostgreSQL 16 Testcontainer, migrated the current tenant-school/student
schemas, and used only generated fixture data. Three targeted tests passed with no skips or failures.

| Assertion | Result |
| --- | --- |
| Export archive contract | Passed: workbook/photo mapping tests |
| In-memory export rows | 20 synthetic target students |
| Export SHA-256 | `7a120a238a307b4e4095aacc8cf2b044332fc756444ccb30a954d9e152853299` |
| Cross-tenant export content | Control-tenant prefix absent |
| Target fixtures before deletion | 20 students, 20 enrollments, 20 import rows, 1 import batch, 1 guardian, 1 consent, 800 outbox events, 390 sections, 1 school |
| Target after first deletion and retry | Every recorded count was 0 |
| Idempotent resume | Passed: the dependency-ordered deletion ran twice |
| Control tenant | Every before/after count identical |

The wrapper treats an opt-in/Testcontainers skip as failure, validates the evidence marker schema, requires
non-empty target fixtures, requires all post-delete counts to be zero, and compares the control counts.
This same drill is now required on every pull request.

## Completed technical controls

- Sensitive export/current-tree and large-binary CI guard.
- All-branch/all-object history scan and GitHub release-asset scan.
- Actual student export archive format/photo-mapping test.
- Checksummed, target-only synthetic export.
- Positive student, guardian and consent deletion fixtures.
- Idempotent school-core deletion retry and control-tenant preservation.
- A decision-ready lifecycle/offboarding template that distinguishes configured durations from approvals.

## Explicitly not completed

This evidence does not approve any retention duration or legal basis and does not certify production
offboarding. The synthetic delete is intentionally limited to tenant-school and student schema surfaces.
Production still lacks an approved, resumable two-person workflow covering identity/session revocation,
operations, billing, reporting projections/inboxes, notification/provider records, object/Drive data,
audit evidence, logs, backups, holds, export custody and delayed-erasure expiry.

Those decisions remain blocked on the named owner and approval fields in
`DATA-LIFECYCLE-POLICY-DECISION-TEMPLATE.md`. DATA-02 closes only after those fields are approved and the
resulting full-system workflow passes a synthetic end-to-end drill.
