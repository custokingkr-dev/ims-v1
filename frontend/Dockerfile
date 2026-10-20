FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS build
WORKDIR /app
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
COPY public ./public
RUN npm run build

FROM nginx:1.31.6-alpine3.24@sha256:df221db836e1754089190208cee7eeda94f233197056426eda74a43ab1abeac2
ENV API_UPSTREAM=http://api-gateway:8080
ENV FRONTEND_CSP_HEADER=Content-Security-Policy
ENV NGINX_RESOLVER="127.0.0.11 valid=10s ipv6=off"
# The security-patch layer below must not be served from the build cache. Its cache key is the
# base image plus this literal command, and neither changes when Alpine publishes a fix, so a
# cached layer keeps installing the versions current when it was first built. The image then
# looks patched and is not: on 2026-09-24 this shipped libexpat 2.8.4-r0 for days after 2.8.5-r0
# was available, until Trivy failed the build on CVE-2026-93990. CI passes SECURITY_PATCH_EPOCH
# as the current UTC date, so this layer rebuilds at most once a day and the rest still caches.
ARG SECURITY_PATCH_EPOCH=unset
RUN echo "security patches for ${SECURITY_PATCH_EPOCH}" \
  && apk update \
  && apk upgrade --no-cache
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
RUN sed -i 's|^pid[[:space:]].*;|pid /tmp/nginx.pid;|' /etc/nginx/nginx.conf \
  && chown -R nginx:nginx /etc/nginx/conf.d /var/cache/nginx
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/frontend-health || exit 1
CMD ["nginx", "-g", "daemon off;"]
