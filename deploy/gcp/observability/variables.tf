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
  description = "Whether to create uptime checks for services whose hosts can be resolved. Keep false during cost-controlled shutdowns so probes do not wake services."
  type        = bool
  default     = false
}

variable "notification_email_addresses" {
  description = "Operator email channels to create, keyed by a stable short name such as primary or backup."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for address in values(var.notification_email_addresses) : can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", address))])
    error_message = "Every notification email address must be syntactically valid."
  }
}

variable "manage_compliance_logging" {
  description = "Create the single project-wide India-resident compliance log bucket and sink. Enable only in the prod state."
  type        = bool
  default     = false
}

variable "compliance_log_retention_days" {
  description = "Retention for security, request, and audit logs routed to the India-resident compliance bucket."
  type        = number
  default     = 180

  validation {
    condition     = var.compliance_log_retention_days >= 180 && var.compliance_log_retention_days <= 3650
    error_message = "Compliance log retention must be between 180 and 3650 days."
  }
}

variable "uptime_period" {
  description = "How often uptime checks run. Supported values include 60s, 300s, 600s, and 900s."
  type        = string
  default     = "900s"
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

variable "notification_inbox_dead_letter_threshold" {
  description = "Alert threshold for notification inbox events in terminal dead-letter state."
  type        = number
  default     = 0
}

variable "storage_bucket_ids" {
  description = "Bucket IDs monitored for sustained storage growth; empty monitors the environment student-photo bucket."
  type        = list(string)
  default     = []
}

variable "storage_total_bytes_threshold" {
  description = "Sustained total-byte threshold per monitored bucket. Override from measured retention and commercial limits."
  type        = number
  default     = 107374182400

  validation {
    condition     = var.storage_total_bytes_threshold > 0
    error_message = "storage_total_bytes_threshold must be greater than zero."
  }
}

variable "cloud_sql_instance_name" {
  description = "Cloud SQL instance monitored for saturation; empty derives custoking-db-<env>."
  type        = string
  default     = ""
}

variable "cloud_sql_cpu_threshold" {
  description = "Cloud SQL CPU utilization ratio that opens an incident."
  type        = number
  default     = 0.8
}

variable "cloud_sql_memory_threshold" {
  description = "Cloud SQL database/memory/components Usage percentage that opens an incident."
  type        = number
  default     = 90

  validation {
    condition     = var.cloud_sql_memory_threshold > 0 && var.cloud_sql_memory_threshold <= 100
    error_message = "cloud_sql_memory_threshold must be a percentage greater than 0 and no more than 100."
  }
}

variable "cloud_sql_connection_threshold" {
  description = "PostgreSQL backend count that opens an incident; 140 is 70% of max_connections=200."
  type        = number
  default     = 140
}

variable "pubsub_subscription_ids" {
  description = "Subscription IDs monitored for backlog; empty derives reporting and notification push IDs."
  type        = list(string)
  default     = []
}

variable "pubsub_backlog_message_threshold" {
  description = "Unacknowledged Pub/Sub messages that open a backlog incident."
  type        = number
  default     = 100
}

variable "pubsub_oldest_unacked_age_threshold_seconds" {
  description = "Oldest unacknowledged Pub/Sub message age that opens an incident."
  type        = number
  default     = 300
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
  description = "Long lookback window for sustained SLO burn-rate alerts."
  type        = string
  default     = "360m"
}

variable "slo_burn_rate_threshold" {
  description = "Alert when sustained SLO error-budget burn exceeds this multiple."
  type        = number
  default     = 6
}

variable "slo_burn_rate_short_window" {
  description = "Short confirmation window for sustained SLO burn-rate alerts."
  type        = string
  default     = "30m"
}

variable "slo_burn_rate_retest_window" {
  description = "Time both sustained-burn conditions must remain violated before opening an incident."
  type        = string
  default     = "300s"
}

variable "slo_fast_burn_rate_window" {
  description = "Long lookback window for fast SLO burn-rate alerts."
  type        = string
  default     = "60m"
}

variable "slo_fast_burn_rate_short_window" {
  description = "Short confirmation window for fast SLO burn-rate alerts."
  type        = string
  default     = "5m"
}

variable "slo_fast_burn_rate_threshold" {
  description = "Alert when fast SLO error-budget burn exceeds this multiple."
  type        = number
  default     = 14.4
}

variable "slo_fast_burn_rate_retest_window" {
  description = "Time both fast-burn conditions must remain violated before opening an incident."
  type        = string
  default     = "180s"
}

variable "enable_cost_metric_export" {
  description = "Publish billing-export spend into Cloud Monitoring as custom metrics. Requires a BigQuery billing export in this project."
  type        = bool
  default     = false
}

variable "cost_metric_schedule" {
  description = <<-DESC
    Cron schedule for the cost exporter.

    Hourly rather than three-hourly. The underlying billing data only refreshes a few times a day, so
    this re-queries identical rows most of the time -- but a custom metric is a point in time, not a
    level, and a dashboard window shorter than the write interval simply contains no point and renders
    blank. Publishing hourly keeps a one-hour view populated. The query scans a small table and costs
    approximately nothing.
  DESC
  type        = string
  default     = "0 * * * *"
}

variable "cost_metric_scheduler_region" {
  description = "Region for the Cloud Scheduler trigger. Separate from var.region because Cloud Scheduler is not available in every region that runs Cloud Run, asia-south2 included."
  type        = string
  default     = "asia-south1"
}

variable "gauge_metric_buckets" {
  description = <<-DESC
    Bucket bounds for log-based metrics that carry a gauge rather than a count.

    Log-based metrics can only be counters or distributions, so a gauge has to be smuggled through a
    distribution -- and distributions only support percentile aligners, which INTERPOLATE WITHIN A BUCKET
    rather than returning the recorded value. With coarse bounds that is badly wrong: against bounds
    [0,1,5,10,...] a true value of 5 falls in [5,10) and P99 reported 9.95, roughly double.

    These bounds sit on half-integers so every integer N falls in the middle of bucket [N-0.5, N+0.5).
    Any percentile estimate is then within half a unit of the truth and displays correctly. Resolution
    degrades above 30, where exactness stops mattering for the things measured here.
  DESC
  type        = list(number)
  default = [
    0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5,
    10.5, 11.5, 12.5, 13.5, 14.5, 15.5, 16.5, 17.5, 18.5, 19.5,
    20.5, 25.5, 30.5, 50.5, 100.5, 250.5, 500.5, 1000.5,
  ]
}
