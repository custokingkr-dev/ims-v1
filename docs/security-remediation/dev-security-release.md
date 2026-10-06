# Dev security release verification

All seven application services deployed successfully to dev at `9509d20c4413dafd1196eaf9815f83cf4d3d2fb8` in [release 37524985447](https://github.com/custokingkr-dev/ims-v1/actions/runs/37524985447). Production was not changed. The full security plan still has open live acceptance and operational criteria.

## Verified release evidence

The actual release artifacts and job logs establish seven passing release suites, five isolated owner migration jobs, seven successful serial Cloud Deploy rollouts and seven approved immutable images. School's student schema reached V38, preserving owner-only denial policies and the fail-closed startup guard. Every final service revision was ready, matched the expected runtime child image digest and served 100% traffic. Gateway health was UP and frontend HTTP returned 200. These readiness records do not prove authenticated private Java health or user authorization.

All seven image vulnerability verdicts passed. School's changed image was freshly scanned; six unchanged exact image digests reused verified passing scan verdicts within the 24-hour cache policy. They were not freshly rescanned. The sanitized [gate evidence](dev-release-gates.json) records source and runtime digests, scanner results, migration schema versions, rollout IDs and approvals without credentials, environment dumps or user data.

## Tests and source assurance

The complete [follow-up CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/37523104488) passed:

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| Identity | 182 | 1 opt-in benchmark |
| Platform | 380 | 0 |
| Operations | 195 | 0 |
| Billing | 95 | 0 |
| School | 893 | 0 |
| Gateway | 99 | 0 |
| Frontend unit | 431 | 0 |
| Frontend browser | 116 | 0 |

Java totals are 1,746 tests, 1,745 passed and one intentional benchmark skip, with zero failures/errors. Counts select each final service aggregate once and are checked against unique Java test classes. The Linux catalog storage subset passed 8/8 including real symlink enforcement; it is already included in school totals.

[Orchestration CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/37524381259) passed; unchanged application suites were skipped there. Both languages passed [CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/37524380978), with zero open alerts on the reviewed PR 317 ref. This does not assert zero historical alerts across the repository. The routing fixture batch passed 13 tests, including two routing cases; four readiness tamper tests passed and all 24 offline architecture stages passed. Those subsets are not counted twice.

## Actual final live probes

[Signed and anonymous authorization proof](dev-live-authorization-proof.json) records 15 signed checks and 12 anonymous checks passing. Each private Java service accepted the gateway's real signed Cloud IAM health control (200), rejected forged principal/branch headers without a carrier (403), and rejected the gateway as an internal relay machine caller (403). The internal check used GET and could not execute the POST relay. The temporary signed probe job was independently confirmed absent. These checks use no tenant login fixtures and do not certify tenant role/object access or real WebAuthn ceremonies.

[Fresh anonymous frontend proof](dev-frontend-live-proof.json) matches final revision `custoking-frontend-dev-mux4y4gz` and its runtime image. Security headers, inline-script rejection and raster blob preview checks passed. [Digest-bound image user proof](dev-image-config-user-proof.json) verified exact manifest/config hashes and nonroot configured users: gateway `node`, frontend `nginx`. It does not observe Cloud Run process UID. Evidence hashes link sanitized permanent proofs to their captured source records.

[Runtime secret access revocation proof](dev-runtime-secret-revocation-proof.json) records 11 actual IAM membership removals: five runtime accesses to the owner database secret, five to the shared database secret, and gateway access to the JWT signing secret. All seven current revisions remained unchanged before/after, two IAM ancestor policies were checked, migration-owner and identity-signer access remained, and no secret values were read. This does not prove database role retirement or termination of existing shared-role sessions.

[Shared runtime retirement proof](dev-shared-role-retirement-proof.json) records `app_rt` NOLOGIN and zero remaining sessions at 2026-10-06T21:03:33Z. The temporary owner job was independently confirmed absent. Legacy ACLs were retained for migration callbacks; the role was not dropped, and this does not substitute for independent dedicated-role/RLS catalog readback.

[Pub/Sub metadata proof](dev-pubsub-metadata-proof.json) and [configuration report](dev-pubsub-metadata-findings.md) record four topics and four subscriptions with zero current metadata/IAM configuration findings after authorized repairs. No messages were published, pulled, acknowledged or replayed; broker delivery/restart/replay acceptance remains open.

## Final metadata acceptance and remaining criteria

The [final role catalog](dev-final-runtime-role-catalog.json) passed all five dedicated roles, with no foreign-table CRUD and 79 existing RLS-enabled relations in owned schemas forced with policies, including exact denial on two owner-only repair tables. This count covers existing RLS-enabled relations, not every database table. The identity schema has zero RLS tables and relies on its authoritative application authorization. Catalog inspection does not execute a positive tenant-row matrix. [Capacity readback](dev-final-capacity.json) verified seven exact ready revisions and scale caps; old-instance drain remains UNKNOWN, the source 198-connection model is not certified by sampled metrics, and no load/SLO certificate was produced. Conflicting memory gauges require investigation.

[Scheduler configuration](dev-final-scheduler-config.json) verifies five enabled POST/OIDC jobs. [Request history](dev-final-scheduler-delivery.json) retains 37 requests: 27 HTTP200 and ten earlier HTTP403. The latest request to each of the five targets returned200. Provider receipt is not verified. See [the operational correction history](dev-operational-corrections.md) for bounded retries and helper fixes, and [the ledger's owner actions](IMPLEMENTATION-STATUS.md#remaining-acceptance-owners-and-actions) for the remaining criteria.

Still open: real two-school user/object and WebAuthn ceremonies, managed rotation/emergency recovery rehearsals, actual provider delivery and idle/scale-zero workloads, broker restart/replay/DLQ drills, load/SLO and drain certification, privacy/retention/backup deletion replay, dashboard cross-instance/TTL and protected edge/repository-owned controls.

The earlier release stopped safely on owner-only zero-policy repair tables. The forward migration fixed that conflict without a guard bypass. Two subsequent operator-canceled attempts performed no deployment work. These histories remain recorded in the gate evidence; the successful final release does not erase remaining verification criteria.

## Proof provenance and ongoing verification

The sanitized gate record preserves first-release failure, operator cancellations, complete application CI, scoped CodeQL, final release, live probes and subsequent proof-tooling source hashes. [Operational corrections](dev-operational-corrections.md) describe actual failures, bounded retries and controlled test coverage without assigning an unproved cause. Original audit documents link to [the current ledger](IMPLEMENTATION-STATUS.md). Source-level proof tooling and CI additions after the successful application release require their own remote CI result; they do not change the recorded deployed image digests.
