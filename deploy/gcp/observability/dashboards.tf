resource "google_monitoring_dashboard" "service" {
  for_each = local.service_names

  project = var.project

  dashboard_json = jsonencode({
    displayName = "Custoking ${var.env} - ${local.service_display_names[each.key]}"
    gridLayout = {
      columns = 2
      widgets = [
        {
          title = "Request latency p50/p95/p99"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_latencies\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_50"
                      crossSeriesReducer = "REDUCE_PERCENTILE_50"
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_latencies\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_95"
                      crossSeriesReducer = "REDUCE_PERCENTILE_95"
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_latencies\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_99"
                      crossSeriesReducer = "REDUCE_PERCENTILE_99"
                    }
                  }
                }
              }
            ]
            yAxis = {
              label = "milliseconds"
              scale = "LINEAR"
            }
          }
        },
        {
          title = "Request rate"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "1/s"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_count\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_RATE"
                      crossSeriesReducer = "REDUCE_SUM"
                    }
                  }
                }
              }
            ]
            yAxis = {
              label = "requests/s"
              scale = "LINEAR"
            }
          }
        },
        {
          title = "5xx rate"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "1"
                  timeSeriesFilterRatio = {
                    numerator = {
                      filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_count\" AND metric.labels.response_code_class=\"5xx\""
                      aggregation = {
                        alignmentPeriod    = "60s"
                        perSeriesAligner   = "ALIGN_RATE"
                        crossSeriesReducer = "REDUCE_SUM"
                      }
                    }
                    denominator = {
                      filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/request_count\""
                      aggregation = {
                        alignmentPeriod    = "60s"
                        perSeriesAligner   = "ALIGN_RATE"
                        crossSeriesReducer = "REDUCE_SUM"
                      }
                    }
                  }
                }
              }
            ]
            yAxis = {
              label = "ratio"
              scale = "LINEAR"
            }
          }
        },
        {
          title = "Active instances"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "1"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/container/instance_count\" AND metric.labels.state=\"active\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_MAX"
                      crossSeriesReducer = "REDUCE_SUM"
                    }
                  }
                }
              }
            ]
            yAxis = {
              label = "instances"
              scale = "LINEAR"
            }
          }
        },
        {
          title = "CPU utilization"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "10^2.%"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/container/cpu/utilizations\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_95"
                      crossSeriesReducer = "REDUCE_PERCENTILE_95"
                    }
                  }
                }
              }
            ]
            yAxis = {
              label = "utilization"
              scale = "LINEAR"
            }
          }
        },
        {
          title = "Memory utilization"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "10^2.%"
                  timeSeriesFilter = {
                    filter = "${local.cloud_run_revision_filters[each.key]} AND metric.type=\"run.googleapis.com/container/memory/utilizations\""
                    aggregation = {
                      alignmentPeriod    = "60s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_95"
                      crossSeriesReducer = "REDUCE_PERCENTILE_95"
                    }
                  }
                }
              }
            ]
            yAxis = {
              label = "utilization"
              scale = "LINEAR"
            }
          }
        }
      ]
    }
  })

  lifecycle {
    # The Monitoring API injects dashboard JSON fields such as name, etag, and
    # targetAxis on readback. Ignore that provider churn after creation.
    ignore_changes = [dashboard_json]
  }
}

resource "google_monitoring_dashboard" "async_health" {
  project = var.project

  dashboard_json = jsonencode({
    displayName = "Custoking ${var.env} - Async Health"
    gridLayout = {
      columns = 2
      widgets = [
        {
          title = "Outbox pending count"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "1"
                timeSeriesFilter = {
                  filter = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_pending_count.name}\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_MAX"
                  }
                }
              }
            }]
          }
        },
        {
          title = "Outbox dead-letter count"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "1"
                timeSeriesFilter = {
                  filter = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_dead_letter_count.name}\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_MAX"
                  }
                }
              }
            }]
          }
        },
        {
          title = "Oldest pending outbox age"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "s"
                timeSeriesFilter = {
                  filter = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.outbox_oldest_pending_age_seconds.name}\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_MAX"
                  }
                }
              }
            }]
          }
        },
        {
          title = "Notification inbox backlog"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "1"
                timeSeriesFilter = {
                  filter = "metric.type=\"logging.googleapis.com/user/${google_logging_metric.notification_inbox_backlog_count.name}\""
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_MAX"
                  }
                }
              }
            }]
          }
        }
      ]
    }
  })

  lifecycle {
    # The Monitoring API injects dashboard JSON fields such as name, etag, and
    # targetAxis on readback. Ignore that provider churn after creation.
    ignore_changes = [dashboard_json]
  }
}
