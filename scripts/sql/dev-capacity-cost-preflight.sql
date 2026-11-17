-- Read-only invoice-grade cost preflight. Query job must be billed to custoking-dev.
-- The shared billing account exports to custoking-prod; WHERE isolates dev usage.
-- The wildcard covers every billing account's DETAILED export in the dataset, so the month stays whole
-- when the projects move between billing accounts (they did on 2026-11-16). Never name one account's table.
-- Freshness (latest_export_time, latest_usage_end and both lags) comes ONLY from rows of the account dev
-- bills to now, passed as the @billing_account_id query parameter (never written here: the repository is
-- public). A closed account's export keeps delivering late rows after it stops, so it must not look fresh;
-- with no current-account row those columns are NULL and the collector fails closed.
-- The budget and its 80% guard are read live by scripts/read-dev-capacity-evidence.ps1; never hardcode them.
-- Do not sum standard and detailed exports together: they describe the same usage.
-- Use --maximum_bytes_billed=104857600 and do not replace gross with credit-adjusted net.
SELECT
  'custoking-dev' AS project,
  currency,
  ROUND(SUM(cost), 4) AS gross_cost_inr,
  COUNT(*) AS matching_rows,
  MAX(IF(billing_account_id = @billing_account_id, export_time, NULL)) AS latest_export_time,
  MAX(IF(billing_account_id = @billing_account_id, usage_end_time, NULL)) AS latest_usage_end,
  TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(IF(billing_account_id = @billing_account_id, export_time, NULL)), MINUTE) / 60.0 AS export_lag_hours,
  TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(IF(billing_account_id = @billing_account_id, usage_end_time, NULL)), MINUTE) / 60.0 AS usage_lag_hours
FROM `custoking-prod.billing_export.gcp_billing_export_resource_v1_*`
WHERE project.id = 'custoking-dev'
  AND DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH)
GROUP BY currency;
