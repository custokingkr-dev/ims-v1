# ---------------------------------------------------------------------------------------------------
# Spend budget
#
# Nothing watched spend before this. `cost_metric_job.tf` publishes billing-export figures into Cloud
# Monitoring every three hours and no alert policy read them, so a runaway would have been discovered by
# looking rather than by being told -- against a measured zero-user floor of roughly INR 4,450/month and
# free-trial credit that ends around 2026-10-02.
#
# The reason it did not exist earlier was believed to be a permission problem on the billing account.
# That was wrong: `gcloud billing` defaults its quota project to the gcloud core project, which is still
# the pre-split `custoking`, and that project has the Cloud Billing API disabled. The resulting error
# says "does not have permission", which reads as an IAM denial and is not one. Checked directly with an
# explicit quota project, this account grants billing.budgets.create and billing.budgets.list.
#
# KNOW WHAT THIS DOES AND DOES NOT DO.
#
# A budget is a NOTIFICATION, never a cap -- Google's own spend-cap pattern works by disabling billing
# on the project, which shuts down every resource and warns that "resources might be irretrievably
# deleted". That is not acceptable for a live school SaaS. Budgets are also slow: notifications arrive
# "multiple times per day", the first can take several hours, and they are computed from estimated data.
# So this catches a sustained overrun, not a fast one. The fast signal has to come from resource-side
# metrics -- Cloud Run instance time, Cloud SQL, and egress -- which are near real time.
# ---------------------------------------------------------------------------------------------------

resource "google_billing_budget" "environment" {
  count = var.manage_billing_budget ? 1 : 0

  billing_account = var.billing_account_id
  display_name    = "custoking-${var.env}-monthly"

  budget_filter {
    # Scoped to this environment's own project, so prod and dev each carry their own budget against the
    # shared billing account rather than one blended figure that hides which side moved.
    projects        = ["projects/${data.google_project.current.number}"]
    calendar_period = "MONTH"
  }

  amount {
    specified_amount {
      currency_code = "INR"
      units         = tostring(var.monthly_budget_inr)
    }
  }

  # Current spend at half, ninety percent and full.
  threshold_rules {
    threshold_percent = 0.5
  }

  threshold_rules {
    threshold_percent = 0.9
  }

  threshold_rules {
    threshold_percent = 1.0
  }

  # The forecast rule is the one that earns its place. Current-spend thresholds tell you the month is
  # already lost; a forecast breach fires while there is still a month left to act in.
  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "FORECASTED_SPEND"
  }

  all_updates_rule {
    # Deliberately NOT local.effective_notification_channel_ids. That local is gated by
    # enable_alert_notifications, which is false in dev -- correctly, because dev's database is stopped
    # and its services scale to zero, so dev cannot tell you anything about reliability worth waking for.
    # Money is the exception: a runaway in dev spends real rupees from the same account. Dev may not page
    # about health, and must still be able to say it is burning money.
    monitoring_notification_channels = [
      for channel in google_monitoring_notification_channel.operator_email : channel.name
    ]

    # Without this, budget alerts also go to every Billing Account Administrator and User by default.
    disable_default_iam_recipients = true
  }
}
