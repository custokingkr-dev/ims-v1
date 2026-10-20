mock_provider "google" {
  mock_data "google_compute_network" {
    defaults = { self_link = "https://www.googleapis.com/compute/v1/projects/custoking-dev/global/networks/controlled-network" }
  }
  mock_data "google_compute_subnetwork" {
    defaults = {
      self_link                = "https://www.googleapis.com/compute/v1/projects/custoking-dev/regions/asia-south2/subnetworks/controlled-subnet"
      network                  = "https://www.googleapis.com/compute/v1/projects/custoking-dev/global/networks/controlled-network"
      private_ip_google_access = true
    }
  }
  mock_data "google_compute_router_nat" {
    defaults = {
      type                               = "PUBLIC"
      endpoint_types                     = ["ENDPOINT_TYPE_VM"]
      min_ports_per_vm                   = 64
      source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
      rules                              = []
      subnetwork                         = [{ name = "https://www.googleapis.com/compute/v1/projects/custoking-dev/regions/asia-south2/subnetworks/controlled-subnet", source_ip_ranges_to_nat = ["PRIMARY_IP_RANGE"], secondary_ip_range_names = [] }]
    }
  }
}
run "disabled_has_no_reads_or_resources" {
  command = plan
  assert {
    condition     = length(google_compute_firewall.allow) == 0 && length(google_compute_firewall.deny) == 0 && length(data.google_compute_subnetwork.existing) == 0 && length(data.google_compute_router_nat.existing) == 0 && length(terraform_data.prerequisites) == 0
    error_message = "Disabled module must do nothing."
  }
}
variables {
  required_nat_ports_per_instance = 32
  network_name                    = "controlled-network"
  subnet_name                     = "controlled-subnet"
  router_name                     = "controlled-router"
  nat_name                        = "controlled-nat"
  target_tags                     = ["ims-egress-dev-controlled"]
  allow_rules                     = { sql = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["192.0.2.10/32"], protocol = "tcp", ports = ["5432"] } }
}
run "explicit_rules_and_dual_stack_deny" {
  command = apply
  variables { enabled = true }
  assert {
    condition     = length(google_compute_firewall.deny) == 2 && toset(flatten([for r in google_compute_firewall.deny : r.destination_ranges])) == toset(["0.0.0.0/0", "::/0"]) && alltrue([for r in google_compute_firewall.deny : r.direction == "EGRESS" && r.priority == 1100 && r.project == "custoking-dev" && toset(r.target_tags) == toset(["ims-egress-dev-controlled"])])
    error_message = "Both address families must be denied for exact tags."
  }
  assert {
    condition     = google_compute_firewall.allow["sql"].priority < google_compute_firewall.deny["ipv4"].priority && toset(google_compute_firewall.allow["sql"].destination_ranges) == toset(["192.0.2.10/32"]) && one(google_compute_firewall.allow["sql"].allow).protocol == "tcp" && toset(one(google_compute_firewall.allow["sql"].allow).ports) == toset(["5432"])
    error_message = "Only the explicit controlled allow rule is permitted."
  }
}
run "unprotected_allow_tag_rejected" {
  command = plan
  variables {
    enabled     = true
    allow_rules = { sql = { target_tags = ["ims-egress-dev-other"], destination_ranges = ["192.0.2.10/32"], protocol = "tcp", ports = ["5432"] } }
  }
  expect_failures = [terraform_data.prerequisites]
}
run "empty_inventory_rejected" {
  command = plan
  variables {
    enabled     = true
    allow_rules = {}
  }
  expect_failures = [terraform_data.prerequisites]
}
run "open_internet_rejected" {
  command = plan
  variables {
    allow_rules = { internet = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["0.0.0.0/0"], protocol = "tcp", ports = ["443"] } }
  }
  expect_failures = [var.allow_rules]
}
run "broad_cidr_rejected" {
  command = plan
  variables {
    allow_rules = { internet = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["10.0.0.0/8"], protocol = "tcp", ports = ["443"] } }
  }
  expect_failures = [var.allow_rules]
}
run "wildcard_protocol_rejected" {
  command = plan
  variables {
    allow_rules = { bad = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["192.0.2.10/32"], protocol = "all", ports = ["443"] } }
  }
  expect_failures = [var.allow_rules]
}
run "range_port_rejected" {
  command = plan
  variables {
    allow_rules = { bad = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["192.0.2.10/32"], protocol = "tcp", ports = ["1-65535"] } }
  }
  expect_failures = [var.allow_rules]
}
run "pga_missing_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_subnetwork.existing[0]
    values = { private_ip_google_access = false }
  }
  expect_failures = [terraform_data.prerequisites]
}
run "foreign_network_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_subnetwork.existing[0]
    values = { network = "https://www.googleapis.com/compute/v1/projects/custoking-prod/global/networks/controlled-network" }
  }
  expect_failures = [terraform_data.prerequisites]
}
run "nat_wrong_subnet_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_router_nat.existing[0]
    values = { subnetwork = [{ name = "https://www.googleapis.com/compute/v1/projects/custoking-dev/regions/asia-south2/subnetworks/other", source_ip_ranges_to_nat = ["PRIMARY_IP_RANGE"], secondary_ip_range_names = [] }] }
  }
  expect_failures = [terraform_data.prerequisites]
}
run "private_nat_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_router_nat.existing[0]
    values = { type = "PRIVATE" }
  }
  expect_failures = [terraform_data.prerequisites]
}

run "ipv6_open_allow_rejected" {
  command = plan
  variables { allow_rules = { bad = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["::/0"], protocol = "tcp", ports = ["443"] } } }
  expect_failures = [var.allow_rules]
}
run "mixed_family_rule_rejected" {
  command = plan
  variables { allow_rules = { bad = { target_tags = ["ims-egress-dev-controlled"], destination_ranges = ["192.0.2.10/32", "2001:db8::/64"], protocol = "tcp", ports = ["443"] } } }
  expect_failures = [var.allow_rules]
}
run "conditional_nat_rules_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_router_nat.existing[0]
    values = { rules = [{ rule_number = 1, match = "destination.ip == '192.0.2.10'", action = [] }] }
  }
  expect_failures = [terraform_data.prerequisites]
}

run "nat_endpoint_missing_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_router_nat.existing[0]
    values = { endpoint_types = [] }
  }
  expect_failures = [terraform_data.prerequisites]
}
run "nat_port_reserve_rejected" {
  command = plan
  variables { enabled = true }
  override_data {
    target = data.google_compute_router_nat.existing[0]
    values = { min_ports_per_vm = 32 }
  }
  expect_failures = [terraform_data.prerequisites]
}
