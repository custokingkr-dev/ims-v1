# Minimal on purpose. The dashboard has no dependencies -- it uses only Node built-ins and shells out to
# nothing -- so there is no install step, no lockfile to honour and no build to cache. Copying four files
# onto a base image is the whole thing.
#
# Node rather than google/cloud-sdk:slim, which the other jobs use, because this one never calls gcloud:
# on Cloud Run it takes its token from the metadata server directly. That keeps the image ~50MB instead
# of ~1GB, which matters because pulling images is billed egress and this project has been bitten by
# exactly that before.
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32

# Refreshes the base image's runtime packages so a newly disclosed Alpine fix does not have to wait for
# the upstream Node image digest to be rebuilt. This is deliberately limited to upgrades from the signed
# repositories configured by the pinned base image.
#
# Deletes npm, which this image never uses and which was its entire application-library vulnerability
# surface.
#
# Nothing here has dependencies -- no package.json, no lockfile, no install step, only Node built-ins --
# so npm is dead weight that ships anyway because the base image bundles it. After bumping the base, all
# 16 remaining CVEs were npm's own transitive dependencies: tar, brace-expansion, sigstore, picomatch,
# ip-address. None of them is reachable by anything this container runs.
#
# Done as root before the USER switch because package upgrades and deleting from /usr/local require it.
# The security-patch layer below must not be served from the build cache. Its cache key is the
# base image plus this literal command, and neither changes when Alpine publishes a fix, so a
# cached layer keeps installing the versions current when it was first built. The image then
# looks patched and is not: on 2026-09-24 this shipped libexpat 2.8.4-r0 for days after 2.8.5-r0
# was available, until Trivy failed the build on CVE-2026-93990. CI passes SECURITY_PATCH_EPOCH
# as the current UTC date, so this layer rebuilds at most once a day and the rest still caches.
ARG SECURITY_PATCH_EPOCH=unset
RUN echo "security patches for ${SECURITY_PATCH_EPOCH}" \
  && apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx

# Runs as an unprivileged user. The image ships one, and there is no reason for a read-only dashboard to
# be root.
USER node
WORKDIR /app

COPY --chown=node:node server.mjs panels.mjs cost.mjs auth.mjs ./
COPY --chown=node:node public ./public

# Cloud Run supplies PORT; the default matches local use so the same command works in both places.
ENV PORT=8080
EXPOSE 8080

CMD ["node", "server.mjs"]
