locals {
  project      = "custoking-dev"
  region       = "asia-south2"
  network_link = "https://www.googleapis.com/compute/v1/projects/${local.project}/global/networks/${var.network_name}"
  subnet_link  = "https://www.googleapis.com/compute/v1/projects/${local.project}/regions/${local.region}/subnetworks/${var.subnet_name}"
}
# Disabled by default: no data reads, resources or network/service mutation.
data "google_compute_network" "existing" {
  count   = var.enabled ? 1 : 0
  project = local.project
  name    = var.network_name
}
data "google_compute_subnetwork" "existing" {
  count   = var.enabled ? 1 : 0
  project = local.project
  region  = local.region
  name    = var.subnet_name
}
data "google_compute_router" "existing" {
  network = local.network_link
  count   = var.enabled ? 1 : 0
  project = local.project
  region  = local.region
  name    = var.router_name
}
data "google_compute_router_nat" "existing" {
  count   = var.enabled ? 1 : 0
  project = local.project
  region  = local.region
  router  = var.router_name
  name    = var.nat_name
}
resource "terraform_data" "prerequisites" {
  count = var.enabled ? 1 : 0
  lifecycle {
    precondition {
      condition     = alltrue([for name in [var.network_name, var.subnet_name, var.router_name, var.nat_name] : can(regex("^[a-z][a-z0-9-]{0,61}[a-z0-9]$", name))]) && length(var.target_tags) > 0 && length(var.allow_rules) > 0 && var.required_nat_ports_per_instance > 0
      error_message = "Enable only with explicit existing dev network/subnet/router/NAT, dedicated tags and a reviewed nonempty destination inventory."
    }
    precondition {
      condition     = alltrue([for rule in values(var.allow_rules) : length(rule.target_tags) > 0 && length(setsubtract(rule.target_tags, var.target_tags)) == 0])
      error_message = "Each allow rule must target a nonempty subset of the exact protected tags."
    }
    precondition {
      condition     = data.google_compute_network.existing[0].self_link == local.network_link && data.google_compute_subnetwork.existing[0].self_link == local.subnet_link && data.google_compute_subnetwork.existing[0].network == local.network_link && data.google_compute_subnetwork.existing[0].private_ip_google_access && data.google_compute_router.existing[0].network == local.network_link
      error_message = "Existing dev network/subnet/router must agree; subnet Private Google Access must already be enabled through an independently reviewed change."
    }
    precondition {
      condition = contains(data.google_compute_router_nat.existing[0].endpoint_types, "ENDPOINT_TYPE_VM") && data.google_compute_router_nat.existing[0].min_ports_per_vm >= 2 * var.required_nat_ports_per_instance && data.google_compute_router_nat.existing[0].type == "PUBLIC" && length(data.google_compute_router_nat.existing[0].rules) == 0 && (
        contains(["ALL_SUBNETWORKS_ALL_IP_RANGES", "ALL_SUBNETWORKS_ALL_PRIMARY_IP_RANGES"], data.google_compute_router_nat.existing[0].source_subnetwork_ip_ranges_to_nat) ||
        (data.google_compute_router_nat.existing[0].source_subnetwork_ip_ranges_to_nat == "LIST_OF_SUBNETWORKS" && anytrue([for subnet in data.google_compute_router_nat.existing[0].subnetwork : subnet.name == local.subnet_link && (contains(subnet.source_ip_ranges_to_nat, "ALL_IP_RANGES") || contains(subnet.source_ip_ranges_to_nat, "PRIMARY_IP_RANGE"))]))
      )
      error_message = "Existing Public NAT must support VM endpoints, twice the reviewed per-instance port requirement and the exact subnet primary range; conditional NAT rules require separate implementation review."
    }
  }
}
resource "google_compute_firewall" "allow" {
  for_each           = var.enabled ? var.allow_rules : {}
  project            = local.project
  name               = "ims-egress-dev-allow-${each.key}"
  network            = local.network_link
  direction          = "EGRESS"
  priority           = 1000
  target_tags        = sort(tolist(each.value.target_tags))
  destination_ranges = sort(tolist(each.value.destination_ranges))
  allow {
    protocol = each.value.protocol
    ports    = sort(tolist(each.value.ports))
  }
  log_config { metadata = "INCLUDE_ALL_METADATA" }
  depends_on = [terraform_data.prerequisites]
}
resource "google_compute_firewall" "deny" {
  for_each           = var.enabled ? { ipv4 = "0.0.0.0/0", ipv6 = "::/0" } : {}
  project            = local.project
  name               = "ims-egress-dev-deny-${each.key}"
  network            = local.network_link
  direction          = "EGRESS"
  priority           = 1100
  target_tags        = sort(tolist(var.target_tags))
  destination_ranges = [each.value]
  deny { protocol = "all" }
  log_config { metadata = "INCLUDE_ALL_METADATA" }
  depends_on = [terraform_data.prerequisites]
}
output "proposed_revision_binding" {
  value       = var.enabled ? { project = local.project, region = local.region, network = var.network_name, subnet = var.subnet_name, tags = var.target_tags, egress = "ALL_TRAFFIC" } : null
  description = "Preparation only. This module does not attach tags or change Cloud Run routing; validate each proposed revision and job separately before cutover."
}
