resource "google_logging_project_bucket_config" "compliance_india" {
  count = var.manage_compliance_logging ? 1 : 0

  project        = var.project
  location       = var.region
  bucket_id      = "custoking-compliance-india"
  description    = "India-resident Custoking security, request, and audit logs."
  retention_days = var.compliance_log_retention_days
}

resource "google_logging_project_sink" "compliance_india" {
  count = var.manage_compliance_logging ? 1 : 0

  project                = var.project
  name                   = "custoking-compliance-india"
  destination            = "logging.googleapis.com/${google_logging_project_bucket_config.compliance_india[0].id}"
  unique_writer_identity = true

  # Keep complete Cloud Run request logs, errors, explicit security/audit application
  # events, and Google Cloud audit logs. The short-lived _Default bucket remains useful
  # for broader operational logs without multiplying long-retention storage cost.
  filter = <<-EOT
    log_id("cloudaudit.googleapis.com/activity") OR
    log_id("cloudaudit.googleapis.com/system_event") OR
    log_id("cloudaudit.googleapis.com/access_transparency") OR
    (resource.type="cloud_run_revision" AND resource.labels.service_name=~"custoking-.*-(dev|prod)" AND (
      log_id("run.googleapis.com/requests") OR
      severity>=ERROR OR
      jsonPayload.auditEvent:* OR
      jsonPayload.securityEvent:* OR
      textPayload=~"(?i)(authentication|authorization|login|logout|permission denied|audit)"
    ))
  EOT
}
