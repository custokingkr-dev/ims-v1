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
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "ERROR"

  conditions {
    display_name = "availability SLO burn rate above ${var.slo_burn_rate_threshold}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_burn_rate_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_burn_rate_threshold
      duration        = "0s"

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Availability SLO burn rate is above threshold. Use the service dashboard first, then Cloud Trace for failing request waterfalls."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "3600s"
  }

  user_labels = local.common_user_labels
}

resource "google_monitoring_alert_policy" "latency_slo_burn" {
  for_each = google_monitoring_slo.latency

  project               = var.project
  display_name          = "custoking-${var.env}-${each.key}-latency-burn-rate"
  combiner              = "OR"
  notification_channels = var.notification_channel_ids
  severity              = "WARNING"

  conditions {
    display_name = "latency SLO burn rate above ${var.slo_burn_rate_threshold}"

    condition_threshold {
      filter          = "select_slo_burn_rate(\"${each.value.name}\", ${var.slo_burn_rate_window})"
      comparison      = "COMPARISON_GT"
      threshold_value = var.slo_burn_rate_threshold
      duration        = "0s"

      trigger {
        count = 1
      }
    }
  }

  documentation {
    content   = "Latency SLO burn rate is above threshold. Inspect p95 latency, instance saturation, DB spans, and Pub/Sub projection lag."
    mime_type = "text/markdown"
  }

  alert_strategy {
    auto_close = "3600s"
  }

  user_labels = local.common_user_labels
}
