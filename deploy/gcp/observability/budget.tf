# ---------------------------------------------------------------------------------------------------
# Spend budgets
#
# Nothing watched spend before this. `cost_metric_job.tf` publishes billing-export figures into Cloud
# Monitoring every hour and, until `spend_anomaly_alerts.tf`, no alert policy read them, so a runaway
# would have been discovered by looking rather than by being told.
#
# The reason it did not exist earlier was believed to be a permission problem on the billing account.
# That was wrong: `gcloud billing` defaults its quota project to the gcloud core project, and if that
# project has the Cloud Billing API disabled the resulting error says "does not have permission", which
# reads as an IAM denial and is not one. Checked directly with an explicit quota project, this account
# grants billing.budgets.create and billing.budgets.list.
#
# THE CREDIT TRAP -- read this before touching threshold values.
#
# The first version of this file omitted `credit_types_treatment`, which defaults to INCLUDE_ALL_CREDITS.
# That makes the budget measure NET cost -- cost after every credit -- and both projects sit on a
# free-trial promotion that offsets 100% of spend. Measured 2026-09-11 from the billing export: September
# to date, custoking-prod gross INR 1,252.11 / net INR -0.0015; custoking-dev gross INR 1,142.37 / net
# INR -0.0008. Against a net of zero, no threshold on any budget could ever be reached. Both budgets
# existed, both were "enabled", and both were structurally incapable of firing. The dev budget of INR
# 2,000 would have reported dev at 188% of budget for the whole of August had it been able to see gross.
# `docs/GCP-COST-GUARDRAILS-RUNBOOK.md` had said "must use EXCLUDE_ALL_CREDITS" since before this file
# was written; the code just never did it.
#
# KNOW WHAT THIS DOES AND DOES NOT DO.
#
# A budget is a NOTIFICATION, never a cap -- Google's own spend-cap pattern works by disabling billing
# on the project, which shuts down every resource and warns that "resources might be irretrievably
# deleted". That is not acceptable for a live school SaaS. Budgets are also slow: notifications arrive
# "multiple times per day", the first can take several hours, and they are computed from estimated data.
# So this catches a sustained overrun, not a fast one. The fast signal comes from
# `spend_anomaly_alerts.tf`, which reads resource-side metrics that are near real time.
# ---------------------------------------------------------------------------------------------------

locals {
  # Money notifies even where health does not. Deliberately NOT local.effective_notification_channel_ids:
  # that local is gated by enable_alert_notifications, which is false in dev -- correctly, because dev's
  # database is stopped and its services scale to zero, so dev cannot tell you anything about
  # reliability worth waking for. Spend is the exception: a runaway in dev spends real rupees from the
  # same account, and the dev cold-start loop found on 2026-09-11 (INR ~83/day) is exactly the kind of
  # thing that only a money signal would have surfaced. Dev may not page about health, and must still
  # be able to say it is burning money.
  spend_notification_channel_ids = distinct(concat(
    var.notification_channel_ids,
    var.budget_notification_channel_ids,
    [for channel in google_monitoring_notification_channel.operator_email : channel.name],
  ))
}

resource "google_billing_budget" "environment" {
  count = var.manage_billing_budget ? 1 : 0

  billing_account = var.billing_account_id
  display_name    = "custoking-${var.env}-monthly"

  budget_filter {
    # Scoped to this environment's own project, so prod and dev each carry their own budget against the
    # shared billing account rather than one blended figure that hides which side moved.
    projects        = ["projects/${data.google_project.current.number}"]
    calendar_period = "MONTH"

    # GROSS, not net. See "THE CREDIT TRAP" above. This is also the right measure of what the invoice
    # will look like after the trial: gross overstates it only by the Cloud Run always-free tier (about
    # INR 500/month at current usage), which is the conservative direction for a tripwire.
    credit_types_treatment = "EXCLUDE_ALL_CREDITS"
  }

  amount {
    specified_amount {
      currency_code = "INR"
      units         = tostring(var.monthly_budget_inr)
    }
  }

  # Thresholds are chosen so that a NORMAL month fires nothing. The previous 50% rule fired around the
  # 18th of every month against a run rate that sits at ~84% of budget, which trains the reader to
  # delete budget mail. Every rule below means something has actually moved.
  #
  #   90%  current   -- drift: reachable only if the month runs ~7% above the measured rate.
  #  100%  current   -- the envelope is gone; ~19% above the measured rate.
  #  150%  current   -- runaway; half again the envelope. Something is broken, not busy.
  #  100%  forecast  -- the one that earns its place. Current-spend thresholds tell you the month is
  #                     already lost; a forecast breach fires while there is still a month left to act in.
  threshold_rules {
    threshold_percent = 0.9
  }

  threshold_rules {
    threshold_percent = 1.0
  }

  threshold_rules {
    threshold_percent = 1.5
  }

  threshold_rules {
    threshold_percent = 1.0
    spend_basis       = "FORECASTED_SPEND"
  }

  all_updates_rule {
    monitoring_notification_channels = local.spend_notification_channel_ids

    # Without this, budget alerts also go to every Billing Account Administrator and User by default.
    # Kept configurable: the default IAM recipients are a SECOND delivery path that does not depend on a
    # Cloud Monitoring channel existing or being enabled, and for a two-person operation that redundancy
    # is worth more than the duplicate mail it produces.
    disable_default_iam_recipients = !var.budget_notify_default_iam_recipients
  }
}

# ---------------------------------------------------------------------------------------------------
# Trial-credit runway budget
#
# The two monthly budgets answer "is this month normal". They cannot answer the question that actually
# ends the product: "will the free-trial credit last until its expiry date". Billing account 014C0A is a
# Free Trial account. Google stops every resource when the trial ends -- and the trial ends at the
# EARLIER of the expiry date and credit exhaustion. Measured 2026-09-11: INR 28,262.87 remained on
# 2026-08-20; INR 4,958.57 of promotion credit was drawn between then and 2026-09-10; combined burn is
# INR ~262/day gross, of which roughly INR 240/day draws on the promotion once the monthly Cloud Run
# free tier is used up (it ran out on 2026-09-05 this month). Sixty-seven days remain to 2026-11-16, so
# the projected draw is INR ~16,500 against INR ~23,300 remaining. That is a margin of roughly one
# month of burn. A runaway that lasts three weeks exhausts the credit BEFORE the expiry date, and the
# monthly budgets above would report it only as a percentage of a month.
#
# This budget is scoped to the WHOLE billing account, runs over a custom period ending on the expiry
# date, and its amount is the credit remaining at the start of that period. Each threshold is therefore
# "you have consumed N% of the runway". Forecast rules are not available on custom-period budgets
# (Google restricts FORECASTED_SPEND to calendar periods), so the 75% and 90% rules stand in for one.
#
# Only the prod root manages this: an account-wide budget must have exactly one owner.
#
# It measures the promotion draw precisely rather than gross: every credit type EXCEPT the promotion is
# netted off, because the Cloud Run free tier and similar discounts do not consume trial credit. Using
# gross here would overstate consumption by about INR 1,200 over the period -- a fifth of the margin.
# ---------------------------------------------------------------------------------------------------

resource "google_billing_budget" "trial_runway" {
  count = var.manage_billing_budget && var.manage_trial_runway_budget && var.env == "prod" ? 1 : 0

  billing_account = var.billing_account_id
  display_name    = "custoking-trial-credit-runway-to-${var.trial_expiry_date}"

  budget_filter {
    # No project filter: the credit is an account-level fact and every project on the account draws it,
    # including the auto-created "My First Project" that sits on 014C0A at zero cost today.
    custom_period {
      start_date {
        year  = tonumber(split("-", var.trial_runway_start_date)[0])
        month = tonumber(split("-", var.trial_runway_start_date)[1])
        day   = tonumber(split("-", var.trial_runway_start_date)[2])
      }
      end_date {
        year  = tonumber(split("-", var.trial_expiry_date)[0])
        month = tonumber(split("-", var.trial_expiry_date)[1])
        day   = tonumber(split("-", var.trial_expiry_date)[2])
      }
    }

    credit_types_treatment = "INCLUDE_SPECIFIED_CREDITS"
    # Everything that is NOT the free-trial promotion. The billing export records the Cloud Run free tier
    # as type DISCOUNT ("CPU Allocation Time", "Memory Allocation Time"); the others are listed so a
    # future discount of a different type still nets off rather than silently counting as credit draw.
    credit_types = [
      "DISCOUNT",
      "FREE_TIER",
      "SUSTAINED_USAGE_DISCOUNT",
      "COMMITTED_USAGE_DISCOUNT",
      "COMMITTED_USAGE_DISCOUNT_DOLLAR_BASE",
      "SUBSCRIPTION_BENEFIT",
      "RESELLER_MARGIN",
    ]
  }

  amount {
    specified_amount {
      currency_code = "INR"
      units         = tostring(var.trial_credit_remaining_inr)
    }
  }

  threshold_rules {
    threshold_percent = 0.5
  }

  threshold_rules {
    threshold_percent = 0.75
  }

  threshold_rules {
    threshold_percent = 0.9
  }

  threshold_rules {
    threshold_percent = 1.0
  }

  all_updates_rule {
    monitoring_notification_channels = local.spend_notification_channel_ids
    disable_default_iam_recipients   = !var.budget_notify_default_iam_recipients
  }
}
