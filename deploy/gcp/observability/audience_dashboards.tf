# ---------------------------------------------------------------------------------------------------
# Audience dashboards
#
# Live Operations answers "is it working right now". These two answer different questions for different
# people, which is why they are separate dashboards rather than more panels on that one:
#
#   Product & Usage        -- who is using this, how much, and what for
#   Engineering & Infra    -- is the system healthy, and where does it break first
#
# Keeping them apart matters more than it looks. A pane that serves everyone gets scanned by no one: the
# person asking "did adoption move this week" and the person asking "why is p99 up" need different default
# time ranges and will ignore each other's panels.
# ---------------------------------------------------------------------------------------------------

locals {
  product_users_metric    = "logging.googleapis.com/user/${google_logging_metric.session_active_users.name}"
  product_logins_metric   = "logging.googleapis.com/user/${google_logging_metric.session_logins_recent.name}"
  product_requests_metric = "logging.googleapis.com/user/${google_logging_metric.gateway_requests_by_feature.name}"
  product_schools_metric  = "logging.googleapis.com/user/${google_logging_metric.platform_schools.name}"
  product_reach_metric    = "logging.googleapis.com/user/${google_logging_metric.platform_schools_with_students.name}"
  product_students_metric = "logging.googleapis.com/user/${google_logging_metric.platform_students.name}"
  product_sections_metric = "logging.googleapis.com/user/${google_logging_metric.platform_sections.name}"

  eng_latency_metric = "logging.googleapis.com/user/${google_logging_metric.gateway_latency_by_feature.name}"
  eng_outbox_pending = "logging.googleapis.com/user/${google_logging_metric.outbox_pending_count.name}"
  eng_outbox_dead    = "logging.googleapis.com/user/${google_logging_metric.outbox_dead_letter_count.name}"
  eng_outbox_age     = "logging.googleapis.com/user/${google_logging_metric.outbox_oldest_pending_age_seconds.name}"
  eng_inbox_backlog  = "logging.googleapis.com/user/${google_logging_metric.notification_inbox_backlog_count.name}"

  # Gauges carried through distributions must be read at the median. A high percentile reports the top of
  # whichever bucket the value landed in, which on the previous bucket layout turned a true 5 into 9.95.
  gauge_agg = {
    alignmentPeriod    = "300s"
    perSeriesAligner   = "ALIGN_PERCENTILE_50"
    crossSeriesReducer = "REDUCE_MAX"
  }
}

# ------------------------------------------------------------------ Product & Usage

resource "google_monitoring_dashboard" "product_usage" {
  project = var.project

  dashboard_json = jsonencode({
    displayName = "Custoking ${var.env} - Product & Usage"
    gridLayout = {
      columns = 2
      widgets = [
        {
          title = "Active users right now"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = { filter = "metric.type=\"${local.product_users_metric}\"", aggregation = local.gauge_agg }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          title = "Students on the platform"
          scorecard = {
            timeSeriesQuery = {
              timeSeriesFilter = { filter = "metric.type=\"${local.product_students_metric}\"", aggregation = local.gauge_agg }
            }
            sparkChartView = { sparkChartType = "SPARK_LINE" }
          }
        },
        {
          # The gap between these two lines is the number worth watching: a school that is provisioned but
          # holds no students has been sold and not yet onboarded. Inventory versus reach.
          title = "Adoption: schools provisioned vs schools actually holding students"
          xyChart = {
            dataSets = [
              {
                plotType        = "LINE"
                timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.product_schools_metric}\"", aggregation = local.gauge_agg } }
              },
              {
                plotType        = "LINE"
                timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.product_reach_metric}\"", aggregation = local.gauge_agg } }
              },
            ]
            yAxis = { label = "schools", scale = "LINEAR" }
          }
        },
        {
          title = "Sign-ins over time"
          xyChart = {
            dataSets = [{
              plotType        = "LINE"
              timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.product_logins_metric}\"", aggregation = local.gauge_agg } }
            }]
            yAxis = { label = "sign-ins per 15 min", scale = "LINEAR" }
          }
        },
        {
          # What the product is actually used FOR. Built-and-unused features show up here as a flat line,
          # which is usually more actionable than anything on an infrastructure chart.
          title = "Feature usage: which parts of the product get used"
          xyChart = {
            dataSets = [{
              plotType = "STACKED_AREA"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "metric.type=\"${local.product_requests_metric}\""
                  aggregation = {
                    alignmentPeriod    = "300s"
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
          # Deliberately 5xx only. A 4xx is usually a client sending something invalid; a 5xx is the
          # product failing a person who was trying to do their job.
          title = "Failures people actually experienced (5xx by feature)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = join(" AND ", [
                    "metric.type=\"${local.product_requests_metric}\"",
                    "metric.label.status_class=\"5\"",
                  ])
                  aggregation = {
                    alignmentPeriod    = "300s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["metric.label.feature"]
                  }
                }
              }
            }]
            yAxis = { label = "server errors per second", scale = "LINEAR" }
          }
        },
        {
          title = "Class sections configured"
          xyChart = {
            dataSets = [{
              plotType        = "LINE"
              timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.product_sections_metric}\"", aggregation = local.gauge_agg } }
            }]
            yAxis = { label = "sections", scale = "LINEAR" }
          }
        },
        {
          title = "How to read this"
          text = {
            format = "MARKDOWN"
            content = join("\n", [
              "**Active users** counts distinct people holding an unexpired session, not requests. One",
              "person on two devices is one user.",
              "",
              "**Adoption** plots schools provisioned against schools that actually hold students. The gap",
              "is sold-but-not-onboarded.",
              "",
              "**Feature usage** is attributed from the gateway's own routing decision, so it reflects which",
              "domain served the call rather than a guess parsed from the URL.",
              "",
              "Counts come from a distribution read at the median and are exact below 30; above that",
              "precision degrades with magnitude, to under two percent at current student numbers.",
              "",
              "Spend is deliberately not here -- see the Live Operations dashboard.",
            ])
          }
        },
      ]
    }
  })
}

# ------------------------------------------------------------------ Engineering & Infrastructure

resource "google_monitoring_dashboard" "engineering_infrastructure" {
  project = var.project

  dashboard_json = jsonencode({
    displayName = "Custoking ${var.env} - Engineering & Infrastructure"
    gridLayout = {
      columns = 2
      widgets = [
        {
          # Cold starts are the largest latency a user can experience and are invisible in request
          # latency averages, because they happen to the unlucky first request against a new instance.
          title = "Cold starts: container startup latency (p50 / p95)"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/startup_latencies\""
                    aggregation = {
                      alignmentPeriod    = "300s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_50"
                      crossSeriesReducer = "REDUCE_MEAN"
                      groupByFields      = ["resource.label.service_name"]
                    }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  unitOverride = "ms"
                  timeSeriesFilter = {
                    filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/startup_latencies\""
                    aggregation = {
                      alignmentPeriod    = "300s"
                      perSeriesAligner   = "ALIGN_PERCENTILE_95"
                      crossSeriesReducer = "REDUCE_MAX"
                      groupByFields      = ["resource.label.service_name"]
                    }
                  }
                }
              },
            ]
            yAxis = { label = "milliseconds", scale = "LINEAR" }
          }
        },
        {
          title = "Request latency per service (p95)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "ms"
                timeSeriesFilter = {
                  filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/request_latencies\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_MAX"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "milliseconds", scale = "LINEAR" }
          }
        },
        {
          title = "Gateway latency per feature (p99) -- attribute a regression to a domain"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride = "ms"
                timeSeriesFilter = {
                  filter = "metric.type=\"${local.eng_latency_metric}\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_99"
                    crossSeriesReducer = "REDUCE_PERCENTILE_99"
                    groupByFields      = ["metric.label.feature"]
                  }
                }
              }
            }]
            yAxis = { label = "milliseconds", scale = "LINEAR" }
          }
        },
        {
          # Against maxScale, which is 3 for the gateway and 2 for backends. This is the ceiling that
          # decides whether a morning attendance rush degrades.
          title = "Instances running per service (ceiling is maxScale)"
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
          title = "Peak concurrent requests per instance"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = "${local.all_services_filter} AND metric.type=\"run.googleapis.com/container/max_request_concurrencies\""
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_PERCENTILE_95"
                    crossSeriesReducer = "REDUCE_MAX"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "concurrent requests", scale = "LINEAR" }
          }
        },
        {
          title = "CPU and memory utilisation (1.0 is the limit)"
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
          title = "Database: connections, CPU, memory"
          xyChart = {
            dataSets = [
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter      = "resource.type=\"cloudsql_database\" AND metric.type=\"cloudsql.googleapis.com/database/postgresql/num_backends\""
                    aggregation = { alignmentPeriod = "60s", perSeriesAligner = "ALIGN_MEAN", crossSeriesReducer = "REDUCE_MAX" }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter      = "resource.type=\"cloudsql_database\" AND metric.type=\"cloudsql.googleapis.com/database/cpu/utilization\""
                    aggregation = { alignmentPeriod = "60s", perSeriesAligner = "ALIGN_MEAN", crossSeriesReducer = "REDUCE_MAX" }
                  }
                }
              },
              {
                plotType = "LINE"
                timeSeriesQuery = {
                  timeSeriesFilter = {
                    filter      = "resource.type=\"cloudsql_database\" AND metric.type=\"cloudsql.googleapis.com/database/memory/utilization\""
                    aggregation = { alignmentPeriod = "60s", perSeriesAligner = "ALIGN_MEAN", crossSeriesReducer = "REDUCE_MAX" }
                  }
                }
              },
            ]
            yAxis = { label = "connections / utilisation", scale = "LINEAR" }
          }
        },
        {
          title = "Database transaction rate"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter      = "resource.type=\"cloudsql_database\" AND metric.type=\"cloudsql.googleapis.com/database/postgresql/transaction_count\""
                  aggregation = { alignmentPeriod = "60s", perSeriesAligner = "ALIGN_RATE", crossSeriesReducer = "REDUCE_SUM" }
                }
              }
            }]
            yAxis = { label = "transactions per second", scale = "LINEAR" }
          }
        },
        {
          # These collected nothing at all until the logback <arguments/> provider was added, so every
          # alert built on them had never been capable of firing. They are the earliest signal that an
          # async path is silently stuck, which is exactly the failure a health check cannot see.
          title = "Async backlog: outbox pending and dead-lettered"
          xyChart = {
            dataSets = [
              {
                plotType        = "LINE"
                timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.eng_outbox_pending}\"", aggregation = local.gauge_agg } }
              },
              {
                plotType        = "LINE"
                timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.eng_outbox_dead}\"", aggregation = local.gauge_agg } }
              },
              {
                plotType        = "LINE"
                timeSeriesQuery = { timeSeriesFilter = { filter = "metric.type=\"${local.eng_inbox_backlog}\"", aggregation = local.gauge_agg } }
              },
            ]
            yAxis = { label = "events", scale = "LINEAR" }
          }
        },
        {
          title = "Oldest unpublished event age"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                unitOverride     = "s"
                timeSeriesFilter = { filter = "metric.type=\"${local.eng_outbox_age}\"", aggregation = local.gauge_agg }
              }
            }]
            yAxis = { label = "seconds", scale = "LINEAR" }
          }
        },
        {
          title = "Error rate per service (5xx)"
          xyChart = {
            dataSets = [{
              plotType = "LINE"
              timeSeriesQuery = {
                timeSeriesFilter = {
                  filter = join(" AND ", [
                    local.all_services_filter,
                    "metric.type=\"run.googleapis.com/request_count\"",
                    "metric.label.response_code_class=\"5xx\"",
                  ])
                  aggregation = {
                    alignmentPeriod    = "60s"
                    perSeriesAligner   = "ALIGN_RATE"
                    crossSeriesReducer = "REDUCE_SUM"
                    groupByFields      = ["resource.label.service_name"]
                  }
                }
              }
            }]
            yAxis = { label = "errors per second", scale = "LINEAR" }
          }
        },
        {
          title = "Where this breaks first"
          text = {
            format = "MARKDOWN"
            content = join("\n", concat([
              "**Instance ceilings** are the binding constraint, not CPU:",
              "",
              ], [for svc, maximum in var.max_instances_by_service : "- `${svc}` max **${maximum}**"], [
              "",
              "**Database** `${var.cloud_sql_instance_name}` -- connections are capped by `max_connections`,",
              "and every service holds a pool, so the ceiling is reached by instance count rather than load.",
              "",
              "**Cold starts** matter more than average latency here: services scale to zero, so the first",
              "request after idle pays full JVM startup. Watch p95 startup, not mean request latency.",
              "",
              "**Async backlog** collected nothing until the logback `<arguments/>` provider was added, so",
              "the alerts on it had never been able to fire. Treat a rising line here as real.",
            ]))
          }
        },
      ]
    }
  })
}
