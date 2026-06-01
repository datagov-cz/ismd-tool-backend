### Build stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

RUN apk add --no-cache bash

ARG GITHUB_ACTOR=""

# Copy build manifests first so dependency layers cache across source changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw

# Maven settings.xml references the token via ${env.GITHUB_TOKEN}, which is
# resolved at Maven runtime from the secret mount below — never embedded
# in the image or build log.
RUN mkdir -p /root/.m2 && \
    printf '%s\n' \
      '<settings><servers><server>' \
      '<id>github</id>' \
      "<username>${GITHUB_ACTOR}</username>" \
      '<password>${env.GITHUB_TOKEN}</password>' \
      '</server></servers></settings>' \
      > /root/.m2/settings.xml

# Copy source after manifests so source changes don't bust the dep cache layer.
COPY src src

# Build with:
#   - persistent BuildKit cache for /root/.m2/repository
#     (dependencies are downloaded once, reused across builds)
#   - GITHUB_TOKEN exposed only as a secret env var inside this RUN
RUN --mount=type=cache,target=/root/.m2/repository \
    --mount=type=secret,id=github_token,env=GITHUB_TOKEN \
    ./mvnw clean install -DskipTests -B

### Runtime stage
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=production

COPY --from=builder /app/target/ismd-tool-backend-*.jar app.jar

LABEL org.opencontainers.image.title="ISMD Tool Backend"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
