# CI/CD plane for the migrated production environment. This project owns ONLY prod; the dev identities
# belong to custoking-dev. Before the split one project held both, which is why environments defaults to
# both and has to be narrowed here.
project_id     = "custoking-prod"
project_number = "182609177023"
region         = "asia-south2"
environments   = ["prod"]

# There are no dev identities in this project.
enable_dev_identities = false

# The recovery-drill identity's Cloud SQL clone and delete permissions still need a security owner's
# review, exactly as in the source project. The identity itself is created because recovery is a
# production concern; only its permissions stay behind this flag.
enable_recovery_bindings = false

artifact_registry_repository_id = "custoking"
clouddeploy_source_bucket       = "custoking-prod-github-deploy-source"
scan_evidence_bucket            = "custoking-prod-scan-evidence"
# Keep the validation bucket project-scoped and explicit. Recovery IAM remains disabled above until
# the security owner reviews the clone/delete and conditioned bucket-policy permissions.
recovery_validation_bucket = "custoking-prod-db-snapshots"

# No Cloud Billing export exists in this project yet. The source export lives in custoking and dies with
# it, so a replacement has to be configured before that project is deleted; until then this grant has
# nothing to point at.
billing_export_dataset = ""

runtime_service_account_emails = {
  prod = [
    "ims-identity-prod@custoking-prod.iam.gserviceaccount.com",
    "ims-school-core-prod@custoking-prod.iam.gserviceaccount.com",
    "ims-operations-prod@custoking-prod.iam.gserviceaccount.com",
    "ims-platform-prod@custoking-prod.iam.gserviceaccount.com",
    "ims-billing-prod@custoking-prod.iam.gserviceaccount.com",
    "ims-api-gateway-prod@custoking-prod.iam.gserviceaccount.com",
    "ims-frontend-prod@custoking-prod.iam.gserviceaccount.com",
  ]
}
