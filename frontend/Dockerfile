FROM node:20-alpine AS build
WORKDIR /app
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
COPY package.json package-lock.json tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm ci && npm run build

FROM nginx:1.27-alpine
ENV API_UPSTREAM=http://api-gateway:80
ENV NGINX_RESOLVER="127.0.0.11 valid=10s ipv6=off"
RUN apk upgrade --no-cache libcrypto3 libssl3
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=10 \
  CMD wget -q -O /dev/null http://127.0.0.1:80/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
