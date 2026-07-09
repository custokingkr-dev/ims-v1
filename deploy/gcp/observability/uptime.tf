moved {
  from = google_monitoring_uptime_check_config.service["api-gateway"]
  to   = google_monitoring_uptime_check_config.public_service["api-gateway"]
}

locals {
  public_uptime_services = var.enable_uptime_checks ? {
    for service, config in local.uptime_services : service => config
    if !config.authenticated
  } : {}

  authenticated_uptime_services = var.enable_uptime_checks ? {
    for service, config in local.uptime_services : service => config
    if config.authenticated
  } : {}
}

resource "google_monitoring_uptime_check_config" "public_service" {
  for_each = local.public_uptime_services

  project            = var.project
  display_name       = "custoking-${var.env}-${each.key}-health"
  timeout            = var.uptime_timeout
  period             = var.uptime_period
  log_check_failures = true
  checker_type       = "STATIC_IP_CHECKERS"

  http_check {
    path         = each.value.health_path
    port         = "443"
    use_ssl      = true
    validate_ssl = true

    accepted_response_status_codes {
      status_class = "STATUS_CLASS_2XX"
    }
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project
      host       = each.value.host
    }
  }

  content_matchers {
    content = "UP"
    matcher = "CONTAINS_STRING"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_uptime_check_config" "authenticated_service" {
  for_each = local.authenticated_uptime_services

  depends_on = [google_cloud_run_v2_service_iam_member.uptime_check_invoker]

  project            = var.project
  display_name       = "custoking-${var.env}-${each.key}-health"
  timeout            = var.uptime_timeout
  period             = var.uptime_period
  log_check_failures = true
  checker_type       = "STATIC_IP_CHECKERS"

  http_check {
    path    = each.value.health_path
    port    = "443"
    use_ssl = true

    accepted_response_status_codes {
      status_class = "STATUS_CLASS_2XX"
    }

    service_agent_authentication {
      type = "OIDC_TOKEN"
    }
  }

  monitored_resource {
    type = "cloud_run_revision"
    labels = {
      project_id         = var.project
      location           = var.region
      service_name       = each.value.cloud_run_name
      configuration_name = ""
      revision_name      = ""
    }
  }

  content_matchers {
    content = "UP"
    matcher = "CONTAINS_STRING"
  }

  user_labels = local.common_user_labels
}

resource "google_cloud_run_v2_service_iam_member" "uptime_check_invoker" {
  for_each = local.authenticated_uptime_services

  project  = var.project
  location = var.region
  name     = each.value.cloud_run_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${local.monitoring_service_agent}"
}

locals {
  uptime_check_ids = merge(
    { for service, check in google_monitoring_uptime_check_config.public_service : service => check.uptime_check_id },
    { for service, check in google_monitoring_uptime_check_config.authenticated_service : service => check.uptime_check_id },
  )

  uptime_check_resource_types = merge(
    { for service, _ in google_monitoring_uptime_check_config.public_service : service => "uptime_url" },
    { for service, _ in google_monitoring_uptime_check_config.authenticated_service : service => "cloud_run_revision" },
  )
}
