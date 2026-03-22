# External Integrations

**Analysis Date:** 2026-03-22

## APIs & External Services

**NKD SPARQL Endpoint:**
- National Catalog Data (NKD) - Optional public Czech government data service
  - What it's used for: Fetching published concept and ontology data, verifying if resources are published
  - SDK/Client: Apache Jena `QueryExecutionHTTPBuilder` + custom client `NkdSparqlClient`
  - Implementation: `src/main/java/com/dia/ismdtoolbackend/client/NkdSparqlClient.java`
  - Configuration:
    - Endpoint URL: `nkd.sparql.endpoint` property (empty by default, optional)
    - Query timeout: `nkd.sparql.timeout` (default 10000ms)
    - Max concurrent requests: `nkd.sparql.max-concurrent-requests` (default 4)
  - Methods:
    - `fetchPublishedConcept(conceptIri)` - SPARQL CONSTRUCT query to fetch concept detail
    - `fetchPublishedOntology(ontologyIri)` - SPARQL CONSTRUCT query to fetch ontology detail
    - `getPublishedResourcesList(resourceIris)` - Batch verification of resources

**Validation Service API:**
- ISMD Validator API - Validation rules and ontology validation service
  - What it's used for: Validating ontology RDF data against ISMD rules
  - Configuration:
    - Base URL: `validation.service.url` property (default: `http://localhost:8080/validujeme`)
  - Implementation: Custom integration via `ismd-validator-common` library (v1.0.8)
  - SDK/Client: Internal `ismd-validator-common` package

## Data Storage

**Databases:**

**PostgreSQL (Relational):**
- Type: PostgreSQL 17
- Connection: JDBC via `org.postgresql:postgresql` driver
- Connection string: `jdbc:postgresql://[host]:[port]/[database]`
  - Default (local): `jdbc:postgresql://localhost:5432/ismd_tool_db`
  - Docker service name: `ismd-postgres-dev`
- Client: Spring Data JPA + Hibernate ORM
- Schema: `ismd_schema` (configured in Liquibase and Hibernate)
- Credentials:
  - Username env var: `DB_USERNAME` (default in local: `ismd_user`)
  - Password env var: `DB_PASSWORD` (default in local: `ismd_password`)
- Database init script location: `docker/init-scripts/` (executed by PostgreSQL container)
- Purpose: Relational data storage for ontologies, concepts, comments, validation reports, metadata

**Apache Jena TDB2 (RDF Triplestore):**
- Type: File-based RDF triplestore
- Location: `jena.tdb2.location` property
  - Default (local): `./data/tdb2/ismd-tool-dataset`
  - Default (production): `./data/tdb2/production-dataset`
- Access method: `jena.access.method` (values: `direct` or `http`)
  - Local development uses `http` (via Fuseki)
  - Production uses `direct` (file-based)
- Data files: Persisted in `./data/tdb2/` directory (committed to git as needed)
- Client: Apache Jena libraries (`jena-core`, `jena-arq`, `jena-tdb2`)
- SPARQL Query Engine: Apache Jena ARQ

**File Storage:**
- Local filesystem only - No external cloud storage
- File upload handling: Multipart form data via Spring multipart support
- Max upload size: 10MB (configured via `MAX_UPLOAD_SIZE` env var)
- Uploaded files: Stored and accessed as RDF data via Jena TDB2

**Caching:**
- None configured - No Redis, Memcached, or caching layer deployed
- All queries hit live databases (PostgreSQL or TDB2)

## Authentication & Identity

**Auth Provider:**
- Keycloak 24.0.2 - OAuth2/OIDC authorization server
  - Docker service: `quay.io/keycloak/keycloak:24.0.2`
  - Database backend: PostgreSQL (shared with main app)

**Implementation Approach:**

**OAuth2 Client Registration (Browser-based):**
- Endpoint discovery: Auto-discovered from `spring.security.oauth2.client.provider.keycloak.issuer-uri`
- Configuration properties (defaults for local):
  - `spring.security.oauth2.client.registration.keycloak.client-id` - Keycloak client ID
  - `spring.security.oauth2.client.registration.keycloak.client-secret` - Client secret (required)
  - `spring.security.oauth2.client.registration.keycloak.authorization-grant-type` - `authorization_code`
  - `spring.security.oauth2.client.registration.keycloak.redirect-uri` - Post-login redirect (auto-constructed)
  - `spring.security.oauth2.client.registration.keycloak.scope` - `openid,profile,email`
- Default success URL: `/` (configurable in `SecurityConfig`)
- Implementation: `src/main/java/com/dia/ismdtoolbackend/config/security/SecurityConfig.java`
  - OAuth2 login filter chain (Order 1 for public endpoints, Order 2 for protected)

**OAuth2 Resource Server (JWT-based API):**
- Token validation: JWK Set URI auto-discovered from issuer
- JWT issuer validation: `spring.security.oauth2.resourceserver.jwt.issuer-uri`
- JWK Set endpoint: `{issuer-uri}/protocol/openid-connect/certs`
- Custom JWT converter: `JwtAuthenticationConverter`
  - Implementation: `src/main/java/com/dia/ismdtoolbackend/config/security/JwtAuthenticationConverter.java`
  - Extracts authorities from JWT token claims
- Stateless authentication: Session policy set to `STATELESS` (no server-side sessions)

**Keycloak Realm Configuration:**
- Realm file: `docker/keycloak/ismd-realm.json`
- Default admin credentials (local/dev): `admin:admin`
- Default user client: `ismd-app` (local/dev: client-secret `secret123`)
- Keycloak issuer URI: `http://localhost:8080/realms/ismd` (configurable)

**External Identity Provider (CAAIS):**
- CAAIS - Czech government identity federation (external OAuth2 IdP)
- Integration: Keycloak acts as intermediary
- Authentication method: OAuth2 with mTLS (mutual TLS) certificate exchange
- Certificate handling:
  - Proxied via Nginx service (`ismd-nginx-mtls`) with client certificates
  - Client certificate: `docker/keycloak/secrets/certifikate.crt` (gitignored)
  - Private key: `docker/keycloak/secrets/private.key` (gitignored)
- Configuration: `docker/keycloak/ismd-realm.json` defines CAAIS IdP endpoint
- Environment variable: `CAAIS_CLIENT_ID` - Client ID registered with CAAIS
  - Set in `.env` file (template: `.env.example`)

**Security Configuration Files:**
- Base config: `src/main/java/com/dia/ismdtoolbackend/config/security/SecurityConfig.java`
- CORS config: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfig.java`
- Test security: `src/test/java/com/dia/ismdtoolbackend/config/security/TestSecurityConfig.java`

## Monitoring & Observability

**Error Tracking:**
- None detected - No error tracking service (Sentry, Rollbar, etc.) configured

**Logs:**
- Standard approach: Java logging via SLF4J (provided by Spring Boot)
- Configuration location: `src/main/resources/application-*.properties`
- Log levels (local development example):
  - `logging.level.org.springframework.web=DEBUG`
  - `logging.level.org.hibernate.SQL=DEBUG`
  - `logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE`
  - `logging.level.com.dia.ismdtoolbackend.*=DEBUG`
- Output: Console (default Spring Boot logging)
- No external log aggregation service (ELK, Datadog, etc.)

**Metrics & Health Checks:**
- Spring Boot Actuator endpoints (enabled):
  - `/actuator/health` - Health status (always public)
  - `/actuator/info` - Application info
  - `/actuator/metrics` - Metrics data
- Configuration: `src/main/resources/application-local.properties`
  - `management.endpoints.web.exposure.include=health,info,metrics`
  - `management.endpoint.health.show-details=always`
- Health check in Docker Compose:
  - PostgreSQL: `pg_isready` command
  - Nginx: `curl -fsk https://localhost:8443/health`
  - No health endpoint for Fuseki

## CI/CD & Deployment

**Hosting:**
- Containerized deployment target (Docker/Kubernetes ready)
- Dockerfile: `Dockerfile` (multi-stage: builder + runtime)
- Base image (runtime): `eclipse-temurin:17-jre-alpine`

**CI Pipeline:**
- GitHub Actions workflows: `.github/workflows/` directory
- Build artifacts: Executable JAR via Spring Boot Maven Plugin
- GitHub Packages: Maven repository for dependencies (requires GitHub token for private packages)

**Docker Compose:**
- Local development stack: `docker-compose.yml`
- Services:
  1. `postgres:17` - PostgreSQL database (port 5432)
  2. `keycloak:24.0.2` - OAuth2/OIDC server (port 8080)
  3. `nginx-mtls:1.25-alpine` - mTLS proxy (port 8443)
  4. `fuseki:latest` - SPARQL endpoint (port 3030)
- Network: `ismd-network` (bridge network for service discovery)
- Volumes: `postgres_data`, `postgres_keycloak_data`, `fuseki_data`

**Environment Files:**
- `.env.example` - Template for environment variables
- `.env` - Runtime environment config (gitignored, must be created locally)
- Variables needed: `CAAIS_CLIENT_ID` (for CAAIS integration)

## Environment Configuration

**Required Environment Variables:**

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `SPRING_PROFILES_ACTIVE` | No | `local` | Active Spring profile |
| `KEYCLOAK_CLIENT_SECRET` | Yes | None | OAuth2 client secret (must be set) |
| `KEYCLOAK_CLIENT_ID` | No | `ismd-backend` | Keycloak client ID |
| `KEYCLOAK_ISSUER_URI` | No | `http://localhost:8080/realms/ismd` | Keycloak realm issuer URI |
| `CAAIS_CLIENT_ID` | Yes (if using CAAIS) | None | CAAIS OAuth2 client ID |
| `MAX_UPLOAD_SIZE` | No | `10MB` | Max multipart file upload size |
| `SERVER_SERVLET_CONTEXT_PATH` | No | `/popisujeme` | Application context path |
| `DB_URL` | No | Profile-specific | PostgreSQL JDBC URL override |
| `DB_USERNAME` | No | Profile-specific | PostgreSQL username override |
| `DB_PASSWORD` | No | Profile-specific | PostgreSQL password override |

**Secrets Location:**
- Keycloak secrets: Environment variables in container (spring.security.oauth2 properties)
- Database credentials: Environment variables or properties files (not committed to git)
- CAAIS certificates: `docker/keycloak/secrets/` directory (gitignored)
- GitHub token for Maven: CI/CD build secrets or Maven settings.xml (not committed)

**Environment Profiles:**

| Profile | Purpose | Database | Keycloak | Fuseki |
|---------|---------|----------|----------|--------|
| `local` | Local development | localhost:5432 | localhost:8080 | localhost:3030 |
| `dev` | Development environment | localhost:5432 | localhost:8080 | localhost:3030 |
| `stage` | Staging environment | External host | External host | External host |
| `production` | Production deployment | External host | External host | External host |

## Webhooks & Callbacks

**Incoming:**
- None - No webhook endpoints for external services to call

**Outgoing:**
- None configured - Application does not trigger callbacks to external systems
- Note: Keycloak integration is request-response based (no webhooks)
- NKD SPARQL is query-response based (no webhooks)

## Network & Security

**CORS Configuration:**
- Configurable per environment: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfig.java`
- Configuration source: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfigUtil.java`
- Test configurations:
  - `src/test/java/com/dia/ismdtoolbackend/config/CorsConfigLocalhostTest.java`
  - `src/test/java/com/dia/ismdtoolbackend/config/CorsConfigStageTest.java`
  - `src/test/java/com/dia/ismdtoolbackend/config/CorsConfigProductionTest.java`

**SSL/TLS:**
- mTLS for CAAIS integration via Nginx proxy
- Certificate management: Docker volumes mount certs from `docker/nginx-mtls/` and `docker/keycloak/secrets/`
- Nginx listens on port 8443 (internal, not exposed externally by default)

**API Endpoints (Public vs Authenticated):**

Public endpoints (no authentication required):
- `GET /actuator/health` - Health status
- `GET /actuator/info` - App info
- `GET /api/ontology/list` - List ontologies
- `GET /api/ontology/{id}/download` - Download ontology RDF
- `GET /api/ontology/{id}/detail` - Ontology details
- `GET /api/concept/list` - List concepts
- `GET /v3/api-docs/**` - OpenAPI specs
- `GET /swagger-ui/**` - Swagger UI

Authenticated endpoints (require valid JWT or OAuth2 session):
- `POST /api/ontology/upload` - Upload ontology
- `POST /api/ontology/create` - Create ontology
- `PATCH /api/ontology/{id}/edit` - Edit ontology
- `DELETE /api/ontology/{id}/delete` - Delete ontology
- `POST /api/concept/create` - Create concept
- `PATCH /api/concept/{id}/edit` - Edit concept
- `DELETE /api/concept/{id}/delete` - Delete concept
- `POST /api/comment/post` - Post comment
- `DELETE /api/comment/{id}/delete` - Delete comment
- `GET /api/user/me` - Get current user info

---

*Integration audit: 2026-03-22*
