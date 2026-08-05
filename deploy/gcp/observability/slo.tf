resource "google_monitoring_service" "cloud_run" {
  for_each = local.service_names

  project      = var.project
  service_id   = each.value
  display_name = "Custoking ${var.env} ${local.service_display_names[each.key]}"

  basic_service {
    service_type = "CLOUD_RUN"
    service_labels = {
      service_name = each.value
      location     = var.region
    }
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_slo" "availability" {
  for_each = google_monitoring_service.cloud_run

  project             = var.project
  service             = each.value.service_id
  slo_id              = "${each.key}-availability-${var.env}"
  display_name        = "Availability - ${local.service_display_names[each.key]}"
  goal                = var.availability_slo_goal
  rolling_period_days = var.slo_rolling_period_days

  basic_sli {
    availability {
      enabled = true
    }
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_slo" "latency" {
  for_each = google_monitoring_service.cloud_run

  project             = var.project
  service             = each.value.service_id
  slo_id              = "${each.key}-latency-${var.env}"
  display_name        = "Latency - ${local.service_display_names[each.key]}"
  goal                = var.latency_slo_goal
  rolling_period_days = var.slo_rolling_period_days

  basic_sli {
    latency {
      threshold = var.latency_slo_threshold
    }
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "availability_slo_burn" {
  for_each = google_monitoring_slo.availability

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-availability-burn-rate"
  combiner              = "AND"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "availability sustained burn above ${var.slo_burn_rate_threshold} over ${var.slo_burn_rate_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_burn_rate_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_burn_rate_threshold
      duration        = var.slo_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  conditions {
    display_name = "availability sustained burn above ${var.slo_burn_rate_threshold} over ${var.slo_burn_rate_short_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_burn_rate_short_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_burn_rate_threshold
      duration        = var.slo_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Availability error-budget burn is sustained across ${var.slo_burn_rate_window} and ${var.slo_burn_rate_short_window} windows. Use the service dashboard first, then Cloud Trace for failing request waterfalls."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close           = "3600s"
    notification_prompts = ["OPENED"]
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "latency_slo_burn" {
  for_each = google_monitoring_slo.latency

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-latency-burn-rate"
  combiner              = "AND"
  notification_channels = local.effective_notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "latency sustained burn above ${var.slo_burn_rate_threshold} over ${var.slo_burn_rate_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_burn_rate_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_burn_rate_threshold
      duration        = var.slo_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  conditions {
    display_name = "latency sustained burn above ${var.slo_burn_rate_threshold} over ${var.slo_burn_rate_short_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_burn_rate_short_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_burn_rate_threshold
      duration        = var.slo_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Latency error-budget burn is sustained across ${var.slo_burn_rate_window} and ${var.slo_burn_rate_short_window} windows. Inspect p95 latency, instance saturation, DB spans, and Pub/Sub projection lag."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close           = "3600s"
    notification_prompts = ["OPENED"]
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "availability_slo_fast_burn" {
  for_each = google_monitoring_slo.availability

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-availability-fast-burn-rate"
  combiner              = "AND"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "availability fast burn above ${var.slo_fast_burn_rate_threshold} over ${var.slo_fast_burn_rate_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_fast_burn_rate_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_fast_burn_rate_threshold
      duration        = var.slo_fast_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  conditions {
    display_name = "availability fast burn above ${var.slo_fast_burn_rate_threshold} over ${var.slo_fast_burn_rate_short_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_fast_burn_rate_short_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_fast_burn_rate_threshold
      duration        = var.slo_fast_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Availability error-budget burn is severe across ${var.slo_fast_burn_rate_window} and ${var.slo_fast_burn_rate_short_window} windows. Investigate the service dashboard, recent deployments, Cloud Trace, and downstream dependencies immediately."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close           = "3600s"
    notification_prompts = ["OPENED"]
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "latency_slo_fast_burn" {
  for_each = google_monitoring_slo.latency

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-latency-fast-burn-rate"
  combiner              = "AND"
  notification_channels = local.effective_notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "latency fast burn above ${var.slo_fast_burn_rate_threshold} over ${var.slo_fast_burn_rate_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_fast_burn_rate_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_fast_burn_rate_threshold
      duration        = var.slo_fast_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  conditions {
    display_name = "latency fast burn above ${var.slo_fast_burn_rate_threshold} over ${var.slo_fast_burn_rate_short_window}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_fast_burn_rate_short_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_fast_burn_rate_threshold
      duration        = var.slo_fast_burn_rate_retest_window

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Latency error-budget burn is severe across ${var.slo_fast_burn_rate_window} and ${var.slo_fast_burn_rate_short_window} windows. Inspect p95 latency, instance saturation, recent deployments, DB spans, and Pub/Sub projection lag immediately."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close           = "3600s"
    notification_prompts = ["OPENED"]
  }

  user_labels = local.common_user_labels
}
