locals {
  services = {
    frontend = "custoking-frontend-dev"
    gateway  = "custoking-api-gateway-dev"
  }
  waf_rules = {
    "100" = "sqli-v33-stable"
    "110" = "xss-v33-stable"
    "120" = "lfi-v33-stable"
    "130" = "rfi-v33-stable"
    "140" = "protocolattack-v33-stable"
  }
}

# These are existing Cloud Deploy services. This module never owns their images,
# environment, traffic or IAM and cannot overwrite their release configuration.
data "google_cloud_run_v2_service" "application" {
  for_each = local.services
  project  = "custoking-dev"
  location = "asia-south2"
  name     = each.value
}

resource "google_compute_global_address" "application" {
  project      = "custoking-dev"
  name         = "custoking-application-edge-dev-ip"
  address_type = "EXTERNAL"
}

resource "google_compute_security_policy" "application" {
  project = "custoking-dev"
  name    = "custoking-application-edge-dev"
  type    = "CLOUD_ARMOR"
  advanced_options_config {
    json_parsing                 = "STANDARD"
    log_level                    = "NORMAL"
    request_body_inspection_size = "64KB"
  }
  rule {
    priority    = 10
    action      = "deny(403)"
    description = "Require the explicitly owned application host"
    match {
      expr {
        expression = "!has(request.headers['host']) || (request.headers['host'].lower() != '${var.domain}' && request.headers['host'].lower() != '${var.domain}:443')"
      }
    }
  }
  dynamic "rule" {
    for_each = local.waf_rules
    content {
      priority    = tonumber(rule.key)
      action      = "deny(403)"
      description = "OWASP CRS sensitivity one: ${rule.value}"
      match {
        expr {
          expression = "evaluatePreconfiguredWaf('${rule.value}', {'sensitivity': 1})"
        }
      }
    }
  }
  rule {
    priority = 1000
    action   = "throttle"
    match {
      versioned_expr = "SRC_IPS_V1"
      config { src_ip_ranges = ["*"] }
    }
    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"
      rate_limit_threshold {
        count        = var.requests_per_ip_per_minute
        interval_sec = 60
      }
    }
  }
  rule {
    priority = 2147483647
    action   = "allow"
    match {
      versioned_expr = "SRC_IPS_V1"
      config { src_ip_ranges = ["*"] }
    }
  }
}

resource "google_compute_region_network_endpoint_group" "application" {
  for_each              = local.services
  project               = "custoking-dev"
  region                = "asia-south2"
  name                  = "custoking-edge-${each.key}-dev"
  network_endpoint_type = "SERVERLESS"
  cloud_run { service = data.google_cloud_run_v2_service.application[each.key].name }
}

resource "google_compute_backend_service" "application" {
  for_each              = local.services
  project               = "custoking-dev"
  name                  = "custoking-edge-${each.key}-dev"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  protocol              = "HTTP"
  security_policy       = google_compute_security_policy.application.id
  enable_cdn            = false
  backend { group = google_compute_region_network_endpoint_group.application[each.key].id }
  log_config {
    enable      = true
    sample_rate = 0.1
  }
}

resource "google_compute_url_map" "application" {
  project         = "custoking-dev"
  name            = "custoking-application-edge-dev"
  default_service = google_compute_backend_service.application["frontend"].id
  host_rule {
    hosts        = [var.domain]
    path_matcher = "application"
  }
  path_matcher {
    name            = "application"
    default_service = google_compute_backend_service.application["frontend"].id
    path_rule {
      paths   = ["/api", "/api/*", "/gateway-health"]
      service = google_compute_backend_service.application["gateway"].id
    }
  }
}

resource "google_compute_ssl_policy" "application" {
  project         = "custoking-dev"
  name            = "custoking-application-edge-dev"
  profile         = "MODERN"
  min_tls_version = "TLS_1_2"
}

resource "google_compute_managed_ssl_certificate" "application" {
  project = "custoking-dev"
  name    = "custoking-application-edge-dev"
  managed { domains = [var.domain] }
}

resource "google_compute_target_https_proxy" "application" {
  project          = "custoking-dev"
  name             = "custoking-application-edge-dev"
  url_map          = google_compute_url_map.application.id
  ssl_certificates = [google_compute_managed_ssl_certificate.application.id]
  ssl_policy       = google_compute_ssl_policy.application.id
}

resource "google_compute_global_forwarding_rule" "application" {
  project               = "custoking-dev"
  name                  = "custoking-application-edge-dev"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  ip_address            = google_compute_global_address.application.address
  target                = google_compute_target_https_proxy.application.id
  port_range            = "443"
}

output "dns_a_record" { value = google_compute_global_address.application.address }
output "application_origin" { value = "https://${var.domain}" }
output "requires_cloud_run_ingress_cutover" { value = true }
