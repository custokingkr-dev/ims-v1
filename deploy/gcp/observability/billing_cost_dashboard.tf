# ---------------------------------------------------------------------------------------------------
# Billing & Cost dashboard
#
# Billing export is authoritative but delayed. Cloud Monitoring usage is current but only a cost proxy.
# Keeping those two evidence classes next to each other makes a missing export visible without turning an
# estimate into an invoice. Detailed service/SKU rows remain in BigQuery; Monitoring cannot query them.
# ---------------------------------------------------------------------------------------------------

locals {
  billing_cost_read_project = var.cost_metric_bq_project != "" ? var.cost_metric_bq_project : var.project

  billing_cost_export_available = "custom.googleapis.com/custoking/cost/export_available"
  billing_cost_export_lag       = "custom.googleapis.com/custoking/cost/export_lag_hours"
  billing_cost_gross_yesterday  = "custom.googleapis.com/custoking/cost/gross_yesterday"
  billing_cost_net_yesterday    = "custom.googleapis.com/custoking/cost/net_yesterday"
  billing_cost_gross_mtd        = "custom.googleapis.com/custoking/cost/gross_month_to_date"
  billing_cost_net_mtd          = "custom.googleapis.com/custoking/cost/net_month_to_date"

  billing_cost_project_filter = "metric.label.project_id=\"${var.project}\""
  # The observer is also a Cloud Run service. Excluding it prevents opening this dashboard from moving
  # the cost-driver lines it is meant to explain.
  billing_cost_application_services_filter = join(" AND ", [
    local.all_services_filter,
    "resource.labels.service_name!=\"custoking-dashboard-${var.env}\"",
  ])

  billing_cost_report_notes = [
    "## How to read this dashboard",
    "",
    "**Confirmed spend** comes from `${local.billing_cost_read_project}.billing_export` and is delayed by",
    "Cloud Billing. Check **export available** and **source lag** before trusting a money panel.",
    "",
    "**Live cost drivers** come directly from Cloud Monitoring. They remain current when billing export is",
    "delayed, but they are usage signals rather than invoice amounts.",
    "",
    "For the detailed report, open [BigQuery](https://console.cloud.google.com/bigquery?project=${local.billing_cost_read_project})",
    "and query `${local.billing_cost_read_project}.billing_export.gcp_billing_export_v1_*` for authoritative",
    "project/service/SKU rows. When that export is unavailable, use the existing estimated views in",
    "`custoking-prod.cost_analysis.v_daily_cost` and `custoking-prod.cost_analysis.v_service_cost`.",
    "",
    "The estimated views are deliberately separate from billing export: they model Cloud Run usage and the",
    "measured Cloud SQL baseline, and do not include every SKU, discount, credit, tax, or currency effect.",
  ]
}

resource "google_monitoring_dashboard" "billing_cost" {
  project = var.project

  dashboard_json = jsonencode({
    displayName = "Custoking ${var.env} - Billing & Cost"
    gridLayout = {
      columns = 2
      widgets = [
        {
          title = "Billing export available (1 = usable, 0 = unavailable)"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = {
                filter = join(" AND ", [
                  "metric.type=\"${local.billing_cost_export_available}\"",
                  local.billing_cost_project_filter,
                ])
                aggregation = {
                  alignmentPeriod    = "3600s"
                  perSeriesAligner   = "ALIGN_MEAN"
                  crossSeriesReducer = "REDUCE_MIN"
                }
              }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          title = "Billing export source lag (hours)"
          scorecard = {
            timeSeriesQuery = {
              unitOverride = "h"
              timeSeriesFilter = {
                filter = join(" AND ", [
                  "metric.type=\"${local.billing_cost_export_lag}\"",
                  local.billing_cost_project_filter,
                ])
                aggregation = {
                  alignmentPeriod    = "3600s"
                  perSeriesAligner   = "ALIGN_MEAN"
                  crossSeriesReducer = "REDUCE_MAX"
                }
              }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          title = "Confirmed gross spend - month to date"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = {
                filter = "metric.type=\"${local.billing_cost_gross_mtd}\" AND ${local.billing_cost_project_filter}"
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
          title = "Confirmed net spend after credits - month to date"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = {
                filter = "metric.type=\"${local.billing_cost_net_mtd}\" AND ${local.billing_cost_project_filter}"
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
          title = "Confirmed spend yesterday - gross vs net"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "metric.type=\"${local.billing_cost_gross_yesterday}\" AND ${local.billing_cost_project_filter}"
                    aggregation = {
                      alignmentPeriod    = "3600s"
                      perSeriesAligner   = "ALIGN_MEAN"
                      crossSeriesReducer = "REDUCE_SUM"
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = "metric.type=\"${local.billing_cost_net_yesterday}\" AND ${local.billing_cost_project_filter}"
                    aggregation = {
                      alignmentPeriod    = "3600s"
                      perSeriesAligner   = "ALIGN_MEAN"
                      crossSeriesReducer = "REDUCE_SUM"
                    }
                  }
                }
              },
            ]
            yAxis = { label = "billing currency", scale = "LINEAR" }
          }
        },
        {
          # Usually one line in an environment dashboard. Grouping is still important: if the exporter
          # is deliberately scoped to `*`, projects stay separate instead of becoming one plausible total.
          title = "Confirmed gross MTD by project"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "metric.type=\"${local.billing_cost_gross_mtd}\""
                  aggregation = {
                    alignmentPeriod    = "3600s"
                    perSeriesAligner   = "ALIGN_MEAN"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["metric.label.project_id"]
                  }
                }
              }
            }]
            yAxis = { label = "billing currency", scale = "LINEAR" }
          }
        },
        {
          title = "Live SKU proxy - billable instance time by service"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.billing_cost_application_services_filter} AND metric.type=\"run.googleapis.com/container/billable_instance_time\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "billable instance-seconds / second", scale = "LINEAR" }
          }
        },
        {
          title = "Live SKU proxy - CPU allocation by service"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.billing_cost_application_services_filter} AND metric.type=\"run.googleapis.com/container/cpu/allocation_time\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "vCPU-seconds / second", scale = "LINEAR" }
          }
        },
        {
          title = "Live SKU proxy - memory allocation by service"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.billing_cost_application_services_filter} AND metric.type=\"run.googleapis.com/container/memory/allocation_time\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "GiB-seconds / second", scale = "LINEAR" }
          }
        },
        {
          title = "Live SKU proxy - requests by service"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.billing_cost_application_services_filter} AND metric.type=\"run.googleapis.com/request_count\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "requests / second", scale = "LINEAR" }
          }
        },
        {
          title = "Live SKU proxy - network egress by service"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.billing_cost_application_services_filter} AND metric.type=\"run.googleapis.com/container/network/sent_bytes_count\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "bytes / second", scale = "LINEAR" }
          }
        },
        {
          title = "Cloud SQL usage (fixed-cost baseline; detailed estimate is in BigQuery)"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = join(" AND ", [
                      "resource.type=\"cloudsql_database\"",
                      "resource.labels.database_id=\"${local.cloud_sql_database_id}\"",
                      "metric.type=\"cloudsql.googleapis.com/database/cpu/utilization\"",
                    ])
                    aggregation = {
                      alignmentPeriod    = "300s"
                      perSeriesAligner   = "ALIGN_MEAN"
                      crossSeriesReducer = "REDUCE_MAX"
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter = join(" AND ", [
                      "resource.type=\"cloudsql_database\"",
                      "resource.labels.database_id=\"${local.cloud_sql_database_id}\"",
                      "metric.type=\"cloudsql.googleapis.com/database/postgresql/num_backends\"",
                    ])
                    aggregation = {
                      alignmentPeriod    = "300s"
                      perSeriesAligner   = "ALIGN_MEAN"
                      crossSeriesReducer = "REDUCE_MAX"
                    }
                  }
                }
              },
            ]
            yAxis = { label = "utilisation / connections", scale = "LINEAR" }
          }
        },
        {
          title = "Reporting sources and trust boundaries"
          text = {
            format  = "MARKDOWN"
            content = join("\n", local.billing_cost_report_notes)
          }
        },
      ]
    }
  })
}
