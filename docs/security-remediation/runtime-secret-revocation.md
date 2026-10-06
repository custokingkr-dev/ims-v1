# Development runtime secret ACL cutover

Implementation: [revoke-dev-runtime-secret-access.py](../../scripts/security/revoke-dev-runtime-secret-access.py). Controlled verification: nine tests passed using `python -m unittest discover -s scripts/tests -p runtime_secret_revocation_test.py -v`. No IAM mutation was performed by this workstream.

The default command performs metadata/IAM preflight and writes a sanitized dry-run plan:

```text
python scripts/security/revoke-dev-runtime-secret-access.py --evidence release-evidence/runtime-secret-revocation-dev-dry-run.json
```

After the release owner verifies the seven dev cutovers, apply the same guarded operation:

```text
python scripts/security/revoke-dev-runtime-secret-access.py --apply --evidence release-evidence/runtime-secret-revocation-dev.json
```

Project `custoking-dev`, region `asia-south2`, seven service names, runtime identities, dedicated database roles and secret names are fixed. Preflight checks desired configuration and actual latest ready revisions, observed generation, 100 percent latest traffic without revision tags, no owner/shared database secret references, Java migrations explicitly disabled, and exact per-service database credentials. Gateway checks additionally require enforced authentication, disabled local shared-key JWT verification, no signing secret and port 8080. Frontend readiness is also required. Revision snapshots are rechecked before every IAM set and after cutover.

The only planned removals are the five exact Java runtime service-account members from `roles/secretmanager.secretAccessor` bindings on `db-password-dev` and `app-rt-password-dev`, and the gateway member from that role on `jwt-secret-dev`. Unrelated members, roles, condition objects and audit configurations are preserved. Empty bindings are removed only when no members remain. IAM reads request policy version 3; writes carry the original etag and conflicts fail without retries. Conditional policies must retain version 3. Post-write policies must match the exact planned preservation. Identity's unconditional signer binding and the migration account's unconditional owner-secret binding are required before and after writes.

Project and ancestor policies are read to reject inherited runtime secret access, privileged custom-role capabilities, and unresolved group/public secret grants. Protected-secret bindings with unexpected runtime accessors or other secret-grant capabilities block the operation rather than expanding its removal scope. The actual dev organization IAM metadata was readable under the active principal; no read capability or project IAM was added.

Native requests have a 60-second deadline. IAM HTTP connections use a 15-second timeout; response bodies have a 30-second cancellation deadline and 2 MB limit. Access tokens exist only in process memory and authorization headers. No secret-version access endpoint is called, no secret value is downloaded, and evidence contains only readiness identifiers, membership counts and retained-capability assertions. Blocked execution saves sanitized failure and IAM-set attempt/response counts; partial writes do not trigger regranting old owner access.

Tests use native subprocess fixtures and an actual loopback REST server. They cover dry-run no writes, thirteen unsafe runtime/readiness/traffic cases, exact conditional/member preservation, custom inherited permissions, missing migration/signer access, unexpected accessors, etag conflicts, live revision drift, and cancellation of a trickled HTTP body. These are controlled tests; live ACL revocation remains the release owner's step. This helper does not disable `app_rt` LOGIN or modify database roles.
