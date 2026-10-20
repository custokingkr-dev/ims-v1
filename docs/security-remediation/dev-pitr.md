# Isolated development PITR verification

Authorized development drill completed on 2026-10-06 UTC (2026-10-07 in India). Source `custoking-db-dev` in `custoking-dev`, region `asia-south2`, was never restored over. PITR created the separate nonce instance `custoking-dev-security-restore-20261006182803-abe03276` at recovery point `2026-10-06T18:23:03Z`.

Observed timings from drill start:

| Measurement | Elapsed |
| --- | ---: |
| Successful CLONE operation plus RUNNABLE/private/encrypted readiness check | 715.487 seconds (11 minutes 55.487 seconds) |
| Private SQL schema catalog validation completed | 755.011 seconds (12 minutes 35.011 seconds) |

These are measured recovery timings. No agreed RPO/RTO is certified: the drill did not check business transactions, data-loss bounds, application recovery, or production availability.

Validation used one temporary Cloud Run job with Direct VPC private egress, pinned official PostgreSQL image `docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b`, and the new `ims-db-migration-dev` service account. Its password came directly from the existing owner secret reference; no secret value was accessed or exported locally. PostgreSQL used required TLS, a read-only transaction, ten-second statement timeout, and fixed catalog queries. All twelve expected schemas were present with stored relation definitions. No business rows, contacts, SQL dump, or Cloud Storage export were read or produced.

Cleanup is verified: the exact temporary job is absent and the exact clone's deletion operation completed with subsequent instance describe returning 404. The source remains RUNNABLE, private-only, ENCRYPTED_ONLY and deletion-protected. No project IAM was broadened. The clone inherited deletion protection, so its first deletion was rejected; protection was then disabled only on the exact nonce clone and deletion completed. Job deletion had already succeeded, but its `Cannot find job` absence wording initially needed matcher correction. The saved script now handles both conditions in `finally`; the evidence preserves these initial cleanup diagnostics.

Machine-readable evidence: [dev-pitr-20261006182803-abe03276.json](dev-pitr-20261006182803-abe03276.json). Reusable bounded dev-only operator script: [invoke-dev-schema-only-pitr-drill.ps1](../../scripts/invoke-dev-schema-only-pitr-drill.ps1). The script pins the project/source, generates exact nonce targets, bounds CLI requests and operation polling, checks source safeguards, and cleans only its own target resources.

The design follows Google's [PostgreSQL PITR procedure](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/pitr), [clone command](https://docs.cloud.google.com/sdk/gcloud/reference/sql/instances/clone), [Direct VPC job configuration](https://docs.cloud.google.com/run/docs/configuring/vpc-direct-vpc), and [job secret configuration](https://docs.cloud.google.com/run/docs/configuring/jobs/secrets). Those documents explain the mechanism; the local evidence above records the actual authorized dev execution.
