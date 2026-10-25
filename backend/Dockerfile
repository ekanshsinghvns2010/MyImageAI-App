FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
# curl for the health check; fontconfig + fonts so the mock provider can draw its SIMULATED label
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -r -u 10001 app && mkdir -p /data && chown app /data
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
