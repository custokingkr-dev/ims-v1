# ---------------------------------------------------------------------------------------------------
# Cost metric exporter
#
# Cloud Monitoring cannot query BigQuery, and billing data exists nowhere else, so spend can only reach a
# Monitoring dashboard by being published as a custom metric. This job does that on a schedule.
#
# It lives in the observability module rather than a deployment module because it exists purely to make a
# dashboard work. It is the only thing here that runs code, which is a deliberate trade: the alternative
# was a second Terraform root for a single job.
# ---------------------------------------------------------------------------------------------------

locals {
  cost_metric_enabled = var.enable_cost_metric_export ? 1 : 0
  cost_metric_sa      = "cost-metric-exporter@${var.project}.iam.gserviceaccount.com"

  # CRLF would break the shebang and every heredoc terminator once decoded inside the container, and the
  # failure would look like a shell syntax error rather than a line-ending problem. A silently
  # CRLF-mangled payload has already cost this project a day during the migration, so it is normalised
  # here rather than trusted to whatever the checkout produced.
  cost_metric_script = base64encode(
    replace(file("${path.module}/../../../scripts/cost-metric-exporter.sh"), "\r\n", "\n")
  )
}

resource "google_service_account" "cost_metric_exporter" {
  count = local.cost_metric_enabled

  project      = var.project
  account_id   = "cost-metric-exporter"
  display_name = "Cost metric exporter"
  description  = "Reads the billing export and publishes spend as Cloud Monitoring custom metrics."
}

resource "google_project_iam_member" "cost_metric_exporter" {
  for_each = var.enable_cost_metric_export ? toset([
    # jobUser runs the query; dataViewer reads the export tables; metricWriter publishes the result.
    # Deliberately not a broader role: this identity should never be able to alter billing or deploy.
    "roles/bigquery.jobUser",
    "roles/bigquery.dataViewer",
    "roles/monitoring.metricWriter",
  ]) : toset([])

  project = var.project
  role    = each.value
  member  = "serviceAccount:${google_service_account.cost_metric_exporter[0].email}"
}

resource "google_cloud_run_v2_job" "cost_metric_exporter" {
  count = local.cost_metric_enabled

  project             = var.project
  name                = "ims-cost-metric-${var.env}"
  location            = var.region
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.cost_metric_exporter[0].email
      max_retries     = 1
      timeout         = "600s"

      containers {
        # A stock image with bq, gcloud, curl and python3 already present. Building and maintaining a
        # container for a script this small would cost more than it saves, and would add an image to keep
        # patched for no benefit.
        image   = "google/cloud-sdk:slim"
        command = ["bash"]
        args    = ["-c", "echo $SCRIPT_B64 | base64 -d > /tmp/exporter.sh && bash /tmp/exporter.sh"]

        env {
          name  = "COST_METRIC_PROJECT"
          value = var.project
        }

        env {
          name  = "COST_METRIC_BQ_PROJECT"
          value = var.cost_metric_bq_project != "" ? var.cost_metric_bq_project : var.project
        }

        env {
          name  = "COST_METRIC_SCOPE_PROJECT"
          value = var.cost_metric_scope_project != "" ? var.cost_metric_scope_project : var.project
        }

        env {
          name  = "COST_METRIC_PUBLISH_PROJECT"
          value = var.project
        }

        env {
          name  = "SCRIPT_B64"
          value = local.cost_metric_script
        }
      }
    }
  }

  depends_on = [google_project_iam_member.cost_metric_exporter]
}

resource "google_cloud_run_v2_job_iam_member" "cost_metric_scheduler_invoker" {
  count = local.cost_metric_enabled

  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_job.cost_metric_exporter[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.cost_metric_exporter[0].email}"
}

resource "google_cloud_scheduler_job" "cost_metric_exporter" {
  count = local.cost_metric_enabled

  project = var.project
  name    = "cost-metric-export-${var.env}"
  # Cloud Scheduler is not offered in asia-south2, so the trigger lives in the nearest region that has
  # it. Where the trigger runs has no bearing on where the job runs.
  region    = var.cost_metric_scheduler_region
  schedule  = var.cost_metric_schedule
  time_zone = "Asia/Kolkata"

  description = "Publishes billing-export spend into Cloud Monitoring as custom metrics."

  http_target {
    http_method = "POST"
    uri         = "https://${var.region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.project}/jobs/${google_cloud_run_v2_job.cost_metric_exporter[0].name}:run"

    oauth_token {
      service_account_email = google_service_account.cost_metric_exporter[0].email
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.cost_metric_scheduler_invoker]
}

# When the export lives in another project, this identity needs to read it there. Scoped to the one
# dataset rather than granting project-wide BigQuery access.
resource "google_bigquery_dataset_iam_member" "cost_metric_cross_project_reader" {
  count = (var.enable_cost_metric_export && var.cost_metric_bq_project != "" && var.cost_metric_bq_project != var.project) ? 1 : 0

  project    = var.cost_metric_bq_project
  dataset_id = "billing_export"
  role       = "roles/bigquery.dataViewer"
  member     = "serviceAccount:${google_service_account.cost_metric_exporter[0].email}"
}
