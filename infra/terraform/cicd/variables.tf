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

variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker repository id."
  type        = string
  default     = "custoking"
}

variable "runtime_service_account_email" {
  description = "Current Cloud Run runtime service account. Replace with per-service runtime accounts when ready."
  type        = string
  default     = "305630109861-compute@developer.gserviceaccount.com"
}
