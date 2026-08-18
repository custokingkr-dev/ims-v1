output "workload_identity_provider" {
  value = "projects/${var.project_number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}/providers/${google_iam_workload_identity_pool_provider.github.workload_identity_pool_provider_id}"
}

output "release_dev_service_account" {
  value = try(google_service_account.github["release_dev"].email, null)
}

output "release_prod_service_account" {
  value = try(google_service_account.github["release_prod"].email, null)
}

output "rollback_dev_service_account" {
  value = try(google_service_account.github["rollback_dev"].email, null)
}

output "rollback_prod_service_account" {
  value = try(google_service_account.github["rollback_prod"].email, null)
}

output "config_dev_service_account" {
  value = try(google_service_account.github["config_dev"].email, null)
}

output "config_prod_service_account" {
  value = try(google_service_account.github["config_prod"].email, null)
}

output "cost_controller_service_account" {
  value = google_service_account.github["cost_controller"].email
}

output "recovery_operator_service_account" {
  value = try(google_service_account.github["recovery"].email, null)
}

output "artifact_registry_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.custoking.repository_id}"
}

output "clouddeploy_source_staging_dir" {
  value = "gs://${var.clouddeploy_source_bucket}/source"
}
