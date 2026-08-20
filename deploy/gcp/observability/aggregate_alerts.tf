# ---------------------------------------------------------------------------------------------------
# Aggregate alerts
#
# One policy covering every service, replacing seven per-service ones. Two reasons, and the second
# matters more than the noise reduction.
#
# 1. A single upstream failure fans out to seven separate emails, which is how an inbox becomes
#    something you filter rather than read.
#
# 2. The per-service 5xx policies are RATIO conditions computed from
#    run.googleapis.com/request_count -- a metric that INCLUDES uptime-probe traffic. At roughly 600
#    real requests a day against ~3,456 synthetic probes, those policies are mostly measuring the
#    prober. This one counts real 5xx from the gateway's own request log, whose metric filter already
#    excludes /gateway-health, so it sees what users saw and nothing else.
#
# Deliberately an ABSOLUTE COUNT rather than a ratio. At this traffic volume a ratio is dominated by
# whichever handful of requests happened to arrive: overnight, a five-minute window can hold two
# requests, so a single failure reads as a 50% error rate. Five real server errors in fifteen minutes
# is the same thing whether the denominator was ten or ten thousand.
# ---------------------------------------------------------------------------------------------------

resource "google_monitoring_alert_policy" "gateway_5xx_aggregate" {
  count = var.enable_aggregate_error_alert ? 1 : 0

  project               = var.project
  display_name          = "custoking-${var.env}-server-errors-users-hit"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "real 5xx responses above ${var.aggregate_5xx_threshold} in 15 minutes"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.gateway_requests_by_feature.name}\"",
        "resource.type=\"cloud_run_revision\"",
        "metric.label.status_class=\"5\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.aggregate_5xx_threshold
      duration        = "0s"

      aggregations {
        alignment_period     = "900s"
        per_series_aligner   = "ALIGN_DELTA"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = <<-DOC
      Real users received server errors. Probe traffic is excluded, so every counted request was
      somebody trying to do their job.

      This is deliberately an absolute count, not a rate. At this volume a rate is meaningless -- a
      single failure in a quiet window reads as a catastrophic percentage.

      First question is not "which service", it is "is this still happening". Check the Live Operations
      dashboard, then `gcloud logging read 'jsonPayload.message="gateway.request" AND
      jsonPayload.status>=500' --project=${var.project} --freshness=1h` for the actual paths.

      If a release went out in the last hour, roll it back before diagnosing -- see docs/runbooks/3am.md.
    DOC
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "1800s"
  }

  user_labels = local.common_user_labels
}
