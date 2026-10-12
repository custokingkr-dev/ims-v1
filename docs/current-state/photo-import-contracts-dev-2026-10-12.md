# Controlled Drive photo-import contracts and lifecycle fixes

This continuation adds all fifteen canonical operations owned by
`StudentPhotoImportController`: context, school batch lists, creation, managed-folder
provisioning, detail, scan, freeze, execute, cancel, access-revocation recording, row review,
full-frame photo recovery/progress, private JPEG preview and CSV download. Browser coverage
is now 108 operations across twelve clients, including a separate fifteen-operation
photo-import client and the existing forty-operation student client.

## Findings and fixes

| Finding | Evidence and implemented fix |
| --- | --- |
| Execution and recovery continued scheduling writes after the panel closed | Two regression tests failed against the earlier implementation: another execution chunk and another recovery request were sent after unmount. Every asynchronous workflow now captures its school/batch scope, passes cancellation signals and checks that scope before publishing state or scheduling another chunk. Closing the panel stops further scheduling. A request already sent may still commit on the server; cancellation is not rollback. Existing deliberate confirmation, bounded execution and audited recovery/resume semantics remain. |
| Late private preview responses could allocate a Blob URL without a mounted owner | A regression test now ensures a closed panel does not allocate the delayed preview. Preview request IDs also invalidate closed/superseded previews, and effect cleanup releases displayed URLs. Existing school-switch/New Batch cancellation remains paired with selection and request guards. No cross-school data exposure was established. |
| Handwritten browser types omitted serialized fields and nullable values | Named schemas preserve complete batch/row/recovery IDs, school attribution, audit/version fields and nullable Drive metadata, source evidence, actor IDs and timestamps. Java record/property/type bindings include UUIDs. Actual PostgreSQL-backed records and serialized controller responses are checked against these schemas. UI projections remain separate from the complete HTTP response types. |
| Direct photo-import JSON/CSV responses lacked an explicit cache prohibition | Source inspection found the gap; the gateway already had a private/no-store default from the earlier phase. The photo-import controller now applies private/no-store and Vary Authorization, and CSV responses explicitly forbid storage. JPEG previews retain their existing no-store policy, attachment/media/byte handling is preserved, and no data leak is claimed. |
| A generated client could not preserve this workflow's timeout, cancellation and download-progress settings | A bounded opt-in generator extension accepts only signal, timeout and onDownloadProgress. It selects those properties individually at runtime; caller config cannot replace the URL, auth/identity headers, query scope or binary response handling. Both callers use the shared authenticated Axios transport, and denied or uncertain writes are not automatically retried by the client. |

The implementation follows [React effect cleanup guidance](https://react.dev/reference/react/useEffect)
and [Axios cancellation guidance](https://axios-http.com/docs/cancellation). React calls cleanup
when a component is removed or an effect is replaced; Axios supports AbortController signals.
Explicit scope checks also discard late results when a mock, transport or server finishes after
cancellation. The cache control rationale remains [RFC 9111 section 5.2.2.5](https://www.rfc-editor.org/rfc/rfc9111.html#section-5.2.2.5).

## Authorization and architecture

The canonical gateway owner remains `student`, backed by school-core. Service access still
requires Operations or Superadmin and the existing `student:photo-import` permission.
Operators remain limited to their assigned school set. Batch reads, writes, downloads and
recovery resolve the owning school; module checks remain on the existing guarded actions.
No tenant predicate, role policy, transaction owner, provider configuration or database
migration is changed. A recorded access-revoked flag does not itself prove Drive permission
revocation. Recovery here means the existing bounded photo workflow; it is not a full-system restore.

## Validation

The focused server suite passed 51 tests with no failures/errors/skips, including all thirteen
JSON responses and both binary responses, all-operation invalid peer/role/permission/principal
denials, foreign-school and disabled-module denials, and invalid body/UUID rejection before
owner mutation. Existing tests retain certified source/revision checks, formula-safe CSV,
complete-frame normalization, current-photo protection and audited recovery idempotency.
The focused frontend suite passed 25 tests, including all three lifecycle regressions and
four generated-client transport tests. Thirteen generator tests passed. The route inventory
remains 449 mappings, with twelve clients and 108 canonical operations.

Initial local runs preserved timing failures in existing school-management/spreadsheet tests
and the gateway loopback burst test. The gateway test now freezes only its fixture clock, so
budget exhaustion does not depend on network throughput outrunning token refill. Longer UI
flows and cold spreadsheet-codec setup have explicit bounded test waits, retaining every
behavior/security assertion. Production admission limits and file-processing policies are unchanged.
The full local frontend suite passed all 476 tests with zero pending tests; gateway passed all 108 tests, TypeScript/build passed, and exact source CI/dev acceptance are recorded below. Tests use
synthetic fixtures; no real Drive scan, folder provisioning, photo application/recovery or
provider send is performed by this acceptance work. Generated TypeScript is not a runtime
response validator.

## Remaining fix plan

Continue contracts with actual school/guardian/consent callers and other uncovered domain
payloads, retaining canonical routing, nullable fields, tenant/permission denials and real
persistence checks. Full payload coverage remains incremental.

The eleven owner acceptance areas remain open under [the exact owner action plan](../security-remediation/acceptance-owner-actions.md).
Retain installed package/version/CVE rows, review authoritative vendor and runtime applicability,
apply supported native fixes and rerun exact-image/application checks. A passing HIGH/CRITICAL
gate does not close lower-severity advisories. Private snapshot custody and the operational
target/credential/device/policy approvals remain separate. Production is outside this dev release.

## Dev acceptance

[PR 351](https://github.com/custokingkr-dev/ims-v1/pull/351) deployed application source
`669330109a4bd64b529252a0600550462b34cdea`, tree
`b800cc2a459e1b4d5b51ed3a71b5772f0875d60e`. Both
[source CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/38216234676) and
[dev release](https://github.com/custokingkr-dev/ims-v1/actions/runs/38216907722) passed
1,008 school tests with no failures/errors/skips, 476 frontend tests, 116 authenticated
browser checks and 108 gateway tests. Both actual language uploads in
[source CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/38216234358) and
[post-merge CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/38216907337)
report zero findings. Independent verification bound the source/tree, server-hash-verified
release archive, image manifests, exact-digest scan evidence and runtime readbacks.

| Changed service | Ready revision | Traffic |
| --- | --- | ---: |
| Gateway | `custoking-api-gateway-dev-00042-dbg` | 100% |
| Frontend | `custoking-frontend-dev-00061-txj` | 100% |
| School-core | `custoking-school-core-service-dev-00083-8vk` | 100% |

Four exact prior runtimes, images and selected configurations were retained. All seven services
are Ready with sole untagged 100% traffic; five runtime roles and disabled migrations remain
guarded. An intermediate school-core readiness/traffic rejection during rollout was retained
as an unaccepted checkpoint. Final matching snapshots bracket 19 existing anonymous/forged
negative requests, twelve photo-import read/write/preview/download denials, full two-origin
CSP/header/browser checks and six cache checks. Anonymous photo requests returned 401 JSON
with private/no-store; hashed assets retain their immutable cache policy. Digest/config hashes
verify configured nonroot frontend/gateway image users, without claiming an observed process UID.

Fresh scans report gateway 0, frontend 0 and school-core 16 findings: 12 MEDIUM and 4 LOW,
ten unique CVEs. Every package/version/CVE row and evidence hash is saved in
[the acceptance evidence](../security-remediation/acceptance-photo-import-dev-2026-10-12.json).
Blank scanner fixed versions do not establish vendor fix availability or application applicability.
The retained platform image keeps its separate dated native record.

Partial vendor follow-up: Ubuntu's [CVE-2026-18374 record](https://ubuntu.com/security/CVE-2026-18374)
was retrieved through primary search content (direct open timed out), and its
[CVE-2026-89092 record](https://ubuntu.com/security/CVE-2026-89092) was opened directly.
Both listed Ubuntu 24.04 glibc as vulnerable. The first concerns a caller-controlled native
file-opening mode; the second requires nscd and hostile DNS responses. These descriptions
identify checks for the applicability review; they do not establish deployed distribution,
daemon state or reachable native call paths. This is a partial review and closes no advisory.

The eleven owner areas and broader payload coverage remain open. No real dev student mutation,
Drive operation, system restore or production deployment was performed by this acceptance work.
The [prior directory acceptance](student-directory-contracts-dev-2026-10-12.md) remains historical.
