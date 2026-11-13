# Dependency reconciliation — 2026-11-14

This integration applies the open Dependabot proposals to dev base
`ed8aeba5e7bc2609c3fb46d3d74b8cb0f5538ba3` (merge of #382) in one branch,
`chore/deps-reconcile-2026-11-14`, the same way PR #360 did on 2026-10-20.
Every target is exactly the version its Dependabot PR proposed. Nothing newer
was taken, so the 7-day cooldown in `.github/dependabot.yml` still holds.

| Original PR | Integrated target |
| --- | --- |
| 363 | nginx 1.31.7-alpine3.24, digest `892bb2152464f0c5d8972593a5f5afaf8054552580b699d95f8ff4af08c2d33d` (was 1.31.6, `df221db8…`) |
| 365 | OpenTelemetry GCP auth extension 1.61.0-alpha (was 1.60.0-alpha) |
| 366 | Google Auth Java 1.54.0 (was 1.53.0) |
| 368 | gRPC BOM 1.85.1 (was 1.84.0) |
| 369 | lucide-react 1.60.0 (was 1.47.0) |
| 370 | @axe-core/playwright and axe-core 4.14.0 (were 4.13.0) |
| 371 | Google Cloud libraries BOM 26.90.0 (was 26.89.0) |
| 372 | Playwright 1.64.1: @playwright/test, playwright, playwright-core (were 1.64.0) |
| 374 | setup-java v6.1.0; CodeQL v4.39.2 for init, analyze and upload-sarif; reviewed-pin allowlist updated with them |
| 377 | axios 1.21.0 (was 1.20.0) |
| 379 | react-router-dom and react-router 7.18.5 (were 7.18.4), set-cookie-parser 2.7.3 (was 2.7.2) |
| 378 | **Replaced, not integrated.** The gateway takes auto-instrumentations-node 0.81.2 on the SDK 2.x line and Dependabot stops proposing the 3.x set. See "OpenTelemetry decision". |
| 373 | **Obsolete.** webauthn-server-core is no longer a dependency. Closed separately. |

## Why one branch

- #371/#368 edit adjacent `pom.xml` lines 31-32 and #365/#366 edit lines 41-42.
  All five frontend PRs rewrite the same lockfile. Merging one by one makes each
  next PR conflict and re-run CI.
- Root `pom.xml` and `_test-java-service.yml` are global deploy triggers. One
  merge is one full dev deploy, instead of about eleven.
- #374 cannot pass on its own (next section). #378 cannot install at all.

## Maven parent (#365, #366, #368, #371)

`pom.xml` properties:

- `google-cloud-libraries-bom.version` 26.89.0 → 26.90.0 (`pom.xml:31`)
- `grpc.version` 1.84.0 → 1.85.1 (`pom.xml:32`)
- `opentelemetry-gcp-auth-extension.version` 1.60.0-alpha → 1.61.0-alpha (`pom.xml:41`)
- `google-auth-library.version` 1.53.0 → 1.54.0 (`pom.xml:42`)

#365 and #366 belong together: gcp-auth-extension 1.61.0-alpha declares
google-auth-library 1.54.0.

`dependency:tree` on this branch shows:

- google-auth-library 1.54.0 and gcp-auth-extension 1.61.0-alpha in all five
  services;
- gRPC 1.85.1 (`grpc-api`, `grpc-netty-shaded`) in billing, operations and
  school-core. Identity and platform do not import grpc-bom and keep
  `grpc-api` 1.83.1 at runtime through Spring Boot, as before;
- the Netty shaded inside `grpc-netty-shaded` 1.85.1 is 4.2.17.Final (1.84.0
  shaded 4.2.16.Final), the same version Spring Boot 4.1.1 manages. No
  unshaded `io.netty` artifact is on any service's classpath;
- the service-local guava pins unchanged: 33.8.0-jre in billing, identity and
  platform, 33.7.2-jre in operations and school-core.

The dependency-convergence enforcer passes in every module.

## Frontend npm (#369, #370, #372, #377, #379)

`frontend/package.json` was edited with exact versions, not bare ranges.
Bare ranges would resolve to axios 1.21.1, lucide-react 1.72.0 and
Playwright 1.65.0, which no PR proposed and some of which are inside the
cooldown. Commands, run in `frontend/`:

```
npm install --package-lock-only --ignore-scripts axios@1.21.0 lucide-react@1.60.0
npm install --package-lock-only --ignore-scripts --save-exact react-router-dom@7.18.5
npm install --package-lock-only --ignore-scripts --save-dev @axe-core/playwright@4.14.0 @playwright/test@1.64.1
```

The resulting lockfile is byte-identical to the one the triage produced in a
scratch copy. Thirteen lock entries change, and nothing is added or removed:

| Lock entry | Change | Why |
| --- | --- | --- |
| `axios` | 1.20.0 → 1.21.0 | #377. Its dependency list is unchanged; follow-redirects stays at 1.16.2. |
| `lucide-react` | 1.47.0 → 1.60.0 | #369. Now declares `@types/react` as an optional peer. |
| `@types/react`, `@types/prop-types`, `csstype` | `dev` → `devOptional` | Follows from the optional peer above. Type-only packages: `npm ci` still installs them, and none reach the nginx image, which carries only the built bundle. |
| `react-router-dom` | 7.18.4 → 7.18.5 (exact pin kept) | #379. |
| `react-router` | 7.18.4 → 7.18.5 | Exact dependency of react-router-dom 7.18.5. |
| `set-cookie-parser` | 2.7.2 → 2.7.3 | Dependency of react-router, moved by #379. |
| `@axe-core/playwright` | 4.13.0 → 4.14.0 | #370. Adds `engines.node >=22.12.0`; we run Node 24. |
| `axe-core` | 4.13.0 → 4.14.0 | `~4.14.0` dependency of @axe-core/playwright 4.14.0. |
| `@playwright/test` | 1.64.0 → 1.64.1 | #372. |
| `playwright`, `playwright-core` | 1.64.0 → 1.64.1 | Exact dependencies of @playwright/test 1.64.1. |

Dependabot's own PRs each also flipped about twenty `"peer": true` flags.
Regenerating once avoids that churn.

Two behaviour changes were checked against our code:

- **axios 1.21.0.** Header defaults now only count plain objects or
  `AxiosHeaders`, and XSRF cookie reads become async. We set no default
  headers (only `Authorization` per request, `frontend/src/services/api.ts`)
  and use no XSRF cookie.
- **axe-core 4.14** turns `label-content-name-mismatch` on by default.
  `frontend/e2e/password-reset.spec.ts:20,37` asserts zero violations with all
  rules. It passes unchanged (4/4), and the nav audit, whose `wcag21a` tag
  filter includes the rule, reports no violations in any state. No UI change
  was needed.

All 150 names imported from `lucide-react` in `frontend/src` are exported by
1.60.0, and none resolves to one of its three deprecated icons (the Swiss franc
set). Checked against `node_modules/lucide-react/dist/lucide-react.d.ts`,
aliases included.

## Frontend image (#363)

`frontend/Dockerfile:12` is now:

```
FROM nginx:1.31.7-alpine3.24@sha256:892bb2152464f0c5d8972593a5f5afaf8054552580b699d95f8ff4af08c2d33d
```

`docker buildx imagetools inspect nginx:1.31.7-alpine3.24` on 2026-11-14
returned image index digest `sha256:892bb215…c2d33d`, the same as #363. Its
linux/amd64 manifest is `sha256:138af827713b7671ef4ee2deea73bc6f08ae265130ce350307e3e7b52cd4b51b`,
created 2026-10-14T18:51:30Z from `nginx/docker-nginx@4b9f658b` (`mainline/alpine`).

nginx 1.31.7 (14 Oct 2026) fixes five CVEs, per
[nginx CHANGES](https://nginx.org/en/CHANGES): CVE-2026-15176 (`alias` after
`rewrite`), CVE-2026-93479 (buffer overflow while evaluating string
expressions with variables), CVE-2026-95101 (HTTP/3 unknown frames),
CVE-2026-97629 (TLS session ticket key rotation) and CVE-2026-97205 (AIO with
SSI `include virtual`). CVE-2026-93479 may apply to us:
`frontend/nginx.conf` evaluates variables (`set $api_upstream`,
`proxy_pass $api_upstream`, `try_files $uri`). Exploitability was not
assessed; the fix is taken either way.

## Workflow actions (#374) and the reviewed-pin allowlist

Dependabot can edit workflow files but not
`scripts/audit-security-governance-controls.ps1`. The audit requires every
external `uses:` line to match a reviewed `owner/repo@version-comment` → SHA
entry, and requires the exact CodeQL analyze line in `codeql-analysis.yml`. So
#374 failed `static-architecture-audits` and, merged as-is, would also have
failed `release-static-gate` and stopped every later dev deploy.

This branch updates the workflows and the allowlist together:

- `actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6.1.0` in
  `_test-java-service.yml:21`, `build-release.yml:171` and `ci-pr.yml:157,242,295`.
- `github/codeql-action/{init,analyze}@1fc58d04a4d4374f921bdac573c3811c240f9c26 # v4`
  in `codeql-analysis.yml:35,41`, and `upload-sarif` at the same SHA in
  `security-scan.yml:72`. The repository keeps the `# v4` comment style, so the
  allowlist key stays `github/codeql-action@v4`.
- `scripts/audit-security-governance-controls.ps1`: the required analyze line
  (`:863`), `"actions/setup-java@v6.1.0"` (`:877`, replacing v6.0.1) and
  `"github/codeql-action@v4"` (`:886`).

The pins were checked against the upstream release refs with the GitHub API on
2026-11-14, peeling the CodeQL annotated tag to its commit:

| Upstream release | Tag ref | Reviewed commit | Checks |
| --- | --- | --- | --- |
| [setup-java v6.1.0](https://github.com/actions/setup-java/releases/tag/v6.1.0) | lightweight tag → commit | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` | Release published 2026-10-14, not draft or prerelease. Commit signature verified ("Support Maven plugin repositories in generated settings.xml (#1286)"). `compare main...b843ebf9` = behind 7, so it is on `main`. `actions/setup-java` is an organization repo, not a fork. |
| [CodeQL v4.39.2](https://github.com/github/codeql-action/releases/tag/v4.39.2) | annotated tag `bd01fe9e80db3cb1eb1368bd8f61d3eb5f43d1f4` → commit | `1fc58d04a4d4374f921bdac573c3811c240f9c26` | Tagger github-actions[bot], 2026-11-03. Commit signature verified ("Merge pull request #4286 … update-v4.39.2"). `compare releases/v4...1fc58d04` = behind 46, so it is on `releases/v4`. `github/codeql-action` is an organization repo, not a fork. |

Risk is low for both. setup-java 6.1.0 only adds opt-in settings.xml inputs,
and our workflows pass only `distribution`, `java-version` and `cache`. CodeQL
4.39.0 raised the minimum bundle to 2.20.7; we use the default bundle and no
`tools:`, `checkout_path` or TRAP-cache settings.

The audit was run locally under Windows PowerShell 5.1 (it has no PowerShell 7
dependency). With dev's allowlist and the bumped workflows it reports exactly
the nine violations #374's CI run reported: five "not in the reviewed pin
inventory: actions/setup-java@v6.1.0", three "pin differs … github/codeql-action@v4"
and one "missing required control: github/codeql-action/analyze@2892aa5e…".
With this branch's allowlist it passes. Pin checking stays enforced.

## OpenTelemetry decision (#378)

*Before.* #378 proposed `@opentelemetry/api` 1.10.0 and
auto-instrumentations-node 0.82.0 together with 2.x patch releases. Its
`service-test` and `docker-build (api-gateway)` jobs failed `npm ci` with
ERESOLVE: `@opentelemetry/resources@2.12.1` has peer `@opentelemetry/api
">=1.3.0 <1.10.0"`, while the root and auto-instrumentations 0.82.0 need
`^1.10.0`. #375 failed the same way the week before.

*Why.* auto-instrumentations-node 0.82.0 is built on the OpenTelemetry JS SDK
**3.x** generation: it depends on `@opentelemetry/sdk-node ^0.300.0` and
`@opentelemetry/resources ^3.0.0`, has peers `@opentelemetry/api ^1.10.0` and
`@opentelemetry/core ^3.0.0`, and needs Node >=22.15.0. The npm group allows
only minor and patch updates, and the 3.x set arrives partly as 0.x "minor"
bumps and partly as 2→3 majors, so Dependabot proposes a half-migrated set that
cannot install. It would do so every week.

*Now.*

- `services/api-gateway/package.json:18` takes
  `"@opentelemetry/auto-instrumentations-node": "^0.81.2"`, the newest release
  on the 2.x line (published 2026-10-16), via
  `npm install --package-lock-only --ignore-scripts @opentelemetry/auto-instrumentations-node@0.81.2`.
- `.github/dependabot.yml` ignores, for the npm block:
  `@opentelemetry/auto-instrumentations-node >= 0.82.0`,
  `@opentelemetry/sdk-node >= 0.300.0`,
  `@opentelemetry/exporter-trace-otlp-proto >= 0.300.0`,
  `@opentelemetry/api >= 1.10.0` and `@opentelemetry/resources >= 3.0.0`.
  `sdk-trace-base` has no 3.x; it is replaced by `@opentelemetry/sdk-trace`.
  Patches on the 2.x line (for example sdk-node 0.223.2, resources 2.12.1) can
  still be proposed. The edited file validates against the SchemaStore
  `dependabot-2.0.json` schema, and a deliberately wrong key (`version:`) is
  rejected by the same check.
- The 2.x patches #378 bundled (2.12.1 and 0.223.1, published 2026-11-09) fix
  `host.id` on Darwin/BSD and fetch/XHR redaction, according to their release
  notes as read in the triage. Neither matters on Linux Cloud Run, so they are
  left for a later Dependabot run.

The gateway lockfile changes seven entries and adds or removes none. Every one
stays on `@opentelemetry/instrumentation ^0.223.0` and `core ^2.9.0`, and
`@opentelemetry/api` stays at 1.9.1:

| Lock entry | Change |
| --- | --- |
| `@opentelemetry/auto-instrumentations-node` | 0.81.0 → 0.81.2 |
| `@opentelemetry/instrumentation-express` | 0.71.0 → 0.71.1 |
| `@opentelemetry/instrumentation-mysql` | 0.69.0 → 0.69.1 |
| `@opentelemetry/instrumentation-nestjs-core` | 0.69.0 → 0.70.0 |
| `@opentelemetry/instrumentation-pg` | 0.75.0 → 0.76.0 |
| `@opentelemetry/instrumentation-runtime-node` | 0.36.0 → 0.36.1 |
| `@opentelemetry/instrumentation-winston` | 0.67.0 → 0.68.1 |

The six instrumentation entries move because auto-instrumentations 0.81.2
raised their ranges. The gateway is a plain `http` server with no express,
MySQL, NestJS, pg or winston, so none of them patch anything at runtime. The
instrumentations that do (http, undici, dns, net) are unchanged.

### OpenTelemetry JS 3.x follow-up

This is the work that lets the ignore entries above be removed. Do it in one PR
that also deletes those entries. The 2.x stable packages are supported for one
year from the 3.0.0 release (2026-10-20), so this is not urgent.

- **Imports.** `services/api-gateway/tracing.js:7` requires
  `@opentelemetry/sdk-trace-base`, which 3.0 removes. Use
  `@opentelemetry/sdk-trace`.
- **BatchSpanProcessor constructor.** `tracing.js:72-74` is
  `new BatchSpanProcessor(exporter, { exportTimeoutMillis: 4000 })`. In 3.x it
  is `new BatchSpanProcessor({ exporter, exportTimeoutMillis: 4000 })`. The 3.x
  processor no longer reads `OTEL_BSP_*` itself; none are set in
  `deploy/` today.
- **Manifest.** `services/api-gateway/package.json:17-22`: api `^1.10.0`,
  auto-instrumentations-node 0.83.x or later, sdk-node and
  exporter-trace-otlp-proto 0.30x, resources `^3`, and swap `sdk-trace-base`
  for `sdk-trace ^3`. Regenerate the lockfile with exact versions.
- **Sampling.** `deploy/cloudrun/api-gateway.yaml:112-115` sets
  `OTEL_TRACES_SAMPLER=parentbased_traceidratio` and
  `OTEL_TRACES_SAMPLER_ARG` (0.05). The 3.x `TracerProvider` no longer reads
  them. `NodeSDK` still does: in the sdk-node 0.301.2 tarball,
  `dist/sdk.cjs:109` passes `createSamplerFromEnv()` and
  `dist/create-from-env.cjs:107-121` maps `parentbased_traceidratio`. That was
  read from the package, not run. So keep building the provider through
  `NodeSDK` (no hand-made `TracerProvider`), and add a gateway test that
  asserts the configured sampler, because today `server.test.js` covers only
  resource attributes and the disabled flush path.
- **Resources.** Re-check `defaultResource().merge(resourceFromAttributes(...))`
  (`tracing.js:78`) against `@opentelemetry/resources` 3.x after the bump. This
  was not checked here.
- **Node.** 3.x needs Node >=22.15.0. The gateway image is `node:24-alpine` and
  `engines.node` is `>=24 <25`, so nothing changes.
- **Module format.** The 3.x packages ship dual CJS/ESM. The gateway is
  CommonJS and uses `require`, which the CJS build serves.

Migration guide: [opentelemetry-js `doc/3.x/migration-guide.md`](https://github.com/open-telemetry/opentelemetry-js/blob/main/doc/3.x/migration-guide.md),
sections on sdk-trace-base, sdk-node and supported Node.js versions.

## Superseded and obsolete PRs

- **Superseded by this branch:** #363, #365, #366, #368, #369, #370, #371, #372,
  #374, #377 and #379. Close them after this branch merges, or let Dependabot
  close them as "updatable in another way", as it did with #361, #364, #367 and
  #375.
- **#378:** close it. Its 3.x proposal is replaced by the 0.81.2 bump and the
  ignore entries above.
- **#373 (webauthn-server-core 2.9.0 → 2.10.1):** obsolete. The dependency was
  removed with passkeys (`b082e088`, `76631a1b`) and no `pom.xml` declares it.
  The leftover `IDENTITY_WEBAUTHN_*` parameters and the cookie-origin fallback
  are kept on purpose. Dependabot's recreate job for it errored
  (`record_update_job_unknown_error`), so it will not close itself.

## Newer versions not taken

Each of these exists but was not proposed by a Dependabot PR, so it waits for
the next Dependabot run: libraries-bom 26.93.0, google-auth 1.58.0,
gcp-auth-extension 1.62.1-alpha, lucide-react 1.72.0, Playwright 1.65.0
(2026-11-05), setup-java v6.2.0 (2026-11-04), CodeQL v4.39.3 (2026-11-04; the
floating `v4` tag now points to it), axios 1.21.1 (2026-11-09) and nginx
1.31.8-alpine3.24.

## Validation checkpoint

Local, on this branch, Windows 11, Node 24.15.0, npm 11.12.1, Java 25.0.3,
Docker 29.6.2:

- Maven: `./mvnw -B -ntp clean verify` from the repository root, all six
  reactor modules, BUILD SUCCESS in 35:37 min. Surefire totals: billing
  100, identity 224 (1 skipped), operations 541, platform 519, school-core
  1234 (1 skipped): 2618 tests, 0 failures, 0 errors. Both skips are
  environment-gated: `AuthoritativeAuthLocalBenchmarkTest` runs only with
  `-Dreadiness.benchmark=true`, and one `CatalogOrderAssetStorageTest` case
  needs the Windows symlink privilege. The Testcontainers PostgreSQL suites
  ran against Docker. Dependency convergence and the build-baseline enforcer
  rules passed in every module.
- Gateway: `npm ci` clean, `npm ls --all` clean, `npm test` 117/117 passed.
  A smoke run of `tracing.js` with tracing enabled started `NodeSDK`, patched
  `http`, and produced recording spans for an incoming request. The export
  itself failed for lack of local Google credentials, as it does on dev.
- Frontend: `npm ci` clean; `tsc --noEmit` clean;
  `npm run test:coverage -- --maxWorkers=3` 793/793 tests in 109 files, all
  coverage floors passed; `npm run build` succeeded.
- Browser: `npx playwright install chromium`, then the full suite with
  `PLAYWRIGHT_USE_PREBUILT=1` and 2 workers: 118/118 passed (116 against the
  production bundle, 2 source-security) with no retries.
  `password-reset.spec.ts` passed 4/4 under axe-core 4.14.
- `npm audit`: 0 vulnerabilities in both frontend and gateway.
- All eleven static architecture and security audits from
  `ci-pr.yml` static-architecture-audits passed under Windows PowerShell 5.1.

Not run locally: Linux CI, Trivy image scans of the rebuilt images and the
Windows Maven bootstrap job. The old green checks on the Dependabot PRs are
stale (bases 25–29 commits behind dev), so the PR's own CI run is what counts.

## Owner notes

- **Dependabot alerts are disabled on the repository.**
  `GET /repos/custokingkr-dev/ims-v1/dependabot/alerts` returns 403
  "Dependabot alerts are disabled for this repository". Version updates still
  run. Turning alerts on needs the repository owner, `custokingkr-dev`.
- **The npm and Maven queues are frozen at five.** `/frontend` npm and `/`
  Maven each had exactly five open PRs, the default
  `open-pull-requests-limit`, which `.github/dependabot.yml` does not override.
  The 2026-11-14 triage found no Maven version-update job since 2026-10-27
  apart from #373's recreate. Merging this branch and closing the superseded
  PRs frees the queue. Raise the limit or keep the queue clear.
- **Production still serves nginx 1.31.5.** `origin/main:frontend/Dockerfile:12`
  is `nginx:1.31.5-alpine3.24`. It lacks the five 1.31.7 fixes and the 1.31.6
  fix (CVE-2026-90439, HTTP/3 with OpenSSL 3.5.0 and earlier). With promotion
  on hold, nothing here reaches production. Merging adds to the dev→main gap
  the eventual promotion must carry.
- **Deploy impact on dev.** Root `pom.xml` and `_test-java-service.yml` are
  global deploy triggers, so the dev deploy after merge rebuilds all seven
  services. Merge nothing else until that deploy has finished, or the next
  production release finds no `dev-approved` image.

This document does not close owner-dependent security or operations acceptance
and does not authorize a production deployment.
