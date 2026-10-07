variable "domain" {
  description = "Explicit owned dev hostname; no generated or shared wildcard hostname fallback."
  type        = string
  validation {
    condition = (
      length(var.domain) <= 253 &&
      can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$", var.domain)) &&
      !can(regex("(^|\\.)(run\\.app|sslip\\.io|nip\\.io)$", var.domain))
    )
    error_message = "domain must be an explicit lowercase owned DNS hostname, without a URL, wildcard, IP address or shared dynamic DNS suffix."
  }
}

variable "requests_per_ip_per_minute" {
  description = "Coarse edge flood budget per backend and source IP. Verified-user quotas remain enforced by the gateway."
  type        = number
  default     = 1200
  validation {
    condition     = var.requests_per_ip_per_minute == floor(var.requests_per_ip_per_minute) && var.requests_per_ip_per_minute >= 300 && var.requests_per_ip_per_minute <= 10000
    error_message = "Choose an integral reviewed flood budget from 300 through 10000; validate shared-school NAT traffic before cutover."
  }
}
