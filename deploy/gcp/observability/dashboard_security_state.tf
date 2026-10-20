# Isolated security capability state only: no application student/financial collections.
# Persistent security state can be adopted without enabling the OAuth web deployment.
# After adoption, keep enable_dashboard_security_state=true. The lifecycle guards
# also reject accidental destruction when an operator omits that input.
resource "google_project_service" "dashboard_firestore" {
  count              = local.dashboard_security_enabled
  project            = var.project
  service            = "firestore.googleapis.com"
  disable_on_destroy = false
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_firestore_database" "dashboard_security" {
  count                   = local.dashboard_security_enabled
  project                 = var.project
  name                    = "ims-dashboard-${var.env}"
  location_id             = var.region
  type                    = "FIRESTORE_NATIVE"
  delete_protection_state = "DELETE_PROTECTION_ENABLED"
  depends_on              = [google_project_service.dashboard_firestore]
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_firestore_field" "dashboard_security_expiry" {
  count      = local.dashboard_security_enabled
  project    = var.project
  database   = google_firestore_database.dashboard_security[0].name
  collection = "dashboardSecurityState"
  field      = "expiresAt"
  ttl_config {}
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_project_iam_custom_role" "dashboard_security_state" {
  count       = local.dashboard_security_enabled
  project     = var.project
  role_id     = "dashboardSecurityState_${var.env}"
  title       = "Dashboard security state ${var.env}"
  description = "Read and atomically create short-lived dashboard replay/revocation records only."
  permissions = ["datastore.entities.get", "datastore.entities.create"]
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_project_iam_member" "dashboard_security_state" {
  count   = local.dashboard_security_enabled
  project = var.project
  role    = google_project_iam_custom_role.dashboard_security_state[0].name
  member  = "serviceAccount:${google_service_account.dashboard[0].email}"
  condition {
    title      = "dashboard-named-database-only"
    expression = "resource.name == 'projects/${var.project}/databases/${google_firestore_database.dashboard_security[0].name}'"
  }
  lifecycle {
    prevent_destroy = true
  }
}
