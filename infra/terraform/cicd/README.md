# CI/CD Terraform Foundation

This module is the foundation for CI/CD v2. It manages:

- GitHub Workload Identity Federation.
- GitHub release/rollback service accounts.
- Cloud Deploy execution service accounts.
- Artifact Registry repository and cleanup policy.
- Minimal project IAM for build, release, rollback, and Cloud Run deploy execution.

The live project already has some of these resources from the retired pipeline. Import existing resources before applying, otherwise Terraform will try to create duplicates.

## First-Time Import

```powershell
terraform init

terraform import google_iam_workload_identity_pool.github `
  projects/custoking/locations/global/workloadIdentityPools/github-pool

terraform import google_iam_workload_identity_pool_provider.github `
  projects/custoking/locations/global/workloadIdentityPools/github-pool/providers/github-provider

terraform import google_artifact_registry_repository.custoking `
  projects/custoking/locations/asia-south2/repositories/custoking
```

If the new service accounts already exist, import them too:

```powershell
terraform import 'google_service_account.github["release_builder"]' `
  projects/custoking/serviceAccounts/github-release-builder@custoking.iam.gserviceaccount.com

terraform import 'google_service_account.github["rollback"]' `
  projects/custoking/serviceAccounts/github-release-rollback@custoking.iam.gserviceaccount.com
```

## GitHub Variables

Set these repository or environment variables after apply:

```text
GCP_PROJECT_ID=custoking
GCP_REGION=asia-south2
ARTIFACT_REGISTRY_REPOSITORY=custoking
WORKLOAD_IDENTITY_PROVIDER=<terraform output workload_identity_provider>
RELEASE_BUILDER_SERVICE_ACCOUNT=<terraform output release_builder_service_account>
ROLLBACK_SERVICE_ACCOUNT=<terraform output rollback_service_account>
```

Keep the existing `DEPLOY_SERVICE_ACCOUNT` variable only as a temporary compatibility fallback.

The release workflow also needs repo-level deployment coordinate variables because Cloud Deploy compiles deploy parameters when a release is created:

```text
DEV_DB_HOST
DEV_DB_NAME
DEV_STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID
PROD_DB_HOST
PROD_DB_NAME
PROD_STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID
```

These are not secret values, but they are kept out of source so scanners do not treat DB coordinates or Drive folder IDs as exposed credentials. They were copied from the existing GitHub `dev` and `prod` Environment variables.
