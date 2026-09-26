# Dev remediation release and acceptance — 26 September 2026

The application fixes are deployed and the selected synthetic workflows passed in **custoking-dev**, Local Demo School (ID 1). This report supersedes deployment-status statements in the earlier implementation reports. Production was not changed. SSO remains deferred by the user's choice.

## Release evidence

| Release | Verified result |
| --- | --- |
| Application [PR 290](https://github.com/custokingkr-dev/ims-v1/pull/290), dev `86c5a945063a56937b1774c2726f1826a127d88d` | All seven service suites, exact-digest HIGH/CRITICAL gates, readiness/100% traffic/digest checks, frontend HTTP 200 and gateway UP passed in [CD 36252157938](https://github.com/custokingkr-dev/ims-v1/actions/runs/36252157938). CodeQL passed. School-core passed all 844 tests, superseding the earlier local fixture caveat. |
| Target configuration [PR 291](https://github.com/custokingkr-dev/ims-v1/pull/291), dev `bbf48535b13e43675f6615f7c6c2f614c2463def` | [Dev reconciliation 36253692754](https://github.com/custokingkr-dev/ims-v1/actions/runs/36253692754) passed. Platform target parameters are DRY_RUN, worker-ready true, logging provider and MSG91 dry-run true; other environments retain OFF/false. |
| Private workflows and recovery [PR 292](https://github.com/custokingkr-dev/ims-v1/pull/292), dev `d4f19b466dc481ab9a786c5b02f711cabc8152e2` | Four service suites, builds and CodeQL passed. [CD 36254443940](https://github.com/custokingkr-dev/ims-v1/actions/runs/36254443940) succeeded through serial Cloud Deploy rollouts, serving-image verification and gateway smoke. The changed identity image was scanned; three unchanged exact digests reused valid cached verdicts and their scan evidence. All four runtime platform digests match their resolved image evidence, with 100% traffic on ready revisions. |

The Cloud SQL backup `1790435759457` completed successfully before deployment. Read-only execution `ims-q-dev-czwjh` confirmed all seven new Flyway migrations successful. Runtime `app_rt` has no superuser, bypass-RLS, inherited-role or role-creation privilege; new append-only and restricted-column grants match the reviewed migrations. Execution `ims-q-dev-52df4` confirmed V10 indexes/sequence, complete visibility, four historical payments, no duplicate receipts/keys, and no missing school/receipt values.

Private prerequisites are configured: dedicated private quotation bucket with public access prevention, four-permission bucket-only runtime role, shared policy secret restricted to the two runtime identities, and authenticated operations/platform Scheduler jobs. Current-revision operations Scheduler requests returned HTTP 200; platform Scheduler evidence is paired with successful Cloud Run requests below. No secret values are stored in evidence files.

## Synthetic acceptance

Run `product-20260926-a1` retained one clearly marked contact-free student, a synthetic guardian at a non-routable `.invalid` address, immutable payment/confirmation records, and a procurement request awaiting bursar review. No purchase approval, vendor payment, real email or SMS occurred. These are dev simulations, not customer acceptance or recipient delivery evidence.

| Workflow | Deployed result |
| --- | --- |
| School identity and admission | ADMIN login carried school 1 and required permissions. Exactly one owned student, ID `9911152`, exists after recovery. Temporary administrator `182` is disabled; all runner sessions were logged out. |
| Attendance | Repeated PRESENT saves and readback produced exactly one record for the synthetic student. |
| Collection | One 100-paise CASH payment, receipt `RCPT-V2-1`. The same-key replay returned the same payment/receipt; a changed amount returned HTTP 409. Database aggregate confirms one payment and one receipt. |
| Procurement | Request `FF-014` and its one quotation survived replay. Changed payloads returned conflict. Repeated submission retained AWAITING_BURSAR. Durable creation receipts contain one entity for each operation. |
| Private quotation file | A valid 68-byte synthetic PNG uploaded and downloaded with matching SHA-256 and private/no-store headers. Anonymous access returned 401. Removal returned 204 and subsequent retrieval 404. The cleanup row is DELETED with one attempt; the dedicated bucket was independently verified empty. |
| Annual confirmation | One current-year item was reviewed and confirmed. Replay/readback returned the same confirmation, revision 1, and fingerprint; notification status remained NOT_SENT. |
| Broadcast dry run and replay | `1a287987-5756-4cb9-b8d1-0e2441522212` reached DRY_RUN_COMPLETE: one synthetic recipient, one logging attempt, four other recipients suppressed with zero attempts, zero confirmed deliveries. Queue replay retained one attempt. |
| Consent withdrawal | `d78429ab-f532-444d-bce0-fe0ad5c2f00d` was approved while the synthetic grant was active, then queued after withdrawal. It completed with the owned recipient SUPPRESSED, reason SCHOOL_COMMUNICATIONS_NOT_GRANTED; all five recipients were suppressed and confirmed deliveries stayed zero. Current consent is WITHDRAWN and a fresh preview reports zero eligible recipients. |
| Scheduled execution | Natural platform Scheduler/Cloud Run request pairs returned 200 on revision `custoking-platform-service-dev-muilo6h2` after the first queue and the withdrawal queue, at 16:39 and 16:42 UTC. No manual drain was used. |
| Password-recovery UI | The deployed controller initially returned 401 because it did not normalize a configured peer credential's trailing line ending. PR 292 fixes it consistently with sibling controllers. The endpoint now returns HTTP 200 with enabled=false, and the browser displays administrator-assisted recovery guidance instead of a connection error. |

Read-only execution `ims-q-dev-xnpxw` independently confirmed the owned student/payment counts, both creation receipts, append-only runtime rights, annual fingerprint, document tombstone, both broadcast outcomes and current withdrawn consent.

## Acceptance-tool corrections and recovery

Live acceptance exposed harness mismatches: student ownership needs `/students/{id}/workspace`; Cloud Run has both hash-based and project-number hostname forms; the optional-body queue action needs an explicit JSON object from PowerShell; and Windows native argument parsing stripped quoted Logging filters. The helpers now use the correct workspace contract, constrained URL validation, JSON queue bodies and a structured dev-scoped Logging request. Offline checks cover these cases and cleanup/replay boundaries.

The operator token could not invoke the private peer and service-account impersonation was unavailable. The explicit PublicManifest mode instead binds the owned guardian/current consent through public APIs, requires a fresh preview, and verifies every stored approved recipient before queueing. Actual application preview and dispatch exercise the platform-to-school policy connection. No extra IAM grant was made.

Failed journals were preserved. Preflight-only failures were archived only after proving no fixture writes and completed logout. After the rejected HTTP 415 queue, authoritative readback confirmed the existing approved broadcast had zero attempts; a separately journaled recovery reused it. After the log-query failure, readback confirmed the completed dry run and its one attempt before replay. Cleanup withdrew the synthetic grant after each stopped attempt. New synthetic consent decisions used distinct stable event keys; no blind retry recreated the student, guardian, first broadcast or payment.

Local artifacts under ignored `artifacts/product-dev-release-2026-09-26/` include immutable release evidence, `private-runtime-verified.json`, migration/receipt SQL evidence, the core journal under `acceptance/`, preserved broadcast attempt journals, final `product-20260926-a1-broadcast-recovery-scheduler.json`, paired Scheduler proof, `postacceptance-logs.json`, and `private-bucket-cleanup-proof.json`. Credentials and ordinary recipient destinations are not included.

## Remaining external gates

- **Reset-email activation:** SMTP host/port/user, approved sender and password-secret reference remain missing. Inbox delivery, expiry/replay and idle-worker execution need that setup. Identity currently scales to zero with request-based CPU; worker readiness must be proved before enabling recovery.
- **Live messaging:** provider duplicate/timeout reconciliation and delivery evidence remain unresolved. Keep broadcast processing in dry-run; a logging outcome is not inbox delivery.
- **Capacity certification:** observed dev gross month cost of ₹1,966.3141 exceeds the configured ₹1,600 guard. The reserved load fixture is not verified. No cloud load or budget override was performed.
- **Branch protection:** current GitHub access is write, not admin. An administrator must apply the existing reviewed rules.
- **Independent school/business outcomes:** staff adoption, support ownership, willingness to pay and realized margin require real operating evidence. The September baseline had no new relevant workflow activity and does not establish customer effectiveness.

Production promotion and SSO were not part of this dev completion.
