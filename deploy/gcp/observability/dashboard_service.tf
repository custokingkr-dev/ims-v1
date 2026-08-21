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

  # IAP CANNOT BE ON BOTH the load balancer and the Cloud Run service -- Google is explicit about this.
  # When the load balancer path is enabled, IAP moves to the backend service and this must turn off,
  # and ingress narrows so the only way in is through the load balancer. Leaving ingress open would
  # mean the .run.app URL bypassed the gate entirely.
  # IAP is off for good. Two integration paths were tried -- direct on Cloud Run, then a load balancer
  # with IAP on the backend service -- and both refused an authorised holder of roles/owner and
  # roles/iap.admin at organisation level, with correct IAM on each path, no access levels, and no
  # restricting org policy. The account is a consumer Google identity and the organisation has no
  # Cloud Identity directory; IAP is built around organisational identity and that is the wall.
  #
  # The dashboard authenticates people itself instead: Google Sign-In plus an email allowlist. That
  # works with ordinary gmail.com accounts, needs no domain, and costs nothing.
  ingress     = "INGRESS_TRAFFIC_ALL"
  iap_enabled = false

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

      env {
        name  = "OAUTH_CLIENT_ID"
        value = var.dashboard_oauth_client_id
      }

      # From Secret Manager, never a tfvar. tfvars are gitignored here but still sit in plaintext on a
      # workstation and in whatever shell history created them; a secret reference is auditable and
      # rotatable without touching the deployment.
      env {
        name = "OAUTH_CLIENT_SECRET"
        value_source {
          secret_key_ref {
            secret  = "dashboard-oauth-client-secret"
            version = "latest"
          }
        }
      }

      # Signs session cookies. Its own secret so that rotating it logs everyone out deliberately rather
      # than as a side effect of redeploying.
      env {
        name = "SESSION_SECRET"
        value_source {
          secret_key_ref {
            secret  = "dashboard-session-secret"
            version = "latest"
          }
        }
      }

      env {
        name  = "DASHBOARD_ALLOWED_EMAILS"
        value = join(",", var.dashboard_allowed_emails)
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
  # The load-balancer path declares its own invoker binding; two members with the same role on the same
  # resource would fight over the policy.
  count = var.enable_dashboard_load_balancer ? 0 : local.dashboard_enabled

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
  for_each = (var.enable_shared_dashboard && !var.enable_dashboard_load_balancer) ? toset(var.dashboard_viewers) : toset([])

  project                = var.project
  location               = var.region
  cloud_run_service_name = google_cloud_run_v2_service.dashboard[0].name
  role                   = "roles/iap.httpsResourceAccessor"
  member                 = each.value
}

# The application authenticates people itself, so Cloud Run has to let requests reach it. This is the
# line that makes the service publicly INVOKABLE -- everything that keeps it private now lives in
# auth.mjs, which refuses anyone not on the allowlist and fails closed when unconfigured.
resource "google_cloud_run_v2_service_iam_member" "dashboard_public" {
  count = local.dashboard_enabled

  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_service.dashboard[0].name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

output "dashboard_url" {
  description = "Shared dashboard base URL. Append /owner or /ops."
  value       = local.dashboard_enabled == 1 ? google_cloud_run_v2_service.dashboard[0].uri : null
}
