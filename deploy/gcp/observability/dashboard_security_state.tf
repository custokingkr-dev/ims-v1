# Isolated security capability state only: no application student/financial collections.
resource "google_project_service" "dashboard_firestore" {
  count              = local.dashboard_enabled
  project            = var.project
  service            = "firestore.googleapis.com"
  disable_on_destroy = false
}

resource "google_firestore_database" "dashboard_security" {
  count                   = local.dashboard_enabled
  project                 = var.project
  name                    = "ims-dashboard-${var.env}"
  location_id             = var.region
  type                    = "FIRESTORE_NATIVE"
  delete_protection_state = "DELETE_PROTECTION_ENABLED"
  depends_on              = [google_project_service.dashboard_firestore]
}

resource "google_firestore_field" "dashboard_security_expiry" {
  count      = local.dashboard_enabled
  project    = var.project
  database   = google_firestore_database.dashboard_security[0].name
  collection = "dashboardSecurityState"
  field      = "expiresAt"
  ttl_config {}
}

resource "google_project_iam_custom_role" "dashboard_security_state" {
  count       = local.dashboard_enabled
  project     = var.project
  role_id     = "dashboardSecurityState_${var.env}"
  title       = "Dashboard security state ${var.env}"
  description = "Read and atomically create short-lived dashboard replay/revocation records only."
  permissions = ["datastore.entities.get", "datastore.entities.create"]
}

resource "google_project_iam_member" "dashboard_security_state" {
  count   = local.dashboard_enabled
  project = var.project
  role    = google_project_iam_custom_role.dashboard_security_state[0].name
  member  = "serviceAccount:${google_service_account.dashboard[0].email}"
  condition {
    title       = "dashboard-named-database-only"
    description = "No access to the default or any other Firestore database."
    expression  = "resource.name == 'projects/${var.project}/databases/${google_firestore_database.dashboard_security[0].name}'"
  }
}
