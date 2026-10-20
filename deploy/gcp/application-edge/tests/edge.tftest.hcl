mock_provider "google" {
  mock_data "google_cloud_run_v2_service" {
    defaults = { name = "controlled-existing-dev-service" }
  }
}

variables { domain = "edge.dev.example.test" }

run "dev_only_https_routing_and_protection" {
  # Mock apply computes provider attributes without any Google API calls.
  command = apply
  assert {
    condition     = google_compute_global_forwarding_rule.application.project == "custoking-dev" && google_compute_global_forwarding_rule.application.port_range == "443"
    error_message = "Only the authorized dev project may receive an HTTPS forwarding rule."
  }
  assert {
    condition     = length(google_compute_backend_service.application) == 2 && alltrue([for backend in google_compute_backend_service.application : backend.enable_cdn == false && backend.security_policy == google_compute_security_policy.application.id])
    error_message = "Both application backends must enforce Cloud Armor with sensitive response caching disabled."
  }
  assert {
    condition     = google_compute_ssl_policy.application.min_tls_version == "TLS_1_2" && google_compute_ssl_policy.application.profile == "MODERN"
    error_message = "Legacy TLS versions must remain disabled."
  }
  assert {
    condition     = toset(one(one(google_compute_url_map.application.path_matcher).path_rule).paths) == toset(["/api", "/api/*", "/gateway-health"])
    error_message = "API routes must reach the gateway without an unexpected rewrite."
  }
  assert {
    condition     = length(google_compute_security_policy.application.rule) == 8 && google_compute_security_policy.application.advanced_options_config[0].json_parsing == "STANDARD" && google_compute_security_policy.application.advanced_options_config[0].request_body_inspection_size == "64KB"
    error_message = "Host, WAF and flood protections must remain configured, including JSON parsing and the explicit body inspection window."
  }
}

run "reject_default_run_origin" {
  command = plan
  variables { domain = "custoking-frontend-dev-controlled-em.a.run.app" }
  expect_failures = [var.domain]
}

run "reject_shared_dynamic_dns" {
  command = plan
  variables { domain = "34-1-2-3.sslip.io" }
  expect_failures = [var.domain]
}

run "reject_url_instead_of_hostname" {
  command = plan
  variables { domain = "https://edge.dev.example.test" }
  expect_failures = [var.domain]
}

run "reject_unreviewed_tiny_shared_ip_budget" {
  command = plan
  variables { requests_per_ip_per_minute = 299 }
  expect_failures = [var.requests_per_ip_per_minute]
}

run "reject_fractional_request_count" {
  command = plan
  variables { requests_per_ip_per_minute = 1200.5 }
  expect_failures = [var.requests_per_ip_per_minute]
}
