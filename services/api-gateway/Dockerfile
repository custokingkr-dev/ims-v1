FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43
WORKDIR /app
# The security-patch layer below must not be served from the build cache. Its cache key is the
# base image plus this literal command, and neither changes when Alpine publishes a fix, so a
# cached layer keeps installing the versions current when it was first built. The image then
# looks patched and is not: on 2026-09-24 this shipped libexpat 2.8.4-r0 for days after 2.8.5-r0
# was available, until Trivy failed the build on CVE-2026-93990. CI passes SECURITY_PATCH_EPOCH
# as the current UTC date, so this layer rebuilds at most once a day and the rest still caches.
ARG SECURITY_PATCH_EPOCH=unset
RUN echo "security patches for ${SECURITY_PATCH_EPOCH}" \
  && apk update \
  && apk upgrade --no-cache libcrypto3 libssl3
COPY package*.json ./
RUN npm ci --omit=dev \
  && npm cache clean --force \
  && rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
COPY tracing.js api-contract.js api-route-inventory.json server.js ./
ENV NODE_ENV=production
ENV PORT=80
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD wget -q -O - http://127.0.0.1:${PORT}/gateway-health | grep -q '"status":"UP"'
CMD ["node", "server.js"]
