# Student lifecycle contract implementation and dev acceptance

This continuation implements the student import, promotion, review and erasure portion of the
saved architecture maintenance plan. Browser coverage grows from 58 to 83 canonical operations
across the same eleven clients; the student contract grows from five to thirty operations.
The 449-route inventory remains broader than payload coverage.

## Findings and fixes

| Finding | Implemented fix and evidence |
| --- | --- |
| ID-card and full-name review status callers omitted the server's required school ID. Shared Axios does not inject it. | Pass the authenticated account's school explicitly, reload the status callback when school changes, and surface a missing school context before sending a request. Drawer and transport regression checks cover this. |
| Canonical review-item and full-name verification handlers checked ownership but omitted the school required by persistence. | Copy the request and set its school from the already-authorized item's ownership. MockMvc checks prove successful attribution and rejection of other-school items before mutation; a browser-supplied school cannot replace the owning school. |
| The browser page type advertised `number` and `totalPages`, while the server emits `page` and `size`. | Bind the wrapper to the actual named envelope, including nullable review fields and timestamps. PostgreSQL-backed serialization checks exercise populated pages. |
| The import UI advertised and accepted 50 MB while the server enforces 10 MB. | Align the UI limit and message with the existing server limit; test rejection before parsing or uploading. |
| A failed job after confirmation could remain in the polling loop indefinitely. | Stop on the reported failure, preserve the error, and require deliberate recovery. A regression proves one confirmation, one failed-status read, no photo phase and no success refresh. |
| Import, promotion, review and erasure callers duplicated untyped wire behavior. | Extend the canonical OpenAPI contract and generated client; migrate callers through the existing authenticated Axios instance. Preserve the original preview token, explicit promotion apply, deletion confirmation and per-student verification. |

The generator now supports explicit multipart text fields alongside binary files. It permits
`X-Student-Delete-Confirmation` only on the canonical student DELETE, with a required string,
explicit TypeScript alias and URI-component encoding. It rejects authentication, cookie, tenant
identity and peer headers, undeclared/duplicate parameter names, and unsupported multipart field
types. Confirmation is encoded once in the browser and decoded once by the controller.

The singular `/students/import/upload-preview` spelling belongs to the canonical controller;
no replacement route was invented. Confirmation and progress callers use the existing plural
canonical paths. Multipart uploads let the browser supply its boundary. Server permissions,
module checks, tenant RLS, transaction boundaries, erasure journal and draft ownership remain
the controlling mechanisms; generated TypeScript adds no production response validator.

## Validation and release record

Local controller, PostgreSQL lifecycle/outbox and reused-connection RLS checks passed (37 tests, zero failures or skips), including import preview structure,
skipped rows, completed-token replay, progress, promotion items/batches, empty/active review
status, nullable review items/pages, verification summaries and confirmed erasure serialization.
Generator, inventory and gateway contract checks passed for all 83 operations. The full frontend suite passed (462 tests), and the frontend TypeScript/build check passed.
[PR #347](https://github.com/custokingkr-dev/ims-v1/pull/347) merged as
`5132f4d5452e83a4d4c3b5a6f3c91a92ad0839ac`, tree
`fa25ee75d7007d185f8097a78063cd601792cf88`. [CI 38180811513](https://github.com/custokingkr-dev/ims-v1/actions/runs/38180811513)
and [dev release 38181290903](https://github.com/custokingkr-dev/ims-v1/actions/runs/38181290903)
passed 995 school tests (zero skips), 462 frontend tests, 116 authenticated browser tests and
108 gateway tests. Both source and post-merge CodeQL runs have two actual language uploads
with zero results. The accepted source matches the tested tree.

Independent readback accepted these changed runtimes against exact release manifest digests:

| Service | Ready revision | Traffic |
| --- | --- | ---: |
| Frontend | `custoking-frontend-dev-00059-fkc` | 100% |
| School-core | `custoking-school-core-service-dev-00081-8fv` | 100% |
| Gateway | `custoking-api-gateway-dev-00040-nwh` | 100% |

The other four services retain their exact prior revision, image and selected configuration.
All seven are Ready with sole untagged 100% traffic. Five runtime database roles and disabled
runtime migrations remain guarded. Matching snapshots bracket 19 passing anonymous/forged
access-denial requests and full security-header/CSP/browser checks on both exact dev origins.
The frontend and gateway image manifests/config hashes verify configured nonroot users
`nginx` and `node`; this establishes image configuration, not an observed process UID.

Fresh exact-digest HIGH/CRITICAL gates passed. Full scans report frontend 0, gateway 0 and
school-core 16 findings (12 MEDIUM, 4 LOW, ten unique CVEs). All sixteen package/version/CVE
rows are saved in the [acceptance evidence](../security-remediation/acceptance-student-lifecycle-dev-2026-10-12.json).
The scanner reports blank fixed-version values; this does not establish vendor fix availability
or applicability. The retained platform image and its earlier findings keep their separate dated
[evidence](../security-remediation/acceptance-architecture-native-findings-2026-10-12.json).
The native fix plan remains: review each installed version against authoritative vendor
advisories, assess the actual runtime path, apply supported fixes, and rerun the exact-image
and application gates. No advisory applicability is closed by the image threshold gate.

Production was not deployed. Anonymous live checks establish their stated ingress/header
behavior; authenticated student mutations were exercised against synthetic local/CI fixtures,
not real dev student records.

## Remaining work

Continue payload contracts by actual uncovered browser workflows: directory/history detail and
import evidence lists, the separate Drive photo-import pipeline, school/guardian/consent flows,
and other critical mutations/reads. Existing extensible profile/report fields are not fully
specified by this release. Defining a schema is not proof of all possible attack resistance.

The eleven owner acceptance areas in the [saved owner-action plan](../security-remediation/acceptance-owner-actions.md)
remain open. They require their recorded configuration, account, device, recovery, workload,
vendor-advisory or custody inputs. No production deployment, provider send, physical restore,
infrastructure provisioning or destructive cleanup is included in this continuation.
