# Identity and dashboard dev acceptance â€” 2026-10-07

These are executed, isolated `custoking-dev` acceptance results. They do not certify production or physical authenticator interoperability. The main identity signing configuration was not changed. The main implementation ledger remains authoritative for the broader plan.

| Executed check | Result | Evidence |
| --- | --- | --- |
| Two independently school-bound SCHOOL_ADMIN logins, own-school reads, reciprocal foreign-school denial and logout revocation | 8/8 passed | [Sessions](acceptance-identity-sessions.json) |
| Own student object reads, reciprocal foreign object denial, forged foreign actor/branch/carrier headers | 6/6 passed; own objects200, foreign objects404 | [Object authorization](acceptance-object-authorization.json) |
| Actual browser WebAuthn registration/assertion verified by deployed Yubico, two independent virtual credentials; replay and foreign-session challenge rejection | 22/22 passed | [Passkeys](acceptance-passkeys.json) |
| Two independent freshly verified recovery administrators; password/enrollment alone denied, same approver denied, independent approval, target sessions revoked, re-enrollment and fresh assertion required | 19/19 passed | [Dual control](acceptance-dual-control-recovery.json) |
| Origin rejection, refresh rotation/reuse, descendant revocation and simultaneous refresh race | 10/10 passed; concurrent statuses200/401, winning descendant subsequently401 | [Refresh race](acceptance-refresh-race.json) |
| Exact immutable identity image in private isolated dev service; actual login/introspection across managed key versions1â†’2, overlap acceptance, previous-key retirement rejection, new key remains accepted | 7 security invariants plus1 coordination checkpoint passed | [Managed rotation](acceptance-managed-key-rotation.json) |
| Real Firestore atomic-create/conflict and revocation persistence across two independent Cloud Run jobs | Passed; foreign existing database and list operations403 | [Firestore](acceptance-dashboard-firestore.json) |
| Actual dashboard cookie/state implementation using real Firestore; independent module instances reject OAuth replay/logout cookie and expired cookies | 5/5 passed | [Auth wrappers](acceptance-dashboard-auth-state.json) |
| Named database, deletion protection, minimal conditional role, managed session key and TTL API | Provisioned; TTL ACTIVE | [Provision](acceptance-dashboard-provision.json), [readback](acceptance-dashboard-config-readback.json) |
| Physical asynchronous TTL deletion | Not observed within bounded300seconds | [Observation](acceptance-dashboard-ttl.json) |

The browser generated real challenges/signatures through Chromium CTAP2 software authenticators on the actual HTTPS frontend RP origin. The deployed verifier accepted these ceremonies. No physical security key or hardware attestation was used. The two dashboard cookie modules shared a live Firestore backend inside one job; the separate persistence probe used two independently executed Cloud Run jobs. No real Google OAuth provider login or dashboard web request is claimed.

## Fixture provenance and cleanup

Only reserved schools990007101/990007102 and marker `SEC-ACPT-20261007` were created. School-bound actors990007201/990007202 use SCHOOL_ADMIN with scoped assignments; they have no SUPERADMIN bypass. Twenty minimal synthetic students990007301..990007320 contain marker names/admission numbers and isolated class/year/sections, with no contacts, guardians, photos or fee records. Existing business rows were not read or mutated. Initial empty own-school lists were supplemented with the marker-owned objects before object/load checks.

Temporary recovery approvers990007203/990007204 were SUPERADMIN only for the controlled recovery procedure. They are now soft-disabled, every session and assignment revoked, authenticators removed, and immutable audit rows retained. Actual password login401 was verified for both. School/ordinary actor cleanup is held until the isolated PITR and broker restart drills finish. Credentials/tokens remain operator-restricted ignored local artifacts and never appear in permanent evidence.

[Independent cleanup proof](acceptance-identity-dashboard-cleanup.json) verifies both rotation attempts' services/jobs/sandbox secrets and the fixture/dashboard probe jobs are absent. The successful rotation clone had maxScale1/concurrency2 and historically inherited pool max5/min0; it is deleted. The promoted helper now explicitly uses pool max2/min0. This future correction does not retroactively certify historical maximum connection headroom. Main secret values were never copied into the sandbox: it used newly generated independent managed keys, one synthetic user and the main runtime's existing ownerless database role.

## Operational corrections retained

The initial fixture used an unsupported ERP entitlement label, so its own-school student read403 was correctly treated as a failed precondition. Marker-only correction added STUDENTS/ATTENDANCE, after which positive and negative checks passed. The promoted provisioning source uses the correct codes initially; its optional repair helper is idempotent.

The first browser probe's direct gateway fetch was blocked by the deployed `connect-src 'self'` policy. It was corrected to the real same-origin frontend API proxy. An initial harness incorrectly expected step-up expiry from the status response; that field is returned by successful assertion verification. A marker-only owner maintenance reset of its temporary virtual factors was explicitly audited before the final two-account pass. That reset is not counted as the dual-control recovery drill.

The first isolated rotation probe failed before a security invariant was measured and was cleaned up. The successful attempt retained seven initial HTTP403 admission retries separately from its passing application checks. No definitive propagation/root-cause claim is made. The application then demonstrated both-key acceptance and old-key rejection after retirement.

## Dashboard resources and remaining dependencies

Dev has Firestore API enabled, native `ims-dashboard-dev` in `asia-south2`, deletion protection, `expiresAt` TTL ACTIVE on `dashboardSecurityState`, service account `ims-dashboard`, custom role `dashboardSecurityState_dev` with only `datastore.entities.get`/`datastore.entities.create`, and exact named-database IAM condition. Managed `dashboard-session-secret` has a generated48-byte-random-input key and only the dashboard runtime accessor binding added by this work. A temporary empty foreign database used for the403 control was deleted. The default database did not exist; its404 is not an actual default-database IAM denial proof.

These resources match [Terraform declarations](../../deploy/gcp/observability/dashboard_security_state.tf) but have **not** been imported into Terraform state. No Terraform apply or drift-free claim is made. Before future apply, the release owner must review/import the existing API, database, field, service account, custom role and conditional member, then inspect the plan; do not recreate or delete the protected database. The session secret is an existing external reference in [service configuration](../../deploy/gcp/observability/dashboard_service.tf), not a Terraform plaintext value.

No dashboard service was deployed: dedicated OAuth client registration/secret, exact registered HTTPS callback and approved human email allowlist remain unavailable. The existing photo-import OAuth secrets are unrelated and were not reused. Once supplied, release requires the scanned immutable dashboard image, actual protected web/OAuth acceptance and representative cross-instance rollout/revocation verification. No auth-off deployment was used.

Expired cookies were rejected independently of cleanup using a controlled13-hour clock advance against the live Firestore implementation. A genuinely expired hashed record remained present throughout the300-second observation. [Google documents TTL deletion as asynchronous, typically within24hours](https://docs.cloud.google.com/firestore/native/docs/ttl). The test service account cannot delete records. Actual TTL deletion and observed billed cleanup cost remain open; current bounded tests created only a handful of expiry/hash records, with no load or stored identities/cookies.

## Reproduction boundaries

The [17 helper source digests](acceptance-identity-dashboard-sources.json) use SHA256 of UTF-8 source with CRLF normalized to LF, making checkout line endings immaterial. Every helper requires explicit `--apply-dev`; none targets prod. Python, local bcrypt and the installed frontend Playwright/Chromium are prerequisites. Cloud output/error streams are captured and withheld, managed secret values travel only through process input or Secret Manager references, HTTP calls are bounded, and cloud subprocesses have finite deadlines. Four controlled regression tests pass in [security_acceptance_tool_test.py](../../scripts/tests/security_acceptance_tool_test.py), including missing-flag rejection and secret redaction on error/timeout.

Fresh fixture provisioning must not be blindly rerun against occupied reserved IDs: its SQL collision gate stops. Run provisioning, minimal student seed, server login/session and object checks, virtual ceremonies, recovery-actor provisioning/dual recovery, then privileged actor disablement and independent cleanup verification. The published recovery helper checks the original target access token; use the same reviewed fixture journal. A physical-key proof, production rollout, actual provider OAuth login and main signing-key rotation require their corresponding owner inputs and separate execution records.

Final ordinary actors and school structures are now cleaned up, while required audit/deletion evidence remains. Password and old access-token rejection, actual student/photo erasure, broker persistence and exact job absence are recorded in [the final live report](acceptance-final-live.md). Eight exact owned local credential/fixture files were removed after verified account/resource revocation; [independent file absences](acceptance-local-private-cleanup.json). The earlier held-fixture cleanup snapshot is preserved as historical evidence.
