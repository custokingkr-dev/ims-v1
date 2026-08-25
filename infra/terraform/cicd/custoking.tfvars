# The legacy project. After the dev migration it owns PRODUCTION ONLY; dev lives in custoking-dev.
# Scoping environments here removes the dev-branch trust from the Workload Identity provider, which
# would otherwise remain a live authentication path into this project from the dev branch.
project_id     = "custoking"
project_number = "305630109861"
region         = "asia-south2"
environments   = ["prod"]

enable_dev_identities      = false
enable_recovery_bindings   = false
recovery_validation_bucket = "custoking-db-snapshots"

runtime_service_account_emails = {
  prod = [
    "ims-identity-prod@custoking.iam.gserviceaccount.com",
    "ims-school-core-prod@custoking.iam.gserviceaccount.com",
    "ims-operations-prod@custoking.iam.gserviceaccount.com",
    "ims-platform-prod@custoking.iam.gserviceaccount.com",
    "ims-billing-prod@custoking.iam.gserviceaccount.com",
    "ims-api-gateway-prod@custoking.iam.gserviceaccount.com",
    "ims-frontend-prod@custoking.iam.gserviceaccount.com",
  ]
}
