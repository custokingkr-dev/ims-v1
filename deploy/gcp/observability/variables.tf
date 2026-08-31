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
    # The frontend is the only thing a human actually loads in a browser, and until now it was the one
    # Cloud Run service with no uptime check, no SLO and no error alert -- monitored by nothing while
    # six backends were monitored six ways each.
    "frontend",
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

variable "uptime_content_matchers" {
  description = <<-DESC
    Optional response-body substring per logical service, defaulting to "UP".

    "UP" is the Spring Boot Actuator convention and is right for the six backends. The frontend is
    nginx serving a single-page app, so until its /healthz route ships it has no endpoint that
    returns "UP" -- and its catch-all returns index.html with a 200 for every unknown path, which
    means a status-code-only check would pass no matter what. Matching a string from the real
    document is the honest check: it proves nginx served the application shell, not just a socket.
  DESC
  type        = map(string)
  default     = {}
}

variable "uptime_authenticated_services" {
  description = "Whether each uptime check should use Monitoring service-agent OIDC authentication."
  type        = map(bool)
  default     = {}
}

variable "manage_billing_budget" {
  description = "Whether this root creates a Cloud Billing budget for its environment's project."
  type        = bool
  default     = false
}

variable "billing_account_id" {
  description = "Billing account the environment's project bills to, without the billingAccounts/ prefix."
  type        = string
  default     = ""
}

variable "monthly_budget_inr" {
  description = <<-DESC
    Monthly budget in INR for this environment's project.

    Not a target and not a cap -- a tripwire. The measured zero-user floor for the whole platform is
    about INR 4,450/month, of which prod is roughly INR 3,832 and 81.5% of that is one Cloud SQL
    instance whose daily cost has a coefficient of variation of zero. So a normal month is close to
    flat, and any real movement is either genuine growth or a defect.

    The default leaves headroom over that floor while still firing well before a runaway becomes
    expensive. Raise it deliberately when real load arrives rather than when it alerts.
  DESC
  type        = number
  default     = 6000
}

variable "uptime_failure_checker_quorum" {
  description = <<-DESC
    How many uptime checker regions must fail before an incident opens.

    Was effectively 1, which contradicts Google's documented default of "at least two regions" and their
    warning that a single checker failing "might be the result of the checker's command timing out due
    to a network issue". Each checker location is a separate time series and there is no cross-series
    reducer, so one region having a bad minute paged.

    Six checkers probe each service, so two is still fast and sensitive: a real outage fails all six.
  DESC
  type        = number
  default     = 2
}

variable "dashboard_oauth_client_id" {
  description = "OAuth 2.0 client ID for dashboard sign-in. Not secret; the client SECRET lives in Secret Manager."
  type        = string
  default     = ""
}

variable "dashboard_public_url" {
  description = <<-EOT
    Public origin the dashboard builds its OAuth redirect_uri from, e.g. https://custoking-dashboard-prod-xxxx-em.a.run.app.
    Pinning it keeps a client-supplied Host header out of the sign-in URL. Empty falls back to request
    headers, which is correct for local use. Must match a redirect URI registered on the OAuth client.
  EOT
  type        = string
  default     = ""

  validation {
    # An http origin would be sent to Google as the callback and rejected, and the resulting
    # redirect_uri_mismatch says nothing about the scheme being the cause.
    condition     = var.dashboard_public_url == "" || startswith(var.dashboard_public_url, "https://")
    error_message = "dashboard_public_url must be empty or an https:// origin."
  }
}

variable "dashboard_allowed_emails" {
  description = <<-DESC
    Email addresses permitted to open the dashboard.

    An EMPTY list denies everyone. That is deliberate: a misconfiguration should lock the door rather
    than remove it. The list is re-checked on every request, so removing someone takes effect on their
    next page load rather than when their session expires.
  DESC
  type        = list(string)
  default     = []
}

variable "enable_dashboard_load_balancer" {
  description = <<-DESC
    Put the dashboard behind an external load balancer with IAP on the backend service.

    The direct IAP-on-Cloud-Run integration was tried first and would not admit an authorised user with
    every ordinary cause eliminated. This is the older, reliable path. It costs roughly INR 1,500/month
    for the forwarding rule, which is the trade for something that works with ordinary Google accounts.

    IAP cannot be on both the load balancer and the Cloud Run service, so enabling this turns the
    service's own iap_enabled off and narrows its ingress to load-balancer traffic only.
  DESC
  type        = bool
  default     = false
}

variable "dashboard_domain" {
  description = <<-DESC
    Domain for the managed certificate. Leave empty to derive one from the reserved IP via sslip.io.

    A Google-managed certificate validates by checking the domain resolves to the load balancer's IP,
    and there is no registered domain anywhere in this project. sslip.io resolves <dashed-ip>.sslip.io
    to that IP by construction, which satisfies validation without buying anything. Set a real domain
    here later and only the certificate changes.
  DESC
  type        = string
  default     = ""
}

variable "enable_shared_dashboard" {
  description = "Whether to run the shared owner/ops dashboard on Cloud Run behind IAP."
  type        = bool
  default     = false
}

variable "dashboard_image" {
  description = "Fully-qualified image for the dashboard service."
  type        = string
  default     = ""
}

variable "dashboard_viewers" {
  description = <<-DESC
    IAM members allowed to open the dashboard, as `user:name@example.com` or `group:...`.

    IAP checks Google identity before a request reaches the container, so granting access is an IAM
    change rather than an account in the application. There are no passwords to manage and no session
    handling to get wrong -- which is the main reason to put IAP in front of it rather than build a
    login.
  DESC
  type        = list(string)
  default     = []
}

variable "enable_aggregate_error_alert" {
  description = <<-DESC
    Whether to run a single aggregate server-error alert in place of seven per-service ones.

    The per-service policies are ratio conditions over run.googleapis.com/request_count, which includes
    uptime-probe traffic -- roughly 3,456 synthetic requests a day against 600 real ones. They largely
    measure the prober. This counts real 5xx from the gateway's own request log, which already excludes
    /gateway-health.
  DESC
  type        = bool
  default     = false
}

variable "aggregate_5xx_threshold" {
  description = <<-DESC
    Real server errors in a 15-minute window before alerting.

    An absolute count, not a rate, and that is the point. At roughly 600 real requests a day a ratio is
    dominated by whichever handful arrived: overnight a five-minute window can hold two requests, so one
    failure reads as a 50% error rate. Five real errors is five real people, regardless of denominator.
  DESC
  type        = number
  default     = 4
}

variable "enable_per_service_error_notifications" {
  description = <<-DESC
    Whether the per-service 5xx, latency and saturation policies notify, or exist only as dashboards.

    Defaults to false once the aggregate alert is on. They stay as objects because their charts remain
    useful for narrowing down a problem after the aggregate has told you there IS one -- but seven
    emails for one upstream failure is how an inbox becomes something you filter rather than read.

    The latency ones are the weakest of the three: with min-instances at zero and Spring Boot cold
    starts, a 2s p95 threshold largely measures cold starts rather than user pain.
  DESC
  type        = bool
  default     = true
}

variable "enable_per_service_latency_incidents" {
  description = <<-DESC
    Whether generic per-service p95 latency policies can open incidents.

    Disable this in production while long-running synchronous import, export, and recovery endpoints share
    the same Cloud Run services as interactive requests. The raw latency metrics and dashboard charts remain
    available. Re-enable incident generation only after latency is measured from a path-aware metric that
    excludes intentional batch work, otherwise one successful request opens matching frontend, gateway, and
    upstream-service incidents.
  DESC
  type        = bool
  default     = false
}

variable "enable_product_liveness_check" {
  description = <<-DESC
    Whether to run the school-hours product liveness check.

    Answers the one question none of the threshold policies can: can people actually USE this right
    now. Runs as a scheduled job rather than an alert policy because the condition is time-bounded and
    Cloud Monitoring cannot express "only during school hours" -- a cron schedule can, for free, where
    the notification-layer alternative is a paid PagerDuty tier.

    Prod only. Dev has a stopped database and no users to fail.
  DESC
  type        = bool
  default     = false
}

variable "enable_cost_analysis_collector" {
  description = <<-DESC
    Whether to run the daily per-service cost collector.

    Independent of the billing export by design. The usage-cost export for this billing account cannot
    be enabled -- a server-side fault confirmed across six configurations -- so cost is reconstructed
    from Cloud Monitoring usage priced at real SKU rates instead. Enable in prod; dev's spend is a
    stopped database and is not worth a daily job.
  DESC
  type        = bool
  default     = false
}

variable "cost_analysis_dataset" {
  description = "BigQuery dataset holding the computed cost tables and the restored historical billing export."
  type        = string
  default     = "cost_analysis"
}

variable "enable_alert_notifications" {
  description = <<-DESC
    Whether alert policies in this environment notify anyone at all.

    Set false for dev. Dev's Cloud SQL is stopped and its services scale to zero, so most of what it
    emits is noise about a system nobody is using -- and both environments were wired to the SAME two
    addresses, which made a dev alert indistinguishable from a production one at a glance. Incidents
    still open and remain visible in the console; they just cannot reach a person.
  DESC
  type        = bool
  default     = true
}

variable "enable_slo_burn_notifications" {
  description = <<-DESC
    Whether the eight SLO burn-rate policies per environment send notifications, or exist only as
    dashboard objects.

    Defaults to false, and the arithmetic is why. A 99.5% availability goal puts the fast-burn
    threshold at 14.4 x 0.005 = 7.2% error rate. Overnight a five-minute window holds roughly two
    requests -- almost all of them uptime probes -- so ONE failed probe is a 50% error rate, and two
    failures inside an hour satisfy both windows of the AND combiner. An ERROR-severity policy then
    emails at 3am about a service no user was trying to reach.

    This is Google's own documented low-traffic failure mode, not a misconfiguration: multi-window
    burn-rate alerting assumes a request rate that makes a ratio meaningful, and roughly 600 requests
    a day does not. The SLOs themselves stay -- the monthly error-budget number is genuinely useful.
    Only the paging is removed.
  DESC
  type        = bool
  default     = false
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

# NOTE: 365 is a compliance floor, not a preference. DPDP Rule 6(1)(e) requires a Data Fiduciary to
# "retain such logs and personal data for a period of one year", and Rule 8(3) independently requires
# "logs of the processing for a minimum period of one year". The previous default of 180 was this
# variable's own validation floor -- i.e. the value you get by not choosing one.
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
  description = <<-DESC
    Alert threshold for the extracted outbox dead-letter count.

    IMPORTANT -- why this default is 0.5 and not 0.

    This gauge is carried through a distribution and read at a percentile, so a TRUE ZERO reports as the
    upper bound of the underflow bucket, which these bounds place at 0.5. Measured against production with
    every queue drained: exactly 0.500 at both p50 and p95.

    A threshold of 0 therefore fires permanently on a perfectly healthy system, and because these policies
    latch open, that incident then masks every real dead letter that follows -- strictly worse than having
    no alert at all. 0.5 separates a true zero (0.5, not greater) from a true one (~1.0, greater).
  DESC
  type        = number
  default     = 0.5
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
  description = <<-DESC
    Alert threshold for notification inbox events in terminal dead-letter state.

    IMPORTANT -- why this default is 0.5 and not 0.

    This gauge is carried through a distribution and read at a percentile, so a TRUE ZERO reports as the
    upper bound of the underflow bucket, which these bounds place at 0.5. Measured against production with
    every queue drained: exactly 0.500 at both p50 and p95.

    A threshold of 0 therefore fires permanently on a perfectly healthy system, and because these policies
    latch open, that incident then masks every real dead letter that follows -- strictly worse than having
    no alert at all. 0.5 separates a true zero (0.5, not greater) from a true one (~1.0, greater).
  DESC
  type        = number
  default     = 0.5
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

variable "enable_attendance_growth_monitoring" {
  description = <<-DESC
    Create DATA-01 attendance relation log metrics and alert policies.

    This defaults to false deliberately. Repository review and a Terraform plan must precede activation;
    adding the collector to school-core-service must not silently create live alerting resources. Enable
    first in development, verify emitted structured logs and distribution values, then make a separately
    approved production apply.
  DESC
  type        = bool
  default     = false
}

variable "attendance_partition_prepare_rows" {
  description = "Approximate attendance fact rows at which partition rollout preparation becomes urgent."
  type        = number
  default     = 10000000

  validation {
    condition     = var.attendance_partition_prepare_rows > 1000000 && var.attendance_partition_prepare_rows < 20000000
    error_message = "attendance_partition_prepare_rows must be greater than 1M and less than 20M."
  }
}

variable "attendance_partition_execute_rows" {
  description = "Approximate attendance fact rows at which the approved partition rollout must be scheduled before 25M."
  type        = number
  default     = 20000000

  validation {
    condition     = var.attendance_partition_execute_rows > 10000000 && var.attendance_partition_execute_rows <= 25000000
    error_message = "attendance_partition_execute_rows must be greater than 10M and no more than the 25M hard boundary."
  }
}

variable "attendance_index_bytes_threshold" {
  description = "Attendance index bytes that trigger an index-growth and write-amplification review."
  type        = number
  default     = 8589934592

  validation {
    condition     = var.attendance_index_bytes_threshold > 4294967296 && var.attendance_index_bytes_threshold < 17179869184
    error_message = "attendance_index_bytes_threshold must be greater than 4 GiB and less than 16 GiB."
  }
}

variable "attendance_full_scan_equivalents_milli_threshold" {
  description = "Sequential tuples read per reporting interval divided by table rows, in thousandths; 1000 is one full-table equivalent."
  type        = number
  default     = 1000

  validation {
    condition     = var.attendance_full_scan_equivalents_milli_threshold > 500 && var.attendance_full_scan_equivalents_milli_threshold < 2000
    error_message = "attendance_full_scan_equivalents_milli_threshold must be greater than 500 and less than 2000."
  }
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

variable "cost_metric_bq_project" {
  description = <<-DESC
    Project holding the BigQuery billing export. Defaults to this project.

    A billing account exports to exactly one dataset, so environments sharing an account cannot each own
    one. Dev points this at the project that does own the export and filters to itself.
  DESC
  type        = string
  default     = ""
}

variable "cost_metric_scope_project" {
  description = "Project whose spend to report. Empty means this project. \"*\" reports every project in the export as separate labelled series, for a cumulative view."
  type        = string
  default     = ""
}

variable "count_gauge_bucket_max" {
  description = <<-DESC
    Upper bound for business-count gauge buckets.

    Log-based metrics cannot carry an exact gauge, only a distribution read through a percentile, and a
    percentile is interpolated WITHIN its bucket. Precision is therefore a property of the bucket layout,
    not of the data. These bounds are dense at the low end -- half-integers to 30, where counts like
    "schools: 11" must be exact -- and coarsen with magnitude, where a trend matters more than a unit.
    At the current student count the worst-case error is under two percent.
  DESC
  type        = number
  default     = 10000
}

variable "async_age_metric_buckets_fine" {
  description = <<-DESC
    Bucket bounds for age-like gauges read through a percentile.

    The original age bounds began at 0, so a drained queue -- age exactly zero -- fell in [0,30) and a
    percentile reported close to 30 seconds. An empty queue therefore looked like a half-minute backlog
    that never cleared. Starting above zero puts a true zero in the underflow bucket, where it reads as
    zero, and the low end is dense enough that a few seconds of lag is distinguishable from a few minutes.
  DESC
  type        = list(number)
  default     = [0.5, 1, 2, 5, 10, 15, 30, 45, 60, 90, 120, 180, 300, 600, 900, 1800, 3600, 7200, 14400, 28800]
}
