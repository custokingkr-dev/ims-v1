# Documentation Index and Source Precedence

Reconciled: 2026-08-14

This index covers the repository's 73 documentation artifacts under `docs/` plus the primary root-level
project documents. It prevents dated plans and test evidence from being mistaken for current live state.
Historical measurements are intentionally preserved; changing them would falsify the record.

## Source precedence

When two documents disagree, use this order:

1. Fresh live GCP/GitHub inventory and the deployed revision configuration.
2. Current source code, migrations, workflows, manifests, and scripts at the checked-out commit.
3. The `docs/current-state/` bundle, reconciled notices, and the visual architecture page.
4. Dated deployment, billing, scale, and certification evidence for the specific event it records.
5. Active runbooks and contracts.
6. Plans, roadmaps, spikes, audits, mockups, and product proposals.

No document may contain secret values, database passwords, token values, personal student data, or local
export filenames that reveal personal data. Local photographer/student exports belong outside Git; the
repository now ignores `outputs/`.

## Authoritative current documents

- `architecture/custoking-architecture.html` — verified application architecture plus live GCP boundary,
  capacity evidence, and unresolved infrastructure gates.
- `REMAINING-WORK-2026-08-12.md` — single prioritized backlog and go/no-go checklist.
- `GCP-SPLIT-PROJECT-MIGRATION-PLAN-2026-08-18.md` — **authoritative migration plan.** Rebuilt from live
  discovery: full verified source inventory, the four decisions that must be made first (Artifact Registry
  topology above all), and the exact GitHub-variable and repository-file changes required. Records that no
  application code change is needed.
- `GCP-SPLIT-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md` — **superseded** by the plan above; retained for its
  procedural scaffolding. Its factual claims about registry topology, DNS cutover, and code impact are wrong.
- `GCP-CUTOVER-RUNBOOK-2026-08-18.md` — **the executable cutover.** Minute-by-minute plan targeting 5–10
  minutes of unavailability with users staying signed in. Uses Cloud Run invoker IAM (`allUsers`) as the
  atomic switch in the absence of DNS, explains why DMS/CDC is not worth it at 146 MB, and confirms the data
  surface is only Cloud SQL + GCS (no Firestore, Redis, Spanner or Bigtable anywhere).
- `GCP-SOURCE-DELETION-CONTINUITY-2026-08-18.md` — **read this before deleting `custoking`.** What survives
  (all BCrypt passwords, scoped RBAC, Drive folders), what must be value-transferred (secrets, or 378 live
  sessions drop), what the dump does **not** carry (the `appuser`/`app_rt` database roles), and what is
  destroyed forever (Drive OAuth client, locked audit logs, backups, `.a.run.app` URLs with no DNS layer).
- `GCP-MIGRATION-DATA-LEDGER-PROD-2026-08-18.md` — **measured** production data ledger: exact row counts,
  Flyway state (matches the repo exactly), drained durable-event state, and a full Cloud Storage ↔ Postgres
  reconciliation. Zero broken photo references. Records why `source_object_key` is designed to dangle, which
  a naive integrity check would misread as hundreds of missing objects and a false NO-GO.
- `GCP-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md` — mandatory source/destination database, object,
  durable-event, and Drive reconciliation criteria for the split-project migration.
- `GCP-MIGRATION-PREFLIGHT-EVIDENCE-2026-08-18.md` — live status of the runbook's section 4 gates. Records
  that both destination projects now exist but sit in a **different organization**, and that their billing
  account is inaccessible to the operating account. Supersedes the "destinations not visible" blocker in
  both documents above; update this file rather than ticking boxes in the runbook.
- `current-state/README.md` — map and evidence rules.
- `current-state/project-architecture.md` — service topology, route flow, schemas, JWT and tenant isolation.
- `current-state/gcp-infrastructure.md` — live resource inventory; its 2026-08-12 reconciliation overrides
  older point-in-time rows in the same file.
- `current-state/deployment-cicd.md` — active branch-owned build/release path and live target drift.
- `current-state/event-models.md` — outbox, Pub/Sub, inbox, idempotency and current subscription state.
- `current-state/observability-operations.md` — health, Logging, Monitoring, tracing, uptime and alert state.
- `current-state/codebase-conventions.md` — repository/runtime conventions.
- `current-state/school-student-lifecycle.md` — implemented onboarding/import/date/localization behavior.
- `current-state/gaps-and-drift.md` — evidence narrative; defer to the remaining-work document for priority.

## Active contracts, operational procedures, and reference designs

These describe behavior or an operator procedure. They do not by themselves prove that a live resource or
business approval exists.

- `ADR-001-service-consolidation-topology.md`
- `ARCHITECTURE-HLD.md`, `ARCHITECTURE-LLD.md`, `HLD-architecture-hardening.md`
- `architecture/time-and-timezones.md`
- `EVENT-ENVELOPE-CONTRACT.md`, `INTERNAL-SERVICE-AUTHORIZATION.md`
- `DB-SCALING-THRESHOLDS.md`, `tenant-isolation.md`, `rbac.md`, `workflow-transitions.md`
- `LOCAL-SETUP.md`, `LOGICAL-E2E-TESTS.md`, `runbook.md`
- `GCP-COST-GUARDRAILS-RUNBOOK.md`, `MSG91-PRODUCTION-SETUP.md`
- `MICROSERVICE-APP-RT-PROVISIONING-RUNBOOK.md`
- `MICROSERVICE-OBSERVABILITY-RUNBOOK.md`
- `MICROSERVICE-RLS-ROLLOUT-RUNBOOK.md`
- `MICROSERVICE-ROLLBACK-RUNBOOK.md`
- `MICROSERVICE-TENANT-KEY-ROLLOUT-RUNBOOK.md`
- `operations/bulk-import-dob-reconciliation.md`
- `operations/foundation-controls-runbook.md`
- `operations/student-photo-import-runbook.md`
- `runbooks/cicd-break-glass.md`, `runbooks/deployment-evidence.md`
- `runbooks/release-operator.md`, `runbooks/rollback.md`
- `MONOLITH_ROUTE_OWNERSHIP_MAP.md`

## Dated evidence — immutable observations

These answer what happened at the stated time. Reconciled banners link to current state; numeric test results
and deployment history must remain unchanged.

- `DEV-SCALE-VALIDATION-2026-08-10.md`
- `GCP-BUDGET-INCIDENT-2026-08-11.md`
- `PRODUCTION-DEPLOYMENT-2026-08-11.md`
- `PRODUCTION-DEPLOYMENT-2026-08-14.md`
- `PLANNED-CHANGES-EXECUTION-2026-08-11.md`
- `SCALE-READINESS-AND-COST-PLAN-2026-08-10.md`
- `workstreams/ONBOARDING-CERTIFICATION-RESULTS-2026-08-11.md`
- `workstreams/ONBOARDING-COST-COMPLIANCE-CHANGES-2026-08-11.md`
- `workstreams/RELIABILITY-SCALE-RECOVERY-CHANGES-2026-08-11.md`
- `workstreams/SECURITY-GOVERNANCE-CHANGES-2026-08-11.md`
- `FRONTEND-AB-DECISIONS-2026-07-02.md`
- `FRONTEND-E2E-AUDIT-2026-07-02.md`

## Historical plans, audits, spikes, and migration records

These remain useful context but are not current topology or live-state claims unless adopted by an
authoritative current document.

- `CHANGELOG-architecture-hardening.md`
- `frontend-design-audit.md`
- `GCP-COST-OPTIMIZATION-PLAN-2026-08.md` — its 2026-08-12 banner provides current deltas.
- `GREENFIELD-DEPLOYMENT-PLAN.md`, `PHASE1-DEPLOYMENT-PLAN.md`
- `MICROSERVICES-COMPLETION-PLAN.md`, `MICROSERVICES-MIGRATION-ROADMAP.md`
- `MONOLITH_REMOVAL_EXECUTION_PLAN.md`
- `PRODUCTION_READINESS_OVERHAUL_PLAN.md`
- `REPORTING-OUTBOX-SPIKE-FINDINGS.md`, `SB4-SPIKE-FINDINGS.md`
- `SUPERSEDED-GCP-IN-PLACE-PROJECT-MIGRATION-RUNBOOK-2026-08-16.md` and
  `SUPERSEDED-GCP-IN-PLACE-MIGRATION-DATA-INTEGRITY-PLAN-2026-08-16.md` — rejected in-place project-move
  analysis; retained for audit history and explicitly marked DO NOT EXECUTE.

## Product proposals and visual mockups

- `product/student-profile-photo-verification.md`
- `mockups/student-verification-mvp.html`
- `mockups/student-verification-future.html`

## Root-level document status

- `README.md`, `CONTRIBUTING.md`, and `DEVELOPMENT_GUIDE.md` are active entry points.
- `PRD.md` is the product baseline; implemented behavior still requires code/current-state evidence.
- `DEMO.md` and `DEMO_CHECKLIST.md` are demonstration procedures, not production certification.
- `ARCHITECTURE_REVIEW.md` and `ENTERPRISE_READINESS.md` are audits/reference analysis; use the current-state
  bundle and the remaining-work document for present decisions.

## Documentation maintenance rule

For every material deployment or infrastructure mutation:

1. capture immutable evidence in a dated document or generated artifact;
2. update the relevant current-state document and its reconciliation date;
3. update the remaining-work item state and acceptance evidence;
4. update the visual architecture only if the topology or a material live boundary changed; and
5. run link/markup checks without adding generated evidence or personal-data exports to Git.
