# ---------------------------------------------------------------------------------------------------
# Spend-anomaly alerts
#
# A monthly budget answers "is this month normal" and answers it slowly: Google computes budgets from
# estimated data a few times a day and the first notification can take hours. A runaway resource can
# spend a month's envelope in days, and on a free-trial account that is not a cost event -- credit
# exhaustion ends the trial early and Google stops every resource. So three signals, at three speeds:
#
#   1. daily-spend-jump          -- one calendar day's GROSS spend above a threshold. Read from the
#                                   billing export via cost_metric_job.tf. Sees everything the invoice
#                                   sees, including Artifact Registry egress, which has no resource-side
#                                   metric. Lags the day it describes by ~12 hours.
#   2. billing-export-stale      -- the feed for (1) has stopped or is late. Without this, (1) failing
#                                   silently is indistinguishable from spend being fine, which is the
#                                   same class of failure as the log metrics that collected nothing for
#                                   months. Also the only policy here that fires on ABSENCE of data.
#   3. instance-time-runaway     -- Cloud Run billable instance time, project-wide, per hour. Near real
#                                   time. Cloud Run is the only line item here that can move by an order
#                                   of magnitude within an hour (Cloud SQL is a flat tier, storage is
#                                   tiny); an instance that never scales to zero is 3,600 s/h.
#
# Every condition here sets evaluation_missing_data EXPLICITLY. The default (NO_OP) is the setting
# under which a dead metric looks identical to a healthy one; (1) and (3) want INACTIVE because their
# absence case is owned by (2), and (2) wants ACTIVE because absence IS its condition.
#
# Notifications use local.spend_notification_channel_ids -- the money channels, not the health
# channels -- for the reason given in budget.tf: dev may not page about health and must still be able
# to say it is burning money.
# ---------------------------------------------------------------------------------------------------

locals {
  spend_anomaly_export_enabled = var.enable_spend_anomaly_alerts && var.enable_cost_metric_export ? 1 : 0
  spend_anomaly_enabled        = var.enable_spend_anomaly_alerts ? 1 : 0

  # The exporter labels each series with the project whose spend it carries; dev's exporter reads
  # prod's dataset filtered to dev, so the label is the scope project, not the publishing one.
  cost_metric_scope_label = var.cost_metric_scope_project != "" ? var.cost_metric_scope_project : var.project
}

resource "google_monitoring_alert_policy" "daily_spend_jump" {
  count = local.spend_anomaly_export_enabled

  project               = var.project
  display_name          = "custoking-${var.env}-daily-spend-jump"
  combiner              = "OR"
  notification_channels = local.spend_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "one day's gross spend above INR ${var.daily_spend_alert_inr}"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"custom.googleapis.com/custoking/cost/gross_yesterday\"",
        "resource.type=\"global\"",
        "metric.labels.project_id=\"${local.cost_metric_scope_label}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.daily_spend_alert_inr
      # The gauge is republished hourly and RISES through the day as export rows for "yesterday" land,
      # then drops when the calendar rolls and "yesterday" becomes a new, partial day. One sample over
      # the line is the day's total exceeding the threshold; there is nothing to wait for. The API
      # rejects "0s" whenever evaluation_missing_data is set explicitly ("must have a non-zero
      # duration" -- seen on the 2026-09-11 apply), and 60s is far below the hourly sample interval,
      # so it still fires on the first sample over the line.
      duration = "60s"

      aggregations {
        alignment_period   = "3600s"
        per_series_aligner = "ALIGN_MAX"
      }

      trigger {
        count = 1
      }

      # No data means the exporter is not publishing, which billing-export-stale reports. Firing this
      # one too would be two incidents for one fault.
      evaluation_missing_data = "EVALUATION_MISSING_DATA_INACTIVE"
    }
  }

  documentation {
    content   = <<-DOC
      A single calendar day's GROSS spend for ${local.cost_metric_scope_label} exceeded INR
      ${var.daily_spend_alert_inr}. Gross, not net: the free-trial credit nets everything to zero, which
      is why nobody sees the burn.

      Find the line item first, then the resource:

          bq query --project_id=custoking-prod --use_legacy_sql=false '
          SELECT service.description, sku.description, ROUND(SUM(cost),2) AS inr, ROUND(SUM(usage.amount),2) AS usage, ANY_VALUE(usage.unit) AS unit
          FROM `custoking-prod.billing_export.gcp_billing_export_v1_014C0A_C6B9AF_5FABC0`
          WHERE project.id="${local.cost_metric_scope_label}" AND DATE(usage_start_time) = CURRENT_DATE() - 1
          GROUP BY 1,2 ORDER BY inr DESC LIMIT 15'

      Known causes, in order of likelihood: Artifact Registry "Network Internet Egress" (images pulled
      to GitHub runners -- the scan verdict cache in the release workflow has regressed, or a run was
      forced with the cache cold); Cloud Run "Services CPU Tier 2" (a service that no longer scales to
      zero, or the dev cold-start loop -- see docs/alerting-remediation-plan.md); a Cloud SQL tier change
      or a second instance; a load test.

      This account is on a free trial that ends at the earlier of ${var.trial_expiry_date} and credit
      exhaustion. Check the trial-runway budget's latest notification before deciding this can wait.
    DOC
    mime_type = "text/markdown"
  }

  alert_strategy {
    # The condition clears on its own when the calendar rolls; a day is the natural incident length.
    auto_close = "86400s"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "billing_export_stale" {
  count = local.spend_anomaly_export_enabled

  project               = var.project
  display_name          = "custoking-${var.env}-billing-export-stale"
  combiner              = "OR"
  notification_channels = local.spend_notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "billing export lag above ${var.billing_export_stale_hours}h, or exporter not publishing"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"custom.googleapis.com/custoking/cost/export_lag_hours\"",
        "resource.type=\"global\"",
        "metric.labels.project_id=\"${local.cost_metric_scope_label}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.billing_export_stale_hours
      # Export lag climbs by one every hour between deliveries and resets on each delivery; it has to
      # stay high for a while to mean anything. Two hours of missing points also covers the exporter
      # job itself failing, which publishes nothing rather than a high lag.
      duration = "7200s"

      aggregations {
        alignment_period   = "3600s"
        per_series_aligner = "ALIGN_MAX"
      }

      trigger {
        count = 1
      }

      # THIS is the dead-man switch. If the exporter stops publishing -- job deleted, scheduler
      # disabled, service account lost its BigQuery role, the export table renamed -- there is no data,
      # and no data must open an incident, because the daily-spend-jump policy above is blind for
      # exactly as long as this one is silent.
      evaluation_missing_data = "EVALUATION_MISSING_DATA_ACTIVE"
    }
  }

  conditions {
    display_name = "exporter reports the export as unavailable"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"custom.googleapis.com/custoking/cost/export_available\"",
        "resource.type=\"global\"",
        "metric.labels.project_id=\"${local.cost_metric_scope_label}\"",
      ])
      comparison      = "COMPARISON_LT"
      threshold_value = 1
      duration        = "7200s"

      aggregations {
        alignment_period   = "3600s"
        per_series_aligner = "ALIGN_MAX"
      }

      trigger {
        count = 1
      }

      evaluation_missing_data = "EVALUATION_MISSING_DATA_ACTIVE"
    }
  }

  documentation {
    content   = <<-DOC
      The spend figures for ${local.cost_metric_scope_label} cannot be trusted right now: the billing
      export is more than ${var.billing_export_stale_hours} hours behind, or the exporter job has stopped
      publishing altogether. While this is open, daily-spend-jump is blind.

      Check, in order: the Cloud Scheduler job `cost-metric-export-${var.env}` (asia-south1) is enabled
      and its last run succeeded; the Cloud Run job `ims-cost-metric-${var.env}` has a recent successful
      execution and its logs do not end in a BigQuery permission error; the table
      `custoking-prod.billing_export.gcp_billing_export_v1_014C0A_C6B9AF_5FABC0` has rows with
      export_time in the last day.

      Billing-export configuration is Console-only (no API, no gcloud). If the table has simply stopped
      receiving rows, the fix is Console -> Billing -> Billing export, and there is no scripted retry.
    DOC
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "86400s"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "cloud_run_instance_time_runaway" {
  count = local.spend_anomaly_enabled

  project               = var.project
  display_name          = "custoking-${var.env}-cloud-run-instance-time-runaway"
  combiner              = "OR"
  notification_channels = local.spend_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "billable instance time above ${var.cloud_run_instance_seconds_per_hour_alert}s/h project-wide"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"run.googleapis.com/container/billable_instance_time\"",
        "resource.type=\"cloud_run_revision\"",
        "resource.labels.project_id=\"${var.project}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = var.cloud_run_instance_seconds_per_hour_alert
      # Two consecutive hours. A release warms every service at once and a bulk photo import can hold
      # an instance for most of an hour; neither should page. A stuck instance does not stop after one.
      duration = "3600s"

      aggregations {
        alignment_period     = "3600s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger {
        count = 1
      }

      # Absence of instance time is the healthy state of a scale-to-zero fleet at night.
      evaluation_missing_data = "EVALUATION_MISSING_DATA_INACTIVE"
    }
  }

  documentation {
    content   = <<-DOC
      Cloud Run in ${var.project} has billed more than ${var.cloud_run_instance_seconds_per_hour_alert}
      instance-seconds per hour for two consecutive hours. Measured normal for prod is ~200 s/h across
      all seven services; a single instance that never scales to zero is 3,600 s/h and costs roughly
      INR 240/day, which doubles production's entire spend.

      Which service:

          gcloud monitoring metrics list is not enough -- use the Live Operations dashboard's
          "billable instance time by service" panel, or the Monitoring REST API grouped by
          resource.labels.service_name over the last three hours.

      Then the usual suspects: min-instances set to >0 on a service (check `autoscaling.knative.dev/minScale`
      on the serving revision -- it must be 0 everywhere; CLOUD_RUN_DOMAIN_MIN_INSTANCES /
      CLOUD_RUN_GATEWAY_MIN_INSTANCES must not have been set); a request that never completes holding an
      instance (look for requests in the Cloud Run request log with no response yet); a cold-start loop
      where every probe boots a JVM that cannot become healthy -- container/startup_latencies count in the
      hundreds per day is the tell, and the dev environment did exactly this against a stopped database.
    DOC
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "7200s"
  }

  user_labels = local.common_user_labels
}
