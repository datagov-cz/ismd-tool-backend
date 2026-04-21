FROM eclipse-temurin:21-jre-alpine

ARG JENA_VERSION=5.4.0
ARG FUSEKI_HOME=/opt/fuseki
ARG JENA_HOME=/opt/jena

# SHA-512 checksums from https://archive.apache.org/dist/jena/binaries/
# When bumping JENA_VERSION, update both checksums to the values published
# alongside the tarballs (the *.tar.gz.sha512 files).
ARG FUSEKI_SHA512=41a161d058e3f5ee96f2f4e49494cb4b1ca7413d575465f90e312e2a2711adc2e4bb91dcc6fb6f7006ff5d7c095beb4bb84fab3f055520d51b6d71ef975d601f
ARG JENA_SHA512=0d1d13dd8bb098b28996ffd9871f3a42f54984ac276c10dab0c0d2ccd8c66d69737f20bc3fc05f1ee3a2ceeacba318ef5f1a1f4fd2cba19041210675fac245ed

# Download official Fuseki distribution (includes jena-text + Lucene JARs)
# and verify integrity against the published SHA-512 checksums before
# extracting, so a compromised mirror or MITM cannot replace the tarballs.
RUN apk add --no-cache curl bash && \
    cd /tmp && \
    curl -fsSL -o fuseki.tar.gz \
        "https://archive.apache.org/dist/jena/binaries/apache-jena-fuseki-${JENA_VERSION}.tar.gz" && \
    echo "${FUSEKI_SHA512}  fuseki.tar.gz" | sha512sum -c - && \
    tar xz -C /opt -f fuseki.tar.gz && \
    mv /opt/apache-jena-fuseki-${JENA_VERSION} ${FUSEKI_HOME} && \
    rm fuseki.tar.gz && \
    curl -fsSL -o jena.tar.gz \
        "https://archive.apache.org/dist/jena/binaries/apache-jena-${JENA_VERSION}.tar.gz" && \
    echo "${JENA_SHA512}  jena.tar.gz" | sha512sum -c - && \
    tar xz -C /opt -f jena.tar.gz && \
    mv /opt/apache-jena-${JENA_VERSION} ${JENA_HOME} && \
    rm jena.tar.gz

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
