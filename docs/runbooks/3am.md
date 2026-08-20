# 3am

One page. If you are reading this at a sensible hour, read the decision anyway — it is the part that
matters, and it is easier to agree with now than at 3am.

---

## STOP. Does this need you right now?

**Custoking is used during Indian school hours.** At 3am, essentially every technical failure affects
nobody. A total outage at 02:00 is a thing to fix over breakfast, not a thing to fix now.

You are also worse at this than you think. Cognitive performance in the first minutes after waking is
comparable to having been awake fourteen to sixteen hours, and the effect is *circadially amplified* —
worst when woken during the biological night. Recovery takes two to four hours. The functions that come
back slowest are working memory, planning and sequencing: exactly what debugging needs. Knowing the
system does not protect you, because the impairment is in the machinery you would use to recall it.

**Only three things justify acting now.** Everything else waits.

| Act now | Why it cannot wait |
| --- | --- |
| **Money is being destroyed** | A runaway spends compounding money while you sleep. Budgets are notifications, not caps. |
| **Data or access is being destroyed** | Irreversible. Every hour makes recovery worse. |
| **Recovery capability is gone** | Backups failing *and* the database in trouble. Backups failing alone is a morning problem. |

If it is not one of those three: **write down what you saw, and go back to sleep.**

---

## If it IS one of those three

Run these in order. They are verbatim and verified — do not compose new ones while impaired.

**1. What is actually serving?**

```bash
gcloud run services describe custoking-api-gateway-prod --region=asia-south2 \
  --project=custoking-prod --format="value(status.traffic[0].revisionName,status.traffic[0].percent)"
```

**2. Is the database alive?**

```bash
gcloud sql instances describe custoking-db-prod --project=custoking-prod \
  --format="value(state,settings.activationPolicy)"
```

`RUNNABLE ALWAYS` is healthy. Anything else is your answer.

**3. What is actually erroring?**

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND severity>=ERROR' \
  --project=custoking-prod --limit=20 --freshness=1h \
  --format="value(resource.labels.service_name,jsonPayload.message)"
```

**4. Roll back — the one action worth taking half asleep.**

```bash
gh workflow run rollback.yml -f service=all -f environment=prod -f reason="3am incident"
```

`reason` is **required** — the command fails without it, and the failure is a usage error that is
genuinely hard to read while half asleep. `service` accepts `all` or a single service name; valid ones
are `school-core-service`, `identity-service`, `operations-service`, `billing-service`,
`platform-service`, `api-gateway`, `frontend`.

Rollback is a traffic shift, not a rebuild. It is fast and it is reversible. If you are unsure whether
a release caused this, roll back anyway and investigate in daylight — the cost of an unnecessary
rollback is close to zero, and the cost of debugging a live incident at 3am is not.

**5. Is anyone actually affected?**

```bash
python scripts/assert-product-liveness.py
```

Reports whether anyone can use the product. Remember it counts *unexpired sessions*, so it lags — a
non-zero reading does not prove the product works right now, only that it worked recently.

---

## Do NOT do these at 3am

- **Do not run migrations, or anything with `terraform apply`.** Sequencing is the first faculty to go.
- **Do not delete anything.** Not a table, not a dataset, not a project. If something looks like it
  should be removed, that judgement is exactly what you cannot trust right now.
- **Do not "just try" a config change in production.** Every silent failure this system has produced —
  the metric that collected nothing for twenty days, the job that exited 0 while writing nothing, the
  guard neutered by its own default — looked correct at the time to someone fully awake.
- **Do not trust a green check.** A command that fails and a system that is healthy can print the same
  thing. If a check passes, ask what it would have printed had it been broken.

---

## In the morning

- What actually happened, in one paragraph, written before you forget.
- If an alert woke you and should not have, **change or delete that alert the same day.** An alert that
  wakes you without earning it will do so again, and the cost is that you start ignoring all of them.
- If nothing woke you and it should have, that gap is worth more attention than the incident.
