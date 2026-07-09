locals {
  outbox_service_name_regex       = "custoking-(school-core-service|operations-service|billing-service)-${var.env}"
  notification_service_name_regex = "custoking-platform-service-${var.env}"
}

resource "google_logging_metric" "outbox_pending_count" {
  project     = var.project
  name        = "custoking/${var.env}/outbox_pending_count"
  description = "Distribution of structured outbox pending counts emitted by domain-service health logs."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=~\"${local.outbox_service_name_regex}\"",
    "jsonPayload.health.outbox.pendingCount:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Outbox pending count"
  }

  value_extractor = "EXTRACT(jsonPayload.health.outbox.pendingCount)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_count_metric_buckets
    }
  }
}

resource "google_logging_metric" "outbox_dead_letter_count" {
  project     = var.project
  name        = "custoking/${var.env}/outbox_dead_letter_count"
  description = "Distribution of structured outbox dead-letter counts emitted by domain-service health logs."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=~\"${local.outbox_service_name_regex}\"",
    "jsonPayload.health.outbox.deadLetterCount:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Outbox dead-letter count"
  }

  value_extractor = "EXTRACT(jsonPayload.health.outbox.deadLetterCount)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_count_metric_buckets
    }
  }
}

resource "google_logging_metric" "outbox_oldest_pending_age_seconds" {
  project     = var.project
  name        = "custoking/${var.env}/outbox_oldest_pending_age_seconds"
  description = "Distribution of structured oldest pending outbox ages emitted by domain-service health logs."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=~\"${local.outbox_service_name_regex}\"",
    "jsonPayload.health.outbox.oldestPendingAgeSeconds:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "s"
    display_name = "Outbox oldest pending age"
  }

  value_extractor = "EXTRACT(jsonPayload.health.outbox.oldestPendingAgeSeconds)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_age_metric_buckets
    }
  }
}

resource "google_logging_metric" "notification_inbox_backlog_count" {
  project     = var.project
  name        = "custoking/${var.env}/notification_inbox_backlog_count"
  description = "Distribution of structured notification inbox backlog counts emitted by platform-service health logs."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=~\"${local.notification_service_name_regex}\"",
    "jsonPayload.health.notificationInbox.backlogCount:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Notification inbox backlog count"
  }

  value_extractor = "EXTRACT(jsonPayload.health.notificationInbox.backlogCount)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_count_metric_buckets
    }
  }
}

resource "google_monitoring_alert_policy" "outbox_pending" {
  project               = var.project
  display_name          = "custoking-${var.env}-outbox-pending"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "outbox pending count above ${var.outbox_pending_threshold}"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_pending_count.name}\"",
        "resource.type=\"cloud_run_revision\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.outbox_pending_threshold
      duration        = "600s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Outbox pending count is above threshold. Check relay logs, Pub/Sub publish errors, and oldest pending age."
    mime_type = "text/markdown"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "outbox_dead_letter" {
  project               = var.project
  display_name          = "custoking-${var.env}-outbox-dead-letter"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "outbox dead-letter count above ${var.outbox_dead_letter_threshold}"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_dead_letter_count.name}\"",
        "resource.type=\"cloud_run_revision\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.outbox_dead_letter_threshold
      duration        = "300s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Outbox dead-letter count is non-zero. Inspect the owning service logs and replay or repair the failed events before they age out."
    mime_type = "text/markdown"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "outbox_oldest_pending_age" {
  project               = var.project
  display_name          = "custoking-${var.env}-outbox-oldest-pending-age"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "oldest pending outbox age above ${var.outbox_oldest_age_seconds_threshold}s"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_oldest_pending_age_seconds.name}\"",
        "resource.type=\"cloud_run_revision\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.outbox_oldest_age_seconds_threshold
      duration        = "600s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Oldest pending outbox age is above threshold. Confirm scheduled relay instances are alive and Pub/Sub accepts publishes."
    mime_type = "text/markdown"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "notification_inbox_backlog" {
  project               = var.project
  display_name          = "custoking-${var.env}-notification-inbox-backlog"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "notification inbox backlog above ${var.notification_inbox_backlog_threshold}"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.notification_inbox_backlog_count.name}\"",
        "resource.type=\"cloud_run_revision\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.notification_inbox_backlog_threshold
      duration        = "600s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Notification inbox backlog is above threshold. Check Pub/Sub push delivery, provider failures, and platform-service retry logs."
    mime_type = "text/markdown"
  }

  user_labels = local.common_user_labels
}
