locals {
  services = toset([
    "identity-service",
    "school-core-service",
    "operations-service",
    "platform-service",
    "billing-service",
    "api-gateway",
    "frontend",
  ])

  environments = toset(["dev", "stage", "prod"])

  github_service_accounts = {
    release_builder = "github-release-builder"
    promoter        = "github-release-promoter"
    rollback        = "github-release-rollback"
  }

  deploy_service_accounts = {
    dev   = "clouddeploy-dev-deployer"
    stage = "clouddeploy-stage-deployer"
    prod  = "clouddeploy-prod-deployer"
  }

  github_principal_set = "principalSet://iam.googleapis.com/projects/${var.project_number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}/attribute.repository/${var.github_repository}"
}

resource "google_project_service" "required" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "clouddeploy.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "sts.googleapis.com",
  ])

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "custoking" {
  project       = var.project_id
  location      = var.region
  repository_id = var.artifact_registry_repository_id
  description   = "Custoking immutable release images"
  format        = "DOCKER"

  labels = {
    app         = "custoking-ims"
    component   = "artifact-registry"
    owner       = "engineering"
    cost-center = "school-saas"
  }

  cleanup_policies {
    id     = "delete-old-release-images"
    action = "DELETE"
    condition {
      older_than = "604800s"
    }
  }

  cleanup_policies {
    id     = "keep-recent-release-images"
    action = "KEEP"
    most_recent_versions {
      keep_count = 3
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool" "github" {
  project                   = var.project_id
  workload_identity_pool_id = "github-pool"
  display_name              = "GitHub Actions"
  description               = "Keyless GitHub Actions federation for Custoking CI/CD."
  disabled                  = false

  depends_on = [google_project_service.required]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-provider"
  display_name                       = "GitHub repository provider"
  disabled                           = false

  attribute_mapping = {
    "google.subject"             = "assertion.sub"
    "attribute.actor"            = "assertion.actor"
    "attribute.aud"              = "assertion.aud"
    "attribute.environment"      = "assertion.environment"
    "attribute.ref"              = "assertion.ref"
    "attribute.repository"       = "assertion.repository"
    "attribute.repository_owner" = "assertion.repository_owner"
    "attribute.workflow"         = "assertion.workflow"
  }

  attribute_condition = "assertion.repository == '${var.github_repository}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "github" {
  for_each     = local.github_service_accounts
  project      = var.project_id
  account_id   = each.value
  display_name = "GitHub ${replace(each.key, "_", " ")}"
  description  = "Keyless GitHub Actions account for ${each.key}."
}

resource "google_service_account" "clouddeploy" {
  for_each     = local.deploy_service_accounts
  project      = var.project_id
  account_id   = each.value
  display_name = "Cloud Deploy ${each.key} deployer"
  description  = "Cloud Deploy execution account for ${each.key} targets."
}

resource "google_service_account_iam_member" "github_wif" {
  for_each           = google_service_account.github
  service_account_id = each.value.name
  role               = "roles/iam.workloadIdentityUser"
  member             = local.github_principal_set
}

resource "google_project_iam_member" "release_builder_roles" {
  for_each = toset([
    "roles/artifactregistry.writer",
    "roles/clouddeploy.releaser",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["release_builder"].email}"
}

resource "google_project_iam_member" "promoter_roles" {
  for_each = toset([
    "roles/clouddeploy.operator",
    "roles/clouddeploy.releaser",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["promoter"].email}"
}

resource "google_project_iam_member" "rollback_roles" {
  for_each = toset([
    "roles/clouddeploy.operator",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["rollback"].email}"
}

resource "google_project_iam_member" "clouddeploy_deployer_roles" {
  for_each = {
    for pair in setproduct(local.environments, toset([
      "roles/run.developer",
      "roles/iam.serviceAccountUser",
      "roles/secretmanager.viewer",
      "roles/logging.viewer",
      "roles/artifactregistry.reader",
      ])) : "${pair[0]}:${pair[1]}" => {
      env  = pair[0]
      role = pair[1]
    }
  }

  project = var.project_id
  role    = each.value.role
  member  = "serviceAccount:${google_service_account.clouddeploy[each.value.env].email}"
}

resource "google_service_account_iam_member" "clouddeploy_act_as_runtime" {
  for_each           = google_service_account.clouddeploy
  service_account_id = "projects/${var.project_id}/serviceAccounts/${var.runtime_service_account_email}"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${each.value.email}"
}
