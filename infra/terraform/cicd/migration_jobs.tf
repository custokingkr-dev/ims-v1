# Owner credentials belong exclusively to a one-shot migration identity, never runtime SAs.
resource "google_service_account" "db_migration" {
  for_each     = local.environments
  project      = var.project_id
  account_id   = "ims-db-migration-${each.value}"
  display_name = "IMS ${each.value} one-shot owner schema migrations"
}

resource "google_secret_manager_secret_iam_member" "db_migration_owner_password" {
  for_each  = local.environments
  project   = var.project_id
  secret_id = "db-password-${each.value}"
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.db_migration[each.value].email}"
}

locals {
  migration_release_members = {
    for env in local.environments : env => env == "dev" ? "serviceAccount:${var.dev_release_service_account}" : "serviceAccount:${google_service_account.github["release_prod"].email}"
  }
}

resource "google_service_account_iam_member" "release_act_as_migration" {
  for_each           = local.environments
  service_account_id = google_service_account.db_migration[each.value].name
  role               = "roles/iam.serviceAccountUser"
  member             = local.migration_release_members[each.value]
}

resource "google_artifact_registry_repository_iam_member" "db_migration_image_reader" {
  for_each   = local.environments
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.db_migration[each.value].email}"
}

# Existing trusted release roles already create/update/run jobs. Add deletion only.
# Cloud Run does not support resource.type/resource.name IAM conditions for this
# permission. The exact nonce restriction is an application guard, not an IAM boundary.
# https://docs.cloud.google.com/iam/docs/conditions-resource-attributes
# https://docs.cloud.google.com/run/docs/securing/managing-access
resource "google_project_iam_custom_role" "migration_job_cleanup" {
  project     = var.project_id
  role_id     = "imsMigrationJobCleanup"
  title       = "IMS migration job cleanup"
  description = "Delete transient owner migration jobs after bounded execution"
  permissions = ["run.jobs.delete"]
}

resource "google_project_iam_member" "release_migration_job_cleanup" {
  for_each = local.environments
  project  = var.project_id
  role     = google_project_iam_custom_role.migration_job_cleanup.name
  member   = local.migration_release_members[each.value]
}
