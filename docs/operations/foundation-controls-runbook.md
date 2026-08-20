# Foundation Controls Runbook

Last verified against source: 2026-08-05.

This runbook covers the first operational foundation package: guardian and consent
records, external notification delivery, alert routing, India-resident compliance
logs, and Cloud SQL recovery.

## Guardian And Consent Records

The school-core `student` schema owns:

- `student.guardians`: one school-scoped guardian identity with optimistic versioning.
- `student.student_guardians`: the student's relationship and access flags.
- `student.student_consent_events`: append-only purpose-specific consent evidence.

`V14__guardians_and_consent_ledger.sql` backfills existing father and mother fields.
The legacy student columns remain populated for compatibility while consumers move
to the normalized API.

School users manage these records in **Students -> Student details -> Guardians and
consent**. A guardian edit resets active profile verification. Consent events are
never updated in place; a withdrawal or new grant is another event.

API routes:

```text
GET    /api/v1/students/{studentId}/guardians
POST   /api/v1/students/{studentId}/guardians
PUT    /api/v1/students/{studentId}/guardians/{guardianId}
DELETE /api/v1/students/{studentId}/guardians/{guardianId}
POST   /api/v1/students/{studentId}/consents
```

Supply `Idempotency-Key` when recording consent. Every event records purpose,
status, notice version, evidence source, effective time, and actor.

## Notification Delivery

All environments remain on `logging` with `MSG91_DRY_RUN=true` until provider
readiness is proven. The production configuration audit on 2026-08-05 found one
active sender profile and zero profiles with an SMS flow ID. The safe production
target therefore remains:

```text
NOTIFICATION_DELIVERY_PROVIDER=logging
MSG91_DRY_RUN=true
```

The auth key remains a Secret Manager reference. Never put the key in a manifest,
GitHub variable, workflow output, or evidence artifact.

Failed provider sends use exponential backoff from 30 seconds to one hour. After
eight attempts the inbox event moves to `DEAD_LETTER`. Check the event status API,
the `notificationInbox` health component, and Cloud Logging before replaying or
creating a replacement event. Provider delivery is at-least-once; templates and
downstream actions must remain idempotent.

To activate real delivery, configure the applicable sender profile with an approved
MSG91 SMS flow ID (and WhatsApp template/number when used), confirm the static egress
IP allowlist, change the prod target to `msg91`/`false`, and deploy. Then send one
approved test notification and verify the provider receipt and delivery-attempt row.
Do not use a real parent number for this test.

## Monitoring Channels

The observability Terraform root creates email channels from
`notification_email_addresses` and attaches them to all managed alert policies.
Example production apply:

State lives in the bucket **inside the environment's own project** -- `custoking-prod-terraform-state`
for prod, `custoking-dev-terraform-state` for dev. It is not `custoking-terraform-state`: that bucket
belongs to the pre-split project, which is being deleted. Both buckets currently exist and both carry an
`observability/` prefix, so pointing at the wrong one does not error -- it reads stale state, and the
resulting plan proposes creating 63 alert policies that already exist.

There is no Application Default Credentials on the operator workstation, so the backend needs an
explicit access token as well as the provider environment variable. The token expires after about an
hour; a stale one surfaces as a 401 reading state, which looks like state loss and is not.

```powershell
$tok = gcloud auth print-access-token
$env:GOOGLE_OAUTH_ACCESS_TOKEN = $tok
terraform -chdir=deploy/gcp/observability init -reconfigure `
  -backend-config="bucket=custoking-prod-terraform-state" `
  -backend-config="prefix=observability/prod" `
  -backend-config="access_token=$tok"
terraform -chdir=deploy/gcp/observability apply -var-file=custoking-prod.tfvars
```

Anything touching Cloud Billing -- the budget resources in particular -- additionally needs the
provider's quota project pinned, or it fails with a 403 naming a project number you will not
recognise:

```powershell
$env:USER_PROJECT_OVERRIDE = "true"
$env:GOOGLE_BILLING_PROJECT = "custoking-prod"
```

The same trap catches `gcloud billing` from the command line, and there it is worse because the error
is actively misleading. gcloud takes its quota project from the gcloud core project, which is still the
pre-split `custoking`, and that project has the Cloud Billing API disabled. The resulting message says
`does not have permission to access billingAccounts instance`, which reads as an IAM denial and is not
one -- this account grants `billing.budgets.create` and `billing.budgets.list` when asked with a valid
quota project. Pass `--billing-project=custoking-prod`, or query the REST API with an explicit
`x-goog-user-project` header, before concluding anything about billing permissions.

The production state was applied on 2026-08-05 and its email channel was attached
to all managed policies. Google sends a verification message for email channels.
The operator must complete that verification and then use **Test notification
channel** in Cloud Monitoring.

## Compliance Log Retention

Only the production observability state may set `manage_compliance_logging=true`.
It owns one project-level bucket:

```text
projects/custoking/locations/asia-south2/buckets/custoking-compliance-india
```

The bucket retains selected Cloud Run request/security/error logs and Google Cloud
audit logs for 180 days. The `_Default` bucket remains at short retention for broad
operational logs. Do not lock the compliance bucket until routing and retrieval have
been verified; bucket locking is irreversible.

The bucket and project sink were applied in production on 2026-08-05. Routing was
verified by reading a generated production gateway HTTP 200 request log from the
bucket's `_AllLogs` view.

Verification:

```powershell
gcloud logging buckets describe custoking-compliance-india `
  --location=asia-south2 --project=custoking
gcloud logging sinks describe custoking-compliance-india --project=custoking
```

Confirm retention obligations with legal/security ownership. The implementation is
an engineering control, not a legal determination.

## Cloud SQL Recovery

Enforce the production policy:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/apply-cloudsql-recovery-policy.ps1 -Apply
```

The policy enables automated backups, seven-day PITR transaction logs, 14 retained
backups, regional backup placement, deletion protection, and grants the recovery
operator the custom bucket-IAM role in
`deploy/gcp/recovery-bucket-iam-operator-role.yaml` only on the isolated validation
bucket. During a drill, the operator grants the temporary clone's service identity
object access and revokes that binding during cleanup.

The monthly `recovery-drill.yml` workflow performs an isolated point-in-time clone,
checks that the application database exists, exports it to prove restored data is
readable, stores non-sensitive evidence, and removes the temporary export and SQL
instance in a `finally` block.

The first live production drill passed on 2026-08-05. It restored a point in time,
found `custoking_prod`, exported 2,771,531 bytes for validation, revoked the temporary
clone identity, and left zero temporary instances and zero temporary exports.

The `prod` GitHub environment must contain:

```text
RECOVERY_OPERATOR_SERVICE_ACCOUNT
PROD_DB_NAME
WORKLOAD_IDENTITY_PROVIDER
```

The recovery operator needs Cloud SQL administration, validation-object access, and
the three-permission custom bucket-IAM role scoped only to the dedicated validation
bucket. Do not reuse a broad release identity. Review the uploaded evidence after
every scheduled run and treat cleanup failure as an incident because a temporary
production-data copy may remain.
