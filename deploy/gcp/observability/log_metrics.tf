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

resource "google_logging_metric" "notification_inbox_dead_letter_count" {
  project     = var.project
  name        = "custoking/${var.env}/notification_inbox_dead_letter_count"
  description = "Distribution of terminal notification dead-letter counts emitted by platform-service health logs."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=~\"${local.notification_service_name_regex}\"",
    "jsonPayload.health.notificationInbox.deadLetterCount:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Notification inbox dead-letter count"
  }

  value_extractor = "EXTRACT(jsonPayload.health.notificationInbox.deadLetterCount)"

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
  notification_channels = local.effective_notification_channel_ids
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
  notification_channels = local.effective_notification_channel_ids
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
  notification_channels = local.effective_notification_channel_ids
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
  notification_channels = local.effective_notification_channel_ids
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

resource "google_monitoring_alert_policy" "notification_inbox_dead_letter" {
  project               = var.project
  display_name          = "custoking-${var.env}-notification-inbox-dead-letter"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "notification dead-letter count above ${var.notification_inbox_dead_letter_threshold}"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.notification_inbox_dead_letter_count.name}\"",
        "resource.type=\"cloud_run_revision\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.notification_inbox_dead_letter_threshold
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
    content   = "Notification delivery exhausted all retries. Inspect the event status and provider response before replaying or replacing the event."
    mime_type = "text/markdown"
  }

  user_labels = local.common_user_labels
}

# ---------------------------------------------------------------------------------------------------
# Live operations metrics
#
# These back the "Live Operations" dashboard. Two of them extract from logs the gateway already writes,
# so they cost nothing to collect and needed no application change; the session metrics come from a
# structured health log emitted by identity-service, following the same shape as the outbox metrics
# above because Cloud Monitoring cannot query Postgres directly.
# ---------------------------------------------------------------------------------------------------

locals {
  gateway_service_name  = "custoking-api-gateway-${var.env}"
  identity_service_name = "custoking-identity-service-${var.env}"
}


resource "google_logging_metric" "gateway_requests_by_feature" {
  project     = var.project
  name        = "custoking/${var.env}/gateway_requests_by_feature"
  description = "Gateway request count labelled by the upstream the gateway actually routed to."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.gateway_service_name}\"",
    "jsonPayload.message=\"gateway.request\"",
    # Health checks are answered before routing, so they carry no upstream and would appear as a large
    # unlabelled series. Uptime probes alone are six polls a minute -- enough to swamp real traffic on a
    # per-feature chart and make the application look busier than it is.
    "NOT jsonPayload.path=\"/gateway-health\"",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    unit         = "1"
    display_name = "Gateway requests by feature"

    labels {
      key         = "feature"
      value_type  = "STRING"
      description = "Upstream the gateway routed to: tenant, student, identity, fee, billing, reporting, attendance, catalog, firefighting or workflow."
    }

    labels {
      key         = "status_class"
      value_type  = "STRING"
      description = "HTTP status class: 2, 3, 4 or 5."
    }

    labels {
      key         = "method"
      value_type  = "STRING"
      description = "HTTP method."
    }
  }

  # upstreamService is the gateway's OWN routing decision, not a guess parsed from the path. Deriving
  # the feature from the URL would be wrong here: every real API call is /api/v1/... regardless of which
  # domain serves it, so a path-prefix label would collapse the entire application into one bucket.
  label_extractors = {
    feature = "EXTRACT(jsonPayload.upstreamService)"
    # Bucketing to a class keeps cardinality bounded; the exact code stays in the logs for triage.
    status_class = "REGEXP_EXTRACT(jsonPayload.status, \"^([0-9])\")"
    method       = "EXTRACT(jsonPayload.method)"
  }
}

resource "google_logging_metric" "gateway_latency_by_feature" {
  project     = var.project
  name        = "custoking/${var.env}/gateway_latency_by_feature"
  description = "Distribution of gateway-measured request duration in milliseconds, labelled by upstream."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.gateway_service_name}\"",
    "jsonPayload.message=\"gateway.request\"",
    "jsonPayload.durationMs:*",
    "NOT jsonPayload.path=\"/gateway-health\"",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Gateway latency by feature"

    labels {
      key         = "feature"
      value_type  = "STRING"
      description = "Upstream the gateway routed to."
    }
  }

  # durationMs is already numeric in the payload, so this needs no parsing. Note it is the GATEWAY's
  # view of the request, which includes upstream time plus gateway overhead -- that is what a user
  # experiences, and it is the number worth alerting on.
  value_extractor = "EXTRACT(jsonPayload.durationMs)"

  label_extractors = {
    feature = "EXTRACT(jsonPayload.upstreamService)"
  }

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 20
      growth_factor      = 2
      scale              = 1
    }
  }
}

resource "google_logging_metric" "session_active_users" {
  project     = var.project
  name        = "custoking/${var.env}/session_active_users"
  description = "Distinct users holding an unexpired ACTIVE session, from identity-service health logs."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.identity_service_name}\"",
    "jsonPayload.health.sessions.activeUsers:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Active users"
  }

  value_extractor = "EXTRACT(jsonPayload.health.sessions.activeUsers)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_count_metric_buckets
    }
  }
}

resource "google_logging_metric" "session_active_sessions" {
  project     = var.project
  name        = "custoking/${var.env}/session_active_sessions"
  description = "Unexpired ACTIVE sessions. Exceeds active users when one person is signed in on several devices."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.identity_service_name}\"",
    "jsonPayload.health.sessions.activeSessions:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Active sessions"
  }

  value_extractor = "EXTRACT(jsonPayload.health.sessions.activeSessions)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_count_metric_buckets
    }
  }
}

resource "google_logging_metric" "session_logins_recent" {
  project     = var.project
  name        = "custoking/${var.env}/session_logins_recent"
  description = "Sessions created in the last 15 minutes, as a live sign-in rate."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.identity_service_name}\"",
    "jsonPayload.health.sessions.loginsLast15m:*",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Logins in last 15 minutes"
  }

  value_extractor = "EXTRACT(jsonPayload.health.sessions.loginsLast15m)"

  bucket_options {
    explicit_buckets {
      bounds = var.async_count_metric_buckets
    }
  }
}
