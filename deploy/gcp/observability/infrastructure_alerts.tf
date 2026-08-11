locals {
  cloud_sql_instance_name = var.cloud_sql_instance_name != "" ? var.cloud_sql_instance_name : "custoking-db-${var.env}"
  cloud_sql_database_id   = "${var.project}:${local.cloud_sql_instance_name}"
  pubsub_subscription_ids = length(var.pubsub_subscription_ids) > 0 ? toset(var.pubsub_subscription_ids) : toset([
    "ims-reporting-service-push-${var.env}",
    "ims-notification-service-push-${var.env}",
  ])
}

resource "google_monitoring_alert_policy" "cloud_sql_cpu" {
  project               = var.project
  display_name          = "custoking-${var.env}-cloud-sql-cpu"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "Cloud SQL CPU above ${var.cloud_sql_cpu_threshold * 100}%"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloudsql_database\"",
        "resource.labels.database_id=\"${local.cloud_sql_database_id}\"",
        "metric.type=\"cloudsql.googleapis.com/database/cpu/utilization\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.cloud_sql_cpu_threshold
      duration        = "300s"
      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
  documentation {
    content   = "Cloud SQL CPU is above the tested 80% stop boundary. Stop load growth and inspect slow queries before increasing compute."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}
resource "google_monitoring_alert_policy" "cloud_sql_memory" {
  project               = var.project
  display_name          = "custoking-${var.env}-cloud-sql-memory"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "Cloud SQL memory usage above ${var.cloud_sql_memory_threshold}%"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloudsql_database\"",
        "resource.labels.database_id=\"${local.cloud_sql_database_id}\"",
        "metric.type=\"cloudsql.googleapis.com/database/memory/components\"",
        "metric.labels.component=\"Usage\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.cloud_sql_memory_threshold
      duration        = "600s"
      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
  documentation {
    content   = "Cloud SQL process memory usage (excluding cache and free memory) is sustained above the configured percentage. Inspect working-set growth, temporary files, query plans, and OOM logs before resizing."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "cloud_sql_connections" {
  project               = var.project
  display_name          = "custoking-${var.env}-cloud-sql-connections"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "PostgreSQL backends above ${var.cloud_sql_connection_threshold}"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"cloudsql_database\"",
        "resource.labels.database_id=\"${local.cloud_sql_database_id}\"",
        "metric.type=\"cloudsql.googleapis.com/database/postgresql/num_backends\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.cloud_sql_connection_threshold
      duration        = "300s"
      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_SUM"
      }
    }
  }
  documentation {
    content   = "PostgreSQL connections crossed the 70% guardrail. Check Cloud Run instance counts and Hikari pool saturation before raising max_connections."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "pubsub_backlog" {
  for_each = local.pubsub_subscription_ids

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-backlog"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "Pub/Sub backlog above ${var.pubsub_backlog_message_threshold} messages"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"pubsub_subscription\"",
        "resource.labels.subscription_id=\"${each.key}\"",
        "metric.type=\"pubsub.googleapis.com/subscription/num_undelivered_messages\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.pubsub_backlog_message_threshold
      duration        = "300s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
    }
  }
  documentation {
    content   = "Pub/Sub backlog is growing for ${each.key}. Inspect authenticated push responses, Cloud Run readiness, and the dead-letter subscription."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "pubsub_oldest_unacked" {
  for_each = local.pubsub_subscription_ids

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-oldest-unacked"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "Oldest Pub/Sub message above ${var.pubsub_oldest_unacked_age_threshold_seconds}s"
    condition_threshold {
      filter = join(" AND ", [
        "resource.type=\"pubsub_subscription\"",
        "resource.labels.subscription_id=\"${each.key}\"",
        "metric.type=\"pubsub.googleapis.com/subscription/oldest_unacked_message_age\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.pubsub_oldest_unacked_age_threshold_seconds
      duration        = "300s"
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_MAX"
      }
    }
  }
  documentation {
    content   = "A Pub/Sub message has remained unacknowledged for more than five minutes on ${each.key}. Inspect push status classes and dead-letter forwarding."
    mime_type = "text/markdown"
  }
  alert_strategy { auto_close = "3600s" }
  user_labels = local.common_user_labels
}
