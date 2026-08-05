output "cloud_run_service_names" {
  description = "Environment-suffixed Cloud Run service names covered by this Terraform root."
  value       = local.service_names
}

output "service_dashboard_ids" {
  description = "Cloud Monitoring dashboard IDs for per-service golden-signal dashboards."
  value       = { for service, dashboard in google_monitoring_dashboard.service : service => dashboard.id }
}

output "async_health_dashboard_id" {
  description = "Cloud Monitoring dashboard ID for async health."
  value       = google_monitoring_dashboard.async_health.id
}

output "uptime_check_ids" {
  description = "Uptime check IDs keyed by service."
  value       = local.uptime_check_ids
}

output "availability_slo_names" {
  description = "Full Cloud Monitoring availability SLO resource names keyed by service."
  value       = { for service, slo in google_monitoring_slo.availability : service => slo.name }
}

output "latency_slo_names" {
  description = "Full Cloud Monitoring latency SLO resource names keyed by service."
  value       = { for service, slo in google_monitoring_slo.latency : service => slo.name }
}

output "notification_channel_names" {
  description = "All existing and Terraform-created notification channels attached to alert policies."
  value       = local.effective_notification_channel_ids
}

output "compliance_log_bucket" {
  description = "India-resident compliance log bucket, when managed by this state."
  value       = try(google_logging_project_bucket_config.compliance_india[0].id, null)
}
