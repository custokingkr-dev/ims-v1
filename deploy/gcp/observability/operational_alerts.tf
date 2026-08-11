locals {
  operational_storage_bucket_ids = length(var.storage_bucket_ids) > 0 ? toset(var.storage_bucket_ids) : toset([
    "custoking-student-photos-${var.env}",
  ])
}

resource "google_logging_metric" "scheduler_failure_count" {
  project     = var.project
  name        = "custoking/${var.env}/async_scheduler_failure_count"
  description = "Count of failed Cloud Scheduler attempts for the authenticated async relay jobs."
  filter = join(" AND ", [
    "resource.type=\"cloud_scheduler_job\"",
    "resource.labels.job_id=~\"ims-(school-core-service|operations-service|billing-service|platform-service)-async-relay-${var.env}\"",
    "jsonPayload.\"@type\"=\"type.googleapis.com/google.cloud.scheduler.logging.AttemptFinished\"",
    "severity>=ERROR",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    unit         = "1"
    display_name = "Async Scheduler failed attempts"
  }
}

resource "google_monitoring_alert_policy" "scheduler_failure" {
  project               = var.project
  display_name          = "custoking-${var.env}-async-scheduler-failure"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "Authenticated async Scheduler attempt failed"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloud_scheduler_job\"",
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.scheduler_failure_count.name}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger { count = 1 }
    }
  }

  documentation {
    content   = "An authenticated async relay Scheduler attempt failed. Inspect the AttemptFinished log, HTTP status, OIDC audience/invoker binding, target readiness, and database state. Dev jobs are intentionally paused while dev SQL is stopped."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "1800s" }
  user_labels = local.common_user_labels
}

resource "google_logging_metric" "trace_export_failure_count" {
  project     = var.project
  name        = "custoking/${var.env}/trace_export_failure_count"
  description = "Count of explicit OpenTelemetry/OTLP span export failures from Custoking Cloud Run services."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=~\"custoking-.*-${var.env}\"",
    "severity>=ERROR",
    "(textPayload=~\"(?i)(failed to export.*span|otlp.*export.*fail|span.*export.*fail)\" OR jsonPayload.message=~\"(?i)(failed to export.*span|otlp.*export.*fail|span.*export.*fail)\")",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    unit         = "1"
    display_name = "Trace export failures"
  }
}

resource "google_monitoring_alert_policy" "trace_export_failure" {
  project               = var.project
  display_name          = "custoking-${var.env}-trace-export-failure"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "OpenTelemetry span export failed"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloud_run_revision\"",
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.trace_export_failure_count.name}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger { count = 1 }
    }
  }

  documentation {
    content   = "A Cloud Run service logged an explicit OTLP/span export failure. Check exporter credentials, endpoint reachability, quota and sampling configuration; application requests may still succeed while trace coverage is degraded."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "1800s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "storage_growth" {
  for_each = local.operational_storage_bucket_ids

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-storage-growth"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "Bucket storage above ${var.storage_total_bytes_threshold} bytes"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"gcs_bucket\"",
        "resource.labels.bucket_name=\"${each.key}\"",
        "metric.type=\"storage.googleapis.com/storage/v2/total_bytes\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.storage_total_bytes_threshold
      duration        = "86400s"

      aggregations {
        alignment_period     = "86400s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger { count = 1 }
    }
  }

  documentation {
    content   = "Bucket ${each.key} exceeded the approved storage guardrail for a full daily measurement period. Inspect per-school object growth, unfinished imports, noncurrent/soft-deleted objects and lifecycle coverage before raising the threshold."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "86400s" }
  user_labels = local.common_user_labels
}
