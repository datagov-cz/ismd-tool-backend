# Keycloak for the ISMD tool, with the NIA identity-provider extension baked in.
# Build context: docker/keycloak. KEYCLOAK_VERSION must match keycloak.version in
# extension/pom.xml — the extension is compiled against that exact server version.
ARG KEYCLOAK_VERSION=24.0.2
# Multi-arch index digest of quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}. Update it with
# KEYCLOAK_VERSION — Dependabot cannot, as the image reference is built from ARGs.
ARG KEYCLOAK_DIGEST=sha256:b8a3f00fc433f2999bc304b73df331e2005037e8f5673f644f9c0eacd5fbe048

# 1. Build and test the extension jar
# Digest-pinned; Dependabot raises a PR when the tag moves.
FROM maven:3.9-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS extension
ARG KEYCLOAK_VERSION
WORKDIR /build
COPY extension/pom.xml .
RUN mvn -B -q -Dkeycloak.version=${KEYCLOAK_VERSION} dependency:go-offline
COPY extension/src ./src
RUN mvn -B -Dkeycloak.version=${KEYCLOAK_VERSION} verify

# Patched Java runtime for Keycloak. The JDK bundled in the 24.0.2 image (built 2024-02)
# is affected by CVE-2026-114938 and that image will not be rebuilt, so Keycloak runs on
# this Temurin instead: kc.sh uses $JAVA_HOME/bin/java when JAVA_HOME is set.
# Digest-pinned; Dependabot raises a PR when the tag moves. Drop with the Keycloak 26 upgrade.
FROM eclipse-temurin:17-jre@sha256:541b08fc7f494edf14e436ff6bfb55767e4a115c87bfc547b8c5de957b58737f AS jre

# 2. Add it to Keycloak and pre-build the server. The build-time options mirror what
# the Container App sets at runtime (ismd-infrastructure modules/tool_apps/keycloak.tf),
# so a plain `start` finds a matching build and does not re-augment on every start.
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@${KEYCLOAK_DIGEST} AS builder
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/popisujeme/auth
COPY --from=extension /build/target/ismd-keycloak-nia.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

# 3. Runtime image. Configuration (DB, hostname, secrets, keystores) stays in Terraform.
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@${KEYCLOAK_DIGEST}
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY themes/ /opt/keycloak/themes/
