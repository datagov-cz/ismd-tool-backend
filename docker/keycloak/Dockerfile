# Keycloak for the ISMD tool, with the NIA identity-provider extension baked in.
# Build context: docker/keycloak. KEYCLOAK_VERSION must match keycloak.version in
# extension/pom.xml — the extension is compiled against that exact server version.
ARG KEYCLOAK_VERSION=24.0.2

# 1. Build and test the extension jar
FROM maven:3.9-eclipse-temurin-17 AS extension
ARG KEYCLOAK_VERSION
WORKDIR /build
COPY extension/pom.xml .
RUN mvn -B -q -Dkeycloak.version=${KEYCLOAK_VERSION} dependency:go-offline
COPY extension/src ./src
RUN mvn -B -Dkeycloak.version=${KEYCLOAK_VERSION} verify

# 2. Add it to Keycloak and pre-build the server. The build-time options mirror what
# the Container App sets at runtime (ismd-infrastructure modules/tool_apps/keycloak.tf),
# so a plain `start` finds a matching build and does not re-augment on every start.
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/popisujeme/auth
COPY --from=extension /build/target/ismd-keycloak-nia.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

# 3. Runtime image. Configuration (DB, hostname, secrets, keystores) stays in Terraform.
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
