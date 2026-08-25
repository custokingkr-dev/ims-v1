# CI/CD Terraform Foundation

This module is the foundation for CI/CD v2. It manages:

- GitHub Workload Identity Federation.
- Branch-isolated GitHub release, rollback, and deployment-configuration reconciliation service
  accounts, plus cost-control and recovery service accounts.
- Cloud Deploy execution service accounts.
- Artifact Registry repository and cleanup policy.
- Minimal project IAM for build, release, rollback, and Cloud Run deploy execution.

The provider trust condition is fail-closed on the immutable GitHub repository ID, immutable owner
ID and exact, coupled `ref`/`workflow_ref` pairs for the approved `main` and `dev` workflows.
Service-account impersonation is narrowed again with the full `workflow_ref` attribute. Dev and
prod build/release, rollback, and configuration reconciliation each have a separate identity bound
to its exact workflow file and branch. Cost control and recovery are main-only identities. A dev
workflow can therefore never mint a prod deployment identity even if the workflow code is changed.
Repository-name-only and workflow-file-only trust are intentionally not modeled.

The live project already has some of these resources from the retired pipeline. Import existing resources before applying, otherwise Terraform will try to create duplicates.

`recovery_validation_bucket` is intentionally required in every project tfvars file. Use the exact
project-scoped `<project_id>-db-snapshots` bucket; the module has no legacy cross-project default.
The split-project values are `custoking-dev-db-snapshots` and `custoking-prod-db-snapshots`.
Configuring the bucket name does not enable recovery IAM: `enable_recovery_bindings` remains a
separate security-owner gate.

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
# This identity already exists live and must be imported, not recreated.
terraform import 'google_service_account.github["recovery"]' `
  projects/custoking/serviceAccounts/custoking-recovery-operator@custoking.iam.gserviceaccount.com

terraform import 'google_project_iam_member.recovery_roles["roles/cloudsql.admin"]' `
  'custoking roles/cloudsql.admin serviceAccount:custoking-recovery-operator@custoking.iam.gserviceaccount.com'

terraform import google_storage_bucket_iam_member.recovery_bucket_policy_operator `
  'b/custoking-db-snapshots projects/custoking/roles/custokingRecoveryBucketIamOperator serviceAccount:custoking-recovery-operator@custoking.iam.gserviceaccount.com'
```

The imported recovery custom-role binding is currently unconditional. The reviewed plan must show
its replacement with `recovery_bucket_and_validation_prefix_only`; apply that change only after the
custom role itself has the added object get/delete permissions.

At the 2026-08-11 audit, the six branch-specific release/rollback/configuration identities, the
cost-controller identity, and the two Cloud Deploy execution identities did not exist. Terraform
creates:

```text
github-release-dev@custoking.iam.gserviceaccount.com
github-release-prod@custoking.iam.gserviceaccount.com
github-rollback-dev@custoking.iam.gserviceaccount.com
github-rollback-prod@custoking.iam.gserviceaccount.com
github-config-dev@custoking.iam.gserviceaccount.com
github-config-prod@custoking.iam.gserviceaccount.com
github-cost-controller@custoking.iam.gserviceaccount.com
clouddeploy-dev-deployer@custoking.iam.gserviceaccount.com
clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com
```

Both release identities receive bucket-scoped `roles/storage.bucketViewer` and
`roles/storage.objectCreator` on `clouddeploy_source_bucket`. `gcloud deploy releases create`
uploads the local Skaffold source before creating the release; `roles/clouddeploy.releaser` alone
does not authorize that upload. Set the environment-scoped GitHub variable
`CLOUD_DEPLOY_SOURCE_STAGING_DIR` to the `clouddeploy_source_staging_dir` output. Supplying this
explicit staging directory avoids the project-wide `storage.buckets.list` discovery permission.
Do not replace these bindings with project-wide Storage Admin or object read/delete access.
The bucket is an existing prerequisite and should use uniform bucket-level access, public access
prevention, and a short lifecycle (14 days in the current project) for transient source archives.
Use one dedicated source bucket in each future GCP project; do not rely on Cloud Deploy's
per-pipeline auto-generated buckets.

Do not import these unless a fresh read-only inventory proves one was created outside this module.
Stage is intentionally not provisioned: there is no stage target manifest, runtime IAM matrix, or
protected GitHub Environment. Add those three contracts together rather than creating a dormant
privileged stage identity.

`github-cost-controller` is not live at the 2026-08-11 audit and is therefore created rather than
imported. The exact `attribute.workflow_ref` WIF member for `recovery-drill.yml@refs/heads/main` and recovery bucket
custom-role object permissions are also source-side changes. The custom role grants only bucket
policy get/set plus exact-object get/delete; it does not grant object list/create/restore or ACL
management. Its conditional bucket binding limits object access to `recovery-drills/`, and the
temporary clone receives only Storage Object Creator. The live recovery operator's broader
`roles/storage.objectAdmin` bucket member is
deliberately not modeled; remove it only after the updated custom-role path and cleanup pass a
recovery drill.

## GitHub Variables

Set these repository or environment variables after apply:

```text
GCP_PROJECT_ID=custoking
GCP_REGION=asia-south2
ARTIFACT_REGISTRY_REPOSITORY=custoking
WORKLOAD_IDENTITY_PROVIDER=<terraform output workload_identity_provider>
COST_CONTROLLER_SERVICE_ACCOUNT=<terraform output cost_controller_service_account>
GOVERNANCE_AUDITOR_SERVICE_ACCOUNT=<terraform output governance_auditor_service_account>
GCP_GOVERNANCE_AUDIT_ENABLED=false
RECOVERY_OPERATOR_SERVICE_ACCOUNT=<terraform output recovery_operator_service_account>
```

Identity variables are environment-scoped:

```text
Repository RELEASE_BUILDER_SERVICE_ACCOUNT=<terraform output release_dev_service_account>
dev Environment RELEASE_BUILDER_SERVICE_ACCOUNT=<terraform output release_dev_service_account>
prod Environment RELEASE_BUILDER_SERVICE_ACCOUNT=<terraform output release_prod_service_account>
dev Environment CLOUD_DEPLOY_SOURCE_STAGING_DIR=<terraform output clouddeploy_source_staging_dir>
prod Environment CLOUD_DEPLOY_SOURCE_STAGING_DIR=<terraform output clouddeploy_source_staging_dir>
dev Environment ROLLBACK_SERVICE_ACCOUNT=<terraform output rollback_dev_service_account>
prod Environment ROLLBACK_SERVICE_ACCOUNT=<terraform output rollback_prod_service_account>
dev Environment DEPLOYMENT_CONFIG_SERVICE_ACCOUNT=<terraform output config_dev_service_account>
prod Environment DEPLOYMENT_CONFIG_SERVICE_ACCOUNT=<terraform output config_prod_service_account>
```

`GOVERNANCE_AUDITOR_SERVICE_ACCOUNT` is repository-scoped because its scheduled workflow runs only from
`main` and deliberately does not enter the production deployment Environment. Set it only after the
dedicated account, read-only custom role, exact workflow-ref impersonation binding, and provider condition
have been reviewed and applied together. Keep `GCP_GOVERNANCE_AUDIT_ENABLED` unset or `false` during that
apply. Provisioning adds the governance service account and custom role to Cloud Asset Inventory, so the
pre-provision baseline is guaranteed to be stale afterward. Capture and review the post-provision inventory,
merge its approved baseline plus enabled repository config, and set the repository variable to `true` only
as the final activation step. A premature `true` fails before the workflow requests cloud credentials.

The repository release value is required because the dev-only image-build job intentionally runs
without a GitHub Environment. The prod image-build job is skipped; the protected prod Environment
overrides that repository value for the prod release job. The exact `workflow_ref` WIF members make
email substitution insufficient to cross this boundary: a dev token is denied by the prod service
account policy.

Keep the existing `DEPLOY_SERVICE_ACCOUNT` variable only as a temporary compatibility fallback.
At the 2026-08-11 audit, both `DEPLOY_SERVICE_ACCOUNT` and `COST_CONTROLLER_SERVICE_ACCOUNT` still
name `github-actions-sa`. Update each variable only after its new identity, exact workflow-ref
binding, and roles exist. The prod Environment recovery variable already names the live recovery
operator; verify it rather than rotating it during this migration.

Before applying this module to an existing provider, import the resources, save the current provider
and service-account IAM policies, and review `terraform plan`. Create/apply the production runtime
matrix before this module because its resource-scoped `actAs` bindings intentionally fail when the
seven prod runtime identities do not exist. Prove one allowed dev workflow, one approved prod
workflow, and the documented cross-branch/cross-account denials before removing legacy
`attribute.repository` principal-set bindings. Do not apply this module directly to the live project
from an unreviewed local state.

The cost-control identity receives `roles/cloudsql.editor` and Service Usage Consumer; the dev
release identity also retains Cloud SQL Editor because `build-release.yml` starts a stopped dev database
before a deployment. Both paths additionally reject instance names that do not end in `-dev`.
Recovery remains a separate identity. Google Cloud's predefined Cloud SQL Editor role cannot clone
or delete instances, so the current recovery contract retains Cloud SQL Admin until a successful
drill supplies an exact custom-role permission trace. Service Usage Consumer permits API
consumption without adding resource administration. Recovery bucket access is bucket-scoped: the
custom IAM-policy operator role, extended only with validation-object get/delete.

The dev release identity has Cloud Run Developer and resource-scoped `actAs` on the seven dev
runtime identities for the fast direct-dev path. Dev and prod release identities can each act only
as their matching Cloud Deploy execution identity; prod has Cloud Run Viewer for release
verification and gateway smoke discovery. Normal release identities have Cloud Deploy Releaser,
not Operator. The manual, environment-gated `reconcile-deployment-config.yml` workflow uses its own
dev/prod configuration identity with a custom role limited to target/pipeline create, get, list, and
update plus read-only location/operation polling. The role deliberately excludes release, rollout,
delete, tag, and IAM-policy permissions. Each identity can `actAs` only its matching Cloud Deploy
execution identity. The workflow renders and applies targets/pipelines only and creates zero
releases or rollouts. The dev release writer, prod release reader, and both Cloud Deploy execution
readers are scoped to the single `custoking` Artifact Registry repository.

Push-time change detection deliberately treats target, delivery-pipeline, and either renderer
changes as configuration reconciliation only. A configuration-only change has an empty service
matrix, builds no images, and creates no release. A target plus one service change retains exactly
that one service in the matrix but still blocks automatic release until the manual reconciliation
has completed; release the service in a separate service-specific commit after reconciliation.
The pipeline renderer additionally selects and validates exactly seven pipelines for the chosen
environment, so the normal dev path never applies the prod pipeline documents (and vice versa).
Because target/pipeline creation is initially authorized at project scope, the checked-in branch
guard and renderer are the environment filter during bootstrap. After all resources exist, move the
custom-role bindings to the seven matching targets and pipelines and remove the project binding;
Google documents resource-level Cloud Deploy IAM for both resource types. See
[Cloud Deploy IAM restrictions](https://docs.cloud.google.com/deploy/docs/securing/iam).

The dev rollback identity has Cloud Run Developer for direct traffic movement. The prod rollback
identity has Cloud Deploy Operator, Cloud Run Viewer for post-rollback smoke inspection, and
resource-scoped `actAs` only on the prod Cloud Deploy execution identity. `rollback.yml` also fails
before authentication unless dev is invoked from `refs/heads/dev` or prod from `refs/heads/main`.
The IAM split is the actual boundary; the workflow check is defense in depth.

The dedicated Cloud Deploy execution accounts receive only `roles/clouddeploy.jobRunner` and
`roles/run.developer` at project scope. `roles/iam.serviceAccountUser` is granted separately on the
configured runtime service-account resource; it is not granted project-wide. Secret Manager viewer,
Logging viewer are not execution prerequisites and are omitted. Artifact Registry Reader is granted
only on the release repository because Cloud Run deployment requires the deployer to read the
selected image.

All 14 checked-in targets specify an alternate execution identity for both `RENDER` and `DEPLOY`.
Production targets also reference the seven dedicated prod runtime identities; they must not be
applied until `scripts/configure-runtime-service-accounts.ps1 -Environment prod -Apply
-AllowProduction` has completed and its canary prerequisites are approved. The renderer supports
only dev and prod and rejects templates that fall back to default Compute identities.

The release and configuration-reconciliation workflows need deployment coordinate variables because
Cloud Deploy compiles deploy parameters when a release is created or a target is rendered:

```text
DEV_DB_HOST
DEV_DB_NAME
DEV_STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID
PROD_DB_HOST
PROD_DB_NAME
PROD_STUDENT_PHOTO_IMPORT_DRIVE_ROOT_FOLDER_ID
```

These are not secret values, but they are kept out of source so scanners do not treat DB coordinates or Drive folder IDs as exposed credentials. They were copied from the existing GitHub `dev` and `prod` Environment variables.

## Split-project usage

`var.environments` selects which environments a project owns. Before the split-project migration one
project held both, so it defaults to `["dev", "prod"]` and existing behaviour is unchanged. A destination
project must own exactly its own environment, otherwise it grows the other environment's identities:

    terraform init -reconfigure \
      -backend-config="bucket=custoking-dev-terraform-state" \
      -backend-config="prefix=cicd" \
      -backend-config="access_token=$(gcloud auth print-access-token)"
    terraform apply -var-file=custoking-dev.tfvars

There is no Application Default Credentials on the operator workstation and
`gcloud auth application-default login` needs a browser, so authenticate with an access token:
export `GOOGLE_OAUTH_ACCESS_TOKEN` for the provider and pass `access_token` to the backend. **The token
expires after about an hour**; a stale one surfaces as a 401 reading state, which looks like state loss
but is not. Re-run `init -reconfigure` with a fresh token.

A destination project sets `enable_dev_identities = true` because there is no shared `github-actions-sa`
to fall back on, and `billing_export_dataset = ""` until its billing account is accessible.
