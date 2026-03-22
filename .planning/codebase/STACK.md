# Technology Stack

**Analysis Date:** 2026-03-22

## Languages

**Primary:**
- Java 17 - Core backend application language (`pom.xml` property: `java.version`)

## Runtime

**Environment:**
- Java Runtime Environment (JRE) 17 - Alpine-based Docker image (`eclipse-temurin:17-jre-alpine`)

**Build Runtime:**
- Java Development Kit (JDK) 17 - Alpine-based for building (`eclipse-temurin:17-jdk-alpine`)

**Package Manager:**
- Maven 3.x - Bundled with project via `mvnw` wrapper (Windows: `mvnw.cmd`, Unix: `mvnw`)
- Lockfile: `pom.xml` (Maven Project Object Model)

## Frameworks

**Core:**
- Spring Boot 3.5.4 - Web application framework and dependency container
- Spring Security 6.x (via Spring Boot parent) - Authentication and authorization

**Data Access:**
- Spring Data JPA 3.5.4 - ORM abstraction layer for relational data
- Hibernate - JPA implementation (configured via Spring Data JPA)
- Liquibase - Database schema versioning and migrations (`src/main/resources/db/changelog/`)

**RDF/Semantic Web:**
- Apache Jena 5.3.0 - RDF processing and SPARQL querying
  - `jena-core` - Core RDF model and APIs
  - `jena-arq` - SPARQL query engine
  - `jena-tdb2` - Triplestore (TDB2) for RDF data persistence
  - `jena-fuseki-server` & `jena-fuseki-core` - SPARQL endpoint server

**Authentication & Authorization:**
- Spring OAuth2 Client - OAuth2 client for Keycloak integration
- Spring OAuth2 Resource Server - JWT token validation for stateless API access
- Spring Security Test - Security testing utilities

**Web & API:**
- Spring Web (MVC) - REST controller handling
- Spring Actuator - Application monitoring endpoints (`/actuator/health`, `/actuator/info`, `/actuator/metrics`)
- SpringDoc OpenAPI 2.8.5 - OpenAPI 3.0 documentation and Swagger UI

**Object Mapping:**
- MapStruct 1.5.5.Final - Type-safe bean mapping (compile-time code generation)
- Lombok - Boilerplate reduction via annotations

**Testing:**
- Spring Boot Test - Integrated testing framework
- JUnit Jupiter (JUnit 5) - Test runner and assertions
- H2 Database 2.x (via parent) - In-memory database for testing
- Spring Security Test - Security context mocking

**JSON Processing:**
- org.json 20250517 - JSON parsing and generation

**Development Tools:**
- Spring Boot DevTools - Live reload during development (optional, runtime scope)
- Spring Boot Docker Compose - Docker Compose integration for local development (optional, runtime scope)

## Database

**Primary (Relational):**
- PostgreSQL 17 - Production and development relational database
- Driver: `org.postgresql:postgresql` (runtime scoped, via Spring Data JPA)
- Connection via JDBC: `jdbc:postgresql://[host]:[port]/[database]`
- Schema: `ismd_schema` (default schema for migrations and Hibernate)

**Testing:**
- H2 - In-memory relational database for unit/integration tests (test scope)

**Secondary (RDF/Triplestore):**
- Apache Jena TDB2 - Triplestore for RDF data
  - Default location: `./data/tdb2/production-dataset` (configured via `jena.tdb2.location`)
  - Access method: Direct file-based or via Fuseki HTTP endpoint

## External Services

**SPARQL Endpoints:**
- Apache Jena Fuseki - SPARQL endpoint server (Docker service: `secoresearch/fuseki:latest`)
  - Port: 3030
  - Dataset endpoint: `/ismd-tool-dataset` or `/production-dataset`
  - HTTP client with configurable timeout (default 5000ms) and connection pooling via Java HttpClient

**National Catalog Data (NKD):**
- NKD SPARQL endpoint - Optional external SPARQL service
  - Configurable via `nkd.sparql.endpoint` property
  - Timeout: 10000ms default, configurable via `nkd.sparql.timeout`
  - Max concurrent requests: 4 (configurable)
  - Client: `NkdSparqlClient` (`src/main/java/com/dia/ismdtoolbackend/client/NkdSparqlClient.java`)

**Authentication Provider:**
- Keycloak 24.0.2 - OAuth2/OIDC authorization server
  - Docker service: `quay.io/keycloak/keycloak:24.0.2`
  - Database: PostgreSQL (shared with main application)
  - Realm configuration: `docker/keycloak/ismd-realm.json`
  - Integration method: OAuth2 Client Registration + Resource Server (JWT validation)

**Certificate Proxy:**
- Nginx 1.25 (Alpine) - Reverse proxy for mTLS (mutual TLS) certificate handling
  - Docker service: `nginx:1.25-alpine`
  - Purpose: Proxies Keycloak requests to CAAIS IdP with client certificates
  - Certificate paths:
    - Internal TLS (Keycloak → nginx): `docker/nginx-mtls/certs/internal.crt/key`
    - CAAIS client certs (nginx → CAAIS): `docker/keycloak/secrets/certifikate.crt` and `private.key`

## Configuration

**Application Configuration:**
- Property files: `src/main/resources/application*.properties`
  - `application.properties` - Base configuration
  - `application-local.properties` - Local development (Spring profile: `local`)
  - `application-dev.properties` - Development environment
  - `application-stage.properties` - Staging environment
  - `application-production.properties` - Production environment
- Active profile selected via `SPRING_PROFILES_ACTIVE` environment variable (default: `local`)
- Configuration hierarchy: Base properties + profile-specific overrides

**Environment Variables (Key):**
- `SPRING_PROFILES_ACTIVE` - Active Spring profiles (`local`, `stage`, `production`)
- `KEYCLOAK_CLIENT_ID` - OAuth2 client ID (configured in properties, env override possible)
- `KEYCLOAK_CLIENT_SECRET` - OAuth2 client secret (must be set via environment)
- `KEYCLOAK_ISSUER_URI` - Keycloak realm issuer URI
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` - PostgreSQL connection overrides (optional)
- `MAX_UPLOAD_SIZE` - Max multipart file upload size (default: 10MB)
- `SERVER_SERVLET_CONTEXT_PATH` - Application context path (default: `/popisujeme`)
- `CAAIS_CLIENT_ID` - CAAIS OAuth2 client ID (defined in `.env`, gitignored)

**File Upload:**
- Max file size: 10MB (configurable via `MAX_UPLOAD_SIZE` env var)
- Multipart request limit: Same as file size limit

**RDF Processing:**
- RDF parsing timeout: 60 seconds (configurable via `rdf.parsing.timeout`)
- Jena Fuseki connection timeout: 5000ms (configurable)
- Jena Fuseki max concurrent requests: 10 (configurable via `jena.fuseki.max-concurrent-requests`)
- Jena Fuseki semaphore timeout: 30000ms (configurable)

## Build Configuration

**Maven Plugins:**
- Spring Boot Maven Plugin - Builds executable JAR and provides `spring-boot:run`
- Maven Compiler Plugin - Java compilation with annotation processors configured:
  - Lombok processor (boilerplate generation)
  - MapStruct processor (type-safe mapping code generation)

**Build Process:**
- Clean build: `./mvnw clean install -DskipTests`
- Docker multi-stage build:
  - Builder stage: Compiles application with Maven
  - Runtime stage: Runs compiled JAR on minimal JRE base image
- GitHub Packages authentication: Optional via `GITHUB_TOKEN` and `GITHUB_ACTOR` build args

**Repository:**
- GitHub Packages - Maven repository for internal dependencies
  - Repository URL: `https://maven.pkg.github.com/datagov-cz/ismd-validator-backend`
  - Custom dependency: `ismd-validator-common:1.0.8` (framework for validation rules)

## Docker & Deployment

**Container Image:**
- Base: `eclipse-temurin:17-jre-alpine` - Lightweight OpenJDK runtime
- Exposed port: 8080
- Environment: `SPRING_PROFILES_ACTIVE=production`
- JAR location: `/app/app.jar`

**Docker Compose Services:**
- `postgres:17` - PostgreSQL database
- `keycloak:24.0.2` - OAuth2/OIDC server
- `nginx-mtls:1.25-alpine` - mTLS certificate proxy
- `fuseki:latest` - SPARQL endpoint

**Volumes:**
- `postgres_data` - PostgreSQL data persistence
- `./data/tdb2` - Jena TDB2 triplestore data (local directory)
- `./fuseki-config.ttl` - Fuseki server configuration

## Platform Requirements

**Development:**
- Java 17 JDK (for compilation)
- Maven 3.x (or use bundled `mvnw`)
- PostgreSQL 17 client/server (or Docker)
- Docker & Docker Compose (for local stack: Postgres, Keycloak, Nginx, Fuseki)

**Production:**
- Java 17 JRE (minimal)
- PostgreSQL 17 database (external or containerized)
- Keycloak 24.0.2 (external or containerized)
- Jena Fuseki (external or containerized)
- Nginx with mTLS support (for CAAIS integration)

## Key Dependencies Summary

| Dependency | Version | Purpose | Scope |
|------------|---------|---------|-------|
| spring-boot-starter-parent | 3.5.4 | BOM for Spring Boot dependencies | compile |
| spring-boot-starter-web | 3.5.4 | Spring MVC, embedded Tomcat | compile |
| spring-boot-starter-data-jpa | 3.5.4 | ORM abstraction, Hibernate | compile |
| spring-boot-starter-oauth2-client | 3.5.4 | OAuth2 client for Keycloak | compile |
| spring-boot-starter-oauth2-resource-server | 3.5.4 | JWT validation for APIs | compile |
| jena-core, jena-arq, jena-tdb2 | 5.3.0 | RDF/SPARQL processing | compile |
| jena-fuseki-server, jena-fuseki-core | 5.3.0 | SPARQL endpoint | compile |
| postgresql | Latest via parent | PostgreSQL JDBC driver | runtime |
| liquibase-core | Latest via parent | Database migrations | compile |
| mapstruct | 1.5.5.Final | Type-safe bean mapping | compile |
| springdoc-openapi-starter-webmvc-ui | 2.8.5 | OpenAPI docs & Swagger UI | compile |
| lombok | Latest via parent | Boilerplate reduction | optional |
| h2 | Latest via parent | In-memory test database | test |
| junit-jupiter | Latest via parent | JUnit 5 test framework | test |
| spring-security-test | Latest via parent | Security testing utilities | test |
| org.json | 20250517 | JSON processing | compile |

---

*Stack analysis: 2026-03-22*
