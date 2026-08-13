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

  # Stage is intentionally absent: the repository has no stage target manifests, protected
  # environment contract, or runtime identities. Add all three together before enabling it.
  environments = toset(["dev", "prod"])

  github_service_accounts = {
    release_dev     = "github-release-dev"
    release_prod    = "github-release-prod"
    rollback_dev    = "github-rollback-dev"
    rollback_prod   = "github-rollback-prod"
    config_dev      = "github-config-dev"
    config_prod     = "github-config-prod"
    cost_controller = "github-cost-controller"
    recovery        = "custoking-recovery-operator"
  }

  # Service-account impersonation is branch-specific. Provider-level allowlisting alone is not
  # sufficient because a workflow changed on dev must never be able to request a prod identity.
  github_service_account_workflow_refs = {
    release_dev     = toset(["${var.github_repository}/.github/workflows/build-release.yml@refs/heads/dev"])
    release_prod    = toset(["${var.github_repository}/.github/workflows/build-release.yml@refs/heads/main"])
    rollback_dev    = toset(["${var.github_repository}/.github/workflows/rollback.yml@refs/heads/dev"])
    rollback_prod   = toset(["${var.github_repository}/.github/workflows/rollback.yml@refs/heads/main"])
    config_dev      = toset(["${var.github_repository}/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev"])
    config_prod     = toset(["${var.github_repository}/.github/workflows/reconcile-deployment-config.yml@refs/heads/main"])
    cost_controller = toset(["${var.github_repository}/.github/workflows/gcp-cost-controls.yml@refs/heads/main"])
    recovery        = toset(["${var.github_repository}/.github/workflows/recovery-drill.yml@refs/heads/main"])
  }

  allowed_workflow_claims = [
    { ref = "refs/heads/dev", workflow_ref = "${var.github_repository}/.github/workflows/build-release.yml@refs/heads/dev" },
    { ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/build-release.yml@refs/heads/main" },
    { ref = "refs/heads/dev", workflow_ref = "${var.github_repository}/.github/workflows/rollback.yml@refs/heads/dev" },
    { ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/rollback.yml@refs/heads/main" },
    { ref = "refs/heads/dev", workflow_ref = "${var.github_repository}/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev" },
    { ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/reconcile-deployment-config.yml@refs/heads/main" },
    { ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/gcp-cost-controls.yml@refs/heads/main" },
    { ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/recovery-drill.yml@refs/heads/main" },
  ]

  github_provider_attribute_condition = join(" && ", [
    "assertion.repository_id == '${var.github_repository_id}'",
    "assertion.repository_owner_id == '${var.github_repository_owner_id}'",
    "(${join(" || ", [for claim in local.allowed_workflow_claims : "(assertion.ref == '${claim.ref}' && assertion.workflow_ref == '${claim.workflow_ref}')"])})",
  ])

  deploy_service_accounts = {
    dev  = "clouddeploy-dev-deployer"
    prod = "clouddeploy-prod-deployer"
  }

  runtime_service_account_bindings = {
    for binding in flatten([
      for env, emails in var.runtime_service_account_emails : [
        for email in emails : {
          key   = "${env}:${email}"
          env   = env
          email = email
        }
      ]
    ]) : binding.key => binding
  }

  github_workflow_ref_bindings = {
    for binding in flatten([
      for account, workflow_refs in local.github_service_account_workflow_refs : [
        for workflow_ref in workflow_refs : {
          key          = "${account}:${workflow_ref}"
          account      = account
          workflow_ref = workflow_ref
        }
      ]
    ]) : binding.key => binding
  }

  github_principal_set_prefix = "principalSet://iam.googleapis.com/projects/${var.project_number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github.workload_identity_pool_id}"
}

resource "google_project_service" "required" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "clouddeploy.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "storage.googleapis.com",
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
    "google.subject"                = "assertion.sub"
    "attribute.ref"                 = "assertion.ref"
    "attribute.repository"          = "assertion.repository"
    "attribute.repository_id"       = "assertion.repository_id"
    "attribute.repository_owner_id" = "assertion.repository_owner_id"
    "attribute.workflow_file"       = "assertion.workflow_ref.extract('/.github/workflows/{workflow_file}@')"
    "attribute.workflow_ref"        = "assertion.workflow_ref"
  }

  attribute_condition = local.github_provider_attribute_condition

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
  for_each           = local.github_workflow_ref_bindings
  service_account_id = google_service_account.github[each.value.account].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "${local.github_principal_set_prefix}/attribute.workflow_ref/${each.value.workflow_ref}"
}

resource "google_project_iam_member" "release_dev_roles" {
  for_each = toset([
    "roles/clouddeploy.releaser",
    "roles/cloudsql.editor",
    "roles/run.developer",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_project_iam_member" "release_prod_roles" {
  for_each = toset([
    "roles/clouddeploy.releaser",
    "roles/run.viewer",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

# gcloud uploads the local Skaffold/render source before it calls Cloud Deploy. The releaser role
# does not include Cloud Storage data-plane access, so grant only bucket metadata read and
# create-only object access on Cloud Deploy's regional staging bucket.
resource "google_storage_bucket_iam_member" "release_prod_source_bucket_viewer" {
  bucket = var.clouddeploy_source_bucket
  role   = "roles/storage.bucketViewer"
  member = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

resource "google_storage_bucket_iam_member" "release_prod_source_object_creator" {
  bucket = var.clouddeploy_source_bucket
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

resource "google_project_iam_member" "rollback_dev_roles" {
  for_each = toset([
    "roles/run.developer",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["rollback_dev"].email}"
}

resource "google_project_iam_member" "rollback_prod_roles" {
  for_each = toset([
    "roles/clouddeploy.operator",
    "roles/run.viewer",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["rollback_prod"].email}"
}

resource "google_project_iam_custom_role" "clouddeploy_config_reconciler" {
  project     = var.project_id
  role_id     = "custokingCloudDeployConfigReconciler"
  title       = "Custoking Cloud Deploy Configuration Reconciler"
  description = "Create and update Cloud Deploy targets and delivery pipelines without release, rollout, delete, or IAM permissions."
  permissions = [
    "clouddeploy.config.get",
    "clouddeploy.deliveryPipelines.create",
    "clouddeploy.deliveryPipelines.get",
    "clouddeploy.deliveryPipelines.list",
    "clouddeploy.deliveryPipelines.update",
    "clouddeploy.locations.get",
    "clouddeploy.locations.list",
    "clouddeploy.operations.get",
    "clouddeploy.operations.list",
    "clouddeploy.targets.create",
    "clouddeploy.targets.get",
    "clouddeploy.targets.list",
    "clouddeploy.targets.update",
    "resourcemanager.projects.get",
  ]
}

resource "google_project_iam_member" "config_reconciler_roles" {
  for_each = toset(["dev", "prod"])

  project = var.project_id
  role    = google_project_iam_custom_role.clouddeploy_config_reconciler.name
  member  = "serviceAccount:${google_service_account.github["config_${each.value}"].email}"
}

resource "google_project_iam_member" "config_reconciler_service_usage" {
  for_each = toset(["dev", "prod"])

  project = var.project_id
  role    = "roles/serviceusage.serviceUsageConsumer"
  member  = "serviceAccount:${google_service_account.github["config_${each.value}"].email}"
}

resource "google_project_iam_member" "clouddeploy_deployer_roles" {
  for_each = {
    for pair in setproduct(local.environments, toset([
      "roles/clouddeploy.jobRunner",
      "roles/run.developer",
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
  for_each           = local.runtime_service_account_bindings
  service_account_id = "projects/${var.project_id}/serviceAccounts/${each.value.email}"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.clouddeploy[each.value.env].email}"
}

# Release creation must impersonate the render/deploy execution identities. Direct dev releases
# also deploy with the existing per-service Cloud Run runtime identities. Keep actAs resource-scoped.
resource "google_service_account_iam_member" "release_builder_act_as_clouddeploy" {
  for_each = {
    dev  = "release_dev"
    prod = "release_prod"
  }
  service_account_id = google_service_account.clouddeploy[each.key].name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github[each.value].email}"
}

resource "google_service_account_iam_member" "release_builder_act_as_dev_runtime" {
  for_each           = var.runtime_service_account_emails["dev"]
  service_account_id = "projects/${var.project_id}/serviceAccounts/${each.value}"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_service_account_iam_member" "rollback_prod_act_as_clouddeploy" {
  service_account_id = google_service_account.clouddeploy["prod"].name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["rollback_prod"].email}"
}

resource "google_service_account_iam_member" "config_reconciler_act_as_clouddeploy" {
  for_each           = local.environments
  service_account_id = google_service_account.clouddeploy[each.value].name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["config_${each.value}"].email}"
}

# Cloud Run deployment requires the deployer to read the selected image. Scope that permission to
# the one release repository instead of granting Artifact Registry Reader project-wide.
resource "google_artifact_registry_repository_iam_member" "clouddeploy_image_reader" {
  for_each   = local.environments
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.clouddeploy[each.value].email}"
}

resource "google_artifact_registry_repository_iam_member" "release_prod_image_reader" {
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

resource "google_artifact_registry_repository_iam_member" "release_dev_image_writer" {
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_project_iam_member" "cost_controller_roles" {
  for_each = toset([
    "roles/cloudsql.editor",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["cost_controller"].email}"
}

# Clone and delete are intentionally absent from roles/cloudsql.editor. Keep recovery on a
# workflow-dedicated identity; reduce this predefined role only after a live drill proves an exact
# custom-permission set and cleanup path.
resource "google_project_iam_member" "recovery_roles" {
  for_each = toset([
    "roles/cloudsql.admin",
    "roles/serviceusage.serviceUsageConsumer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["recovery"].email}"
}

resource "google_storage_bucket_iam_member" "recovery_bucket_policy_operator" {
  bucket = var.recovery_validation_bucket
  role   = "projects/${var.project_id}/roles/${var.recovery_bucket_iam_role_id}"
  member = "serviceAccount:${google_service_account.github["recovery"].email}"

  condition {
    title       = "recovery_bucket_and_validation_prefix_only"
    description = "Allow bucket policy operations and exact-object cleanup only under recovery-drills/."
    expression = join(" || ", [
      "(resource.type == 'storage.googleapis.com/Bucket' && resource.name == 'projects/_/buckets/${var.recovery_validation_bucket}')",
      "(resource.type == 'storage.googleapis.com/Object' && resource.name.startsWith('projects/_/buckets/${var.recovery_validation_bucket}/objects/recovery-drills/'))",
    ])
  }
}
