FROM eclipse-temurin:21-jre-alpine

ARG JENA_VERSION=5.3.0
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
             ${FUSEKI_HOME}/run/databases/lucene/ismd-tool-dataset

# Create non-root user
RUN addgroup -S fuseki && adduser -S fuseki -G fuseki && \
    chown -R fuseki:fuseki ${FUSEKI_HOME}/run

ENV FUSEKI_HOME=${FUSEKI_HOME}
ENV JENA_HOME=${JENA_HOME}

EXPOSE 3030

USER fuseki
WORKDIR ${FUSEKI_HOME}

# Use FusekiMainCmd (lightweight Fuseki) via java -cp, not the fuseki-server script.
# The shell script launches the full webapp server (FusekiCmd) which auto-discovers
# configs from run/configuration/ causing double-loading conflicts with --conf.
# FusekiMainCmd loads config exactly once via --conf.
ENTRYPOINT ["java", "-cp", "/opt/fuseki/fuseki-server.jar", \
            "org.apache.jena.fuseki.main.cmds.FusekiMainCmd"]
CMD ["--conf", "/opt/fuseki/config/assembler.ttl"]
