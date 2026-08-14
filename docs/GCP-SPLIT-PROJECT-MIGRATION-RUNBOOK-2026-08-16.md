# Custoking Split-Project GCP Migration Runbook

**Prepared:** 2026-08-14 IST
**Target:** development to `custoking-dev`; production to `custoking-prod`
**Source:** existing mixed-environment project `custoking`
**Companion:** `GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md`
**Status:** authoritative plan; execution is **NO-GO** until the target projects exist and every mandatory
preflight gate below has evidence.

This runbook contains no credential values or student-level data. Evidence containing resource names,
school identifiers, object manifests, or database fingerprints belongs in the restricted migration evidence
location, not Git.

## 1. Approved decision and non-negotiable sequencing

The existing `custoking` project contains both environments. It will not be moved in place. Each environment
will be rebuilt into a separate project so billing, IAM, quotas, deployments, alerts, data access, and future
incidents are isolated.

| Environment | Source resources | Destination | Required order |
| --- | --- | --- | --- |
| Development | all resources/data/configuration ending in or assigned to `dev` | `custoking-dev` | build, copy, validate, cut over, and soak first |
| Production | all resources/data/configuration ending in or assigned to `prod` | `custoking-prod` | begin only after the development migration is accepted |

Do not attempt both migrations in one unrehearsed window. The development migration is the rehearsal and
must complete its stabilization period before production receives a GO decision. The source project remains
recoverable and authoritative until the destination environment is formally accepted; source deletion is a
separate future change.

As of 14 August 2026, neither destination project is visible to the operating account. A Sunday production
cutover is therefore NO-GO unless the projects, billing, access, quotas, infrastructure, data rehearsal, and
rollback route are created and verified beforehand.

## 2. Scope: everything that must be recreated or re-bound

The source inventory is recorded in `current-state/gcp-infrastructure.md`. The migration ledger must assign
every item to development, production, shared external state, or retired state. At minimum it covers:

- project hierarchy, billing account, budgets, billing export, APIs, regional quotas, labels, and organization
  policies;
- service accounts, project/resource IAM, custom roles, service agents, Workload Identity Federation pools,
  providers, attribute conditions, and GitHub environment variables;
- VPC, subnets, private service access, serverless networking, DNS or domain mappings, and egress rules;
- Artifact Registry repositories, immutable image digests/tags, Cloud Deploy pipelines/targets, Cloud Build
  dependencies, Cloud Run services, Cloud Run jobs, Scheduler jobs, and invocation IAM;
- Cloud SQL instances, databases, flags, users, passwords, backups, PITR, deletion protection, connectivity,
  Query Insights decision, and application connection budgets;
- Cloud Storage buckets, lifecycle rules, retention/soft-delete policy, CORS, uniform access, public access
  prevention, object manifests, signed-URL identity, Terraform state, deploy source, recovery exports, and
  student photos;
- Pub/Sub topics, subscriptions, OIDC push identities/audiences, retry, dead-letter topics, retention,
  Scheduler relay paths, and bounded in-flight events;
- Secret Manager secret metadata, per-service access, version rollout, and externally supplied values;
- Logging buckets/sinks/exclusions, log metrics, dashboards, alert policies, notification channels, uptime
  checks, SLOs, trace configuration, and the cost-control workflow;
- Google Drive roots and sharing, OAuth consent/client ownership, MSG91 configuration, GitHub repository
  environments, required reviewers, branch restrictions, and any operator-owned local procedures.

No resource is considered migrated because a similarly named destination resource exists. It must be tied to
an inventory row, configured from reviewed source or captured live state, and pass its acceptance test.

## 3. Required authorities and named roles

Record names and backup contacts in the private change record, not this repository.

| Role | Minimum responsibility |
| --- | --- |
| Change commander | owns GO/NO-GO, freeze, timeline, rollback, and communications |
| Source project operator | captures inventory and grants temporary read/copy access |
| Destination project/IAM operator | creates hierarchy, billing, APIs, identities, IAM, WIF, and quotas |
| Database/recovery operator | performs exports/restores, integrity ledgers, RTO/RPO evidence, and rollback |
| Application operator | builds/deploys exact releases and executes authenticated functional tests |
| Security verifier | reviews IAM/policies/secrets/evidence handling and removes temporary grants |
| Billing owner | approves budgets, alerts, billing export, duplicate-resource window, and cost ceiling |
| Integration owner | verifies GitHub, OAuth/Drive, MSG91, DNS, and recipient-controlled alert delivery |

Two independent identities must retain source and destination administrative access during stabilization.
Do not make one personal account the only rollback path.

## 4. Hard prerequisites

- [ ] `custoking-dev` and `custoking-prod` exist under the intended organization/folders and are visible to
  both primary and backup operators.
- [ ] Billing is attached; environment-specific budgets and alert recipients are enabled before paid
  resources are created.
- [ ] Organization policies, allowed regions, IAM domain restrictions, service-account constraints, VPC
  Service Controls, CMEK requirements, and audit requirements are recorded for both destinations.
- [ ] `asia-south2` Cloud Run and Cloud SQL quotas are sufficient for rollout overlap and the approved SQL
  tiers. Quota is verified rather than inferred from the source project.
- [ ] Required APIs and service agents are enabled deliberately in each destination.
- [ ] The complete source inventory and environment classification have zero unassigned resources.
- [ ] A release ledger records the exact image digest and Flyway history for every service in each source
  environment. Production must not silently receive dev-only commits.
- [ ] The companion data-integrity plan has a successful independent restore rehearsal for the same export
  mechanism that will be used at cutover.
- [ ] GitHub environments and WIF attribute conditions are designed so `dev` can authenticate only to
  `custoking-dev` and `main`/approved production jobs only to `custoking-prod`.
- [ ] Source routing can be restored within the approved RTO without modifying or destroying source data.
- [ ] A duplicate-resource cost ceiling and automatic cleanup/stop dates are approved.

Any unchecked prerequisite is a NO-GO for production.

## 5. Destination bootstrap order

Build from reviewed Terraform or scripted configuration wherever repository coverage exists. For resources
not yet declarative, export metadata to restricted evidence and create a reviewed, repeatable command or add
it to infrastructure-as-code before cutover. Never treat an unreviewed console recreation as final state.

1. Create project labels, billing budgets/alerts, billing export destination, audit access, and organization
   policy baseline.
2. Enable the exact required API list and record newly created Google-managed service agents.
3. Create VPC/subnet/private service access and verify address ranges do not conflict with operator or
   future connectivity.
4. Create dedicated runtime, deploy, recovery, Pub/Sub push, Scheduler, and monitoring identities. Grant
   least privilege per resource; do not reproduce broad legacy/default-Compute bindings automatically.
5. Create separate WIF resources or a deliberately shared identity project. Bind immutable repository,
   workflow, ref/environment, and audience claims. Run an authentication-only positive test and a wrong-ref
   negative test for each destination.
6. Create Artifact Registry and copy only required immutable digests. Compare source/destination digest,
   architecture, and manifest; tags alone are not evidence.
7. Create environment-specific Storage buckets with the approved region and security/lifecycle settings.
   Bucket names are globally unique and therefore cannot be assumed to match the source names.
8. Create Cloud SQL with approved source-equivalent flags and recovery controls. Development remains the
   cheapest adequate stopped-on-idle tier; production tier/HA changes require the separate DB-01 capacity
   decision, not the migration itself.
9. Recreate secret names and IAM. Supply secret values through the approved secret-transfer ceremony; do
   not export values to files, logs, shell history, GitHub output, or migration evidence.
10. Recreate Pub/Sub, DLQs, push identities, and Scheduler in a disabled/paused state. Push audiences must
    use destination Cloud Run URLs.
11. Deploy exact approved image digests to Cloud Run with zero minimum instances and capped maximum scale.
    Keep external traffic/callers disabled until database and secret verification passes.
12. Recreate dashboards, log metrics, sinks/buckets, SLOs, uptime checks, alert policies, and notification
    channels. Keep external uptime traffic disabled until cutover but prove policies can evaluate.

Official mechanics used by this plan:

- Cloud SQL export/import requires both the initiating identity and the Cloud SQL instance service account
  to have explicit database and Storage permissions:
  https://cloud.google.com/sql/docs/postgres/import-export/import-export-sql
- Storage Transfer Service supports source and destination buckets in different projects and requires
  explicit source/destination access:
  https://cloud.google.com/storage-transfer/docs/create-transfers
- Artifact Registry documents cross-project digest-preserving copies with `gcrane`:
  https://cloud.google.com/artifact-registry/docs/docker/copy-images
- WIF provider audiences include a project number and must be recreated/re-bound for the destination:
  https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines

## 6. Data migration and integrity

Execute the companion data plan separately for development and production. The cutover snapshot boundary is
the database freeze/export time, not the start of the infrastructure build.

1. Build empty destination databases and buckets; validate connectivity without application writes.
2. Capture source database structural, row/fingerprint, business-invariant, Flyway, outbox/inbox, and audit
   ledgers at the approved stable snapshot.
3. Export from a stable recovery point, import into the destination, and recompute the same destination
   ledger. Do not accept row count alone as proof.
4. Copy live student-photo objects to environment-specific destination buckets without deleting source
   objects. Compare logical key, size, and CRC32C. GCS generation numbers are project/bucket-local and are
   recorded independently rather than expected to remain equal.
5. Reconcile database photo references, destination objects, and external Drive metadata. Drive data is not
   copied; new runtime/operator identities must be granted and tested explicitly.
6. Drain or bound durable events and Pub/Sub deliveries. Never allow simultaneous source and destination
   publishers/consumers to process the same event set without a proved idempotency design.
7. Retain source backups, exports, manifests, and access through the stabilization and rollback period.

## 7. Development migration and rehearsal

Development must exercise the entire production procedure, including failure handling—not merely deploy an
empty stack.

- [ ] Create and validate all `custoking-dev` resources using the bootstrap order.
- [ ] Copy exact dev release digests, database, student-photo objects, and required configuration.
- [ ] Update only the GitHub `dev` environment variables to the new project number, WIF provider, deploy
  service account, region, SQL addresses, buckets, Drive roots, and destination service URLs.
- [ ] Prove the cost-control workflow authenticates from its intended branch and stops the new dev SQL
  instance (`activationPolicy=NEVER`, state `STOPPED`).
- [ ] Run full CI, Flyway validation, authenticated gateway suite, tenant isolation, student create/read/
  permanent-delete, operator school export, attendance, import, fees, reporting, recovery, tracing, and alert
  receipt tests.
- [ ] Run source-to-destination data ledgers twice and investigate every mismatch.
- [ ] Exercise rollback to the source dev environment within the approved RTO.
- [ ] Soak for the agreed period with zero unexplained 5xx, exporter failures, data mismatches, or budget
  anomalies. Record the destination cost separately.

Only after signed development acceptance may the production change be scheduled.

## 8. Production preparation

- [ ] Create `custoking-prod` from the accepted dev-rehearsal procedure, with production-specific scale,
  retention, recovery, approval, and IAM values.
- [ ] Copy the exact production digests from the release ledger; do not promote unapproved dev-only changes.
- [ ] Complete an isolated import and full integrity comparison before the cutover window.
- [ ] Prove destination backups/PITR, deletion protection, and an independent restore drill.
- [ ] Prove production WIF authentication while preventing an actual traffic change.
- [ ] Verify OAuth/Drive access, MSG91 dry-run/consent posture, signed URLs, CORS, and every configured external
  endpoint using destination identities.
- [ ] Trigger a non-production test alert through the real primary and backup operator channels.
- [ ] Keep Cloud Run minimum instances at zero and pause Scheduler until the final traffic checkpoint.
- [ ] Record final source/destination cost estimates and the date temporary duplicate resources will stop.

## 9. Production cutover procedure

### T-120 to T-60: evidence and GO/NO-GO

1. Open the change bridge; confirm named operators, backup identities, communications, and rollback authority.
2. Confirm source and destination health, current release ledger, project quotas, billing alerts, and no
   unrelated incident.
3. Confirm the validated destination import can be recreated from the final snapshot within RTO/RPO.
4. Set application/operator write freeze. Stop imports, onboarding, writes, relays, Scheduler, and publishers.
5. Drain or record the exact bounded outbox/inbox/Pub/Sub event set.
6. Capture the final source ledgers and recovery point. Any failed mandatory data gate is NO-GO.

### T-60 to T0: final copy

1. Export/import the final production database snapshot into the empty/reset approved destination database.
2. Run the complete destination ledger and compare with the frozen source ledger.
3. Perform the final incremental object copy and checksum/size manifest comparison.
4. Reconcile photo references, Drive mappings, durable events, secret metadata, Flyway state, and release
   digests.
5. Keep destination consumers and public traffic disabled until the change commander records GO.

### T0 to T+45: activate destination

1. Enable destination Cloud Run callers/routes and update the production integration variables or domain/DNS
   routing using the pre-reviewed change.
2. Enable only the accepted production Scheduler/subscriptions; prevent source and destination from consuming
   concurrently.
3. Run health, login/token refresh, tenant isolation, school/student reads, one approved reversible write,
   attendance, fees, reporting, photo signed URL, Drive, tracing, logging, and alert evaluation tests.
4. Compare SQL writes, outbox/inbox state, Pub/Sub backlog/DLQ, object changes, and audit events to the bounded
   test actions.
5. Keep the write freeze if any result is ambiguous. Never repair a mismatch by deleting source evidence.

### T+45 through stabilization

1. Unfreeze gradually after the signed acceptance matrix passes.
2. Monitor error rate, latency, SQL CPU/memory/connections/storage, Cloud Run scale, exporter failures,
   async age/backlog/DLQ, object-write failures, auth failures, and gross cost.
3. Re-run database/object/durable-event invariants at the end of the observation window.
4. Keep the source environment stopped or traffic-isolated but intact and recoverable.
5. Revoke temporary cross-project copy grants only after evidence is captured and rollback no longer needs
   them. Source decommissioning requires a later approved retention/deletion plan.

## 10. Rollback

Rollback routes traffic and processing back to the intact source environment. It is not a reverse project
move and it must not depend on reconstructing deleted source resources.

Rollback immediately when a mandatory integrity mismatch, tenant isolation failure, unrecoverable auth/
secret failure, sustained Sev-1/2 error, or RTO breach occurs.

1. Reapply the write freeze and stop destination Scheduler, subscriptions/consumers, and external traffic.
2. Record the exact destination-only write/event/object window. Do not discard it.
3. Restore source routing and only the source publishers/consumers.
4. If destination accepted writes, reconcile them explicitly into source using an approved application/data
   procedure. Do not blindly replace the source database with the destination.
5. Validate source health, data ledgers, login, tenant isolation, business paths, traces, and alerts.
6. Record rollback cause and preserve both environments/evidence until incident review determines cleanup.

## 11. Cost controls

- Budgets alert; they do not stop spend. Create project-specific budgets and retain the automated dev SQL
  stop workflow.
- Keep all destination Cloud Run services at minimum scale zero unless an evidence-backed exception is
  approved.
- Keep destination dev SQL stopped outside deployment/rehearsal windows. Stop duplicate validation SQL as
  soon as evidence is captured.
- Copy only required release digests and use reviewed Artifact Registry lifecycle cleanup after rollback
  retention expires.
- Keep transfer source deletion disabled. Avoid cross-region copies; the verified source application data is
  in `asia-south2` except the existing US Cloud Build bucket, which must be classified before copying.
- Review daily gross cost for all three projects during coexistence and assign an owner/end date to every
  temporary resource.

## 12. Final sign-off

| Mandatory gate | Owner | Evidence reference | Result |
| --- | --- | --- | --- |
| destination projects, billing, policies, APIs, and quotas verified | project/billing operators | | GO / NO-GO |
| complete inventory has zero unassigned resources | change commander | | GO / NO-GO |
| WIF positive and wrong-ref negative tests pass | IAM/security | | GO / NO-GO |
| exact production release digests and Flyway state match ledger | application/database | | GO / NO-GO |
| database export/import and full integrity comparison pass | database operator | | GO / NO-GO |
| Storage checksum/size and DB-photo/Drive reconciliation pass | recovery/integration | | GO / NO-GO |
| durable events are drained or exactly bounded | application operator | | GO / NO-GO |
| secrets, external integrations, observability, and alert routes pass | security/integration | | GO / NO-GO |
| dev rehearsal and rollback completed within RTO | change commander | | GO / NO-GO |
| production rollback route and source retention are ready | change commander | | GO / NO-GO |
| duplicate-resource cost ceiling and cleanup dates approved | billing owner | | GO / NO-GO |

The change commander may record GO only when every row is GO. A conditional or missing result is NO-GO.
