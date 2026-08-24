# Cost analysis without the billing export

The Cloud Billing **usage-cost** export for account `014C0A-C6B9AF-5FABC0` cannot be enabled. Six
attempts across every configuration available to us fail with a generic server error, while the
**pricing** export succeeds every time on the same account and dataset — and Google's own docs put
pricing at *strictly higher* privilege than usage cost. That isolates the fault to a broken config
record on their side. There is no API for export configuration (`cloudbilling` v1 and v1beta expose
none), so there is no scripted workaround either.

So cost is reconstructed from usage instead.

## What exists

| Object | What it is |
| --- | --- |
| `cost_analysis.daily_service_cost` | Per-service, per-metric daily cost. Appended by the collector. |
| `cost_analysis.v_daily_cost` | Daily totals with the fixed/variable split. |
| `cost_analysis.v_service_cost` | Per-service cost and INR/day. |
| `cost_analysis.historical_v1` | Pre-migration billing export, restored from Avro. Real Google data. |
| `cost_analysis.historical_resource_v1` | Same, resource-level. |

`historical_*` is the genuine article — actual billing rows from account `018AC9` for the now-deleted
`custoking` project. It is the only surviving record of pre-migration cost, archived in
`gs://custoking-prod-billing-archive/`. `daily_service_cost` is an **estimate**; every row carries a
`method` column so the two are never confused.

## Running it

```bash
python scripts/cost-analysis-collect.py                         # yesterday
python scripts/cost-analysis-collect.py 2026-08-18 2026-08-19  # selected days
```

Each selected day is deleted and reloaded independently, so re-running corrects rather than duplicates.
The production Cloud Run job runs the same Python file daily; workstation and scheduled results therefore
share one implementation.

## Viewing the report

The current estimate is available without the billing export:

```sql
SELECT *
FROM `custoking-prod.cost_analysis.v_daily_cost`
ORDER BY usage_date DESC
LIMIT 90;

SELECT *
FROM `custoking-prod.cost_analysis.v_service_cost`
ORDER BY inr_per_day DESC
LIMIT 200;
```

When the standard usage-cost export is available, this is the authoritative month-to-date cut by
project, service, and SKU. Replace `BILLING_EXPORT_PROJECT` with the project named in the Billing & Cost
dashboard's reporting-sources panel:

```sql
SELECT
  project.id AS project_id,
  service.description AS service,
  sku.description AS sku,
  currency,
  ROUND(SUM(cost), 4) AS gross_cost,
  ROUND(SUM(cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)), 4) AS net_cost,
  MAX(export_time) AS newest_export
FROM `BILLING_EXPORT_PROJECT.billing_export.gcp_billing_export_v1_*`
WHERE DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH)
GROUP BY 1, 2, 3, 4
ORDER BY gross_cost DESC;
```

Do not union these sources into one total. The standard export is invoice-grade Google billing data;
`cost_analysis` is the explicitly labelled fallback for operational decisions while that source is late
or unavailable.

## What it is and is not

It **gives up** true SKU granularity, sustained- and committed-use discounts, tax, and anything not
modelled in the rate table. It is an estimate.

It **gains** attribution per Cloud Run *service* rather than per SKU — the more useful cut for deciding
what to optimise — and freshness, since billing export lands hours late and carries "no delivery or
latency guarantees" while metrics are near real time.

Cloud SQL is a **measured constant** (INR 102.70/day), not a computed one: it dominates the bill, is
exactly fixed, and was measured over thirteen consecutive days with a coefficient of variation of zero.
That is what Google actually charged. Its rows are marked `measured_constant` so they read differently
from the computed ones.

## The finding it makes obvious

Per-service attribution mostly confirms the shape the unit-economics work predicted: the database is
~93% of spend and every application service costs under INR 1.15/day. Optimising application code has
almost no effect on the bill. The floor is the whole problem.

Rates are pinned to asia-south2 SKU prices as of 2026-08-19, in `scripts/cost-analysis-collect.py`
and `tools/live-dashboard/cost.mjs`. Re-check them if the pricing export is ever re-enabled.
