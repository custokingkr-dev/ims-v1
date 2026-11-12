mock_provider "google" {}

variables {
  project                        = "custoking-dev"
  env                            = "dev"
  region                         = "asia-south2"
  discover_cloud_run_urls        = false
  services                       = []
  enable_cost_metric_export      = true
  enable_cost_analysis_collector = true
  enable_product_liveness_check  = true
  enable_spend_anomaly_alerts    = true
}

# On 2026-11-03 upstream's google/cloud-sdk:slim lost python3. All three jobs re-resolved the floating tag
# and failed on every run, in dev and prod, and the liveness job hid it behind `|| true`.
run "script_jobs_run_a_pinned_image_and_report_failure" {
  command = plan

  assert {
    condition = alltrue([
      for image in [
        google_cloud_run_v2_job.cost_metric_exporter[0].template[0].template[0].containers[0].image,
        google_cloud_run_v2_job.cost_analysis[0].template[0].template[0].containers[0].image,
        google_cloud_run_v2_job.product_liveness[0].template[0].template[0].containers[0].image,
      ] : can(regex("^google/cloud-sdk:[0-9]+\\.[0-9]+\\.[0-9]+-slim@sha256:[0-9a-f]{64}$", image))
    ])
    error_message = "Every script job must run google/cloud-sdk pinned to a version and digest, never a floating tag."
  }

  assert {
    condition     = !strcontains(join(" ", google_cloud_run_v2_job.product_liveness[0].template[0].template[0].containers[0].args), "|| true")
    error_message = "The liveness job must let a failed check fail the execution."
  }
}

# The exporter feed died on 2026-11-03; billing-export-stale closed itself two days later and
# daily-spend-jump, treating no data as fine, never opened.
run "spend_alerts_treat_a_dead_exporter_as_a_problem" {
  command = plan

  assert {
    condition     = google_monitoring_alert_policy.daily_spend_jump[0].conditions[0].condition_threshold[0].evaluation_missing_data == "EVALUATION_MISSING_DATA_ACTIVE"
    error_message = "daily-spend-jump must count missing exporter data as firing."
  }

  assert {
    condition = alltrue([
      for condition in google_monitoring_alert_policy.billing_export_stale[0].conditions :
      condition.condition_threshold[0].evaluation_missing_data == "EVALUATION_MISSING_DATA_ACTIVE"
    ])
    error_message = "billing-export-stale must count missing exporter data as firing."
  }

  assert {
    condition     = google_monitoring_alert_policy.billing_export_stale[0].alert_strategy[0].auto_close == "604800s"
    error_message = "billing-export-stale must stay open for a week with no data, not close after a day."
  }

  assert {
    condition     = google_monitoring_alert_policy.cloud_run_instance_time_runaway[0].conditions[0].condition_threshold[0].evaluation_missing_data == "EVALUATION_MISSING_DATA_INACTIVE"
    error_message = "No instance time is a healthy scale-to-zero night, not a runaway."
  }
}
