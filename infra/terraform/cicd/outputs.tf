output "workload_identity_provider" {
  value = "projects/${var.project_number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}/providers/${google_iam_workload_identity_pool_provider.github.workload_identity_pool_provider_id}"
}

output "release_builder_service_account" {
  value = google_service_account.github["release_builder"].email
}

output "promoter_service_account" {
  value = google_service_account.github["promoter"].email
}

output "rollback_service_account" {
  value = google_service_account.github["rollback"].email
}

output "artifact_registry_repository" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.custoking.repository_id}"
}
