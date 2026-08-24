#!/usr/bin/env bash
#
# Build, SCAN, push the dashboard image.
#
# WHY THIS EXISTS
#
# This image is not part of build-release.yml. It has no source in services/, it is not in the change
# detector, and it is not promoted through Cloud Deploy -- so none of the pipeline's safety applies to
# it. For several revisions it was built by hand with `docker build && docker push`, and the result was
# exactly what you would expect: a scan on 2026-08-21 found 138 vulnerabilities, 5 CRITICAL and 47 HIGH,
# in the one service on this project that is reachable from the internet.
#
# Every one of them was fixable. Nothing had failed -- nothing had ever looked. The application
# pipeline's HIGH/CRITICAL gate would have refused that image outright, which is the point: the image
# was not safer for skipping the pipeline, only less examined.
#
# So this script is the gate for this image, with the same threshold CI uses. It refuses to push a
# HIGH or CRITICAL. Building by hand is fine; shipping unscanned is not.
#
# USAGE
#
#   ./release.sh v11              build, scan, push
#   ./release.sh v11 --scan-only  build and scan, never push
#
# Then set dashboard_image in deploy/gcp/observability/custoking-prod.tfvars and apply. The script
# prints the exact line.

set -euo pipefail

TAG="${1:-}"
MODE="${2:-}"
REGISTRY="asia-south2-docker.pkg.dev/custoking-prod/custoking"
IMAGE="${REGISTRY}/custoking-dashboard:${TAG}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "${TAG}" ]; then
  echo "usage: $0 <tag> [--scan-only]" >&2
  exit 2
fi

# NOTE for anyone extending this on Windows: do NOT set MSYS_NO_PATHCONV=1 here. Git Bash path
# rewriting has to be defeated for container-INTERNAL paths (a `-o /out/x.json` becomes
# C:/Program Files/Git/out/x.json), but disabling it globally breaks the docker build CONTEXT below,
# which must be rewritten to a Windows path. Nothing in this script passes a container-internal path,
# and the socket mount is already protected by its leading double slash, so the default is correct.

echo "==> build ${IMAGE}"
docker build -t "${IMAGE}" "${HERE}"

echo
echo "==> scan (HIGH,CRITICAL -- a finding here stops the release)"
# --ignore-unfixed is deliberately OFF, matching build-release.yml. An unfixable CRITICAL should still
# stop a release and force a decision, rather than being filtered out before anyone sees it.
if ! docker run --rm -v //var/run/docker.sock:/var/run/docker.sock \
      aquasec/trivy:latest image --quiet \
      --severity HIGH,CRITICAL --exit-code 1 --scanners vuln \
      "${IMAGE}"; then
  echo
  echo "REFUSING TO PUSH: ${IMAGE} has HIGH or CRITICAL vulnerabilities." >&2
  echo "Usually the base image digest in the Dockerfile has gone stale. Bump it and rebuild:" >&2
  echo "  docker pull node:22-alpine && docker image inspect node:22-alpine --format '{{index .RepoDigests 0}}'" >&2
  exit 1
fi

echo "    clean."

if [ "${MODE}" = "--scan-only" ]; then
  echo
  echo "==> --scan-only: not pushing."
  exit 0
fi

echo
echo "==> push"
docker push "${IMAGE}"

cat <<EOF

==> next: point the deployment at it

  deploy/gcp/observability/custoking-prod.tfvars

    dashboard_image                = "${IMAGE}"

  cd deploy/gcp/observability
  export GOOGLE_OAUTH_ACCESS_TOKEN="\$(gcloud auth print-access-token | tr -d '\r\n')"
  terraform apply -var-file=custoking-prod.tfvars

  If terraform reports HTTP 401 opening the state file, the backend has a stale token cached from
  init -- re-run terraform init -reconfigure with a fresh -backend-config="access_token=...".
EOF
