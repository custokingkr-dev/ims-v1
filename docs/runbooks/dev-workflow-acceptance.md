# Bounded dev workflow acceptance

`scripts/run-dev-workflow-acceptance.mjs` defaults to a plan. It is fixed to `custoking-dev`, school 1 (exactly **Local Demo School**, active), and the dev API gateway. Node 22+ and authenticated `gcloud` access to the two existing bootstrap secrets are required. Every secret read supplies `--project=custoking-dev`; the runner does not change gcloud configuration.

Run only after the application/configuration releases and the seven new migrations have been independently verified. Private quotation storage must be available. Keep the provider in logging/dry-run configuration and live broadcasts off. This runner performs no cloud/IAM changes, schema writes, reminder/send API calls, broadcast work, procurement approvals, or vendor payments. Normal application outbox events still occur and may be processed by the dev logging provider.

```powershell
node scripts/run-dev-workflow-acceptance.mjs --project custoking-dev --run-id product-20260926-a1
node --test scripts/run-dev-workflow-acceptance.test.mjs
# Root-reviewed mutation step, after deployment and migration checks:
node scripts/run-dev-workflow-acceptance.mjs --project custoking-dev --run-id product-20260926-a1 --apply
```

The runner logs in as the existing bootstrap superadministrator, verifies school/module/current-year/private-storage references, then provisions one run-owned ADMIN through `POST /schools/1/admin`. The temporary password is random and remains in memory. The school administrator performs all business workflow writes. On a repeat run, the runner verifies the exact account's email, name, role, and school before rotating its password and enabling it. It logs out and disables this account in `finally`, verifies the disabled state, and logs out the superadministrator.

The synthetic student has no contact details or photo. For the command above its admission number is `QA-product-20260926-a1`, name is `Synthetic Student product-20260926-a1`, class is `1`, and section is `QA-PRODUCT-20260926-A1`. The server-assigned student ID appears in the safe journal's `results.attendance.studentId` and `results.fees.studentId` for the separate broadcast dry-run runner.

Acceptance checks cover:

- Attendance saved twice with one persisted PRESENT record in a section containing only this student.
- A new run-owned current-year fee band, one ₹1 item (100 paise), one 100% installment, publication, assignment, and a **synthetic CASH record of 100 paise**. Replaying the same key must return the same payment and receipt; changing the amount must return 409. Readback must show one payment and a paid total of 100 paise. This is an application ledger test, not a bank or payment-provider transaction. Any other existing fee band blocks the run before account creation.
- Procurement request and quotation creation, exact replay, changed-payload 409, and bounded readback showing one of each. A tiny generated fixture PNG is uploaded privately, downloaded with matching bytes and private/no-store headers, checked anonymously for 401/403, removed, and checked for 404 before submission. Submission and repeat must both read AWAITING_BURSAR. No approval or purchase follows.
- Annual-plan confirmation only if the current-year review is empty or consists solely of this run's item. Confirmation/repeat/readback must identify the same durable receipt with `notificationStatus=NOT_SENT`. An unrelated plan is reported as skipped without mutation.

The journal is `artifacts/product-dev-release-2026-09-26/acceptance/product-20260926-a1.json`. Sanitized intent and stable keys are flushed before each write. No passwords, tokens, bootstrap SQL, raw responses, existing student records, or file bytes are saved. Identifiers, synthetic text, status codes, and verification summaries are retained. Request paths contain only IDs or this run's synthetic search marker.

Use the **same run ID and journal** to recover. Non-idempotent creations reconcile by exact ownership first; an uncertain attempt with no authoritative match stops instead of creating another record. Idempotent endpoints retry the original intent/key. A later 400 cannot erase an earlier uncertain attempt. Do not delete or edit the journal to bypass recovery. An exclusive `.lock` file prevents concurrent runs; after a process crash, first establish that the process is stopped and review the journal before manually removing only its lock. Cleanup failures are explicit and require finishing the exact owned-account disable/logout before calling the run complete.

There are no automatic HTTP retries. Each request has a 58-second timeout. Business work stops after 15 minutes or 145 requests; cleanup has an independent reserve of 15 requests. The script retains synthetic student/section/fee/payment/procurement/annual-plan rows as audit evidence. It does not delete financial evidence or existing school data. The private file becomes unreadable after removal; storage deletion is asynchronous and must be verified separately through the authenticated operations drain and storage evidence. Bucket soft-delete retention may retain a non-live version for its configured retention period.
