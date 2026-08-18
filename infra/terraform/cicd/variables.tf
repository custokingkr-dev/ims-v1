variable "environments" {
  description = "Environments whose CI/CD identities this project owns. Before the split-project migration one project held both, so the default keeps that behaviour. A destination project must own exactly its own environment, otherwise it grows the other environment's identities."
  type        = list(string)
  default     = ["dev", "prod"]

  validation {
    condition     = length(setsubtract(toset(var.environments), toset(["dev", "prod"]))) == 0 && length(var.environments) > 0
    error_message = "environments may contain only dev and/or prod, and must not be empty; stage is unsupported until its target manifests and IAM contract exist."
  }
}

variable "project_id" {
  description = "GCP project that hosts Custoking CI/CD and Cloud Run."
  type        = string
  default     = "custoking"
}

variable "project_number" {
  description = "Numeric GCP project id. Required for Workload Identity Federation principal sets."
  type        = string
  default     = "305630109861"
}

variable "region" {
  description = "Primary Cloud Deploy, Cloud Run, and Artifact Registry region."
  type        = string
  default     = "asia-south2"
}

variable "github_repository" {
  description = "GitHub repository allowed to federate into deployment service accounts."
  type        = string
  default     = "custokingkr-dev/ims-v1"
}

variable "github_repository_id" {
  description = "Immutable GitHub repository ID allowed to federate into deployment service accounts."
  type        = string
  default     = "1207086249"
}

variable "github_repository_owner_id" {
  description = "Immutable GitHub repository owner ID allowed to federate into deployment service accounts."
  type        = string
  default     = "274906704"
}

variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker repository id."
  type        = string
  default     = "custoking"
}

variable "clouddeploy_source_bucket" {
  description = "Existing regional source-staging bucket shared by gcloud Cloud Deploy release uploads. Configure a lifecycle rule to delete transient source archives."
  type        = string
  default     = "custoking-github-deploy-source"
}

variable "runtime_service_account_emails" {
  description = "Exact per-environment Cloud Run runtime identities each Cloud Deploy execution account may impersonate."
  type        = map(set(string))
  default = {
    dev = [
      "ims-identity-dev@custoking.iam.gserviceaccount.com",
      "ims-school-core-dev@custoking.iam.gserviceaccount.com",
      "ims-operations-dev@custoking.iam.gserviceaccount.com",
      "ims-platform-dev@custoking.iam.gserviceaccount.com",
      "ims-billing-dev@custoking.iam.gserviceaccount.com",
      "ims-api-gateway-dev@custoking.iam.gserviceaccount.com",
      "ims-frontend-dev@custoking.iam.gserviceaccount.com",
    ]
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

  validation {
    condition     = length(setsubtract(toset(keys(var.runtime_service_account_emails)), toset(["dev", "prod"]))) == 0
    error_message = "runtime_service_account_emails may define only dev and/or prod; stage is unsupported until its target manifests and IAM contract exist. Entries for environments absent from var.environments are ignored."
  }
}

variable "recovery_validation_bucket" {
  description = "Existing bucket used only for temporary recovery-drill validation exports."
  type        = string
  default     = "custoking-db-snapshots"
}

variable "scan_evidence_bucket" {
  description = "Existing bucket holding digest-keyed Trivy verdicts and their evidence, so an unchanged image digest is not re-pulled from Artifact Registry on every release. Configure a lifecycle rule to delete objects after 30 days."
  type        = string
  default     = "custoking-scan-evidence"
}

variable "billing_export_dataset" {
  description = "Existing BigQuery dataset holding the detailed Cloud Billing export. The cost-control identity reads it to report Artifact Registry egress; it is granted no other BigQuery data."
  type        = string
  default     = "billing_export"
}

variable "enable_recovery_bindings" {
  description = "Whether to manage the recovery-drill identity's permissions. The custoking-recovery-operator account exists, but as of 2026-08-16 none of these three bindings exist live as declared: roles/cloudsql.admin, roles/serviceusage.serviceUsageConsumer, the recovery-drill workload identity binding, and the recovery bucket binding, which is live unconditionally where this module scopes it to recovery-drills/. Enabling this grants real permissions including clone and delete on Cloud SQL, and replaces an unconditional bucket binding, so it needs a security owner's review rather than riding along with a state-adoption apply. See infra/terraform/cicd/README.md and docs/TERRAFORM-CICD-STATE-ADOPTION-SCOPE-2026-08-17.md."
  type        = bool
  default     = false
}

variable "enable_dev_identities" {
  description = "Whether to manage the per-environment dev CI/CD identities (github-release-dev, github-rollback-dev, github-config-dev, clouddeploy-dev-deployer). These are declared because moving dev off the shared github-actions-sa is the intended end state, but none of them exist in the live project as of 2026-08-16 and dev authenticates as the shared account. Keep false so state reflects reality; flipping it to true begins that migration and requires the dev GitHub environment variables to move in the same change or dev CI breaks. See docs/TERRAFORM-CICD-STATE-ADOPTION-SCOPE-2026-08-17.md."
  type        = bool
  default     = false
}

variable "dev_release_service_account" {
  description = "Email of the identity dev releases actually run as. The dev GitHub environment sets no RELEASE_BUILDER_SERVICE_ACCOUNT, so build-release.yml falls back to the repository-level DEPLOY_SERVICE_ACCOUNT. Point this at github-release-dev once that account exists and the dev environment sets it."
  type        = string
  default     = "github-actions-sa@custoking.iam.gserviceaccount.com"
}

variable "recovery_bucket_iam_role_id" {
  description = "Existing project custom role ID that can add and remove the temporary clone service identity."
  type        = string
  default     = "custokingRecoveryBucketIamOperator"
}
