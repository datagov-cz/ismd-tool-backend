### Build stage
# Ubuntu-based Temurin tags (not -alpine) are published multi-arch
# (linux/amd64 + linux/arm64). The -alpine variants are amd64-only, which
# breaks `docker build`/`pull` on Apple Silicon. bash ships in these images,
# so no extra install is needed.
# Both base images are pinned to the multi-arch index digest; Dependabot
# (docker ecosystem) raises the bump PRs.
FROM eclipse-temurin:24-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS builder
WORKDIR /app

# Copy build manifests first so dependency layers cache across source changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Copy source after manifests so source changes don't bust the dep cache layer.
COPY src src

# Build with a persistent BuildKit cache for /root/.m2/repository
# (dependencies are downloaded once, reused across builds). All dependencies
# are public (Maven Central), so no credentials are needed.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw clean install -DskipTests -B

### Runtime stage
FROM eclipse-temurin:24-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2 AS runtime
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=production

COPY --from=builder /app/target/ismd-tool-backend-*.jar app.jar

# Application Insights Java agent — baked in but NOT activated here on purpose.
# Activation is a runtime toggle: Terraform sets
# JAVA_TOOL_OPTIONS=-javaagent:/app/applicationinsights-agent.jar per env
# (enable_app_insights_agent), so one image serves all envs and telemetry can be
# flipped on/off without a rebuild. The agent reads APPLICATIONINSIGHTS_CONNECTION_STRING
# (injected by TF) for its destination. The checksum must change with the version.
ADD --checksum=sha256:e81ef99fd30444f6f1da70cd31db5e47f8e6906acbbc9199cac3b390dc6cfedf \
    https://github.com/microsoft/ApplicationInsights-Java/releases/download/3.6.2/applicationinsights-agent-3.6.2.jar /app/applicationinsights-agent.jar

LABEL org.opencontainers.image.title="ISMD Tool Backend"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
