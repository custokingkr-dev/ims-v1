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
93 canonical operations. CI and exact dev rollout acceptance are recorded below once verified.

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

Pending exact source CI, release artifact/digest binding, fresh runtime readback and bounded
live dev checks. Prior [student lifecycle acceptance](student-lifecycle-contracts-dev-2026-10-12.md)
remains historical and is not evidence for this source.
