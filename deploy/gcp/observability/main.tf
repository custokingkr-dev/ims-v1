locals {
  default_display_names = {
    identity-service    = "Identity Service"
    school-core-service = "School Core Service"
    operations-service  = "Operations Service"
    platform-service    = "Platform Service"
    billing-service     = "Billing Service"
    api-gateway         = "API Gateway"
    frontend            = "Frontend"
  }

  default_health_paths = {
    api-gateway = "/gateway-health"
    # The frontend probes its ROOT and matches on document content, and that is deliberate rather than
    # a stopgap. Two reasons. First, /healthz is reserved by Cloud Run's front end and 404s before
    # reaching any container -- measured across three services and two projects. Second, and more
    # importantly, this service's try_files sends every unknown path to index.html with a 200, so a
    # status-code check against a dedicated health route proves only that Cloud Run is up. Matching a
    # string from the real document proves nginx served the actual application shell. Measured at 42ms
    # warm and 5.2s on a cold start, inside the 10s timeout.
    frontend = "/"
  }

  default_max_instances_by_service = {
    identity-service    = 2
    school-core-service = 2
    operations-service  = 2
    platform-service    = 2
    billing-service     = 2
    api-gateway         = 3
    frontend            = 3
  }

  service_names = {
    for service in var.services : service => "custoking-${service}-${var.env}"
  }

  service_display_names = {
    for service in var.services : service => lookup(local.default_display_names, service, replace(service, "-", " "))
  }

  service_health_paths = {
    for service in var.services : service => lookup(var.service_health_paths, service, lookup(local.default_health_paths, service, "/actuator/health"))
  }

  service_uptime_content = {
    for service in var.services : service => lookup(var.uptime_content_matchers, service, "UP")
  }

  service_max_instances = {
    for service in var.services : service => lookup(var.max_instances_by_service, service, lookup(local.default_max_instances_by_service, service, 2))
  }

  uptime_authenticated_services = {
    for service in var.services : service => lookup(var.uptime_authenticated_services, service, !contains(["api-gateway", "frontend"], service))
  }

  common_user_labels = {
    app        = "custoking"
    env        = var.env
    managed_by = "terraform"
  }

  cloud_run_revision_filters = {
    for service in var.services : service => join(" AND ", [
      "resource.type=\"cloud_run_revision\"",
      "resource.labels.project_id=\"${var.project}\"",
      "resource.labels.location=\"${var.region}\"",
      "resource.labels.service_name=\"${local.service_names[service]}\"",
    ])
  }
}

data "google_cloud_run_v2_service" "services" {
  for_each = var.discover_cloud_run_urls ? local.service_names : {}

  name     = each.value
  location = var.region
  project  = var.project
}

data "google_project" "current" {
  project_id = var.project
}

locals {
  monitoring_service_agent = "service-${data.google_project.current.number}@gcp-sa-monitoring-notification.iam.gserviceaccount.com"

  discovered_service_hosts = var.discover_cloud_run_urls ? {
    for service, service_data in data.google_cloud_run_v2_service.services :
    service => service_data.uri
  } : {}

  service_hosts = {
    for service, host in merge(local.discovered_service_hosts, var.service_hosts) :
    service => trimsuffix(replace(replace(host, "https://", ""), "http://", ""), "/")
  }

  uptime_services = {
    for service in var.services : service => {
      cloud_run_name = local.service_names[service]
      display_name   = local.service_display_names[service]
      host           = local.service_hosts[service]
      health_path    = local.service_health_paths[service]
      content_match  = local.service_uptime_content[service]
      authenticated  = local.uptime_authenticated_services[service]
    }
    if contains(keys(local.service_hosts), service)
  }
}
