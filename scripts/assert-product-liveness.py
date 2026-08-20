#!/usr/bin/env python3
"""Asserts that people can actually USE the product, during the hours they would be trying to.

WHY THIS EXISTS, AND WHY IT IS NOT AN ALERT POLICY

Research into this system's alerting produced one clear top recommendation: alert on "nobody can sign
in", because it is a true symptom-level signal that no infrastructure metric can fake. A service can be
green on every uptime check, every SLO and every 5xx rate while being useless to every actual user.

Cloud Monitoring cannot express it. The condition is "zero sessions DURING SCHOOL HOURS", and Google's
own docs say you "can't configure the alerting policy to monitor conditions only for certain time
periods". Their suggested workaround, snoozes, is worse than useless here: a snooze suppresses
notifications, does not create incidents, and CLOSES existing ones -- so a nightly recurring snooze
would destroy the record of an overnight failure rather than defer it.

The usual answer is to time-gate at the notification layer, which on PagerDuty means Support Hours --
a Professional-plan feature. So that route costs money.

But a cron schedule IS a time gate, and it is free. Running the check only during school hours means the
check simply does not exist at 3am, so there is nothing to suppress and nothing to snooze. Zero sessions
at midnight is not a condition that gets filtered out; it is a question nobody asks.

The failure is emitted as an ERROR-severity structured log, which a log-based metric and alert policy
turn into a notification through the normal path.

WHAT session_active_users ACTUALLY MEASURES

It counts people holding an UNEXPIRED session, not people currently doing anything. Measured at 00:30
IST with nobody awake: 5 active users, 9 open sessions. So this is a LAGGING signal -- the product
could be broken for an hour and this would still read five, until those sessions expire.

That makes the check insensitive, and deliberately so. It fires only when every signal is zero: no
unexpired session anywhere, no open sessions, and no sign-ins. That is not "the product is slow", it is
"nobody has been able to get in for long enough that every existing session aged out". Insensitive and
trustworthy beats sensitive and ignored, which is the entire lesson of the alerting work that led here.
If a faster signal is wanted later, successful gateway requests are the one to build on -- but only
once per-tenant request data has accumulated enough to know what a normal quiet period looks like.

WHAT IT DELIBERATELY DOES NOT DO

It does not alert on low usage, only on ZERO. With roughly eleven tenants and seventeen staff accounts,
a quiet hour is normal and a threshold on "fewer than N" would fire constantly. Zero across a whole
school morning is different in kind: it means nobody got in.
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

PROJECT = os.environ.get("LIVENESS_PROJECT", "custoking-prod")
ENVIRONMENT = os.environ.get("LIVENESS_ENV", "prod")

# How far back to look. Long enough that a genuinely idle half-hour between lessons does not trip it,
# short enough that a morning-long outage is caught while the morning is still happening.
WINDOW_MINUTES = int(os.environ.get("LIVENESS_WINDOW_MINUTES", "90"))

# Any ONE of these being non-zero means the product is reachable and working for somebody. They are
# checked together rather than separately because they fail independently: sessions can persist while
# login is broken, and logins can succeed while session storage is broken.
SIGNALS = [
    f"logging.googleapis.com/user/custoking/{ENVIRONMENT}/session_active_users",
    f"logging.googleapis.com/user/custoking/{ENVIRONMENT}/session_active_sessions",
    f"logging.googleapis.com/user/custoking/{ENVIRONMENT}/session_logins_recent",
]

# These gauges ride through a distribution, so a true zero reports as the upper bound of the underflow
# bucket rather than as 0. Measured against production with every queue drained: exactly 0.500.
ZERO_FLOOR = 0.5


def access_token():
    if os.environ.get("K_SERVICE") or os.environ.get("CLOUD_RUN_JOB"):
        req = urllib.request.Request(
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token",
            headers={"Metadata-Flavor": "Google"})
        with urllib.request.urlopen(req, timeout=10) as res:
            return json.load(res)["access_token"]
    return subprocess.run(["gcloud", "auth", "print-access-token"],
                          capture_output=True, text=True, shell=(os.name == "nt"),
                          check=True).stdout.strip()


def peak(token, metric):
    """Highest value this metric reached in the window, or None if it reported nothing at all."""
    end = datetime.now(timezone.utc)
    start = end - timedelta(minutes=WINDOW_MINUTES)
    params = [
        ("filter", f'metric.type="{metric}"'),
        ("interval.startTime", start.strftime("%Y-%m-%dT%H:%M:%SZ")),
        ("interval.endTime", end.strftime("%Y-%m-%dT%H:%M:%SZ")),
        ("aggregation.alignmentPeriod", "300s"),
        ("aggregation.perSeriesAligner", "ALIGN_PERCENTILE_50"),
        ("aggregation.crossSeriesReducer", "REDUCE_MAX"),
    ]
    url = (f"https://monitoring.googleapis.com/v3/projects/{PROJECT}/timeSeries?"
           + urllib.parse.urlencode(params))
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            series = json.load(res).get("timeSeries", [])
    except urllib.error.HTTPError as exc:
        # A query failure is NOT evidence of an outage. Reporting one would be a false alarm, and a
        # check that cries wolf gets ignored -- which is exactly the failure this is meant to prevent.
        print(json.dumps({
            "severity": "WARNING",
            "message": "product.liveness.query_failed",
            "metric": metric,
            "error": exc.read().decode("utf-8", "replace")[:200],
        }))
        return None

    values = [float(p["value"].get("doubleValue") or 0)
              for s in series for p in s.get("points", [])]
    return max(values) if values else None


def main():
    token = access_token()
    readings = {}
    for metric in SIGNALS:
        readings[metric.rsplit("/", 1)[-1]] = peak(token, metric)

    # Absent is not the same as zero, and the difference decides whether this is an outage or a broken
    # pipeline. If NOTHING reported, the emitting service is probably down or the metric is broken --
    # which the telemetry-liveness assertion already covers, so this stays quiet rather than
    # double-reporting the same fault under a misleading name.
    reported = {k: v for k, v in readings.items() if v is not None}
    if not reported:
        print(json.dumps({
            "severity": "WARNING",
            "message": "product.liveness.no_signal",
            "detail": "no session metric reported at all; see assert-telemetry-liveness",
            "window_minutes": WINDOW_MINUTES,
        }))
        return 0

    alive = {k: v for k, v in reported.items() if v > ZERO_FLOOR}

    if alive:
        print(json.dumps({
            "severity": "INFO",
            "message": "product.liveness.ok",
            "window_minutes": WINDOW_MINUTES,
            "readings": reported,
        }))
        return 0

    # Every signal that reported, reported zero, during hours when the schedule says people should be
    # using this. That is the alert.
    print(json.dumps({
        "severity": "ERROR",
        "message": "product.liveness.nobody_active",
        "detail": ("No active users, no open sessions and no sign-ins during school hours. "
                   "Infrastructure can be entirely green while this is true."),
        "window_minutes": WINDOW_MINUTES,
        "readings": reported,
    }))
    return 1


if __name__ == "__main__":
    sys.exit(main())
