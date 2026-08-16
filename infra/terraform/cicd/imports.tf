# Generated one-time import blocks. Remove after the adopting apply.
import {
  to = google_artifact_registry_repository.custoking
  id = "projects/custoking/locations/asia-south2/repositories/custoking"
}

import {
  to = google_artifact_registry_repository_iam_member.clouddeploy_image_reader["prod"]
  id = "projects/custoking/locations/asia-south2/repositories/custoking roles/artifactregistry.reader serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_artifact_registry_repository_iam_member.release_prod_image_reader
  id = "projects/custoking/locations/asia-south2/repositories/custoking roles/artifactregistry.reader serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_bigquery_dataset_iam_member.cost_controller_billing_export_viewer
  id = "projects/custoking/datasets/billing_export roles/bigquery.dataViewer serviceAccount:github-cost-controller@custoking.iam.gserviceaccount.com"
}

import {
  to = google_iam_workload_identity_pool.github
  id = "projects/custoking/locations/global/workloadIdentityPools/github-pool"
}

import {
  to = google_iam_workload_identity_pool_provider.github
  id = "projects/custoking/locations/global/workloadIdentityPools/github-pool/providers/github-provider"
}

import {
  to = google_project_iam_custom_role.clouddeploy_config_reconciler
  id = "projects/custoking/roles/custokingCloudDeployConfigReconciler"
}

import {
  to = google_project_iam_member.clouddeploy_deployer_roles["prod:roles/clouddeploy.jobRunner"]
  id = "custoking roles/clouddeploy.jobRunner serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.clouddeploy_deployer_roles["prod:roles/run.developer"]
  id = "custoking roles/run.developer serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.config_reconciler_roles["prod"]
  id = "custoking projects/custoking/roles/custokingCloudDeployConfigReconciler serviceAccount:github-config-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.config_reconciler_service_usage["prod"]
  id = "custoking roles/serviceusage.serviceUsageConsumer serviceAccount:github-config-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.cost_controller_roles["roles/bigquery.jobUser"]
  id = "custoking roles/bigquery.jobUser serviceAccount:github-cost-controller@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.cost_controller_roles["roles/cloudsql.editor"]
  id = "custoking roles/cloudsql.editor serviceAccount:github-cost-controller@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.cost_controller_roles["roles/serviceusage.serviceUsageConsumer"]
  id = "custoking roles/serviceusage.serviceUsageConsumer serviceAccount:github-cost-controller@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.release_prod_roles["roles/clouddeploy.releaser"]
  id = "custoking roles/clouddeploy.releaser serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.release_prod_roles["roles/run.viewer"]
  id = "custoking roles/run.viewer serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.release_prod_roles["roles/serviceusage.serviceUsageConsumer"]
  id = "custoking roles/serviceusage.serviceUsageConsumer serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.rollback_prod_roles["roles/clouddeploy.operator"]
  id = "custoking roles/clouddeploy.operator serviceAccount:github-rollback-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.rollback_prod_roles["roles/run.viewer"]
  id = "custoking roles/run.viewer serviceAccount:github-rollback-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_iam_member.rollback_prod_roles["roles/serviceusage.serviceUsageConsumer"]
  id = "custoking roles/serviceusage.serviceUsageConsumer serviceAccount:github-rollback-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_project_service.required["artifactregistry.googleapis.com"]
  id = "custoking/artifactregistry.googleapis.com"
}

import {
  to = google_project_service.required["clouddeploy.googleapis.com"]
  id = "custoking/clouddeploy.googleapis.com"
}

import {
  to = google_project_service.required["iam.googleapis.com"]
  id = "custoking/iam.googleapis.com"
}

import {
  to = google_project_service.required["iamcredentials.googleapis.com"]
  id = "custoking/iamcredentials.googleapis.com"
}

import {
  to = google_project_service.required["run.googleapis.com"]
  id = "custoking/run.googleapis.com"
}

import {
  to = google_project_service.required["secretmanager.googleapis.com"]
  id = "custoking/secretmanager.googleapis.com"
}

import {
  to = google_project_service.required["storage.googleapis.com"]
  id = "custoking/storage.googleapis.com"
}

import {
  to = google_project_service.required["sts.googleapis.com"]
  id = "custoking/sts.googleapis.com"
}

import {
  to = google_service_account.clouddeploy["prod"]
  id = "projects/custoking/serviceAccounts/clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account.github["config_prod"]
  id = "projects/custoking/serviceAccounts/github-config-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account.github["cost_controller"]
  id = "projects/custoking/serviceAccounts/github-cost-controller@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account.github["recovery"]
  id = "projects/custoking/serviceAccounts/custoking-recovery-operator@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account.github["release_prod"]
  id = "projects/custoking/serviceAccounts/github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account.github["rollback_prod"]
  id = "projects/custoking/serviceAccounts/github-rollback-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-api-gateway-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-api-gateway-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-billing-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-billing-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-frontend-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-frontend-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-identity-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-identity-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-operations-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-operations-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-platform-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-platform-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.clouddeploy_act_as_runtime["prod:ims-school-core-prod@custoking.iam.gserviceaccount.com"]
  id = "projects/custoking/serviceAccounts/ims-school-core-prod@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.config_reconciler_act_as_clouddeploy["prod"]
  id = "projects/custoking/serviceAccounts/clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:github-config-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.github_wif["config_prod:custokingkr-dev/ims-v1/.github/workflows/reconcile-deployment-config.yml@refs/heads/main"]
  id = "projects/custoking/serviceAccounts/github-config-prod@custoking.iam.gserviceaccount.com roles/iam.workloadIdentityUser principalSet://iam.googleapis.com/projects/305630109861/locations/global/workloadIdentityPools/github-pool/attribute.workflow_ref/custokingkr-dev/ims-v1/.github/workflows/reconcile-deployment-config.yml@refs/heads/main"
}

import {
  to = google_service_account_iam_member.github_wif["cost_controller:custokingkr-dev/ims-v1/.github/workflows/gcp-cost-controls.yml@refs/heads/main"]
  id = "projects/custoking/serviceAccounts/github-cost-controller@custoking.iam.gserviceaccount.com roles/iam.workloadIdentityUser principalSet://iam.googleapis.com/projects/305630109861/locations/global/workloadIdentityPools/github-pool/attribute.workflow_ref/custokingkr-dev/ims-v1/.github/workflows/gcp-cost-controls.yml@refs/heads/main"
}

import {
  to = google_service_account_iam_member.github_wif["release_prod:custokingkr-dev/ims-v1/.github/workflows/build-release.yml@refs/heads/main"]
  id = "projects/custoking/serviceAccounts/github-release-prod@custoking.iam.gserviceaccount.com roles/iam.workloadIdentityUser principalSet://iam.googleapis.com/projects/305630109861/locations/global/workloadIdentityPools/github-pool/attribute.workflow_ref/custokingkr-dev/ims-v1/.github/workflows/build-release.yml@refs/heads/main"
}

import {
  to = google_service_account_iam_member.github_wif["rollback_prod:custokingkr-dev/ims-v1/.github/workflows/rollback.yml@refs/heads/main"]
  id = "projects/custoking/serviceAccounts/github-rollback-prod@custoking.iam.gserviceaccount.com roles/iam.workloadIdentityUser principalSet://iam.googleapis.com/projects/305630109861/locations/global/workloadIdentityPools/github-pool/attribute.workflow_ref/custokingkr-dev/ims-v1/.github/workflows/rollback.yml@refs/heads/main"
}

import {
  to = google_service_account_iam_member.release_builder_act_as_clouddeploy["prod"]
  id = "projects/custoking/serviceAccounts/clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_service_account_iam_member.rollback_prod_act_as_clouddeploy
  id = "projects/custoking/serviceAccounts/clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com roles/iam.serviceAccountUser serviceAccount:github-rollback-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_storage_bucket_iam_member.release_prod_source_bucket_viewer
  id = "custoking-github-deploy-source roles/storage.bucketViewer serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_storage_bucket_iam_member.release_prod_source_object_creator
  id = "custoking-github-deploy-source roles/storage.objectCreator serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_storage_bucket_iam_member.release_scan_evidence_bucket_viewer["serviceAccount:github-actions-sa@custoking.iam.gserviceaccount.com"]
  id = "custoking-scan-evidence roles/storage.bucketViewer serviceAccount:github-actions-sa@custoking.iam.gserviceaccount.com"
}

import {
  to = google_storage_bucket_iam_member.release_scan_evidence_bucket_viewer["serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"]
  id = "custoking-scan-evidence roles/storage.bucketViewer serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"
}

import {
  to = google_storage_bucket_iam_member.release_scan_evidence_object_admin["serviceAccount:github-actions-sa@custoking.iam.gserviceaccount.com"]
  id = "custoking-scan-evidence roles/storage.objectAdmin serviceAccount:github-actions-sa@custoking.iam.gserviceaccount.com scan_evidence_trivy_prefix_only"
}

import {
  to = google_storage_bucket_iam_member.release_scan_evidence_object_admin["serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com"]
  id = "custoking-scan-evidence roles/storage.objectAdmin serviceAccount:github-release-prod@custoking.iam.gserviceaccount.com scan_evidence_trivy_prefix_only"
}

