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
  effective_notification_channel_ids = distinct(concat(
    var.notification_channel_ids,
    [for channel in google_monitoring_notification_channel.operator_email : channel.name],
  ))
}
