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
#   ./release.sh --project custoking-dev --scan-only v11
#   ./release.sh --project custoking-dev --push v11
#   ./release.sh --project custoking-prod --scan-only v11
#
# Project and action are required; there is no implicit production target or push.
# After an explicit push, the script prints the selected repository's immutable digest
# for a separately reviewed dashboard deployment. It never applies infrastructure.

set -euo pipefail

export LC_ALL=C
TAG=""
PROJECT=""
ACTION=""
PROJECT_SET=0
TAG_SET=0

usage_error() {
  echo "usage: $0 --project <custoking-dev|custoking-prod> <--scan-only|--push> <tag>" >&2
  echo "$1" >&2
  exit 2
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project)
      [ "$PROJECT_SET" -eq 0 ] || usage_error "Project must be specified exactly once."
      [ "$#" -ge 2 ] || usage_error "--project requires a value."
      PROJECT="$2"
      PROJECT_SET=1
      shift 2
      ;;
    --project=*)
      [ "$PROJECT_SET" -eq 0 ] || usage_error "Project must be specified exactly once."
      PROJECT="${1#--project=}"
      PROJECT_SET=1
      shift
      ;;
    --scan-only|--push)
      [ -z "$ACTION" ] || usage_error "Choose exactly one action; duplicate or conflicting actions are refused."
      ACTION="$1"
      shift
      ;;
    --*) usage_error "Unsupported argument: $1" ;;
    *)
      [ "$TAG_SET" -eq 0 ] || usage_error "Specify exactly one image tag."
      TAG="$1"
      TAG_SET=1
      shift
      ;;
  esac
done

case "$PROJECT" in
  custoking-dev|custoking-prod) ;;
  *) usage_error "An explicitly selected custoking-dev or custoking-prod project is required." ;;
esac
[ -n "$ACTION" ] || usage_error "An explicit --scan-only or --push action is required."
[[ "$TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || usage_error "Tag must be a valid ASCII Docker tag of one through128 characters."

# Every argument is validated before Docker can build, scan or contact a registry.
REGISTRY="asia-south2-docker.pkg.dev/${PROJECT}/custoking"
IMAGE="${REGISTRY}/custoking-dashboard:${TAG}"
ENVIRONMENT="${PROJECT#custoking-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

if [ "${ACTION}" = "--scan-only" ]; then
  echo
  echo "==> --scan-only: not pushing."
  exit 0
fi

echo
echo "==> push"
docker push "${IMAGE}"

# A successful push is not yet a usable immutable deployment reference. Require
# exactly one digest for this selected repository, rather than a stale other-project
# RepoDigest or an assumed tag identity. Failure here never prints a rollout value.
REPO_DIGESTS="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${IMAGE}")"
IMMUTABLE_IMAGE=""
MATCH_COUNT=0
while IFS= read -r candidate; do
  case "$candidate" in
    "${REGISTRY}/custoking-dashboard@sha256:"*)
      digest="${candidate#"${REGISTRY}/custoking-dashboard@sha256:"}"
      [[ "$digest" =~ ^[a-f0-9]{64}$ ]] || continue
      IMMUTABLE_IMAGE="$candidate"
      MATCH_COUNT=$((MATCH_COUNT + 1))
      ;;
  esac
done <<< "$REPO_DIGESTS"
if [ "$MATCH_COUNT" -ne 1 ]; then
  echo "REFUSING DEPLOYMENT REFERENCE: pushed image must have exactly one valid digest in ${REGISTRY}/custoking-dashboard." >&2
  exit 1
fi

cat <<EOF

==> immutable image for separately reviewed ${ENVIRONMENT} dashboard deployment

  deploy/gcp/observability/custoking-${ENVIRONMENT}.tfvars

    dashboard_image = "${IMMUTABLE_IMAGE}"

  No infrastructure or dashboard deployment was applied. Dedicated OAuth, pinned
  callback, allowed identities and deployed replay/logout/expiry acceptance remain required.
EOF
