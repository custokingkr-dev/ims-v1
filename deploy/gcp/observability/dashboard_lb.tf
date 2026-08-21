# ---------------------------------------------------------------------------------------------------
# Load balancer + IAP for the shared dashboard
#
# The direct IAP-on-Cloud-Run integration was tried first and would not admit an authorised user. Every
# ordinary cause was eliminated: the accessor grant existed at service AND project level, IAP's service
# agent held roles/run.invoker, no org policy restricted member domains, the account holds roles/owner
# and roles/iap.admin at organisation level, and no access levels were configured. No request ever
# reached the container and IAP logs no denial, so there was nothing left to read.
#
# This is the older, well-trodden path: external Application Load Balancer, serverless NEG, IAP on the
# BACKEND SERVICE. It costs real money -- roughly INR 1,500/month for the forwarding rule -- which is
# the trade for something that reliably works with ordinary Google accounts.
#
# IAP CANNOT BE ON BOTH. Google is explicit that you cannot configure IAP on the load balancer and on
# the Cloud Run service simultaneously, so dashboard_service.tf turns its own iap_enabled off and
# restricts ingress to load-balancer traffic once this is enabled.
#
# THE CERTIFICATE AND WHY IT LOOKS ODD
#
# A Google-managed certificate validates by checking the domain resolves to the load balancer's IP.
# There is no domain here -- no Cloud DNS zone, no registered name anywhere in the project. sslip.io
# resolves <dashed-ip>.sslip.io to that IP by construction, which satisfies validation without buying
# anything. The URL is ugly; swap `dashboard_domain` for a real name later and only the certificate
# changes.
# ---------------------------------------------------------------------------------------------------

locals {
  dashboard_lb_enabled = var.enable_dashboard_load_balancer ? 1 : 0

  # sslip.io accepts dashes in place of dots: 34-1-2-3.sslip.io resolves to 34.1.2.3.
  dashboard_effective_domain = var.dashboard_domain != "" ? var.dashboard_domain : (
    local.dashboard_lb_enabled == 1
    ? "${replace(google_compute_global_address.dashboard[0].address, ".", "-")}.sslip.io"
    : ""
  )
}

resource "google_compute_global_address" "dashboard" {
  count = local.dashboard_lb_enabled

  project = var.project
  name    = "custoking-dashboard-${var.env}-ip"
  # Reserved rather than ephemeral: the certificate's domain is derived from this address, so an IP
  # that changed would silently invalidate the certificate.
  address_type = "EXTERNAL"
}

resource "google_compute_region_network_endpoint_group" "dashboard" {
  count = local.dashboard_lb_enabled

  project               = var.project
  name                  = "custoking-dashboard-${var.env}-neg"
  region                = var.region
  network_endpoint_type = "SERVERLESS"

  cloud_run {
    service = google_cloud_run_v2_service.dashboard[0].name
  }
}

resource "google_compute_backend_service" "dashboard" {
  count = local.dashboard_lb_enabled

  project               = var.project
  name                  = "custoking-dashboard-${var.env}-backend"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  protocol              = "HTTPS"

  backend {
    group = google_compute_region_network_endpoint_group.dashboard[0].id
  }

  # This is the gate. On this path IAP sits on the backend service, not on Cloud Run.
  iap {
    enabled = true
  }

  log_config {
    enable      = true
    sample_rate = 1.0
  }
}

resource "google_compute_url_map" "dashboard" {
  count = local.dashboard_lb_enabled

  project         = var.project
  name            = "custoking-dashboard-${var.env}-urlmap"
  default_service = google_compute_backend_service.dashboard[0].id
}

resource "google_compute_managed_ssl_certificate" "dashboard" {
  count = local.dashboard_lb_enabled

  project = var.project
  name    = "custoking-dashboard-${var.env}-cert"

  managed {
    domains = [local.dashboard_effective_domain]
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "google_compute_target_https_proxy" "dashboard" {
  count = local.dashboard_lb_enabled

  project          = var.project
  name             = "custoking-dashboard-${var.env}-https"
  url_map          = google_compute_url_map.dashboard[0].id
  ssl_certificates = [google_compute_managed_ssl_certificate.dashboard[0].id]
}

resource "google_compute_global_forwarding_rule" "dashboard" {
  count = local.dashboard_lb_enabled

  project               = var.project
  name                  = "custoking-dashboard-${var.env}-fr"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  port_range            = "443"
  target                = google_compute_target_https_proxy.dashboard[0].id
  ip_address            = google_compute_global_address.dashboard[0].address
}

# Who may open it, on the backend-service path.
resource "google_iap_web_backend_service_iam_member" "dashboard_viewers" {
  for_each = var.enable_dashboard_load_balancer ? toset(var.dashboard_viewers) : toset([])

  project             = var.project
  web_backend_service = google_compute_backend_service.dashboard[0].name
  role                = "roles/iap.httpsResourceAccessor"
  member              = each.value
}

# IAP fronting a load balancer still invokes Cloud Run as its own service agent.
resource "google_cloud_run_v2_service_iam_member" "dashboard_lb_invoker" {
  count = local.dashboard_lb_enabled

  project  = var.project
  location = var.region
  name     = google_cloud_run_v2_service.dashboard[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-iap.iam.gserviceaccount.com"
}

output "dashboard_lb_url" {
  description = "Shared dashboard URL via the load balancer. Append /owner or /ops."
  value       = local.dashboard_lb_enabled == 1 ? "https://${local.dashboard_effective_domain}" : null
}

output "dashboard_lb_ip" {
  description = "Static IP the certificate is validated against."
  value       = local.dashboard_lb_enabled == 1 ? google_compute_global_address.dashboard[0].address : null
}
