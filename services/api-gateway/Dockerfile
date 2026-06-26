FROM node:20-alpine
WORKDIR /app
COPY server.js ./
ENV NODE_ENV=production
ENV PORT=80
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD wget -q -O - http://127.0.0.1:${PORT}/gateway-health | grep -q '"status":"UP"'
CMD ["node", "server.js"]
