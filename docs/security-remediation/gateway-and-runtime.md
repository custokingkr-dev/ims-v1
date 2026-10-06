# Gateway, frontend and dev environment remediation

This records implemented controls and bounded verification, not a certificate of immunity to every attack. Dev is the only authorized rollout environment; production was not changed.

## Implemented

Gateway authentication fails closed on Cloud Run. Bound HS256 access JWTs require issuer, audience, purpose and expiry, and still require authoritative identity introspection. Client identity/carrier headers are discarded. Signed Google service ID tokens carry validated user context to private backends; gateway no longer holds the identity signing secret. Cookie mutations require an allowed Origin or exact same-origin fetch provenance. Independent preauthentication buckets resist arbitrary bearer churn; forwarded addresses are trusted only with explicit reviewed proxy hops.

Proxy deadlines cover complete response bodies, disconnects cancel upstream work, and writable backpressure waits for drain. Identity token metadata and introspection calls have bounded deadlines. Sensitive operations preserve structured step-up denials; clients require a fresh session-bound passkey assertion and do not automatically replay financial mutations.

Frontend and gateway containers run as nonroot on port8080. Enforced nginx CSP and security headers cover HTML, assets and errors. Browser refresh uses same-origin Web Locks, session epochs prevent logout resurrection, transient restore failures remain recoverable, and credentials remain out of Web Storage. Modern Chromium was tested; browsers lacking Web Locks only have same-tab serialization. Memory-held tokens remain accessible to script running in the origin, so CSP and XSS defenses are still necessary.

Generated source comparison normalizes CRLF while retaining meaningful content drift. Ten shared infrastructure classes are now guarded across services. Five service runtime manifests disable migrations and reject all owner credential configuration. Release orchestration executes migrations in a separate bounded owner job before traffic.

## Verified

- 94 gateway tests passed under standard Node test concurrency, including slow-body deadlines and backpressure cancellation.
- 428 frontend unit tests and production build passed;111 established browser tests and5 security browser tests passed at their recorded checkpoints.
- Actual built nonroot nginx container verified headers/CSP/image preview/document download. See browser.md and browser-evidence.json.
- Frontend and gateway npm audits report zero vulnerabilities; Java/container image scans remain release-gate evidence until fresh images are built.
- 24 offline architecture audits passed. Generated inventory/client checks passed at447 endpoints/52 canonical browser operations after workflow contract convergence.
- Actual dev metadata confirms five dedicated NOSUPERUSER/NOBYPASSRLS/NOCREATEROLE/NOCREATEDB/NOINHERIT roles, no memberships, required TLS and access confined to owned schemas. See dev-runtime-role-proof.json. Preparation is not service cutover.

## Dev environment actions

Dev SQL backups were enabled with7 retained backups and7 days of transaction logs; point-in-time recovery enabled. A fresh on-demand backup completed successfully. Server SSL mode is ENCRYPTED_ONLY. Prepared five managed dedicated runtime password secrets/roles using the reviewed SQL hash; temporary job and temporary secret grants were removed.

18 student-photo objects (including6 legacy paths) were changed from public immutable caching to private,max-age=0,no-store using metageneration preconditions. No application records, photo contents or secret values are included in this report. Existing cached public responses may persist until their previous cache expiry; metadata changes cannot revoke already downloaded data.

A dedicated ims-db-migration-dev identity was created with owner password access and scoped image read. Trusted release identity has actAs and a custom run.jobs.delete-only capability. Google IAM does not list Cloud Run resource attributes as supported for IAM Conditions, so cleanup IAM is project scoped; exact nonce checks are script safeguards, not IAM boundaries. Existing unsupported conditional cleanup binding was removed. Runtime owner access must be revoked only after all five ownerless revisions have cut over.

## Remaining operational criteria

Dev deployment, post-cutover role/forced-RLS proof, Scheduler negative/positive caller proof, owner IAM revocation, fresh image scans/provenance, and final real-role browser checks remain distinct from source implementation. Backup recovery is measured with an isolated schema-only clone, not assumed from backup status. External SMTP delivery, alert/revocation exercises, agreed RPO/RTO, a protected edge domain, and administrator-controlled repository protections require independent evidence. Live dashboard has a separate production deployment and was not deployed under dev-only authorization.

Primary references: [Cloud Run service authentication](https://docs.cloud.google.com/run/docs/authenticating/service-to-service), [IAM resource attribute support](https://docs.cloud.google.com/iam/docs/conditions-resource-attributes), [nginx header inheritance](https://nginx.org/en/docs/http/ngx_http_headers_module.html), [Cloud SQL PITR](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/configure-pitr).
