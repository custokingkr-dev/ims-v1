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
node scripts/cost-analysis-collect.mjs              # yesterday
node scripts/cost-analysis-collect.mjs 2026-08-18 2026-08-19   # a range, replaces those days
```

A single day appends. A range replaces, so re-running corrects rather than duplicates.

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

Rates are pinned to asia-south2 SKU prices as of 2026-08-19, in `scripts/cost-analysis-collect.mjs`
and `tools/live-dashboard/cost.mjs`. Re-check them if the pricing export is ever re-enabled.
