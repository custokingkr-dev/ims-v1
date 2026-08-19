# ---------------------------------------------------------------------------------------------------
# Live Operations dashboard
#
# The per-service dashboards in dashboards.tf answer "is this service healthy". This one answers "what
# is the application doing right now, and how close are we to the ceiling", in a single pane.
#
# Spend appears here via custom metrics published by the cost-metric-exporter job, because Cloud
# Monitoring cannot query BigQuery and billing data exists nowhere else. Those figures are hours stale by
# construction -- billing export lags the usage it describes -- so they answer "what did we spend", not
# "what are we spending". The billable-instance-time and egress charts answer the latter, live.
#
# Gross and net are both plotted deliberately. Net alone read as approximately zero for this project's
# entire history because free-trial credit absorbed it, which hides the real consumption that becomes
# payable the moment that credit ends.
# ---------------------------------------------------------------------------------------------------

locals {
  all_services_filter = join(" AND ", [
    "resource.type=\"cloud_run_revision\"",
    "resource.labels.service_name=monitoring.regex.full_match(\"custoking-.*-${var.env}\")",
  ])

  live_ops_session_metric  = "logging.googleapis.com/user/${google_logging_metric.session_active_users.name}"
  live_ops_sessions_metric = "logging.googleapis.com/user/${google_logging_metric.session_active_sessions.name}"
  live_ops_logins_metric   = "logging.googleapis.com/user/${google_logging_metric.session_logins_recent.name}"
  live_ops_requests_metric = "logging.googleapis.com/user/${google_logging_metric.gateway_requests_by_feature.name}"
  live_ops_latency_metric  = "logging.googleapis.com/user/${google_logging_metric.gateway_latency_by_feature.name}"

  live_ops_config_lines = concat(
    [
      "**Project** `${var.project}` | **region** `${var.region}` | **environment** `${var.env}`",
      "",
      "**Cloud SQL instance:** `${var.cloud_sql_instance_name}`",
      "",
      "**Max instances per service** -- this is the real load ceiling:",
      "",
    ],
    [for svc, maximum in var.max_instances_by_service : "- `${svc}` max **${maximum}**"],
    [
      "",
      "Spend figures come from the billing export and are several hours stale by construction. Use them",
      "for what was spent; use the cost-driver charts for what is happening right now.",
    ],
  )
}

resource "google_monitoring_dashboard" "live_operations" {
  project = var.project

  dashboard_json = jsonencode({
    displayName = "Custoking ${var.env} - Live Operations"
    gridLayout = {
      columns = 2
      widgets = [
        {
          title = "Active users (distinct people with an unexpired session)"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = {
                filter = "metric.type=\"${local.live_ops_session_metric}\""
                aggregation = {
                  alignmentPeriod    = "300s"
                  perSeriesAligner   = "ALIGN_PERCENTILE_99"
                  crossSeriesReducer = "REDUCE_MAX"
                }
              }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          title = "Active sessions (higher than users when someone is on several devices)"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = {
                filter = "metric.type=\"${local.live_ops_sessions_metric}\""
                aggregation = {
                  alignmentPeriod    = "300s"
                  perSeriesAligner   = "ALIGN_PERCENTILE_99"
                  crossSeriesReducer = "REDUCE_MAX"
                }
              }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          title = "Sign-ins in the last 15 minutes"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "metric.type=\"${local.live_ops_logins_metric}\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_99"
                    crossSeriesReducer = "REDUCE_MAX"
                  }
                }
              }
            }]
            yAxis = { label = "sign-ins", scale = "LINEAR" }
          }
        },
        {
          title = "Requests per feature"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "metric.type=\"${local.live_ops_requests_metric}\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["metric.label.feature"]
                  }
                }
              }
            }]
            yAxis = { label = "requests per second", scale = "LINEAR" }
          }
        },
        {
          title = "Errors per feature (4xx and 5xx)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = join(" AND ", [
                    "metric.type=\"${local.live_ops_requests_metric}\"",
                    "metric.label.status_class=monitoring.regex.full_match(\"4|5\")",
                  ])
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["metric.label.feature", "metric.label.status_class"]
                  }
                }
              }
            }]
            yAxis = { label = "errors per second", scale = "LINEAR" }
          }
        },
        {
          title = "Latency per feature (p95)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "ms"
                timeSeriesFilter = {
                  filter = "metric.type=\"${local.live_ops_latency_metric}\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_PERCENTILE_95"
                    groupByFields      = ["metric.label.feature"]
                  }
                }
              }
            }]
            yAxis = { label = "milliseconds", scale = "LINEAR" }
          }
        },
        {
          title = "Latency per feature (p50 and p99)"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "metric.type=\"${local.live_ops_latency_metric}\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_50"
                      crossSeriesReducer = "REDUCE_PERCENTILE_50"
                      groupByFields      = ["metric.label.feature"]
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "metric.type=\"${local.live_ops_latency_metric}\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_99"
                      crossSeriesReducer = "REDUCE_PERCENTILE_99"
                      groupByFields      = ["metric.label.feature"]
                    }
                  }
                }
              },
            ]
            yAxis = { label = "milliseconds", scale = "LINEAR" }
          }
        },
        {
          title = "Instance headroom (running instances per service)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/instance_count\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_MEAN"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "instances", scale = "LINEAR" }
          }
        },
        {
          title = "CPU and memory utilisation (1.0 means at the limit)"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/cpu/utilizations\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_95"
                      crossSeriesReducer = "REDUCE_MAX"
                      groupByFields      = ["resource.label.service_name"]
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/memory/utilizations\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_95"
                      crossSeriesReducer = "REDUCE_MAX"
                      groupByFields      = ["resource.label.service_name"]
                    }
                  }
                }
              },
            ]
            yAxis = { label = "utilisation", scale = "LINEAR" }
          }
        },
        {
          title = "Database connections (ceiling is max_connections)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = join(" AND ", [
                    "resource.type=\"cloudsql_database\"",
                    "metric.type=\"cloudsql.googleapis.com/database/postgresql/num_backends\"",
                  ])
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_MEAN"
                    crossSeriesReducer = "REDUCE_MAX"
                  }
                }
              }
            }]
            yAxis = { label = "connections", scale = "LINEAR" }
          }
        },
        {
          title = "Cost driver: billable instance time"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/billable_instance_time\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "billable instance-seconds per second", scale = "LINEAR" }
          }
        },
        {
          title = "Cost driver: egress bytes"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/network/sent_bytes_count\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "bytes per second", scale = "LINEAR" }
          }
        },
        {
          title = "Spend yesterday (gross vs net)"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "metric.type=\"custom.googleapis.com/custoking/cost/gross_yesterday\""
                    aggregation = {
                      alignmentPeriod    = "3600s"
                      perSeriesAligner   = "ALIGN_MEAN"
                      crossSeriesReducer = "REDUCE_SUM"
                      groupByFields      = ["metric.label.billing_account"]
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "metric.type=\"custom.googleapis.com/custoking/cost/net_yesterday\""
                    aggregation = {
                      alignmentPeriod    = "3600s"
                      perSeriesAligner   = "ALIGN_MEAN"
                      crossSeriesReducer = "REDUCE_SUM"
                      groupByFields      = ["metric.label.billing_account"]
                    }
                  }
                }
              },
            ]
            yAxis = { label = "currency units", scale = "LINEAR" }
          }
        },
        {
          title = "Spend month to date (gross -- what becomes payable without credit)"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = {
                filter = "metric.type=\"custom.googleapis.com/custoking/cost/gross_month_to_date\""
                aggregation = {
                  alignmentPeriod    = "3600s"
                  perSeriesAligner   = "ALIGN_MEAN"
                  crossSeriesReducer = "REDUCE_SUM"
                }
              }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          title = "Configuration and capacity ceilings"
          text = {
            format = "MARKDOWN"
            # Rendered from the same variables that build the alert policies, so this panel cannot drift
            # from reality the way a hand-maintained note would.
            content = join("\n", local.live_ops_config_lines)
          }
        },
      ]
    }
  })
}
