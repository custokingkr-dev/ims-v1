locals {
  attendance_storage_metric_filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"custoking-school-core-service-${var.env}\"",
  ])

  # Threshold-adjacent half-unit bounds keep percentile interpolation from moving a sample across the
  # 10M/20M operational boundaries. Log-based numeric extraction only supports distributions, not gauges.
  attendance_row_buckets = [
    0.5,
    100000.5,
    999999.5,
    1000000.5,
    var.attendance_partition_prepare_rows - 0.5,
    var.attendance_partition_prepare_rows + 0.5,
    var.attendance_partition_execute_rows - 0.5,
    var.attendance_partition_execute_rows + 0.5,
    24999999.5,
    25000000.5,
    50000000.5,
    100000000.5,
  ]
}

resource "google_logging_metric" "attendance_rows" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project     = var.project
  name        = "custoking/${var.env}/attendance_approximate_rows"
  description = "Planner/autovacuum row estimate across the attendance fact table or all of its partitions."
  filter      = "${local.attendance_storage_metric_filter} AND jsonPayload.health.attendanceStorage.approximateRows:*"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Attendance approximate rows"
  }
  value_extractor = "EXTRACT(jsonPayload.health.attendanceStorage.approximateRows)"
  bucket_options {
    explicit_buckets { bounds = local.attendance_row_buckets }
  }

  lifecycle {
    precondition {
      condition     = var.attendance_partition_prepare_rows < var.attendance_partition_execute_rows
      error_message = "The attendance preparation threshold must be lower than the execution threshold."
    }
  }
}

resource "google_logging_metric" "attendance_index_bytes" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project     = var.project
  name        = "custoking/${var.env}/attendance_index_bytes"
  description = "Total index bytes across the attendance fact table or all of its partitions."
  filter      = "${local.attendance_storage_metric_filter} AND jsonPayload.health.attendanceStorage.indexBytes:*"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "By"
    display_name = "Attendance index bytes"
  }
  value_extractor = "EXTRACT(jsonPayload.health.attendanceStorage.indexBytes)"
  bucket_options {
    explicit_buckets {
      bounds = [
        0.5,
        1073741824.5,
        4294967296.5,
        var.attendance_index_bytes_threshold - 0.5,
        var.attendance_index_bytes_threshold + 0.5,
        17179869184.5,
        34359738368.5,
      ]
    }
  }
}

resource "google_logging_metric" "attendance_full_scan_equivalents" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project     = var.project
  name        = "custoking/${var.env}/attendance_full_scan_equivalents_milli"
  description = "Sequential tuples read per five-minute reporter interval divided by estimated rows, in thousandths."
  filter      = "${local.attendance_storage_metric_filter} AND jsonPayload.health.attendanceStorage.fullTableScanEquivalentsMilli:*"

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Attendance sequential full-scan equivalents (milli)"
  }
  value_extractor = "EXTRACT(jsonPayload.health.attendanceStorage.fullTableScanEquivalentsMilli)"
  bucket_options {
    explicit_buckets {
      bounds = [
        0.5,
        100.5,
        500.5,
        var.attendance_full_scan_equivalents_milli_threshold - 0.5,
        var.attendance_full_scan_equivalents_milli_threshold + 0.5,
        2000.5,
        5000.5,
        10000.5,
      ]
    }
  }
}

resource "google_monitoring_alert_policy" "attendance_partition_prepare" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project               = var.project
  display_name          = "custoking-${var.env}-attendance-partition-prepare"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"
  conditions {
    display_name = "Attendance rows above ${var.attendance_partition_prepare_rows}"
    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.attendance_rows[0].name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.attendance_partition_prepare_rows
      duration        = "900s"
      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
  documentation {
    content   = "DATA-01 preparation threshold reached. Refresh ANALYZE, verify the estimate and query plans, then execute the reviewed preflight/rehearsal. This alert does not authorize production DDL."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "attendance_partition_execute" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project               = var.project
  display_name          = "custoking-${var.env}-attendance-partition-execute"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"
  conditions {
    display_name = "Attendance rows above ${var.attendance_partition_execute_rows}"
    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.attendance_rows[0].name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.attendance_partition_execute_rows
      duration        = "900s"
      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
  documentation {
    content   = "DATA-01 execution threshold reached before the 25M hard planning boundary. Schedule the approved maintenance/freeze rollout; do not run cutover from an application Flyway startup."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "attendance_index_growth" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project               = var.project
  display_name          = "custoking-${var.env}-attendance-index-growth"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"
  conditions {
    display_name = "Attendance indexes above ${var.attendance_index_bytes_threshold} bytes"
    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.attendance_index_bytes[0].name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.attendance_index_bytes_threshold
      duration        = "1800s"
      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
  documentation {
    content   = "Attendance index storage crossed the review threshold. Inspect pg_stat_user_indexes for unused/duplicate structures and write amplification; do not drop an index without production query-plan evidence."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "attendance_sequential_scan" {
  count = var.enable_attendance_growth_monitoring ? 1 : 0

  project               = var.project
  display_name          = "custoking-${var.env}-attendance-sequential-scan"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"
  conditions {
    display_name = "Attendance sequential reads exceed one full-table equivalent per interval"
    condition_threshold {
      filter          = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.attendance_full_scan_equivalents[0].name}\" AND resource.type=\"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = var.attendance_full_scan_equivalents_milli_threshold
      duration        = "900s"
      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
  documentation {
    content   = "Attendance sequential tuple reads exceeded the configured full-table equivalent for three intervals. Correlate with row volume and EXPLAIN (ANALYZE, BUFFERS); maintenance queries can legitimately scan and should be annotated before silencing."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}
