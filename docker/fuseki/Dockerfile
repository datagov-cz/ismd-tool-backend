FROM eclipse-temurin:21-jre-alpine

ARG JENA_VERSION=5.4.0
ARG FUSEKI_HOME=/opt/fuseki
ARG JENA_HOME=/opt/jena

# Download official Fuseki distribution (includes jena-text + Lucene JARs)
RUN apk add --no-cache curl bash && \
    curl -fsSL "https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${JENA_VERSION}.tar.gz" \
        | tar xz -C /opt && mv /opt/apache-jena-fuseki-${JENA_VERSION} ${FUSEKI_HOME} && \
    curl -fsSL "https://archive.apache.org/dist/jena/binaries/apache-jena-${JENA_VERSION}.tar.gz" \
        | tar xz -C /opt && mv /opt/apache-jena-${JENA_VERSION} ${JENA_HOME}

# Create run directory structure
RUN mkdir -p ${FUSEKI_HOME}/run/databases/tdb2/ismd-tool-dataset \
             ${FUSEKI_HOME}/run/databases/lucene/ismd-tool-dataset \
             ${FUSEKI_HOME}/run/configuration

# Create non-root user
RUN addgroup -S fuseki && adduser -S fuseki -G fuseki && \
    chown -R fuseki:fuseki ${FUSEKI_HOME}/run

ENV FUSEKI_HOME=${FUSEKI_HOME}
ENV JENA_HOME=${JENA_HOME}

EXPOSE 3030

USER fuseki
WORKDIR ${FUSEKI_HOME}

# Use the fuseki-server shell script which auto-discovers config from
# run/configuration/. Do NOT pass --conf — it causes double-loading
# (--conf + auto-discovery both open the Lucene index, the second
# fails with LockObtainFailedException that silently breaks text search).
ENTRYPOINT ["/opt/fuseki/fuseki-server"]
CMD []
