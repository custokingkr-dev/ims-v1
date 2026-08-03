# Deployment Evidence Runbook

Every release must answer five questions:

```text
What commit shipped?
What image digest shipped?
Who approved prod?
What checks passed?
How do we roll back?
```

## GitHub Evidence

The `CD / Deploy branch environment` workflow uploads:

```text
release-evidence/
  release.json
  summary.md
```

`release.json` contains:

- commit SHA
- Artifact Registry root
- image tag
- image digest
- Cloud Deploy pipeline
- Cloud Deploy release id
- first target

Rollback workflows upload their own evidence artifacts:

```text
rollback-evidence/rollback.json
```

## Cloud Evidence

For a release review, export:

```powershell
gcloud deploy releases list `
  --project=custoking `
  --region=asia-south2 `
  --delivery-pipeline=custoking-school-core-service-<env>

gcloud deploy rollouts list `
  --project=custoking `
  --region=asia-south2 `
  --delivery-pipeline=custoking-school-core-service-<env> `
  --release=<release-id>
```

Repeat for each service in the release.

## Retention

Keep release evidence for at least 90 days or longer if a school onboarding, billing, photo import, or production incident depends on it.
