resource "google_project_iam_member" "runtime_trace_agent" {
  project = var.project
  role    = "roles/cloudtrace.agent"
  member  = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

resource "google_project_iam_member" "runtime_telemetry_traces_writer" {
  project = var.project
  role    = "roles/telemetry.tracesWriter"
  member  = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

resource "google_project_iam_member" "runtime_service_usage_consumer" {
  project = var.project
  role    = "roles/serviceusage.serviceUsageConsumer"
  member  = "serviceAccount:${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}
