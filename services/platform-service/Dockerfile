FROM maven:3.9-eclipse-temurin-25@sha256:1b1fc6d0168ea616afd1c861d6f32ec37c9ec2ffe88a0351b3771dd4ad86b0d8 AS build
WORKDIR /app
COPY pom.xml ./pom.xml
COPY services/platform-service/pom.xml ./services/platform-service/pom.xml
WORKDIR /app/services/platform-service
RUN for attempt in 1 2 3; do \
      mvn -q dependency:go-offline && exit 0; \
      if [ "$attempt" -eq 3 ]; then exit 1; fi; \
      sleep $((attempt * 5)); \
    done
COPY services/platform-service/src ./src
RUN mvn -q -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble@sha256:1a99f30a84d58729f1184c1c693f25fb6f91970ddf05395515540bc5773b8edd
ARG APP_NAME=custoking-platform-service
ARG APP_VERSION=0.1.0-SNAPSHOT
ARG APP_DESCRIPTION="Custoking IMS platform microservice (reporting + notification + audit)"

LABEL org.opencontainers.image.title="${APP_NAME}" \
  org.opencontainers.image.version="${APP_VERSION}" \
  org.opencontainers.image.description="${APP_DESCRIPTION}"

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*
RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser
WORKDIR /app
COPY --from=build /app/services/platform-service/target/*.jar app.jar
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
