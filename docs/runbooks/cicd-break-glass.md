# CI/CD Break-Glass Runbook

Break-glass is for restoring production when the normal Cloud Deploy path is unavailable.

## Allowed Conditions

Use break-glass only when:

- Production is down or data integrity is at risk.
- Normal GitHub Actions or Cloud Deploy is unavailable.
- Two responsible people agree in writing.
- The exact command and reason are recorded.

## Hard Rules

- Deploy an existing trusted image digest.
- Do not build new code from a laptop.
- Do not use service account JSON keys.
- Do not bypass Secret Manager.
- Prefer Cloud Run revision traffic rollback before deploying anything new.

## Safer First Option

Use Cloud Run traffic rollback from the console or CLI to move traffic to the previous known-good revision.

```powershell
gcloud run services update-traffic custoking-api-gateway-prod `
  --project=custoking `
  --region=asia-south2 `
  --to-revisions <known-good-revision>=100
```

Repeat only for the affected service.

## Emergency Digest Deploy

Only if Cloud Deploy cannot be used:

```powershell
gcloud run deploy custoking-<service>-prod `
  --project=custoking `
  --region=asia-south2 `
  --image=asia-south2-docker.pkg.dev/custoking/custoking/custoking-<service>@sha256:<digest>
```

Before running this, copy the current service YAML:

```powershell
gcloud run services describe custoking-<service>-prod `
  --project=custoking `
  --region=asia-south2 `
  --format=export > artifacts\break-glass-before.yaml
```

## Cleanup

After production is stable:

1. Create an incident note.
2. Attach commands, timestamps, revisions, and digest.
3. Reconcile Cloud Deploy state.
4. Re-run the normal release pipeline with the same known-good digest or a forward fix.
