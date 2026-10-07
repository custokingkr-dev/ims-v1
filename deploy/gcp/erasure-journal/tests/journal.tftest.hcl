mock_provider "google" {}

run "private_independent_dev_storage" {
  command = plan

  assert {
    condition     = google_storage_bucket.journal.project == "custoking-dev" && google_storage_bucket.journal.name == "custoking-dev-erasure-journal" && google_storage_bucket.journal.location == "ASIA-SOUTH2"
    error_message = "The journal must target its exact dedicated dev bucket."
  }
  assert {
    condition     = google_storage_bucket.journal.uniform_bucket_level_access && google_storage_bucket.journal.public_access_prevention == "enforced" && !google_storage_bucket.journal.force_destroy && google_storage_bucket.journal.versioning[0].enabled
    error_message = "Private access and recovery safeguards must remain enabled."
  }
  assert {
    condition     = length(google_storage_bucket.journal.lifecycle_rule) == 0 && length(google_storage_bucket.journal.retention_policy) == 0
    error_message = "No unapproved journal expiry or irreversible retention lock may be introduced."
  }
}

run "runtime_cannot_replace_or_delete_intents" {
  command = plan

  assert {
    condition     = toset(google_project_iam_custom_role.writer.permissions) == toset(["storage.objects.create", "storage.objects.get"])
    error_message = "The writer must have only create and conflict-verification reads."
  }
  assert {
    condition     = google_storage_bucket_iam_member.writer.member == "serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com" && google_storage_bucket_iam_member.writer.condition[0].expression == "resource.name.startsWith('projects/_/buckets/custoking-dev-erasure-journal/objects/intents/')"
    error_message = "Runtime access must be restricted to the dev intent prefix in this bucket."
  }
  assert {
    condition     = toset(google_project_iam_custom_role.control_reader.permissions) == toset(["storage.objects.get"]) && google_storage_bucket_iam_member.control_reader.condition[0].expression == "resource.name.startsWith('projects/_/buckets/custoking-dev-erasure-journal/objects/control/')"
    error_message = "Runtime must never receive create/update/delete access to its activation epoch."
  }
}
