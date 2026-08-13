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
    condition = (
      length(setsubtract(toset(keys(var.runtime_service_account_emails)), toset(["dev", "prod"]))) == 0 &&
      length(setsubtract(toset(["dev", "prod"]), toset(keys(var.runtime_service_account_emails)))) == 0
    )
    error_message = "runtime_service_account_emails must define exactly dev and prod; stage is unsupported until its target manifests and IAM contract exist."
  }
}

variable "recovery_validation_bucket" {
  description = "Existing bucket used only for temporary recovery-drill validation exports."
  type        = string
  default     = "custoking-db-snapshots"
}

variable "recovery_bucket_iam_role_id" {
  description = "Existing project custom role ID that can add and remove the temporary clone service identity."
  type        = string
  default     = "custokingRecoveryBucketIamOperator"
}
