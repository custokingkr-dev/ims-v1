# Microservice Rollback Runbook

This runbook covers application rollback for the physically split Cloud Run services. It does not roll back database migrations; schema/data repair must be forward-only.

## Service Inventory

| Local name | Cloud Run service | Image |
| --- | --- | --- |
| identity-service | custoking-identity-service | custoking-identity-service |
| school-core-service | custoking-school-core-service | custoking-school-core-service |
| operations-service | custoking-operations-service | custoking-operations-service |
| platform-service | custoking-platform-service | custoking-platform-service |
| billing-service | custoking-billing-service | custoking-billing-service |
| frontend | custoking-frontend | custoking-frontend |
| api-gateway | custoking-api-gateway | custoking-api-gateway |

## Before Rollback

1. Confirm the failing service and revision:

```bash
gcloud run revisions list \
  --service=<cloud-run-service> \
  --region=<region> \
  --format="table(metadata.name,status.conditions[0].status,status.traffic[0].percent,metadata.creationTimestamp)"
```

2. Capture current traffic split:

```bash
gcloud run services describe <cloud-run-service> \
  --region=<region> \
  --format="yaml(status.traffic)"
```

3. Check whether the failing revision ran database migrations. If yes, do not attempt a database rollback. Apply a forward repair migration or roll back only to a revision compatible with the current schema.

## Roll Back One Service

Move all traffic back to a known-good revision:

```bash
gcloud run services update-traffic <cloud-run-service> \
  --region=<region> \
  --to-revisions=<known-good-revision>=100
```

Validate the service:

```bash
gcloud run services describe <cloud-run-service> \
  --region=<region> \
  --format="value(status.url)"
```

Then run the read-only smoke through the gateway:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\smoke-deployment-readiness.ps1 `
  -GatewayBaseUrl https://<gateway-url> `
  -SchoolId <known-school-id> `
  -StudentId <known-student-id> `
  -AdminUserId <known-admin-user-id> `
  -ClassId <known-class-id> `
  -SectionId <known-section-id> `
  -AttendanceDate <yyyy-mm-dd>
```

## Coupled Rollbacks

Use coupled rollback when a domain service revision and gateway/frontend revision changed a request contract together. Roll back in this order:

1. Private domain service.
2. API gateway only if route or upstream environment changed.
3. Frontend only if the browser contract changed.

After every coupled rollback, re-run the read-only deployment smoke and check the affected Java service `/actuator/health` plus gateway `/gateway-health`.

## Notification Provider Failure

If MSG91 delivery fails but the application is otherwise healthy:

1. Set platform-service notification delivery to dry-run only for the affected environment if duplicate provider sends are possible.
2. Pause Pub/Sub push delivery if needed.
3. Keep inbox retry state intact.
4. Resume delivery after provider status and templates are confirmed.

Do not delete notification inbox rows during rollback.

## Database Rule

Never use destructive schema rollback in production. If a Cloud Run rollback exposes an incompatible database state, deploy a forward-only repair migration and then retry the application rollback or roll forward to a fixed revision.
