# Dependency reconciliation — 2026-10-20

This integration applies the thirteen open Dependabot proposals to dev base
`c1e65cf5090b6243d093c7e3ff1d62b4d355701f`. PR305 and PR315 request the same
Jackson target and are represented once. The original PR branches are retained
until the integrated checks and merge succeed.

| Original PR | Integrated target |
| --- | --- |
| 303 | lucide-react 1.47.0 |
| 304 | react-router-dom/react-router 7.18.4 |
| 305, 315 | Jackson 2 databind and BOM 2.22.3 |
| 306 | TwelveMonkeys imageio-webp 3.15.2 |
| 309 | nginx 1.31.6-alpine3.24, digest `df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2` |
| 310 | Google Auth Java 1.53.0 |
| 311 | Google Cloud libraries BOM 26.89.0 |
| 312 | Maven distribution 3.10.0; Maven Wrapper remains 3.3.4 |
| 354 | Vite 6.4.4 |
| 355 | OpenTelemetry auto-instrumentation 0.81.0, SDK/exporter 0.223.0, resources 2.12.0 and matching lockfile graph |
| 356 | setup-node 7.1.0, upload-artifact 7.0.2, CodeQL 4.38.2, setup-terraform 4.0.1 |
| 357 | Playwright 1.64.0 and its matching browser revision |

The Jackson 2 BOM is aligned in the parent and school service so upgrading
identity's explicit databind dependency does not leave core on the older minor
line. Jackson 3 remains at the existing patched 3.1.7 baseline.

PR312's Windows wrapper change has no content difference when ignoring line
endings. This integration preserves the current launcher and updates the
distribution URL, SHA-256, exact cold-bootstrap CI expectation and catalog audit.
The publisher's [Maven 3.10.0 release notes](https://maven.apache.org/docs/3.10.0/release-notes.html)
identify the Resolver 2 transition; full reactor checks remain required.

The action pins were checked against the upstream release refs, including peeling
the CodeQL annotated tag to its commit:

| Upstream release | Reviewed commit |
| --- | --- |
| [setup-node v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0) | `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| [upload-artifact v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| [CodeQL v4.38.2](https://github.com/github/codeql-action/releases/tag/v4.38.2) | `2892aa5e19bbd11bc0cff5427e3b750a04d9e3c2` |
| [setup-terraform v4.0.1](https://github.com/hashicorp/setup-terraform/releases/tag/v4.0.1) | `dfe3c3f87815947d99a8997f908cb6525fc44e9e` |

PR356's prior static failure was the expected mismatch between new workflow SHAs
and the reviewed-pin allowlist. Both now agree; pin checking remains enforced.
The old Google BOM/auth PR image failures were dated scans on an older branch
baseline, including old Jackson/OpenSSL packages. They are not waived:
the integrated build must independently pass fresh image scans.

## Validation checkpoint

- Windows cold Maven 3.10.0 bootstrap passed with SHA-256 verification and Java 25.
- Gateway: 108 tests passed.
- Frontend and gateway npm audits: zero vulnerabilities reported.
- Eleven architecture/security audits passed.
- The initial unrestricted local frontend run timed out in four tests while
  concurrent builds were active (472 passed). A bounded-worker rerun is required;
  no test deadline or assertion was relaxed.
- Combined reactor, frontend/browser, Linux/Windows CI, image scan, independent
  review and dev release evidence are pending at this source checkpoint.

This document does not close owner-dependent security/operations acceptance or
authorize a production deployment.
