# Identity reset-drain failure monitoring

The dev logs-based Scheduler failure counter matched the four domain/platform jobs but omitted the newly enabled identity password-reset drain. The exact existing `custoking/dev/async_scheduler_failure_count` filter now includes all five service names. Source Terraform was corrected to preserve this coverage on future reviewed applies.

Only the existing dev metric filter was updated, using a structured config file and exact project/name checks. Independent readback matched the expected filter and the entire prior metric descriptor. Alert policy thresholds, channels and notification settings were not changed. No test incident or notification was generated; detection routing and recipient receipt still require a controlled operator exercise. [Sanitized readback](acceptance-scheduler-metric.json).

Reference: [Cloud Logging metric update API and required permissions](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.metrics/update). The local update helper remains an ignored one-time artifact; the authoritative repeatable configuration is `deploy/gcp/observability/operational_alerts.tf`.
