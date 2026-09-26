# School acceptance session — 26 September 2026

Status: **prepared; awaiting a nominated school representative and support owner**. Automated dev acceptance passed, but no person has signed off this session. Local Demo School (ID 1) is the approved technical rehearsal environment; it is not evidence of an external school's adoption.

Updated 27 September 2026 (IST): the EMAIL implementation in [PR 294](https://github.com/custokingkr-dev/ims-v1/pull/294) deployed at source `a07c2fa73a4bc8731b2d41015f31f65624bfdb8c` through successful [release 36261225626](https://github.com/custokingkr-dev/ims-v1/actions/runs/36261225626), after successful dev backup `1790446335149`. The platform's ready revision is `custoking-platform-service-dev-muipmx2l`. PR 296's frontend timeout fix has since deployed at source `1da0dc1d99f839bdd6b24aeb6565bb85d7728f63`. These are technical results, not a staff acceptance decision.

## Session record

| Field | Value |
| --- | --- |
| School or staff cohort represented | Awaiting user nomination |
| School acceptance owner | Awaiting user nomination |
| Support owner and escalation channel | Awaiting user nomination |
| Participating operator roles | Proposed: admissions/teacher, fee clerk, procurement reviewer; one nominated person can exercise these roles for a small pilot |
| Appointment/date | To be agreed with the owner |
| Test environment | `custoking-dev`, Local Demo School ID 1; no production changes |
| Starting evidence | [Deployed technical acceptance](dev-release-acceptance-2026-09-26.md), run `product-20260926-a1` |
| EMAIL release | Dev source `a07c2fa73a4bc8731b2d41015f31f65624bfdb8c`; platform `custoking-platform-service-dev-muipmx2l` |
| Latest frontend release | Dev source `1da0dc1d99f839bdd6b24aeb6565bb85d7728f63`; frontend `custoking-frontend-dev-00050-snx` ready with 100% traffic; [CD 36262889154](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262889154) succeeded |
| Live test recipient | Dev school/recipient selection is already authorized and school 1 is selected; a reachable, verified dev test inbox has not been established. Retained `.invalid` fixtures cannot receive mail |
| Decision | NOT ASSESSED |

A product owner can approve a dev rehearsal. Independent school acceptance requires a representative of the actual intended school/cohort to perform the work and record their decision. Record these decisions separately.

The deployed check in `artifacts/product-dev-release-2026-09-26/live-release-dev-verification.json` is complete: school 1 remains `DRY_RUN` with `canQueue=true` and `canSend=false`, retained broadcasts remain dry-run, missing/wrong callback credentials receive 401, and an authenticated unknown synthetic callback receives 202 on both original and replay. No external message was sent. The separate `live-postdeploy-database-proof.json` confirms notification V12, scoped runtime grants/RLS, zero school 1 live submissions/reports, and the original dry-run broadcast modes. `live-postdeploy-scheduler-proof.json` records five natural successful Scheduler requests on the new revision from 18:26 through 18:30 UTC.

Before PR 296, the first gateway login took 33.621 seconds and returned 200; a repeat returned 200 in 0.483 seconds. The login-only 60-second timeout fix in [PR 296](https://github.com/custokingkr-dev/ims-v1/pull/296) passed [CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262566669) and [CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/36262566385), including 401 frontend tests and 109 browser checks, and is now deployed. Frontend HTTP 200 was verified at 18:42:26 UTC, gateway UP at 18:42:36 UTC, and signing/approval completed at 18:42:56 UTC. Evidence is in `artifacts/product-dev-release-2026-09-26/release-evidence-dev-1da0dc1d/`. The participant's actual login acceptance remains NOT ASSESSED; do not mark A1 passed from these automated observations.

## Rehearsal preparation

The existing automation account (administrator 182) is disabled and must not be presented as an available staff login. After nomination, provision or identify the participant's own school-scoped role and deliver credentials through the established private onboarding path. Never put passwords in this document or repository. The staff member should perform the actions; assistance is recorded rather than counted as independent completion.

Use clearly labeled synthetic records only. Start with readback of the retained evidence: student `9911152`, receipt `RCPT-V2-1` for 100 paise, procurement request `FF-014`, and annual confirmation revision 1. The test attachment has been deleted, so its expected retrieval result is not found. Do not rerun the original automated creation script blindly: its data and journal already exist.

For hands-on writes, allocate a new run marker and isolated student/section after checking the proposed cohort. Use the current downloadable import template and a minimal synthetic set. Preview before import; record inserted/skipped/rejected counts. Never import an operational roster into dev for this session. Keep invoices, payment ledger entries and confirmations as labeled audit evidence; do not delete them to make totals look clean.

## Staff exercise and acceptance record

Estimated session length: 60–90 minutes, subject to the owner's availability. Durations below are observations, not a promised service-level agreement. Each case starts **NOT RUN**. Record operator, start/end time, assistance, evidence reference, actual result and defect ID.

| Case | Staff task | Pass criterion | Current human result |
| --- | --- | --- | --- |
| A1 Access and scope | Sign in with the assigned role, open the school and attempt an unavailable role/module | Correct school and permitted actions; no access outside assigned scope; recovery guidance is understandable | NOT RUN |
| A2 Admission/import | Preview and confirm the isolated synthetic import; repeat the original confirmation after an interrupted response | Expected insert/skip/reject totals; one student per intended admission; operator reconciles the original result without recreating it | NOT RUN |
| A3 Attendance | Find the isolated section, record attendance, save/reopen and repeat the same save | Exact roster and date, one mark per student, persisted values match; unsaved navigation is handled clearly | NOT RUN |
| A4 Fees | Review the synthetic assignment, record a 100-paise test CASH payment, recover/replay the original request, inspect receipt and ledger | One payment/receipt, correct outstanding amount and year; operator can identify and resolve an uncertain result | NOT RUN |
| A5 Procurement | Create a labeled request/quotation, attach a synthetic document, download it with an authorized role, submit and reopen | Same request/quotation after recovery, private file accessible only to authorized users, review status understood; stop before purchase approval or vendor payment | NOT RUN |
| A6 Annual plan | Review exact current-year test items, confirm and reopen the persisted confirmation | Same reviewed snapshot/revision; operator understands confirmation does not place an order or send a message | NOT RUN |
| A7 Communication | Review audience and approval, observe the authorized dev live test, then review withdrawal/suppression | Operator distinguishes queued/accepted/delivered/unknown; recipient evidence exists for the live case; withdrawal produces no provider attempt | BLOCKED — EMAIL path deployed with live disabled; MSG91 read-only API returned 401 and console is signed out; provider verification and a reachable verified dev inbox remain unavailable |
| A8 Support and recovery | Report one controlled workflow interruption through the nominated support path and resume using the original identifiers | Named owner acknowledges, original outcome is reconciled, no duplicate transaction; escalation and next action are recorded | BLOCKED — support owner pending |

Do not mix the synthetic 100-paise ledger record with real collections or bank reconciliation. Do not use a real purchase or message to simulate a network failure. Fault injection belongs in disposable/synthetic tests; real ambiguous outcomes are investigated without blind resubmission.

## Proposed support responsibilities

The nominated school owner validates roster, attendance and fee/procurement facts. The nominated support owner receives issues, records severity and identifiers, communicates the next update and coordinates engineering. Engineering investigates technical evidence and recovers the original operation. Financial corrections and purchasing decisions remain with the authorized school decision maker.

Stop the affected exercise on a wrong recipient, cross-school access, duplicate financial record, unexplained balance or uncertain external send. Preserve the original request/key and evidence. Agree response/update targets with the support owner before the session; no response promise has been made here.

## Decision form

For each case record: `case ID | operator role | environment/revision | started/finished | assistance needed | expected/actual | evidence reference | PASS/FAIL/BLOCKED | issue/owner`.

Final decision remains **NOT ASSESSED** until the named school representative supplies:

- Their name/role and represented school/cohort.
- Cases actually performed and any cases excluded from scope.
- Accepted outcomes, unresolved defects and their owners.
- Whether they accept this release for the defined pilot, conditionally accept it, or reject it.
- Decision date and a retained response/evidence reference. The assistant must not sign on their behalf.

Technical pass, staff workflow acceptance and business validation are separate records. A conditional pilot decision cannot be described as all cases passed.

## Business follow-through

Proposed observation period: ten operating days after staff acceptance, to be agreed by the owner. Before starting, record expected attendance days/sections, participating staff and the current process. Capture tasks completed without assistance, reconciliation exceptions, support contacts/time, procurement completion time and actual supplier costs. Use [the existing measurement definitions](local-baselines-and-outcome-measurement.md); absent denominators or costs remain unknown. Dev now contains retained synthetic transactions: its aggregates cannot establish school adoption, real collections or commercial results. Business measurements must identify the real operating cohort and explicitly exclude rehearsal records.

Ask the decision maker to record the problem solved, missing capabilities, whether they would continue using the product, and willingness to pay at an explicitly proposed price. Record an actual response; do not infer willingness to pay from test completion. Realized margin needs invoices, supplier cost and attributable support/operating cost. Those outcomes cannot be created by repeating synthetic tests.
