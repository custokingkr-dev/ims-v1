# Minimal on purpose. The dashboard has no dependencies -- it uses only Node built-ins and shells out to
# nothing -- so there is no install step, no lockfile to honour and no build to cache. Copying four files
# onto a base image is the whole thing.
#
# Node rather than google/cloud-sdk:slim, which the other jobs use, because this one never calls gcloud:
# on Cloud Run it takes its token from the metadata server directly. That keeps the image ~50MB instead
# of ~1GB, which matters because pulling images is billed egress and this project has been bitten by
# exactly that before.
FROM node:22-alpine@sha256:41e4389f3d988d2ed55392df4db1420ad048ae53324a8e2b7c6d19508288107e

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
