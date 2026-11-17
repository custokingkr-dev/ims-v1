# CI/CD plane for the migrated dev environment. This project owns ONLY dev; prod identities belong to
# custoking-prod. Before the split one project held both, which is why environments defaults to both.
project_id     = "custoking-dev"
project_number = "1087017280590"
region         = "asia-south2"
environments   = ["dev"]

# There is no shared github-actions-sa to fall back on in a destination project, so the per-environment
# dev identities are mandatory here rather than optional.
enable_dev_identities       = true
dev_release_service_account = "github-release-dev@custoking-dev.iam.gserviceaccount.com"

artifact_registry_repository_id = "custoking"
clouddeploy_source_bucket       = "custoking-dev-github-deploy-source"
scan_evidence_bucket            = "custoking-dev-scan-evidence"
recovery_validation_bucket      = "custoking-dev-db-snapshots"

# Recovery drills are a production concern; the identity and its Cloud SQL clone/delete permissions are
# deliberately absent from dev.
enable_recovery_bindings = false

runtime_service_account_emails = {
  dev = [
    "ims-identity-dev@custoking-dev.iam.gserviceaccount.com",
    "ims-school-core-dev@custoking-dev.iam.gserviceaccount.com",
    "ims-operations-dev@custoking-dev.iam.gserviceaccount.com",
    "ims-platform-dev@custoking-dev.iam.gserviceaccount.com",
    "ims-billing-dev@custoking-dev.iam.gserviceaccount.com",
    "ims-api-gateway-dev@custoking-dev.iam.gserviceaccount.com",
    "ims-frontend-dev@custoking-dev.iam.gserviceaccount.com",
  ]
}
# Read access for the Artifact Registry egress report (artifact-registry-egress in
# .github/workflows/gcp-cost-controls.yml). The billing export for both projects lives in custoking-prod,
# so this dev root grants the dev cost-control identity dataViewer on that one dataset across projects.
billing_export_dataset = "billing_export"
billing_export_project = "custoking-prod"
