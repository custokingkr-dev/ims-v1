terraform {
  required_version = ">= 1.7.0"

  # Partial configuration, matching deploy/gcp/observability. Supply bucket and prefix at init:
  #   terraform init -backend-config="bucket=custoking-terraform-state" -backend-config="prefix=cicd"
  # Without a remote backend any state produced lives on one workstation and is not shared, which is
  # not state management. See docs/TERRAFORM-CICD-STATE-ADOPTION-SCOPE-2026-08-17.md.
  backend "gcs" {}

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 6.0.0"
    }
  }
}
