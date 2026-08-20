resource "google_monitoring_notification_channel" "operator_email" {
  for_each = var.notification_email_addresses

  project      = var.project
  display_name = "Custoking ${upper(var.env)} ${each.key} operator email"
  type         = "email"
  labels = {
    email_address = each.value
  }
  user_labels = local.common_user_labels
}

locals {
  # Gated rather than emptied. Removing the addresses looks equivalent and is not: a notification
  # channel cannot be deleted while any policy still references it, so Terraform plans the delete and
  # the policy updates together, the delete fails with a 400 listing all 63 referrers, and the apply
  # aborts before the updates commit -- leaving every policy still notifying. Keeping the channels and
  # dereferencing them makes "this environment cannot interrupt a human" one greppable flag, and
  # reversible without recreating anything.
  effective_notification_channel_ids = var.enable_alert_notifications ? distinct(concat(
    var.notification_channel_ids,
    [for channel in google_monitoring_notification_channel.operator_email : channel.name],
  )) : []
}
