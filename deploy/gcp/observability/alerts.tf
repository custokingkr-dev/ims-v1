resource "google_monitoring_alert_policy" "service_5xx_rate" {
  for_each = local.service_names

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-5xx-rate"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "5xx request ratio above ${var.error_rate_threshold}"

    condition_threshold {
      filter             = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_count\" AND metric.labels.response_code_class=\"5xx\""
      denominator_filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_count\""
      comparison         = "COMPARISON_GT"
      threshold_value    = var.error_rate_threshold
      duration           = "300s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_RATE"
        cross_series_reducer = "REDUCE_SUM"
      }

      denominator_aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_RATE"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "5xx ratio is above threshold for ${local.service_names[each.key]}. Check Cloud Run logs, linked traces, and the service dashboard."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "3600s"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "service_p95_latency" {
  for_each = local.service_names

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-p95-latency"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "p95 request latency above ${var.latency_p95_threshold_ms}ms"

    condition_threshold {
      filter          = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_latencies\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.latency_p95_threshold_ms
      duration        = "300s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_PERCENTILE_95"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Cloud Run p95 request latency is above threshold for ${local.service_names[each.key]}. Open Cloud Trace for recent slow requests and compare DB spans."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "3600s"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "service_instance_saturation" {
  for_each = local.service_names

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-max-instance-saturation"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "active instances near configured max"

    condition_threshold {
      filter          = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/container/instance_count\" AND metric.labels.state=\"active\""
      comparison      = "COMPARISON_GT"
      threshold_value = local.service_max_instances[each.key] * var.max_instance_saturation_ratio
      duration        = "300s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Active Cloud Run instances are near the configured max for ${local.service_names[each.key]}. Check traffic, latency, concurrency, and whether the max instance cap should change."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "3600s"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "uptime_failure" {
  for_each = local.uptime_check_ids

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-uptime"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "uptime probe failing"

    condition_threshold {
      filter          = "metric.type=\"monitoring.googleapis.com/uptime_check/check_passed\" AND metric.labels.check_id=\"${each.value}\" AND resource.type=\"${local.uptime_check_resource_types[each.key]}\""
      comparison      = "COMPARISON_LT"
      threshold_value = 1
      duration        = "300s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_FRACTION_TRUE"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "The health endpoint uptime check is failing for ${local.service_names[each.key]}. Confirm Cloud Run revision readiness and invoke the health endpoint with the operator identity."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "3600s"
  }

  user_labels = local.common_user_labels
}
