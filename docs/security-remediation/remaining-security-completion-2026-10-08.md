# Recovery and provider completion follow-up

This parallel round closes three reproduced source gaps: canonical SMS reservation routing, recovery epoch validation before commit admission, and recovery authorization freshness. Full local platform tests passed 496/496. School packaging passed with 978 tests passed and one Windows symlink-privilege skip out of 979; exact Linux CI must execute all 979 without skips. [Local evidence index](acceptance-remaining-round-local.json) preserves the failed controls, corrected results, independent review and source hashes. CI, merge and dev acceptance are pending at this dated checkpoint.

The whole saved security plan remains incomplete: the [nine owner action groups](acceptance-owner-actions.md), residual native advisory applicability and local snapshot custody still require their listed evidence. Prior source and dev acceptance, including PR338 and its evidence publication PR339, retain their dates and bytes.

## Implemented source fixes and reasons

| Reproduced gap | Fix | Evidence and remaining boundary |
| --- | --- | --- |
| The prepared SMS transport coerced persisted destinations and wire recipient values, and admitted routing aliases or passthrough bodies that its report bridge rejects. A reservation could therefore be accepted outside the bridge's canonical contract. | Require an object payload, one ASCII digit string destination of length 10–15, no routing alias fields, no non-null passthrough body, one object recipient with the exact string destination and the existing exact correlation binding. Reject before capacity admission or network calls. Canonical boundary values and null passthrough remain supported. | The original implementation failed four negative controls; the corrected focused suite passed 13/13. This dormant path does not enable live sending or establish account-selected provider authentication or delivery. |
| The isolated recovery runner checked the external RECONCILING epoch initially but did not reread it immediately before committing source replay. A changed generation, ACTIVE state or missing control could escape that admission check. | Reread the exact approved epoch through the existing bounded single-worker reader before commit; require its generation, bytes, context and RECONCILING state to match. Never adopt a newer epoch automatically. Recheck authority freshness after control and target-role checks. | Real local PostgreSQL controls alter or remove the epoch after source work and prove transaction rollback, retaining source/dependents and creating no erasure receipt, photo work or outbox. External object reads and SQL commit remain non-atomic; this does not enforce a global service freeze. |
| Recovery freshness measured capture-to-issue and issue-to-now separately, permitting old physical-target evidence, and truncated fractional duration boundaries. | Measure actual target capture age against the current clock with exact Duration comparisons, including lease validity. Preserve the existing strict runway requirement. | Original focused tests exposed three failures; corrected recovery tests passed 14 unit and 9 real local PostgreSQL tests. No live clone identity, historical coverage, full recovery or delivery resume is certified. |

Focused tests overlap the full suites and are not added to their counts. The independent review found no blocking source issues and explicitly preserves the limits of SDK cancellation, operator freeze attestations and provider account selection.

## Provider account research and completion plan

MSG91's current SMS Webhook(New) documentation exposes configurable fields and optional custom headers. Its EMAIL documentation lists queued, accepted, delivered, opened and failed events, while its request-ID description and example differ. WhatsApp distinguishes request/sent events from outbound delivery/read reports. These are separate contracts; account configuration must select exact API/report versions, field sets, timestamps, correlation and authentication. [SMS documentation](https://msg91.com/help/webhook-new/how-to-receive-sms-delivery-reports-via-webhook-new), [EMAIL documentation](https://msg91.com/help/webhook-new/how-to-receive-email-delivery-reports-via-webhook-new), [WhatsApp documentation](https://msg91.com/help/webhook-new/how-to-receive-whatsapp-delivery-reports-via-webhook-new).

The provider describes an eight-second callback response limit, retries and possible duplicates. Its logs can expose the actual configured request payload and headers; successful callback transport does not prove recipient delivery. Before live admission, supply account-selected contracts and sender/template IDs, managed authentication references and approved recipients. Implement and validate the selected authenticated raw adapters and timeout behavior, then exercise actual accepted, rejected, UNKNOWN and duplicate reports without automatic resend. Public documents do not prove configured vendor-origin authentication or authorize a recipient test. [Webhook behavior](https://msg91.com/help/webhook-new), [webhook logs](https://msg91.com/help/webhooks/webhook-logs).

## Native and HTTP/2 findings

The [new eleven-advisory matrix](remaining-round-native-advisory-research.md) and [exact research capture](acceptance-remaining-round-native-research.json) validate 34 findings across the two previously accepted selected images. Ubuntu libpng 0.7's active source backport matches the upstream patch. The exact Noble zlib source lacks the particular later nonblocking path described by its advisory. These refine source applicability, while vendor metadata disagreements and actual installed binary correspondence remain unresolved. Nine other advisory prerequisites remain unproved; no additional supported distribution package fix was established. No finding was suppressed or removed.

The prior inventory's masked unzip failure and missing nested-JAR inspection are incomplete evidence, not absence. Existing daemon observations bind only their dated retained image digests. Obtain an exact current-image inventory and native consumer evidence before making binary reachability claims, and reconcile the precise source discrepancies with the vendor/scanner owner. Continue monitoring supported security publications.

The saved HTTP/2 length-mismatch probe rejected the stream with INTERNAL_ERROR rather than the required PROTOCOL_ERROR. It remains a strict conformance failure with an unidentified emitting component. Its fix plan is owner-approved upstream attribution and controlled retesting after the owned-edge cutover; no website exploit or downstream exposure has been established. [RFC9113 section 8.1.1](https://www.rfc-editor.org/rfc/rfc9113.html#section-8.1.1).

## Remaining owner actions

Fresh metadata still shows no repository administration or dev branch protection, no dedicated dashboard OAuth secret/service, and no deployed owned edge or compatible enforced egress network. [Dated owner readback](acceptance-remaining-round-owner-readback.json), [exact dashboard follow-up](acceptance-remaining-round-owner-followup.json), [infrastructure review](acceptance-remaining-round-infra-review.json).

| Action | Required next input and completion evidence |
| --- | --- |
| Owned edge | Owned hostname and infrastructure budget; active HTTPS/WAF, legitimate flow tests and old-URL bypass rejection. |
| Repository governance | Repository administrator; independently read-back required checks and bypass/environment policy. |
| Dashboard | Dedicated OAuth reference, callback and allowed identities; deployed login, denial, replay and expiry tests. |
| Provider and incident delivery | Exact account contracts, sender/template IDs, authentication references and approved recipients; actual authenticated reconciliation and recipient/incident receipt. |
| Physical authenticators | Device/browser matrix and physical keys; actual privileged flow and recovery checks. |
| Retention and full recovery | Approved retention/RPO/RTO, complete journal coverage and trusted physical clone; enforced freeze/drain, source/downstream/object/provider fences, governed cutover/resume and recovery-copy purge evidence. |
| Capacity and obsolete revisions | Representative workload/SLO and test window; measured reserve/drain. The existing exact 198-revision dry run still requires explicit irreversible deletion approval and a release freeze with immediate revalidation. Zero revisions have been deleted. |
| HTTP/2 | Upstream attribution and an owner decision or corrected conformance evidence. |
| Egress and files | Approved compatible network/destinations and viewer matrix; actual allowed/denied destination and supported file-processing tests. |
| Native advisories | Exact-image native/binary consumer evidence and vendor resolution or supported fixes; source-only inference cannot close the remaining advisories. |
| Local snapshot custody | Private owner classification of the preserved object and exact cleanup approval if applicable. |

No missing account inputs, budget, physical hardware or irreversible approval has been inferred from the general request to complete the work. Source acceptance must still bind the exact PR head/base/tree to mandatory CI and CodeQL. Dev acceptance must bind the actual release artifact and fresh selected-image scans to seven Ready runtimes, unchanged guarded configuration, absent owner jobs, additive rollback retention and scoped public/CSP checks.
