mock_provider "google" {}

variables {
  project                         = "custoking-dev"
  env                             = "dev"
  region                          = "asia-south2"
  discover_cloud_run_urls         = false
  services                        = []
  enable_shared_dashboard         = false
  enable_dashboard_security_state = true
}

run "persistent_state_without_web" {
  command = plan

  assert {
    condition     = length(google_firestore_database.dashboard_security) == 1 && length(google_cloud_run_v2_service.dashboard) == 0 && length(google_cloud_run_v2_service_iam_member.dashboard_public) == 0
    error_message = "Persistent replay state must not deploy or publicly expose the OAuth web service."
  }
  assert {
    condition     = google_firestore_database.dashboard_security[0].name == "ims-dashboard-dev" && google_firestore_database.dashboard_security[0].delete_protection_state == "DELETE_PROTECTION_ENABLED"
    error_message = "Adoption must preserve the protected named database."
  }
  assert {
    condition     = toset(google_project_iam_custom_role.dashboard_security_state[0].permissions) == toset(["datastore.entities.get", "datastore.entities.create"]) && google_project_iam_member.dashboard_security_state[0].condition[0].expression == "resource.name == 'projects/custoking-dev/databases/ims-dashboard-dev'" && length(google_project_iam_member.dashboard_monitoring) == 0
    error_message = "State-only adoption must retain the exact read/create database scope without monitoring grants."
  }
  assert {
    condition     = google_firestore_field.dashboard_security_expiry[0].field == "expiresAt" && google_firestore_field.dashboard_security_expiry[0].collection == "dashboardSecurityState" && length(google_firestore_field.dashboard_security_expiry[0].ttl_config) == 1
    error_message = "The existing expiry field must retain its TTL policy."
  }
}

run "web_implies_security_state" {
  command = plan
  variables {
    enable_shared_dashboard         = true
    enable_dashboard_security_state = false
    dashboard_image                 = "example.invalid/dashboard@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    dashboard_public_url            = "https://dashboard.example.invalid"
  }
  assert {
    condition     = length(google_firestore_database.dashboard_security) == 1 && length(google_service_account.dashboard) == 1 && length(google_cloud_run_v2_service.dashboard) == 1
    error_message = "A web deployment must always retain persistent security state."
  }
}

run "reject_project_environment_mismatch" {
  command = plan
  variables {
    project = "custoking-prod"
    env     = "dev"
  }
  expect_failures = [data.google_project.current]
}
