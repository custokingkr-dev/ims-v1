# ---------------------------------------------------------------------------------------------------
# Product liveness check
#
# Answers the one question none of the other 43 policies can: "can people actually use this right now?"
# Every uptime check, SLO and 5xx rate can be green while the product is useless to every real user.
#
# WHY A SCHEDULED JOB RATHER THAN AN ALERT POLICY
#
# The condition is "zero sessions DURING SCHOOL HOURS", and Cloud Monitoring cannot express it -- Google
# documents that you "can't configure the alerting policy to monitor conditions only for certain time
# periods". Their suggested workaround, snoozes, is actively harmful here: a snooze suppresses
# notifications, does not create incidents, and CLOSES existing ones, so a nightly recurring snooze
# would destroy the record of an overnight failure rather than defer it. Time-gating at the notification
# layer means PagerDuty Support Hours, which is a Professional-plan feature.
#
# A cron schedule is a time gate, and it is free. Running only during school hours means the check does
# not exist at 3am -- there is nothing to suppress, because the question is never asked.
# ---------------------------------------------------------------------------------------------------

locals {
  product_liveness_enabled = var.enable_product_liveness_check ? 1 : 0

  product_liveness_script = base64encode(
    replace(file("${path.module}/../../../scripts/assert-product-liveness.py"), "\r\n", "\n")
  )
}

resource "google_service_account" "product_liveness" {
  count = local.product_liveness_enabled

  project      = var.project
  account_id   = "product-liveness-check"
  display_name = "Product liveness check"
  description  = "Reads session metrics during school hours and reports when nobody can use the product."
}

resource "google_project_iam_member" "product_liveness" {
  count = local.product_liveness_enabled

  project = var.project
  # Read-only. This job's entire job is to look and then say what it saw.
  role   = "roles/monitoring.viewer"
  member = "serviceAccount:${google_service_account.product_liveness[0].email}"
}

resource "google_cloud_run_v2_job" "product_liveness" {
  count = local.product_liveness_enabled

  project             = var.project
  name                = "ims-product-liveness-${var.env}"
  location            = var.region
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.product_liveness[0].email
      max_retries     = 0
      timeout         = "300s"

      containers {
        image   = "google/cloud-sdk:slim"
        command = ["bash"]
        # `|| true` on purpose. The script exits 1 when nobody is active, and that is the SIGNAL, not a
        # job failure -- the ERROR log it wrote is what raises the alert. Letting the exit code fail the
        # job as well would double-report the same event through Cloud Run job monitoring and make a
        # real outage look like a broken cron.
        args = ["-c", "echo $SCRIPT_B64 | base64 -d > /tmp/check.py && python3 /tmp/check.py || true"]

        env {
          name  = "LIVENESS_PROJECT"
          value = var.project
        }

        env {
          name  = "LIVENESS_ENV"
          value = var.env
        }

        env {
          name  = "SCRIPT_B64"
          value = local.product_liveness_script
        }
      }
    }
  }

  depends_on = [google_project_iam_member.product_liveness]
}

resource "google_cloud_run_v2_job_iam_member" "product_liveness_invoker" {
  count = local.product_liveness_enabled

  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_job.product_liveness[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.product_liveness[0].email}"
}

resource "google_cloud_scheduler_job" "product_liveness" {
  count = local.product_liveness_enabled

  project = var.project
  # Cloud Scheduler is not offered in asia-south2; where the trigger runs has no bearing on the job.
  region = var.cost_metric_scheduler_region
  name   = "product-liveness-${var.env}"

  # THE SCHEDULE IS THE TIME GATE. Half-hourly, 08:00-16:30, Monday to Saturday, Indian school hours --
  # Saturday included because Indian schools commonly work it. Expressed in Asia/Kolkata rather than UTC
  # so the window tracks the hours people are actually at school and does not drift.
  time_zone = "Asia/Kolkata"
  schedule  = "*/30 8-16 * * 1-6"

  description = "Reports when nobody can use the product, during the hours they would be trying to."

  http_target {
    http_method = "POST"
    uri         = "https://${var.region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.project}/jobs/${google_cloud_run_v2_job.product_liveness[0].name}:run"

    oauth_token {
      service_account_email = google_service_account.product_liveness[0].email
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.product_liveness_invoker]
}

# ---------------------------------------------------------------------------------------------------
# Turning the ERROR log into a notification

resource "google_logging_metric" "product_liveness_failures" {
  count = local.product_liveness_enabled

  project     = var.project
  name        = "custoking/${var.env}/product_liveness_failures"
  description = "Count of school-hours checks finding nobody able to use the product."

  filter = join(" AND ", [
    "resource.type=\"cloud_run_job\"",
    "resource.labels.job_name=\"${google_cloud_run_v2_job.product_liveness[0].name}\"",
    "jsonPayload.message=\"product.liveness.nobody_active\"",
  ])

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "INT64"
    unit         = "1"
    display_name = "Product liveness failures"
  }
}

resource "google_monitoring_alert_policy" "product_liveness" {
  count = local.product_liveness_enabled

  project               = var.project
  display_name          = "custoking-${var.env}-nobody-can-use-the-product"
  combiner              = "OR"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "no active users, sessions or sign-ins during school hours"

    condition_threshold {
      filter = join(" AND ", [
        "metric.type=\"logging.googleapis.com/user/${google_logging_metric.product_liveness_failures[0].name}\"",
        "resource.type=\"cloud_run_job\"",
      ])
      comparison = "COMPARISON_GT"
      # Two consecutive failures, not one. The checks are half-hourly, so this means a full hour of
      # school time with nobody able to get in -- long enough that a single transient blip does not
      # page, short enough that a broken morning is still a morning that can be rescued.
      threshold_value = 1
      duration        = "0s"

      aggregations {
        alignment_period     = "3600s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = <<-DOC
      No active users, no open sessions and no sign-ins for a full hour of school time.

      This is a SYMPTOM alert. Infrastructure can be entirely green while it fires, which is the point --
      it is the only signal here that reflects whether the product is usable rather than merely running.

      Check in this order: can you sign in yourself; is the gateway serving non-health traffic; is
      identity-service healthy; is Cloud SQL reachable. If all four are fine, suspect the metric pipeline
      rather than the product, and run scripts/assert-telemetry-liveness.sh.
    DOC
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "1800s"
  }

  user_labels = local.common_user_labels
}
