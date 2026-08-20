# ---------------------------------------------------------------------------------------------------
# Cost analysis collector
#
# Appends per-service daily cost to BigQuery, computed from Cloud Monitoring usage priced at real SKU
# rates. This exists because the Cloud Billing usage-cost export for account 014C0A-C6B9AF-5FABC0
# cannot be enabled -- six attempts across every configuration available to us fail with a server-side
# error while the pricing export succeeds on the same account and dataset, and there is no API for
# export configuration, so there is no scripted workaround.
#
# Distinct from cost_metric_job.tf, which reads the billing export and publishes a Monitoring metric.
# That job stays: if Google ever repairs the export it resumes on its own. This one does not depend on
# the export at all, which is the whole point.
# ---------------------------------------------------------------------------------------------------

locals {
  cost_analysis_enabled = var.enable_cost_analysis_collector ? 1 : 0

  # Normalised the same way as the cost-metric exporter's payload. A CRLF-mangled script decodes into a
  # shell syntax error rather than a line-ending complaint, and that has already cost this project a day.
  cost_analysis_script = base64encode(
    replace(file("${path.module}/../../../scripts/cost-analysis-collect.py"), "\r\n", "\n")
  )
}

resource "google_service_account" "cost_analysis" {
  count = local.cost_analysis_enabled

  project      = var.project
  account_id   = "cost-analysis-collector"
  display_name = "Cost analysis collector"
  description  = "Reads Cloud Monitoring usage and writes computed per-service cost to BigQuery."
}

resource "google_project_iam_member" "cost_analysis" {
  for_each = var.enable_cost_analysis_collector ? toset([
    # viewer to READ usage metrics -- deliberately not metricWriter, since this job publishes nothing
    # back to Monitoring; jobUser to run the load; dataEditor to write the analysis table.
    "roles/monitoring.viewer",
    "roles/bigquery.jobUser",
    "roles/bigquery.dataEditor",
  ]) : toset([])

  project = var.project
  role    = each.value
  member  = "serviceAccount:${google_service_account.cost_analysis[0].email}"
}

resource "google_cloud_run_v2_job" "cost_analysis" {
  count = local.cost_analysis_enabled

  project             = var.project
  name                = "ims-cost-analysis-${var.env}"
  location            = var.region
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.cost_analysis[0].email
      max_retries     = 1
      timeout         = "900s"

      containers {
        # Carries python3 and bq already. The collector is Python rather than Node specifically so that
        # one implementation runs both here and on a workstation; a Node image would need bq installing
        # and would leave two copies of this logic to drift apart.
        image   = "google/cloud-sdk:slim"
        command = ["bash"]
        args    = ["-c", "echo $SCRIPT_B64 | base64 -d > /tmp/collect.py && python3 /tmp/collect.py"]

        env {
          name  = "COST_ANALYSIS_PROJECT"
          value = var.project
        }

        env {
          name  = "COST_ANALYSIS_DATASET"
          value = var.cost_analysis_dataset
        }

        # No CLOUD_RUN_JOB here: it is a RESERVED name and Cloud Run rejects the job outright if you
        # set it. It does not need setting -- Cloud Run injects it automatically, and the collector
        # keys off its presence to take a token from the metadata server rather than shelling out to
        # gcloud, which is not authenticated inside the container. Detecting the real runtime beats
        # trusting a flag we set ourselves.

        env {
          name  = "SCRIPT_B64"
          value = local.cost_analysis_script
        }
      }
    }
  }

  depends_on = [google_project_iam_member.cost_analysis]
}

resource "google_cloud_run_v2_job_iam_member" "cost_analysis_invoker" {
  count = local.cost_analysis_enabled

  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_job.cost_analysis[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.cost_analysis[0].email}"
}

resource "google_cloud_scheduler_job" "cost_analysis" {
  count = local.cost_analysis_enabled

  project = var.project
  # Cloud Scheduler is not offered in asia-south2, so the trigger lives in the nearest region that has
  # it. Where the trigger runs has no bearing on where the job runs.
  region    = var.cost_metric_scheduler_region
  name      = "cost-analysis-${var.env}"
  time_zone = "Etc/UTC"

  # 01:15 UTC, so the previous UTC day -- which is what the collector defaults to -- is closed and its
  # metrics have settled. Deliberately UTC rather than IST: the collector's day boundary is UTC, and
  # scheduling in a different zone than the data is aggregated in produces partial days twice a year
  # when the offset shifts.
  schedule = "15 1 * * *"

  description = "Appends yesterday's per-service computed cost to the cost_analysis dataset."

  http_target {
    http_method = "POST"
    uri         = "https://${var.region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.project}/jobs/${google_cloud_run_v2_job.cost_analysis[0].name}:run"

    oauth_token {
      service_account_email = google_service_account.cost_analysis[0].email
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.cost_analysis_invoker]
}
