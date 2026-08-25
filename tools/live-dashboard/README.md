# Live dashboard

A single-page operations dashboard that reads the Cloud Monitoring API directly. Zero dependencies, no
build step, no container image.

```bash
cd tools/live-dashboard
node server.mjs          # → http://localhost:8787
```

Requires `gcloud auth login`. Override with `DASHBOARD_PROJECT` (default `custoking-prod`),
`DASHBOARD_ENV` (default `prod`), `PORT` (default `8787`).

## Authentication

Cloud Run uses Google OAuth with an email allowlist. Each login creates a cryptographically random,
ten-minute state nonce bound to an HttpOnly browser cookie, an S256 PKCE verifier/challenge pair, and a
single-use marker in the serving process. The callback rejects missing, expired, mismatched, tampered, or
locally reused state before exchanging the authorization code; the authorization code itself remains
provider-enforced single use across instances. OAuth token and JWKS requests have bounded response sizes,
HTTP status checks, and ten-second timeouts. `POST /auth/logout` clears both browser cookies and best-effort
revokes the encrypted session in the current process. It does not provide cross-instance revocation; rotate
`SESSION_SECRET` to invalidate every outstanding encrypted session globally.

Required production variables are `OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET`,
`DASHBOARD_ALLOWED_EMAILS`, `SESSION_SECRET`, and the pinned `DASHBOARD_PUBLIC_URL`. Authentication fails
closed if the OAuth client is absent. `DASHBOARD_AUTH=off` is only for a trusted local workstation.

Run the dependency-free authentication regression suite with:

```bash
node --test tools/live-dashboard/*.test.mjs
```

## Why this rather than a Cloud Monitoring dashboard

The native dashboards are live and unusable: ~76 panels across 10 dashboards with no hierarchy, and --
the part that matters -- an empty chart and a healthy chart are the same picture. That is not cosmetic.
Five log-based metrics collected zero series for twenty days behind panels that rendered as flat lines,
and nothing anywhere errored.

So every panel here resolves to one of five states, each of which looks different:

| state | meaning |
|---|---|
| `live` | reporting a non-zero value |
| `zero` | reporting a real, measured zero — explicitly labelled, never a blank |
| `stale` | emitted before, nothing in the window; shows how long ago |
| `never` | no point in 30 days. The metric exists and has never been written to |
| `failed` | the query itself errored; shows the error |

When a panel returns nothing over the requested window the server re-queries over thirty days, which is
what separates "quiet right now" from "this metric has never emitted a point in its life".

## Two things that will mislead you if you don't know them

**Gauges carried through distributions read their zero as 0.5.** A log-based metric can only be a counter
or a distribution, so a gauge is smuggled through a distribution and read at a percentile. Percentiles
interpolate *within a bucket*, so a true zero lands in the underflow bucket and reports its upper bound.
The bounds start at 0.5, so an empty queue reads 0.5. Panels declare `zeroBelow` to floor it. The same
artefact was making two alert policies fire permanently on `> 0` until it was fixed.

**Refresh is 5 minutes on purpose.** Since 2 October 2025 Cloud Monitoring bills read calls by *time
series returned* ($0.50/million, first million free per billing account). A full render is ~75 series.
At 5 minutes a tab left open all day stays free; at 60 seconds it is ~3.2M series/month.

## Deploying it

It runs unchanged on Cloud Run -- it uses the instance metadata server when `K_SERVICE` is set and
`gcloud` otherwise. The live deployment uses the OAuth flow above because the consumer Google identity
could not be admitted reliably through IAP. Give the service a dedicated identity with only
`roles/monitoring.viewer`, keep the email allowlist narrow, and leave `min-instances` at zero.
