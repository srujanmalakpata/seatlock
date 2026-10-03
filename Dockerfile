# syntax=docker/dockerfile:1

# Build and runtime base images can be overridden with build arguments.
ARG BUILD_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

# Build the jar.
FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace
# Resolve dependencies in their own layer so source-only changes reuse the cache.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests -Djacoco.skip=true \
 && java -Djarmode=tools -jar target/seatlock-*.jar \
        extract --layers --launcher --destination target/extracted

# Runtime image: JRE only, non-root, layered jar.
FROM ${RUNTIME_IMAGE}
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
# Least-frequently-changing layers first: dependencies rarely change, application code often.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
