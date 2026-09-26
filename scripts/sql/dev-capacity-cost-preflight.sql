-- Read-only invoice-grade cost preflight. Query job must be billed to custoking-dev.
-- The shared billing account exports to custoking-prod; WHERE isolates dev usage.
-- Do not sum standard and detailed exports together: they describe the same usage.
-- Use --maximum_bytes_billed=104857600 and do not replace gross with credit-adjusted net.
SELECT
  'custoking-dev' AS project,
  currency,
  ROUND(SUM(cost), 4) AS gross_cost_inr,
  COUNT(*) AS matching_rows,
  MAX(export_time) AS latest_export_time,
  MAX(usage_end_time) AS latest_usage_end,
  TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(export_time), MINUTE) / 60.0 AS export_lag_hours,
  TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(usage_end_time), MINUTE) / 60.0 AS usage_lag_hours,
  2000.0 AS observed_dev_budget_inr,
  1600.0 AS unchanged_80_percent_guard_inr,
  ROUND(SUM(cost), 4) > 1600.0 AS already_over_guard_before_run
FROM `custoking-prod.billing_export.gcp_billing_export_resource_v1_014C0A_C6B9AF_5FABC0`
WHERE project.id = 'custoking-dev'
  AND DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH)
GROUP BY currency;
