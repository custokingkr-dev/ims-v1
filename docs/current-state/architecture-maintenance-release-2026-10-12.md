# Architecture maintenance dev acceptance and remaining fix plan

Captured 2026-10-11T20:43:25.187994+00:00. This closes the planned gateway, student persistence, reporting and
workspace responsibility refactors and their scoped dev delivery. It does not close every security
acceptance area or certify every feature and attack path.

[PR 344](https://github.com/custokingkr-dev/ims-v1/pull/344) merged into `dev` as
`3b2eab5d2a33311947929485625ac1edc2b11b8f`. Its tree `c96d875d45801850bacc576f6e04eaf1ad0292a6` matches the locally tested tree and
the PR merge tree. `dev` deploys to `custoking-dev`; `main` deploys to `custoking-prod`.
This work made no production changes.

## Completed delivery and checks

[Source CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/38171001465) and
[dev release](https://github.com/custokingkr-dev/ims-v1/actions/runs/38171529887) passed.
Both runs independently report the following counts; repeated runs are not summed:

| Suite | Passed |
| --- | ---: |
| School core on Linux, no failures/errors/skips | 990 |
| Platform on Linux, no failures/errors/skips | 506 |
| Frontend unit/component tests | 450 |
| Authenticated browser suite in CI and release | 116 |
| Gateway tests, no failures/skips | 108 |

The two actual source CodeQL uploads and the two actual post-merge uploads passed with zero reported
results. [Post-merge CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/38171529604)
is bound to the merged source, rather than inferred from the workflow name.

Live service and revision readback binds four changed runtimes (gateway, frontend, platform and school
core) to the release manifest and three retained runtimes (identity, operations and billing) to the
before snapshot. All seven are Ready with sole untagged 100% traffic targets. The five dedicated runtime
database roles and disabled runtime migrations passed their configuration checks. The selected edge,
provider and erasure-journal settings match the before snapshot; this is not a comparison of every
environment variable or effective IAM/database privilege.

Matching metadata snapshots bracket 19 passing public authorization-denial cases. Those cover anonymous
private-service ingress, forged user/machine headers, protected gateway reads and canonical/diagnostic
internal and Pub/Sub paths. The private 403 responses prove Cloud Run ingress denial; they do not prove
application authorization for a signed caller. The frontend and gateway origins also pass anonymous HTTP
and browser CSP checks, including blocked inline scripts/objects and permitted raster blob images.
Digest-bound gateway/frontend image configuration declares nonroot users; no live process-UID assertion
is made. These bounded observations do not establish an exhaustive live feature matrix or an atomic
absence-of-drift guarantee.

The expanded probe also recorded a header consistency gap: gateway-generated JSON denials omit
`Permissions-Policy`, while browser documents include the frontend policy. A separate source follow-up
adds that same policy (`geolocation=(), microphone=(), camera=(self)`) at the gateway and verifies
health/401/404 responses and preservation of an upstream policy. Its own CI/dev acceptance is required;
the refactor's captured evidence does not include that later runtime. Earlier failed probe captures
remain preserved. No script execution or authorization bypass was observed from this omission.

## Current changed-image scan findings

All four exact-digest HIGH/CRITICAL gates passed. The complete SARIF files remain in the hash-bound release
artifact; reported lower-severity findings are retained and are not suppressed or converted into an
applicability exception. The three retained runtimes have their earlier evidence dates, not new scans
from this release.

| Changed image | SARIF findings | Evidence source |
| --- | ---: | --- |
| api-gateway | 0 | scanned |
| frontend | 0 | scanned |
| platform-service | 16 | scanned |
| school-core-service | 16 | scanned |

[Scoped acceptance and evidence hashes](../security-remediation/acceptance-architecture-maintenance-dev.json)
record image identities, runtime/configuration observations, suite counts, scan outcomes and public checks.
The raw local captures and downloaded release artifact retain their original bytes in `tmp`.

## Remaining security fix plan

The [owner-action plan](../security-remediation/acceptance-owner-actions.md) remains authoritative for
the nine operational groups, native-advisory applicability and private local snapshot custody. All eleven
areas remain open. The architecture refactor and its deployment do not supply the missing owner inputs.

| Area | Next action and completion evidence |
| --- | --- |
| Owned edge | Supply approved hostname/budget; apply the prepared edge controls and verify origin restrictions, allowed traffic and blocked attack cases. |
| Repository governance | A repository administrator must enforce and verify branch protection, required checks and the saved governance controls. |
| Dashboard | Supply dedicated OAuth/access configuration and verify the separately governed dashboard deployment and cleanup attribution. |
| Provider delivery | Supply selected account contracts and approved test recipients; attest actual delivery, authenticated reports and reconciliation with the same stable operation IDs. |
| Physical authenticators | Run the approved device/browser/OS matrix and verify actual enrollment, authentication, recovery and revocation. |
| Recovery and retention | Provision an approved isolated physical restore target; prove historical intent coverage, all service/object/provider fences, freeze/drain, usable recovery and governed resume/purge. |
| Capacity and obsolete revisions | Approve workload/SLO/window; measure representative resource use and reserve. Refresh the exact cleanup inventory and obtain exact deletion approval before irreversible removal. |
| Upstream HTTP/2 | Resolve the recorded reset-code conformance anomaly with the ingress owner and rerun bounded controls on the owned edge. |
| Egress and file processing | Approve destinations; enforce and test the real egress path, rebinding/redirect controls and supported workbook/viewer matrix. |
| Native advisories | Review scanner findings against actual installed packages and authoritative vendor advisories for each immutable digest; patch supported fixes and rerun exact-image gates. Do not infer applicability from finding absence. |
| Private snapshot custody | Privately classify the recorded unclassified local object and obtain exact cleanup approval before any local ref or physical-storage removal. |

No provider messages, full restore, physical-device acceptance, owner infrastructure changes or revision
deletions were performed by this continuation. Existing dated security evidence and pending captures
retain their recorded scope.

## Incremental browser-contract work

The current 58 canonical operations across 11 generated clients are verified; the 449-mapping inventory
is route coverage rather than full payload coverage. The remaining payload work should proceed by
actual browser workflows:

1. Inventory callers and owning canonical handlers for the remaining student import, promotion, review
   and erasure workflows, then other uncovered critical mutation/read flows.
2. Define request/response schemas from actual Java DTOs and serialized behavior, including nullable
   fields, pagination, upload representations, stable keys and reviewed versions.
3. Add server response/denial checks and generation checks; migrate callers through the shared
   authenticated transport while preserving tenant scope, uncertain outcomes and draft state.
4. Verify malformed-input and cross-school denials, transaction rollback and deliberate conflict/retry
   behavior; run affected CI and independently accept the resulting dev images and public checks.

The detailed boundaries and reasons remain in [the architecture reference](project-architecture.md).

## Later gateway header acceptance

Captured 2026-10-11T21:03:42.938008+00:00. [PR 345](https://github.com/custokingkr-dev/ims-v1/pull/345)
merged into `dev` as `c44c563ce1299a8b2eda32dfd244fd0cc5ef90c8` and passed its separate
[dev release](https://github.com/custokingkr-dev/ims-v1/actions/runs/38174071671) and
[post-merge CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/38174071452).
Both actual CodeQL uploads report zero results; source CI and release each pass 108 gateway tests.

The gateway is now `custoking-api-gateway-dev-00039-xsz`, with its requested root image and actual child digest bound
to the release manifest. The other six service revisions and digests match the accepted before snapshot.
All seven remain Ready at sole untagged 100% traffic; the five database role/migration guards pass.
The original full security-header and CSP/browser assertions now pass on both exact dev origins,
including `Permissions-Policy` on the gateway's 401 JSON response and `camera=(self)` for the existing
photo flow. All 19 public denial cases pass again. This closes the header consistency gap recorded above;
the earlier captures keep their dates and bytes.

[Header follow-up acceptance and evidence hashes](../security-remediation/acceptance-gateway-permissions-policy-dev.json)
record the exact scope. The new gateway scan reports zero findings. The retained Java image findings
remain 24 MEDIUM and eight LOW package rows; they are not new findings from the gateway-only release.
[All 32 reported package rows and their fix plan](../security-remediation/acceptance-architecture-native-findings-2026-10-12.json)
retain immutable image bindings, installed versions and raw SARIF hashes. None reports a fixed version;
that empty scanner field is not a vendor applicability or fix-availability attestation.

[Research checkpoint](../security-remediation/acceptance-architecture-security-research-2026-10-12.json)
records the primary [camera policy reference](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Permissions-Policy/camera).
It describes the same-origin default and limited browser support; real device/browser acceptance remains
open. Two Ubuntu advisory fetches returned internal errors, so no vendor clearance was inferred.
The eleven owner areas and incremental payload coverage listed above remain open. Production was not changed.
