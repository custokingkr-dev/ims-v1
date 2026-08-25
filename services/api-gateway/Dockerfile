FROM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43
WORKDIR /app
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
