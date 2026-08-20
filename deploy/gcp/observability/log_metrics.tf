locals {
  outbox_service_name_regex       = "custoking-(school-core-service|operations-service|billing-service)-${var.env}"
  notification_service_name_regex = "custoking-platform-service-${var.env}"
}

# NOTE ON THE BUCKETS BELOW
#
# These five carry gauges, not counts, and dashboards read them through a percentile. A percentile over a
# distribution is INTERPOLATED WITHIN ITS BUCKET, so bucket width IS the precision of the number shown.
# Against the old count bounds [0,1,5,10,25,...] a dead-letter count of 1 fell in [1,5) and rendered as
# roughly 4.8: a permanently idle queue displayed as a small standing backlog. The old age bounds began
# at 0, so a fully drained queue reported an oldest-pending age of nearly 30 seconds.
#
# The gauge bounds sit on half-integers, so an integer N falls mid-bucket and a percentile recovers it.
# The fine age bounds start above zero so an empty queue reads as empty.
#
# Alert thresholds on these were tuned against the inflated values and will now see smaller, correct
# numbers. They should fire less often -- that is the correction landing, not the alerting weakening.

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
      bounds = var.gauge_metric_buckets
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
      bounds = var.gauge_metric_buckets
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
      bounds = var.async_age_metric_buckets_fine
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
      bounds = var.gauge_metric_buckets
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
      bounds = var.gauge_metric_buckets
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


  # Without this, an incident that opens never closes on its own -- and because Cloud Monitoring notifies
  # on incident CREATION only, a latched-open incident silently swallows every later occurrence.
  alert_strategy {
    auto_close = "1800s"
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


  # Without this, an incident that opens never closes on its own -- and because Cloud Monitoring notifies
  # on incident CREATION only, a latched-open incident silently swallows every later occurrence.
  alert_strategy {
    auto_close = "1800s"
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


  # Without this, an incident that opens never closes on its own -- and because Cloud Monitoring notifies
  # on incident CREATION only, a latched-open incident silently swallows every later occurrence.
  alert_strategy {
    auto_close = "1800s"
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


  # Without this, an incident that opens never closes on its own -- and because Cloud Monitoring notifies
  # on incident CREATION only, a latched-open incident silently swallows every later occurrence.
  alert_strategy {
    auto_close = "1800s"
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


  # Without this, an incident that opens never closes on its own -- and because Cloud Monitoring notifies
  # on incident CREATION only, a latched-open incident silently swallows every later occurrence.
  alert_strategy {
    auto_close = "1800s"
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
      bounds = var.gauge_metric_buckets
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
      bounds = var.gauge_metric_buckets
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
      bounds = var.gauge_metric_buckets
    }
  }
}

# ---------------------------------------------------------------------------------------------------
# Platform business counts (product dashboard)
#
# Emitted by school-core-service, which owns both the tenant_school and student schemas. It counts across
# every tenant deliberately -- "how many schools are on the platform" belongs to the platform, not to any
# one school -- which is why the reporter carries an explicit RLS bypass. Without it the runtime role sees
# zero, and the charts would show an empty platform with no error anywhere.
# ---------------------------------------------------------------------------------------------------

locals {
  school_core_service_name = "custoking-school-core-service-${var.env}"

  # Dense where exactness matters, coarse where only the trend does. Half-integer bounds mean an integer N
  # falls mid-bucket, so a percentile recovers it rather than reporting the bucket edge.
  count_gauge_buckets = concat(
    [for i in range(0, 31) : i + 0.5],        # 0.5 .. 30.5  step 1
    [for i in range(16, 51) : i * 2 + 0.5],   # 32.5 .. 100.5 step 2
    [for i in range(11, 51) : i * 10 + 0.5],  # 110.5 .. 500.5 step 10
    [for i in range(11, 41) : i * 50 + 0.5],  # 550.5 .. 2000.5 step 50
    [for i in range(11, 51) : i * 200 + 0.5], # 2200.5 .. 10000.5 step 200
  )
}

resource "google_logging_metric" "platform_schools" {
  project     = var.project
  name        = "custoking/${var.env}/platform_schools"
  description = "Schools provisioned on the platform."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.school_core_service_name}\"",
    "jsonPayload.health.platform.schools:*",
  ])
  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Schools"
  }
  value_extractor = "EXTRACT(jsonPayload.health.platform.schools)"
  bucket_options {
    explicit_buckets {
      bounds = local.count_gauge_buckets
    }
  }
}

resource "google_logging_metric" "platform_schools_with_students" {
  project     = var.project
  name        = "custoking/${var.env}/platform_schools_with_students"
  description = "Schools that actually hold students. Reach rather than inventory -- the gap against total schools is provisioned-but-unused."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.school_core_service_name}\"",
    "jsonPayload.health.platform.schoolsWithStudents:*",
  ])
  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Schools with students"
  }
  value_extractor = "EXTRACT(jsonPayload.health.platform.schoolsWithStudents)"
  bucket_options {
    explicit_buckets {
      bounds = local.count_gauge_buckets
    }
  }
}

resource "google_logging_metric" "platform_students" {
  project     = var.project
  name        = "custoking/${var.env}/platform_students"
  description = "Live students, excluding soft-deleted records."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.school_core_service_name}\"",
    "jsonPayload.health.platform.studentsLive:*",
  ])
  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Students"
  }
  value_extractor = "EXTRACT(jsonPayload.health.platform.studentsLive)"
  bucket_options {
    explicit_buckets {
      bounds = local.count_gauge_buckets
    }
  }
}

resource "google_logging_metric" "platform_sections" {
  project     = var.project
  name        = "custoking/${var.env}/platform_sections"
  description = "Class sections configured across all schools."
  filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=\"${local.school_core_service_name}\"",
    "jsonPayload.health.platform.sections:*",
  ])
  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "1"
    display_name = "Sections"
  }
  value_extractor = "EXTRACT(jsonPayload.health.platform.sections)"
  bucket_options {
    explicit_buckets {
      bounds = local.count_gauge_buckets
    }
  }
}
