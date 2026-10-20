# Owner migration credential isolation (VER-02)

The deployed runtime credential graph previously included the owner Flyway secret in every Java server container. Compromised application execution could therefore bypass dedicated runtime-role permissions. Database-role separation is incomplete until these owner secrets and corresponding Secret Manager permissions leave runtime instances.

All five Java jars now contain the same one-shot `com.custoking.ims.migration.MigrationOnlyMain`. It never starts Spring, the web server, runtime guards, relay workers or schedulers. It requires explicit `APP_MIGRATION_SERVICE`, `FLYWAY_URL`, `FLYWAY_USERNAME` and `FLYWAY_PASSWORD`. Service names are fixed to `identity-service`, `billing-service`, `school-core-service`, `operations-service`, and `platform-service`; a matching application class must exist in the selected image. Arbitrary schema/location/history overrides and command-line arguments are rejected. This preserves each service's existing schema order and locations, including `flyway_schema_history_tenant_school`; no baseline or existing history is rewritten.

Each schema uses an owner-only Hikari pool of at most three connections, closed before the next schema. The third connection permits Flyway SQL callbacks while the history and migration connections remain active. PostgreSQL connection establishment is bounded, statements have a 30-minute timeout and locks a 60-second timeout. A failed migration exits nonzero and blocks release. Library/database details belong in restricted migration job logs; the terminal process error is generic. A timed-out historical backfill requires investigation and a reviewed operator procedure, never weakening tenant guards.

After successful migration and reading the current version, the process emits `OWNER_MIGRATION_RESULT service=SERVICE schema=SCHEMA version=VERSION migrationsExecuted=N success=true`. Identifiers come from the fixed service map and version is restricted to numeric/dot migration versions. The final marker is `Owner migrations completed for SERVICE`. An earlier schema marker never overrides a later nonzero process exit; the orchestrator must require terminal job success.

Launch from the same Spring Boot executable image using:

```text
java -Dloader.main=com.custoking.ims.migration.MigrationOnlyMain -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

The standard `java -jar` launcher retains the server application main and cannot select this alternate main. Cloud Run migration jobs must override the image command to `java` with these arguments, rather than appending arguments to its normal shell entrypoint. Supply migration-only owner credentials to that job and give only its dedicated migration service account access to them.

`APP_MIGRATIONS_ENABLED=false` now disables custom per-schema Flyway configurations and their JPA dependency processor. A Boot environment postprocessor also forces auto-configured Flyway off, so a profile or `spring.flyway.enabled=true` cannot reenable it. Identity/billing YAML defaults honor the same switch; local/test defaults remain enabled for compatibility. Disabled runtimes reject explicit owner environment/config properties without printing their values. They need only runtime datasource configuration. The switch by itself does not revoke IAM or secret access; root's pipeline and infrastructure changes must remove those capabilities.

Release order is migration job completion, then deployment of the server image with migrations disabled and no owner secrets. Use the same immutable image digest for the two processes. Keep production rollout conditional on the approved working pipeline; this report does not claim cloud deployment, credential revocation, or dev closure. Confirm actual instance environment, IAM effective permissions, histories/checksums, FORCE RLS, and server readiness after the approved release.

Local tests verify actual PostgreSQL migrations and a second idempotent run under a non-superuser/non-bypass owner, fixed histories and FORCE RLS, zero owner connections after return, and denial of owner schema changes by an isolated runtime. Policy tests exercise Boot discovery with a production profile, absent owner credentials, explicit override rejection and local compatibility. These profile tests use a minimal Spring context; final complete server readiness remains a deployment/integration check.

Final targeted `Migration*Test` reactors: 43 tests passed, no failures/errors/skips (school-core 9, operations 9, billing 8, identity 8, platform 9), including actual PostgreSQL owner migrations and prod-profile rejection when owner secrets are still attached. Logs are local ignored build artifacts; these are local execution results, not deployed credential evidence.

Billing's executable jar was repackaged successfully with both entrypoints present. Launching that actual jar with `PropertiesLauncher` and `loader.main`, with its correct service name and owner credentials removed, exits 1 with only the generic migration failure message and no Spring/web startup. This proves alternate-main packaging and fail-closed selection; successful PostgreSQL migration is covered by the integration tests above.
