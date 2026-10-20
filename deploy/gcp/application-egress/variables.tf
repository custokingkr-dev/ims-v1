variable "enabled" {
  type    = bool
  default = false
}
variable "network_name" {
  type    = string
  default = ""
}
variable "subnet_name" {
  type    = string
  default = ""
}
variable "router_name" {
  type    = string
  default = ""
}
variable "nat_name" {
  type    = string
  default = ""
}
variable "target_tags" {
  type    = set(string)
  default = []
  validation {
    condition     = length(var.target_tags) <= 10 && alltrue([for tag in var.target_tags : can(regex("^ims-egress-dev-[a-z0-9-]{1,40}$", tag))])
    error_message = "Only explicit dedicated dev egress tags are accepted (maximum ten)."
  }
}
variable "allow_rules" {
  type = map(object({
    target_tags        = set(string)
    destination_ranges = set(string)
    protocol           = string
    ports              = set(string)
  }))
  default = {}
  validation {
    condition = length(var.allow_rules) <= 32 && alltrue([for name, rule in var.allow_rules :
      can(regex("^[a-z][a-z0-9-]{0,30}$", name)) && contains(["tcp", "udp"], rule.protocol) &&
      length(rule.destination_ranges) > 0 && length(rule.destination_ranges) <= 16 &&
      length(distinct([for cidr in rule.destination_ranges : strcontains(cidr, ":")])) == 1 &&
      length(rule.ports) > 0 && length(rule.ports) <= 16 &&
      alltrue([for port in rule.ports : can(regex("^[0-9]{1,5}$", port)) && try(tonumber(port) >= 1 && tonumber(port) <= 65535, false)]) &&
      alltrue([for cidr in rule.destination_ranges : can(cidrhost(cidr, 0)) && try(cidrhost(cidr, 0) == split("/", cidr)[0], false) && try(tonumber(split("/", cidr)[1]) >= (strcontains(cidr, ":") ? 64 : 24), false)])
    ])
    error_message = "Use bounded named rules with explicit narrow IPv4 /24+ or IPv6 /64+ CIDRs, TCP/UDP and individual numeric ports; no all/protocol wildcard or port ranges."
  }
}

variable "required_nat_ports_per_instance" {
  type    = number
  default = 0
  validation {
    condition     = var.required_nat_ports_per_instance >= 0 && var.required_nat_ports_per_instance <= 32768 && floor(var.required_nat_ports_per_instance) == var.required_nat_ports_per_instance
    error_message = "Provide an explicit bounded whole-number per-instance port requirement before enabling."
  }
}
