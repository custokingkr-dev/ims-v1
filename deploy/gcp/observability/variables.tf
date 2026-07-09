variable "project" {
  description = "Google Cloud project ID that owns the Cloud Run services and observability resources."
  type        = string
  default     = "custoking"
}

variable "region" {
  description = "Google Cloud region for the Cloud Run services."
  type        = string
  default     = "asia-south2"
}

variable "env" {
  description = "Deployment environment suffix used in Cloud Run service names."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "prod"], var.env)
    error_message = "env must be either dev or prod."
  }
}

variable "services" {
  description = "Logical service names without the custoking- prefix and environment suffix."
  type        = list(string)
  default = [
    "identity-service",
    "school-core-service",
    "operations-service",
    "platform-service",
    "billing-service",
    "api-gateway",
  ]
}

variable "notification_channel_ids" {
  description = "Existing Cloud Monitoring notification channel names, for example projects/custoking/notificationChannels/123."
  type        = list(string)
  default     = []
}

variable "discover_cloud_run_urls" {
  description = "When true, read deployed Cloud Run services to discover uptime-check hosts. Disable and set service_hosts for dry planning."
  type        = bool
  default     = true
}

variable "service_hosts" {
  description = "Optional uptime-check host overrides keyed by logical service name. Values may include or omit https://."
  type        = map(string)
  default     = {}
}

variable "service_health_paths" {
  description = "Optional health path overrides keyed by logical service name."
  type        = map(string)
  default     = {}
}

variable "uptime_authenticated_services" {
  description = "Whether each uptime check should use Monitoring service-agent OIDC authentication."
  type        = map(bool)
  default     = {}
}

variable "enable_uptime_checks" {
  description = "Whether to create uptime checks for services whose hosts can be resolved."
  type        = bool
  default     = true
}

variable "uptime_period" {
  description = "How often uptime checks run. Supported values include 60s, 300s, 600s, and 900s."
  type        = string
  default     = "300s"
}

variable "uptime_timeout" {
  description = "Timeout for each uptime probe."
  type        = string
  default     = "10s"
}

variable "max_instances_by_service" {
  description = "Cloud Run max-instance settings keyed by logical service name. Used for saturation alerts."
  type        = map(number)
  default     = {}
}

variable "max_instance_saturation_ratio" {
  description = "Alert when active instances reach this fraction of the configured max instance count."
  type        = number
  default     = 0.9
}

variable "error_rate_threshold" {
  description = "5xx ratio threshold for request-count based alerts."
  type        = number
  default     = 0.02
}

variable "latency_p95_threshold_ms" {
  description = "Cloud Run p95 request latency alert threshold in milliseconds."
  type        = number
  default     = 2000
}

variable "outbox_pending_threshold" {
  description = "Alert threshold for the extracted outbox pending count."
  type        = number
  default     = 100
}

variable "outbox_dead_letter_threshold" {
  description = "Alert threshold for the extracted outbox dead-letter count."
  type        = number
  default     = 0
}

variable "outbox_oldest_age_seconds_threshold" {
  description = "Alert threshold for the extracted oldest pending outbox age in seconds."
  type        = number
  default     = 900
}

variable "notification_inbox_backlog_threshold" {
  description = "Alert threshold for the extracted notification inbox backlog count."
  type        = number
  default     = 100
}

variable "async_count_metric_buckets" {
  description = "Explicit bucket bounds for count-like log-based distribution metrics."
  type        = list(number)
  default     = [0, 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000]
}

variable "async_age_metric_buckets" {
  description = "Explicit bucket bounds for age-like log-based distribution metrics in seconds."
  type        = list(number)
  default     = [0, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400]
}

variable "availability_slo_goal" {
  description = "Rolling request availability SLO goal."
  type        = number
  default     = 0.995
}

variable "latency_slo_goal" {
  description = "Rolling request latency SLO goal."
  type        = number
  default     = 0.95
}

variable "latency_slo_threshold" {
  description = "Good-request latency threshold for Cloud Monitoring basic SLI."
  type        = string
  default     = "2s"
}

variable "slo_rolling_period_days" {
  description = "Rolling window for SLO compliance."
  type        = number
  default     = 30
}

variable "slo_burn_rate_window" {
  description = "Cloud Monitoring burn-rate selector lookback window."
  type        = string
  default     = "60m"
}

variable "slo_burn_rate_threshold" {
  description = "Alert when SLO error budget burns faster than this multiple over the lookback window."
  type        = number
  default     = 2
}
