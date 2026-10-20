# Dedicated dev storage for precommit erasure intents, outside SQL recovery.
# This module cannot change application images, traffic or photo buckets.
resource "google_storage_bucket" "journal" {
  project                     = "custoking-dev"
  name                        = "custoking-dev-erasure-journal"
  location                    = "ASIA-SOUTH2"
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  labels = {
    app     = "custoking"
    env     = "dev"
    purpose = "external-erasure-intents"
  }

  # No lifecycle expiry or irreversible retention lock is invented here.
  versioning {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_project_iam_custom_role" "writer" {
  project     = "custoking-dev"
  role_id     = "erasureJournalWriter_dev"
  title       = "Dev erasure journal create and verify"
  description = "Create immutable intent objects and verify conflicts; no list, update or delete."
  permissions = ["storage.objects.create", "storage.objects.get"]

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_storage_bucket_iam_member" "writer" {
  bucket = google_storage_bucket.journal.name
  role   = google_project_iam_custom_role.writer.name
  member = "serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com"

  condition {
    title       = "dev_erasure_intents_only"
    description = "School runtime may create and verify intent objects only."
    expression  = "resource.name.startsWith('projects/_/buckets/custoking-dev-erasure-journal/objects/intents/')"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_project_iam_custom_role" "control_reader" {
  project     = "custoking-dev"
  role_id     = "erasureJournalControlReader_dev"
  title       = "Dev erasure journal epoch read"
  description = "Read the externally managed source epoch; no create, update, delete or list."
  permissions = ["storage.objects.get"]

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_storage_bucket_iam_member" "control_reader" {
  bucket = google_storage_bucket.journal.name
  role   = google_project_iam_custom_role.control_reader.name
  member = "serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com"

  condition {
    title       = "dev_erasure_control_read_only"
    description = "School runtime can read source epochs but cannot activate or replace them."
    expression  = "resource.name.startsWith('projects/_/buckets/custoking-dev-erasure-journal/objects/control/')"
  }

  lifecycle {
    prevent_destroy = true
  }
}

output "bucket" {
  value = google_storage_bucket.journal.name
}

output "intent_prefix" {
  value = "intents/"
}
