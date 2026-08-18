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
  environments = toset(var.environments)

  # Environments whose Cloud Deploy / per-environment CI identities this module manages. Dev drops
  # out unless var.enable_dev_identities is set, because clouddeploy-dev-deployer and the dev GitHub
  # identities do not exist live. local.environments still means "environments that exist" and is
  # used where the resource does not depend on a dev identity.
  managed_environments = toset([
    for env in local.environments : env
    if var.enable_dev_identities || env != "dev"
  ])

  # Identities whose live existence depends on var.enable_dev_identities. Declared because moving dev
  # off the shared github-actions-sa is the intended end state, but none exist live yet, so they are
  # filtered out by default and this module stays a truthful record of the project.
  dev_identity_keys = ["release_dev", "rollback_dev", "config_dev"]

  # Which environment each identity key belongs to. Keys absent from this map (cost_controller,
  # recovery) are environment-agnostic and are owned by whichever project runs them.
  identity_environment = {
    release_dev   = "dev"
    release_prod  = "prod"
    rollback_dev  = "dev"
    rollback_prod = "prod"
    config_dev    = "dev"
    config_prod   = "prod"
    recovery      = "prod"
  }

  github_service_accounts_all = {
    release_dev     = "github-release-dev"
    release_prod    = "github-release-prod"
    rollback_dev    = "github-rollback-dev"
    rollback_prod   = "github-rollback-prod"
    config_dev      = "github-config-dev"
    config_prod     = "github-config-prod"
    cost_controller = "github-cost-controller"
    recovery        = "custoking-recovery-operator"
  }

  github_service_accounts = {
    for key, account in local.github_service_accounts_all :
    key => account
    if(
      (!contains(keys(local.identity_environment), key) || contains(var.environments, local.identity_environment[key])) &&
      (var.enable_dev_identities || !contains(local.dev_identity_keys, key))
    )
  }

  # Identities that build-release.yml actually authenticates as when it reads and writes Trivy
  # verdicts. Kept as a list rather than reusing github_service_accounts because dev has not yet
  # migrated off the shared deploy account; see var.dev_release_service_account.
  scan_evidence_members = distinct(concat(
    contains(var.environments, "prod") ? ["serviceAccount:${google_service_account.github["release_prod"].email}"] : [],
    contains(var.environments, "dev") ? ["serviceAccount:${var.dev_release_service_account}"] : [],
  ))

  # Service-account impersonation is branch-specific. Provider-level allowlisting alone is not
  # sufficient because a workflow changed on dev must never be able to request a prod identity.
  github_service_account_workflow_refs_all = {
    release_dev     = toset(["${var.github_repository}/.github/workflows/build-release.yml@refs/heads/dev"])
    release_prod    = toset(["${var.github_repository}/.github/workflows/build-release.yml@refs/heads/main"])
    rollback_dev    = toset(["${var.github_repository}/.github/workflows/rollback.yml@refs/heads/dev"])
    rollback_prod   = toset(["${var.github_repository}/.github/workflows/rollback.yml@refs/heads/main"])
    config_dev      = toset(["${var.github_repository}/.github/workflows/reconcile-deployment-config.yml@refs/heads/dev"])
    config_prod     = toset(["${var.github_repository}/.github/workflows/reconcile-deployment-config.yml@refs/heads/main"])
    cost_controller = toset(["${var.github_repository}/.github/workflows/gcp-cost-controls.yml@refs/heads/main"])
    recovery        = toset(["${var.github_repository}/.github/workflows/recovery-drill.yml@refs/heads/main"])
  }

  github_service_account_workflow_refs = {
    for key, refs in local.github_service_account_workflow_refs_all :
    key => refs
    if(var.enable_dev_identities || !contains(local.dev_identity_keys, key)) &&
    (var.enable_recovery_bindings || key != "recovery") &&
    (!contains(keys(local.identity_environment), key) || contains(var.environments, local.identity_environment[key]))
  }

  branch_for_environment = { dev = "refs/heads/dev", prod = "refs/heads/main" }

  environment_workflow_claims = flatten([
    for env in var.environments : [
      for workflow in ["build-release.yml", "rollback.yml", "reconcile-deployment-config.yml"] : {
        ref          = local.branch_for_environment[env]
        workflow_ref = "${var.github_repository}/.github/workflows/${workflow}@${local.branch_for_environment[env]}"
      }
    ]
  ])

  # Scheduled maintenance runs from main whichever environment it acts on, so a dev-only project must
  # still trust refs/heads/main for these or its cost-control run cannot authenticate.
  # The provider condition governs which workflows may ATTEMPT federation; the impersonation bindings
  # govern what they can actually assume. Keep them decoupled: gating this claim on
  # enable_recovery_bindings would silently narrow the provider and break recovery drills for a project
  # that owns production.
  maintenance_workflow_claims = concat(
    [{ ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/gcp-cost-controls.yml@refs/heads/main" }],
    contains(var.environments, "prod") ? [{ ref = "refs/heads/main", workflow_ref = "${var.github_repository}/.github/workflows/recovery-drill.yml@refs/heads/main" }] : []
  )

  allowed_workflow_claims = concat(local.environment_workflow_claims, local.maintenance_workflow_claims)

  github_provider_attribute_condition = join(" && ", [
    "assertion.repository_id == '${var.github_repository_id}'",
    "assertion.repository_owner_id == '${var.github_repository_owner_id}'",
    "(${join(" || ", [for claim in local.allowed_workflow_claims : "(assertion.ref == '${claim.ref}' && assertion.workflow_ref == '${claim.workflow_ref}')"])})",
  ])

  deploy_service_accounts_all = {
    dev  = "clouddeploy-dev-deployer"
    prod = "clouddeploy-prod-deployer"
  }

  deploy_service_accounts = {
    for env, account in local.deploy_service_accounts_all :
    env => account
    if contains(var.environments, env) && (var.enable_dev_identities || env != "dev")
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
    if contains(var.environments, binding.env) && (var.enable_dev_identities || binding.env != "dev")
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
  for_each = var.enable_dev_identities ? toset([
    "roles/clouddeploy.releaser",
    "roles/cloudsql.editor",
    "roles/run.developer",
    "roles/serviceusage.serviceUsageConsumer",
  ]) : toset([])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_project_iam_member" "release_prod_roles" {
  for_each = contains(var.environments, "prod") ? toset([
    "roles/clouddeploy.releaser",
    "roles/run.viewer",
    "roles/serviceusage.serviceUsageConsumer",
  ]) : toset([])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

# gcloud uploads the local Skaffold/render source before it calls Cloud Deploy. The releaser role
# does not include Cloud Storage data-plane access, so grant only bucket metadata read and
# create-only object access on Cloud Deploy's regional staging bucket.
resource "google_storage_bucket_iam_member" "release_prod_source_bucket_viewer" {
  count  = contains(var.environments, "prod") ? 1 : 0
  bucket = var.clouddeploy_source_bucket
  role   = "roles/storage.bucketViewer"
  member = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

resource "google_storage_bucket_iam_member" "release_prod_source_object_creator" {
  count  = contains(var.environments, "prod") ? 1 : 0
  bucket = var.clouddeploy_source_bucket
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

resource "google_storage_bucket_iam_member" "release_dev_source_bucket_viewer" {
  count  = var.enable_dev_identities ? 1 : 0
  bucket = var.clouddeploy_source_bucket
  role   = "roles/storage.bucketViewer"
  member = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_storage_bucket_iam_member" "release_dev_source_object_creator" {
  count  = var.enable_dev_identities ? 1 : 0
  bucket = var.clouddeploy_source_bucket
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_project_iam_member" "rollback_dev_roles" {
  for_each = var.enable_dev_identities ? toset([
    "roles/run.developer",
    "roles/serviceusage.serviceUsageConsumer",
  ]) : toset([])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["rollback_dev"].email}"
}

resource "google_project_iam_member" "rollback_prod_roles" {
  for_each = contains(var.environments, "prod") ? toset([
    "roles/clouddeploy.operator",
    "roles/run.viewer",
    "roles/serviceusage.serviceUsageConsumer",
  ]) : toset([])

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
  for_each = local.managed_environments

  project = var.project_id
  role    = google_project_iam_custom_role.clouddeploy_config_reconciler.name
  member  = "serviceAccount:${google_service_account.github["config_${each.value}"].email}"
}

resource "google_project_iam_member" "config_reconciler_service_usage" {
  for_each = local.managed_environments

  project = var.project_id
  role    = "roles/serviceusage.serviceUsageConsumer"
  member  = "serviceAccount:${google_service_account.github["config_${each.value}"].email}"
}

resource "google_project_iam_member" "clouddeploy_deployer_roles" {
  for_each = {
    for pair in setproduct(local.managed_environments, toset([
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
    for env, key in { dev = "release_dev", prod = "release_prod" } :
    env => key
    if contains(var.environments, env) && (var.enable_dev_identities || env != "dev")
  }
  service_account_id = google_service_account.clouddeploy[each.key].name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github[each.value].email}"
}

resource "google_service_account_iam_member" "release_builder_act_as_dev_runtime" {
  for_each           = var.enable_dev_identities ? var.runtime_service_account_emails["dev"] : toset([])
  service_account_id = "projects/${var.project_id}/serviceAccounts/${each.value}"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

resource "google_service_account_iam_member" "rollback_prod_act_as_clouddeploy" {
  count              = contains(var.environments, "prod") ? 1 : 0
  service_account_id = google_service_account.clouddeploy["prod"].name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["rollback_prod"].email}"
}

resource "google_service_account_iam_member" "config_reconciler_act_as_clouddeploy" {
  for_each           = local.managed_environments
  service_account_id = google_service_account.clouddeploy[each.value].name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["config_${each.value}"].email}"
}

# Cloud Run deployment requires the deployer to read the selected image. Scope that permission to
# the one release repository instead of granting Artifact Registry Reader project-wide.
resource "google_artifact_registry_repository_iam_member" "clouddeploy_image_reader" {
  for_each   = local.managed_environments
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.clouddeploy[each.value].email}"
}

resource "google_artifact_registry_repository_iam_member" "release_prod_image_reader" {
  count      = contains(var.environments, "prod") ? 1 : 0
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.github["release_prod"].email}"
}

resource "google_artifact_registry_repository_iam_member" "release_dev_image_writer" {
  count      = var.enable_dev_identities ? 1 : 0
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.github["release_dev"].email}"
}

# Dev rolls back by moving Cloud Run traffic directly, which re-applies the service spec and therefore
# requires acting as the runtime identity. Production rolls back through a Cloud Deploy rollout, which
# runs as the Cloud Deploy execution account, so its rollback identity deliberately does NOT get actAs.
# Before the split this was invisible: dev rolled back as the shared github-actions-sa, which held
# roles/iam.serviceAccountUser project-wide.
resource "google_service_account_iam_member" "rollback_dev_act_as_runtime" {
  for_each           = (contains(var.environments, "dev") && var.enable_dev_identities) ? var.runtime_service_account_emails["dev"] : toset([])
  service_account_id = "projects/${var.project_id}/serviceAccounts/${each.value}"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.github["rollback_dev"].email}"
}

# Rolling a Cloud Run service back to an earlier revision re-resolves that revision's image, so the
# rollback identity needs to read the release repository. Before the split-project migration dev rolled
# back as the shared github-actions-sa, which held artifactregistry.writer project-wide and masked this;
# a dedicated per-environment identity does not, and the rollback fails with
# "artifactregistry.repositories.downloadArtifacts denied" after the traffic decision is already made.
resource "google_artifact_registry_repository_iam_member" "rollback_image_reader" {
  for_each   = local.managed_environments
  project    = var.project_id
  location   = google_artifact_registry_repository.custoking.location
  repository = google_artifact_registry_repository.custoking.repository_id
  role       = "roles/artifactregistry.reader"
  member     = "serviceAccount:${google_service_account.github["rollback_${each.value}"].email}"
}

resource "google_project_iam_member" "cost_controller_roles" {
  for_each = toset([
    "roles/cloudsql.editor",
    "roles/serviceusage.serviceUsageConsumer",
    # Required to run any query job. It confers no data access on its own; readable data is granted
    # separately and narrowly on the billing export dataset below.
    "roles/bigquery.jobUser",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["cost_controller"].email}"
}

# Clone and delete are intentionally absent from roles/cloudsql.editor. Keep recovery on a
# workflow-dedicated identity; reduce this predefined role only after a live drill proves an exact
# custom-permission set and cleanup path.
resource "google_project_iam_member" "recovery_roles" {
  for_each = var.enable_recovery_bindings ? toset([
    "roles/cloudsql.admin",
    "roles/serviceusage.serviceUsageConsumer",
  ]) : toset([])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.github["recovery"].email}"
}

# Read access for the daily Artifact Registry egress report. Scoped to the billing export dataset
# rather than the project so the cost-control identity cannot read application data. Paired with
# roles/bigquery.jobUser above, which permits running a query but grants no data of its own.
resource "google_bigquery_dataset_iam_member" "cost_controller_billing_export_viewer" {
  count      = var.billing_export_dataset == "" ? 0 : 1
  project    = var.project_id
  dataset_id = var.billing_export_dataset
  role       = "roles/bigquery.dataViewer"
  member     = "serviceAccount:${google_service_account.github["cost_controller"].email}"
}

# Scanning an image pulls it out of Artifact Registry to a GitHub-hosted runner, which is billed as
# internet egress. Both release identities read and write digest-keyed verdicts here so an unchanged
# digest is scanned once rather than on every release, including when the prod promotion resolves an
# image dev already scanned. Prod runs as its own release account; dev still runs as the shared
# deploy account because the dev environment sets no RELEASE_BUILDER_SERVICE_ACCOUNT.
resource "google_storage_bucket_iam_member" "release_scan_evidence_object_admin" {
  for_each = toset(local.scan_evidence_members)

  bucket = var.scan_evidence_bucket
  role   = "roles/storage.objectAdmin"
  member = each.value

  condition {
    title       = "scan_evidence_trivy_prefix_only"
    description = "Allow object access only under trivy/v1/."
    expression  = "resource.type == 'storage.googleapis.com/Object' && resource.name.startsWith('projects/_/buckets/${var.scan_evidence_bucket}/objects/trivy/v1/')"
  }
}

# gcloud storage resolves the bucket before it reads or writes an object, so both release identities
# also need metadata read on the bucket itself.
resource "google_storage_bucket_iam_member" "release_scan_evidence_bucket_viewer" {
  for_each = toset(local.scan_evidence_members)

  bucket = var.scan_evidence_bucket
  role   = "roles/storage.bucketViewer"
  member = each.value
}

resource "google_storage_bucket_iam_member" "recovery_bucket_policy_operator" {
  count  = var.enable_recovery_bindings ? 1 : 0
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
