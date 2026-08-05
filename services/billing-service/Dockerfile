FROM maven:3-eclipse-temurin-24@sha256:a137a467ec89b5713d0be817b55bdba6b4d6ef16e3d05565a79bc08d8e775a1c AS build
WORKDIR /app
COPY pom.xml .
RUN for attempt in 1 2 3; do \
      mvn -q dependency:go-offline && exit 0; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      sleep $((attempt * 5)); \
    done
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre@sha256:f19dbf0a22d0b3658fda48ce7d7181df05ad14bda151dd5ad12cc09d1451c70e
ARG APP_NAME=custoking-billing-service
ARG APP_VERSION=0.1.0-SNAPSHOT
ARG APP_DESCRIPTION="Custoking IMS billing microservice"

LABEL org.opencontainers.image.title="${APP_NAME}" \
  org.opencontainers.image.version="${APP_VERSION}" \
  org.opencontainers.image.description="${APP_DESCRIPTION}"

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

ENV SERVER_PORT=8080 \
  SPRING_PROFILES_ACTIVE=prod \
  JAVA_OPTS="" \
  HEALTH_PATH=/actuator/health

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=10 \
  CMD curl -fsS "http://localhost:${SERVER_PORT}${HEALTH_PATH}" | grep -q '"status":"UP"'
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar /app/app.jar"]
