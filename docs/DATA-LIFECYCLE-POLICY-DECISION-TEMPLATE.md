# Data Lifecycle, Erasure and Offboarding Policy — Decision Template

Status: **UNAPPROVED TEMPLATE — NOT A RETENTION POLICY**

This template separates controls that engineering can prove from decisions that require accountable
business, legal and privacy owners. Blank decision fields are deliberate blockers. Do not activate a new
lifecycle deletion rule, permanent erasure workflow or provider deletion from this document until every
mandatory owner and approval field is completed.

## Document control — all fields mandatory

| Field | Decision |
| --- | --- |
| Policy ID and version | **TBD** |
| Effective date and review interval | **TBD** |
| Applicable jurisdictions/contracts | **TBD — qualified counsel required** |
| Data controller(s) and processor(s) | **TBD** |
| Legal/privacy owner | **UNASSIGNED** |
| Product owner | **UNASSIGNED** |
| Security and incident owner | **UNASSIGNED** |
| Data/offboarding operations owner | **UNASSIGNED** |
| Export custodian and approved transfer channel | **UNASSIGNED** |
| Backup and immutable-log exception owner | **UNASSIGNED** |
| Messaging/photo provider owner | **UNASSIGNED** |
| First independent approver, name/date | **UNASSIGNED** |
| Second independent approver, name/date | **UNASSIGNED** |

Approval is invalid if the requester supplies both approvals or any approver field names only a team
without an accountable individual.

## Data-class decision register

For each row, replace every **TBD**. “Configured technical state” records what the system does today; it is
not evidence that the duration is lawful or contractually approved.

| Data class | Systems / configured technical state | Purpose and legal basis | Retention trigger and active duration | Backup/log/soft-delete lag | Hold and final action | Proof and exception owner |
| --- | --- | --- | --- | --- | --- | --- |
| Accounts, roles, sessions, operator-school assignments | Identity schema; refresh sessions can be revoked | **TBD** | **TBD** | **TBD** | **TBD: delete, anonymise or retain** | **TBD** |
| School configuration, classes, sections and entitlements | Tenant-school schema | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |
| Students and guardians | Student schema; minors' personal data | **TBD — counsel/privacy** | **TBD** | **TBD** | **TBD** | **TBD** |
| Notice and consent/withdrawal evidence | Student consent-event ledger | **TBD by purpose** | **TBD** | **TBD** | **TBD; evidence may differ from content** | **TBD** |
| Attendance detail and aggregates | Attendance and reporting schemas | **TBD** | **TBD before partition/archive activation** | **TBD** | **TBD** | **TBD** |
| Fees, invoices, payments and financial audit | Fee, billing and reporting schemas | **TBD — finance/legal** | **TBD** | **TBD** | **TBD** | **TBD** |
| Import batches, row diagnostics and workbook metadata | Student schema | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |
| Temporary photo-import source objects | GCS `temporary/photo-imports/`; lifecycle delete at 14 days; bucket soft delete currently seven days | **TBD** | Current technical trigger: object age 14 days; **approval TBD** | Current technical lag: seven-day soft delete | **TBD legal hold/exception** | **TBD** |
| Final and prior student photos | GCS school/student prefixes; no blanket permanent-photo lifecycle | **TBD — photo/guardian basis** | **TBD** | Seven-day bucket soft delete currently applies | **TBD** | **TBD** |
| Notification intent, recipients and provider records | Platform schema, outbox/inbox, Pub/Sub/provider | **TBD per channel/purpose** | **TBD** | Pub/Sub resilience uses seven-day retention where configured | **TBD plus provider suppression** | **TBD** |
| Audit events | Audit schema and compliance log sink | **TBD** | **TBD** | Compliance bucket currently 180 days | **TBD** | **TBD** |
| General application logs | Cloud Logging `_Default`, currently seven days | **TBD** | Current technical duration seven days; **approval TBD** | N/A | **TBD redaction/deletion exception** | **TBD** |
| Required platform audit logs | Locked Cloud Logging `_Required`, currently 400 days | **TBD** | Immutable technical duration 400 days | Erasure cannot shorten the locked period | Retain until expiry; **legal approval TBD** | **TBD** |
| Traces, metrics and alert evidence | Cloud Trace/Monitoring and release evidence | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |
| Database backups and PITR logs | Cloud SQL; 14 retained backups and seven transaction-log days | **TBD** | Current technical recovery window; **approval TBD** | Delayed erasure until expiry/rotation | **TBD restoration handling** | **TBD** |
| School/data-subject exports and support artifacts | Operator-controlled encrypted destination only; never Git | **TBD** | **TBD short expiry and custody receipt** | Destination-specific **TBD** | Verified deletion plus recipient record | **TBD export custodian** |

## Mandatory offboarding decision

The production state machine must be resumable and two-person controlled:

```text
REQUESTED -> EXPORTING -> ACCESS_FROZEN -> RETENTION_HOLD -> ERASING -> VERIFIED -> CLOSED
```

Before implementation is approved, record:

- request authority, identity verification and contract/rights-request type: **TBD**;
- export format, encryption, recipient, transfer channel and expiry: **TBD**;
- legal-hold decision and the person authorized to release it: **TBD**;
- exact systems included: identity, school-core, operations, billing, reporting, platform notification,
  object storage/Drive, provider records, outboxes/inboxes, logs, backups and support artifacts;
- retained record categories and expiry after active erasure: **TBD**;
- retry, incident escalation and maximum completion time: **TBD**; and
- approver separation and emergency-stop owner: **TBD**.

Access must be frozen before destructive work. A production run must never use the test-only SQL deletion
order or an ad-hoc cross-schema script. Each step needs an idempotency key, immutable decision evidence and
reconciliation counts that contain no personal rows or filenames.

## Technical acceptance evidence

Engineering can complete these items before policy approval:

- repository current-tree, all-ref object-history and GitHub release-asset scans return zero sensitive
  export paths without printing filenames;
- an in-memory synthetic export has a SHA-256 digest and contains only the target tenant;
- the test-only school-core erasure is idempotent, all target counts become zero and a control tenant is
  unchanged; and
- the inventory explicitly marks identity, operations, billing, reporting, platform/provider, object,
  log and backup deletion as unimplemented until the policy decisions above are approved.

These tests prove a bounded engineering mechanism. They do not establish lawful basis, approve a duration,
prove full-system production erasure or close DATA-02.
