# ---------------------------------------------------------------------------------------------------
# Shared dashboard
#
# The Cloud Monitoring dashboards are live and shareable but were rejected as unusable: eleven of them,
# ~76 panels, no hierarchy, and -- the part that matters -- an empty chart and a healthy chart look
# identical. The local Node dashboard fixed that but ran on one laptop, so nothing could be shared.
#
# This is that dashboard, on Cloud Run, behind IAP. Two pages from one service:
#
#   /owner  -- is it up, what does it cost, who uses it. Absolute counts, not percentages.
#   /ops    -- request and error rates, latency, saturation, queue depth, cold starts.
#
# The split is by content, not layout. Audience is the one axis that genuinely changes dashboard
# design, and Google's SRE guidance says it plainly: "high-level management may want to view quite
# different information than SREs". A page serving both gets scanned by neither.
#
# COST NOTE: min_instances stays at zero. An always-warm instance in asia-south2 runs roughly
# INR 1,000/month -- close to a quarter of the entire measured infrastructure floor -- to avoid a
# one-second cold start on a page somebody opens a few times a day.
# ---------------------------------------------------------------------------------------------------

locals {
  dashboard_enabled = var.enable_shared_dashboard ? 1 : 0
}

resource "google_service_account" "dashboard" {
  count = local.dashboard_enabled

  project      = var.project
  account_id   = "ims-dashboard"
  display_name = "Shared operations dashboard"
  description  = "Reads Cloud Monitoring to render the shared owner and ops dashboards."
}

resource "google_project_iam_member" "dashboard_monitoring" {
  count = local.dashboard_enabled

  project = var.project
  # Read-only, and only monitoring. The dashboard renders what it reads and writes nothing anywhere.
  role   = "roles/monitoring.viewer"
  member = "serviceAccount:${google_service_account.dashboard[0].email}"
}

resource "google_cloud_run_v2_service" "dashboard" {
  count = local.dashboard_enabled

  project             = var.project
  name                = "custoking-dashboard-${var.env}"
  location            = var.region
  deletion_protection = false

  # IAP terminates in front of Cloud Run, so the service itself takes ingress from the load balancer
  # and the internet -- access control is IAP's job, not the ingress setting's.
  ingress     = "INGRESS_TRAFFIC_ALL"
  iap_enabled = true

  template {
    service_account = google_service_account.dashboard[0].email

    scaling {
      min_instance_count = 0
      max_instance_count = 2
    }

    containers {
      image = var.dashboard_image

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        # CPU only while serving a request. This page is idle almost all the time.
        cpu_idle = true
      }

      env {
        name  = "DASHBOARD_PROJECT"
        value = var.project
      }

      env {
        name  = "DASHBOARD_ENV"
        value = var.env
      }
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }

  depends_on = [google_project_iam_member.dashboard_monitoring]
}

# IAP must be able to invoke the backend it is protecting.
#
# This is easy to miss and fails in a misleading way: with the IAP policy correct and this binding
# absent, a permitted user still sees IAP's own "You don't have access" page. That message points at
# the user's identity, so the natural reaction is to re-check the user's grant -- which is fine. The
# actual gap is that IAP itself cannot reach the container.
#
# The service agent is created on demand rather than with the project, so it may not exist until
# `gcloud beta services identity create --service=iap.googleapis.com` has been run once.
resource "google_cloud_run_v2_service_iam_member" "dashboard_iap_invoker" {
  count = local.dashboard_enabled

  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_service.dashboard[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-iap.iam.gserviceaccount.com"
}

# Who may open it. IAP checks Google identity before a request reaches the container, so adding a
# person is an IAM grant rather than an account in the app -- there are no passwords to manage and no
# session store to get wrong.
resource "google_iap_web_cloud_run_service_iam_member" "dashboard_viewers" {
  for_each = var.enable_shared_dashboard ? toset(var.dashboard_viewers) : toset([])

  project                = var.project
  location               = var.region
  cloud_run_service_name = google_cloud_run_v2_service.dashboard[0].name
  role                   = "roles/iap.httpsResourceAccessor"
  member                 = each.value
}

output "dashboard_url" {
  description = "Shared dashboard base URL. Append /owner or /ops."
  value       = local.dashboard_enabled == 1 ? google_cloud_run_v2_service.dashboard[0].uri : null
}
