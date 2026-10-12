# Student directory, history and evidence contracts

This continuation of the saved architecture plan adds ten canonical read operations,
bringing coverage to 93 operations across eleven clients, including 40 student operations.
It covers both directory routes, raw and workspace detail, history, import batches/rows/usage,
and review campaigns/items. Directory/detail/history/profile-update callers now use the
generated student client. The evidence-list methods are available without inventing new UI.

## Findings and fixes

| Finding | Evidence and implemented fix |
| --- | --- |
| A late detail response could replace a newly selected student; a closed verification launch could still start a read | Both failures were reproduced before the fix. Selection epochs and request IDs now guard detail, history, verification, section options and profile/photo callbacks. Three regression tests cover late detail, cancelled verification launch and late private history. Current directory filters are retained after mutations. |
| Student JSON and gateway errors lacked an explicit cache prohibition | Source inspection and an anonymous dev student request confirmed missing Cache-Control, without establishing any data leak. Student reads now set `private, no-store` and vary on Authorization; gateway responses default to `private, no-store`. An explicit upstream static-asset cache policy remains authoritative and has regression coverage. |
| Fleet import rows and status queries failed when a nullable school parameter reached PostgreSQL | Two real PostgreSQL/RLS tests reproduced SQLSTATE 42P18. Nullable school parameters are now bound as BIGINT. Superadmin fleet reads work; absent context and a foreign-school actor remain denied by the existing RLS boundary. |
| Handwritten types did not represent the actual directory/history/evidence responses | Named schemas now preserve pagination/filter envelopes, nullable record fields, dates, enrollment/import/promotion/fee history and assigned/unassigned fees. Java record bindings detect field/type drift, including Java date/time types. Serialized responses are checked against these schemas using real synthetic database fixtures. |

HTTP caching guidance comes from [RFC 9111 section 5.2.2.5](https://www.rfc-editor.org/rfc/rfc9111.html#section-5.2.2.5)
and [section 7.3](https://www.rfc-editor.org/rfc/rfc9111.html#section-7.3).
The no-store directive tells compliant caches not to retain the response. It cannot guarantee
privacy against a cache that ignores the directive. The missing directive was a control gap;
the inspected request did not demonstrate exposure of student records.

## Validation before release

The frontend suite passed all 469 tests, with no pending tests; TypeScript and the production
build passed. Focused school tests passed 44 tests with zero failures/errors/skips. The two
subsequently strengthened database tests passed another 17-test run, including nonempty
review evidence serialization and nullable progress lookup. Gateway tests passed 108 tests;
the strengthened upstream cache-policy test passed its three-test module. Eleven client-generator
tests passed. Inventory and generated-client freshness checks passed for 449 mappings and
93 canonical operations. Exact source CI and dev rollout acceptance passed and are recorded below.

The tests use synthetic fixtures. Generated TypeScript is not a production response validator.
No permission, RLS predicate, business transaction owner or production deployment is changed.

## Remaining fix plan

Continue contracts by actual uncovered callers: Drive photo-import, school/guardian/consent
flows and other domain reads/writes. Keep canonical ownership, actual nullable payloads,
permission/tenant denials and real persistence checks paired with each migration. The route
inventory is not complete payload coverage or proof against every possible attack.

The eleven owner acceptance areas remain open under the exact inputs and completion criteria
in [the owner action plan](../security-remediation/acceptance-owner-actions.md). In particular,
native image advisories require installed-version/vendor/runtime applicability review and
supported fixes followed by fresh image/application checks. A passing severity threshold
does not close advisory applicability. Keep the private snapshot custody review separate.

## Dev acceptance

[PR 349](https://github.com/custokingkr-dev/ims-v1/pull/349) deployed application source
`037e770de9a0507f8897bec9ba68164dc79888a1`, tree
`2f937a2f55c25e1fc1042fd3cb60b0acea1d8bc5`. The [source CI](https://github.com/custokingkr-dev/ims-v1/actions/runs/38208629887)
and [dev release](https://github.com/custokingkr-dev/ims-v1/actions/runs/38209076760) each passed
1,001 school tests with zero failures/errors/skips, 469 frontend tests, 116 authenticated
browser checks and 108 gateway tests. Both actual language uploads in
[source CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/38208629606) and
[post-merge CodeQL](https://github.com/custokingkr-dev/ims-v1/actions/runs/38209076610)
report zero findings. The server-hash-bound release archive, source/tree, image manifests,
exact-digest scans, test logs and runtime readbacks passed independent binding checks.

| Changed service | Ready revision | Traffic |
| --- | --- | ---: |
| Gateway | `custoking-api-gateway-dev-00041-bzf` | 100% |
| Frontend | `custoking-frontend-dev-00060-45b` | 100% |
| School-core | `custoking-school-core-service-dev-00082-pbv` | 100% |

The other four services retain their exact previous revisions, images and selected
configuration. All seven are Ready with sole untagged 100% traffic. Five runtime database
roles and disabled runtime migrations remain guarded. Matching independent metadata
snapshots bracket 19 anonymous/forged denial requests, full CSP/header/browser checks on
both exact dev origins and six cache checks. Anonymous student reads return 401 with
`private, no-store`; content-hashed assets retain `public, max-age=31536000, immutable`.
Both frontend/gateway manifest and config hashes verify configured nonroot image users.
This image configuration proof does not observe the process UID.

Fresh changed-image scans report gateway 0, frontend 0 and school-core 16 findings:
12 MEDIUM, 4 LOW, ten unique CVEs. Every package/version/CVE row is saved in
[the acceptance evidence](../security-remediation/acceptance-student-directory-dev-2026-10-12.json).
Blank scanner fixed-version fields do not prove vendor fix availability or applicability.
The retained platform image keeps its separate dated native findings and owner review.
The native fix plan and eleven owner acceptance areas above remain open.

Authenticated mutations were checked against synthetic local/CI fixtures; live dev checks
used anonymous requests and nonexistent resource identifiers. Production was not deployed.
Prior [student lifecycle acceptance](student-lifecycle-contracts-dev-2026-10-12.md) remains historical.
