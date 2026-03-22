# Codebase Structure

**Analysis Date:** 2026-03-22

## Directory Layout

```
ismd-tool-backend/
├── src/
│   ├── main/
│   │   ├── java/com/dia/ismdtoolbackend/
│   │   │   ├── IsmdToolBackendApplication.java       # Spring Boot entry point
│   │   │   ├── controller/                           # REST API endpoints
│   │   │   ├── service/                              # Business logic interfaces
│   │   │   ├── service/impl/                         # Business logic implementations
│   │   │   ├── service/security/                     # Authorization services
│   │   │   ├── repository/                           # Data access (JPA + Jena)
│   │   │   ├── entity/                               # JPA entities (PostgreSQL models)
│   │   │   ├── models/                               # DTO/domain models
│   │   │   ├── models/concept/                       # Concept-specific models
│   │   │   ├── mapper/                               # MapStruct entity-to-DTO mappers
│   │   │   ├── config/                               # Spring configuration classes
│   │   │   ├── config/cors/                          # CORS configuration
│   │   │   ├── config/security/                      # OAuth2/JWT security config
│   │   │   ├── exception/                            # Custom exception classes
│   │   │   ├── enums/                                # Enumeration types
│   │   │   ├── client/                               # External service clients
│   │   │   ├── query/                                # SPARQL query builders
│   │   │   └── utility/                              # Helper/utility classes
│   │   │       ├── creator/                          # Ontology/concept creation utilities
│   │   │       ├── editor/                           # Ontology/concept editing utilities
│   │   │       ├── exporter/                         # Format exporters (JSON, Turtle)
│   │   │       ├── exporter/json/                    # JSON export utilities
│   │   │       ├── exporter/turtle/                  # Turtle/RDF export utilities
│   │   │       ├── detail/                           # RDF detail extraction
│   │   │       ├── published/                        # Published resource comparison
│   │   │       └── security/                         # Security utilities
│   │   └── resources/
│   │       ├── application.properties                # Default config
│   │       ├── application-dev.properties            # Development config
│   │       ├── application-local.properties          # Local development config
│   │       ├── application-stage.properties          # Staging config
│   │       ├── application-production.properties     # Production config
│   │       └── db/changelog/v1/                      # Liquibase database migrations
│   │           ├── 001-create-schema.yaml            # Initial schema
│   │           ├── 002-create-ontologies.yaml        # Ontologies table
│   │           ├── 003-create-concepts.yaml          # Concepts table
│   │           ├── 004-create-comments.yaml          # Comments table
│   │           └── 005-create-validation-reports.yaml # Validation reports table
│   └── test/
│       ├── java/com/dia/ismdtoolbackend/             # JUnit test classes
│       │   └── repository/                           # Repository tests
│       └── resources/
│           └── com/dia/context/                      # Test fixtures/contexts
├── docker/                                           # Docker configurations
│   ├── nginx-mtls/                                   # Nginx with mTLS
│   ├── keycloak/                                     # Keycloak OAuth provider
│   └── init-scripts/                                 # Database initialization
├── data/
│   └── tdb2/                                         # Jena TDB2 local storage (dev)
├── pom.xml                                           # Maven build configuration
├── docker-compose.yml                               # Local development environment
└── .github/workflows/                                # CI/CD pipelines
```

## Directory Purposes

**Controller Layer (`src/main/java/com/dia/ismdtoolbackend/controller/`):**
- Purpose: REST API endpoint definitions
- Contains: REST controllers, HTTP request/response DTOs
- Key files:
  - `OntologyController.java` - ontology CRUD, upload/download endpoints
  - `ConceptController.java` - concept management endpoints
  - `CommentController.java` - comment endpoints
  - `UserController.java` - user profile endpoints
  - `ValidationController.java` - validation endpoints (inferred)
  - `dto/` - request/response data transfer objects

**Service Layer (`src/main/java/com/dia/ismdtoolbackend/service/`):**
- Purpose: Business logic and orchestration
- Contains: Service interfaces (contracts) and implementations
- Key files:
  - Interface: `OntologyService.java`, `ConceptService.java`, `CommentService.java`, `ValidationService.java`, `OntologyUploadService.java`, `OntologyDownloadService.java`
  - Implementation: `impl/OntologyServiceImpl.java`, `impl/ConceptServiceImpl.java`, etc.
  - Security: `service/security/OntologySecurityService.java`
  - Comparators: `impl/OntologyDeviationComparator.java`, `impl/ConceptDeviationComparator.java`

**Repository Layer (`src/main/java/com/dia/ismdtoolbackend/repository/`):**
- Purpose: Data persistence abstraction
- Contains: Spring Data JPA repositories and custom Jena repository
- Key files:
  - `OntologyMetadataRepository.java` - JPA repository for ontology metadata
  - `ConceptMetadataRepository.java` - JPA repository for concept metadata
  - `CommentRepository.java` - JPA repository for comments
  - `ValidationReportRepository.java` - JPA repository for validation reports
  - `JenaTDB2Repository.java` - Custom repository for RDF/Fuseki operations with semaphore concurrency control

**Entity Layer (`src/main/java/com/dia/ismdtoolbackend/entity/`):**
- Purpose: JPA entity definitions (database schema mapping)
- Contains: Annotated entity classes
- Key files:
  - `OntologyMetadataEntity.java` - Ontology metadata with graph reference
  - `ConceptMetadataEntity.java` - Concept metadata with type classification
  - `CommentEntity.java` - Comments on ontologies/concepts
  - `ValidationReportEntity.java` - Validation results storage

**Model/DTO Layer (`src/main/java/com/dia/ismdtoolbackend/models/`):**
- Purpose: Transfer objects and domain models
- Contains: Service-to-controller DTOs, domain models
- Key files:
  - `OntologyMetadataModel.java` - Ontology DTO
  - `OntologyCreateModel.java`, `OntologyEditModel.java` - Mutation models
  - `OntologyDetailModel.java` - Detail view model
  - `CommentModel.java`, `CommentCreateModel.java`
  - `concept/` - concept-specific models (ClassConceptModel, PropertyConceptModel, etc.)

**Mapper Layer (`src/main/java/com/dia/ismdtoolbackend/mapper/`):**
- Purpose: Entity-to-DTO conversion
- Contains: MapStruct mapper interfaces
- Key files:
  - `OntologyMetadataMapper.java` - Entity ↔ OntologyMetadataModel conversion
  - `ConceptMetadataMapper.java` - Entity ↔ Concept model conversion
  - `CommentMapper.java` - Comment entity ↔ DTO conversion

**Configuration (`src/main/java/com/dia/ismdtoolbackend/config/`):**
- Purpose: Spring configuration and infrastructure setup
- Key files:
  - `JenaConfig.java` - Fuseki HTTP client, semaphore configuration
  - `SecurityConfig.java` - OAuth2 resource server setup, authorization rules
  - `JwtAuthenticationConverter.java` - JWT claims → SecurityUser conversion
  - `GlobalExceptionHandler.java` - Centralized exception handling
  - `JpaAuditingConfig.java` - Entity audit listener setup
  - `cors/CorsConfig.java` - Cross-origin request handling
  - `ValidationConfig.java` - Validation service configuration
  - `ExporterConfiguration.java` - Exporter bean setup
  - `RestClientConfig.java` - HTTP client configuration for external services

**Exception Layer (`src/main/java/com/dia/ismdtoolbackend/exception/`):**
- Purpose: Custom exception definitions
- Contains: Exception classes extending RuntimeException
- Key files: `OntologyValidationException.java`, `OntologyNotFoundException.java`, `OntologyStorageException.java`, `OntologyUploadException.java`, `EmptyFileException.java`, `UnsupportedRdfFormatException.java`, `JenaTDB2Exception.java`

**Utility Layer (`src/main/java/com/dia/ismdtoolbackend/utility/`):**
- Purpose: Specialized helper functions for domain operations
- Subdirectories:
  - `creator/` - Ontology/concept creation builders
  - `editor/` - Ontology/concept editing operations
  - `exporter/json/` - JSON format exporters (JsonExporter, ConceptProcessor, JsonFormatter, ModelAnalyzer)
  - `exporter/turtle/` - Turtle RDF format utilities (TurtleFilterUtil, TurtleFormatterUtil)
  - `detail/` - RDF graph detail extraction (OntologyDetailExtractor)
  - `published/` - Published resource comparison and deviation detection (PublishedResourceUtil)
  - `security/` - Security utility functions (SecurityUtils)

**Query Layer (`src/main/java/com/dia/ismdtoolbackend/query/`):**
- Purpose: SPARQL query builders
- Contains: Query template methods with parameterization
- Key files:
  - `NKDSPARQLConstructQuery.java` - SPARQL CONSTRUCT queries for concept/ontology retrieval

**Enumeration Types (`src/main/java/com/dia/ismdtoolbackend/enums/`):**
- Purpose: Domain enumeration types
- Key files:
  - `ConceptType.java` - CLASS, PROPERTY, RELATIONSHIP

**Client Layer (`src/main/java/com/dia/ismdtoolbackend/client/`):**
- Purpose: Integration with external services
- Contains: REST clients for external APIs
- Key files:
  - `ValidationClient.java` - Calls external validation service

**Security Context (`src/main/java/com/dia/ismdtoolbackend/config/security/`):**
- Purpose: Security configuration and user context
- Contains: SecurityConfig, JWT converter, security user principal
- Key files:
  - `SecurityConfig.java` - OAuth2 security configuration
  - `JwtAuthenticationConverter.java` - Converts JWT to authentication object
  - `SecurityUser.java` - Security principal with userId and roles

**Database Resources (`src/main/resources/db/changelog/v1/`):**
- Purpose: Liquibase database migration scripts
- Schema versioning: 001 to 005 tracking schema evolution
- Migrations: Create ontologies, concepts, comments, validation reports tables

**Configuration Files (`src/main/resources/`):**
- `application.properties` - Default configuration
- `application-{profile}.properties` - Profile-specific config (dev, local, stage, production)
- Properties configure: Database connection, Fuseki endpoint, Keycloak OAuth, file upload limits, timeouts

## Key File Locations

**Entry Points:**
- `src/main/java/com/dia/ismdtoolbackend/IsmdToolBackendApplication.java` - Spring Boot application entry point with @SpringBootApplication

**REST API Controllers:**
- `src/main/java/com/dia/ismdtoolbackend/controller/OntologyController.java` - /api/ontology endpoints
- `src/main/java/com/dia/ismdtoolbackend/controller/ConceptController.java` - /api/concept endpoints
- `src/main/java/com/dia/ismdtoolbackend/controller/CommentController.java` - /api/comment endpoints
- `src/main/java/com/dia/ismdtoolbackend/controller/UserController.java` - /api/user endpoints

**Service Orchestration:**
- `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyServiceImpl.java` - Core ontology business logic
- `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyUploadServiceImpl.java` - Upload orchestration
- `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyDownloadServiceImpl.java` - Download formatting
- `src/main/java/com/dia/ismdtoolbackend/service/impl/ConceptServiceImpl.java` - Concept CRUD

**Data Access:**
- `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` - RDF graph access (Fuseki HTTP)
- `src/main/java/com/dia/ismdtoolbackend/repository/OntologyMetadataRepository.java` - Relational ontology metadata

**Core Utilities:**
- `src/main/java/com/dia/ismdtoolbackend/utility/detail/OntologyDetailExtractor.java` - RDF graph detail extraction
- `src/main/java/com/dia/ismdtoolbackend/utility/editor/OntologyEditor.java` - RDF modification operations
- `src/main/java/com/dia/ismdtoolbackend/utility/exporter/json/JsonExporter.java` - JSON export
- `src/main/java/com/dia/ismdtoolbackend/utility/exporter/turtle/TurtleFormatterUtil.java` - Turtle RDF export

**Security & Auth:**
- `src/main/java/com/dia/ismdtoolbackend/config/security/SecurityConfig.java` - OAuth2 configuration
- `src/main/java/com/dia/ismdtoolbackend/config/security/JwtAuthenticationConverter.java` - JWT claims extraction
- `src/main/java/com/dia/ismdtoolbackend/service/security/OntologySecurityService.java` - Ownership verification

**Configuration:**
- `src/main/resources/application.properties` - Default properties (database, Fuseki, OAuth)
- `pom.xml` - Maven dependencies and build configuration

**Database Migrations:**
- `src/main/resources/db/changelog/master.xml` - Liquibase master changelog
- `src/main/resources/db/changelog/v1/001-create-schema.yaml` through `005-create-validation-reports.yaml`

## Naming Conventions

**Files:**
- Controllers: `{Entity}Controller.java` (e.g., OntologyController, ConceptController)
- Services: `{Entity}Service.java` (interface), `{Entity}ServiceImpl.java` (implementation)
- Repositories: `{Entity}Repository.java`
- Entities: `{Entity}Entity.java`
- Models/DTOs: `{Entity}Model.java` or `{Entity}Dto.java`
- Mappers: `{Entity}Mapper.java`
- Exceptions: `{Specific}Exception.java`
- Utilities: `{Purpose}Util.java` or `{Domain}{Purpose}.java`
- Configuration: `{Domain}Config.java` or `{Domain}Configuration.java`

**Classes:**
- RestController classes: `@RestController` annotation
- Service classes: `@Service` annotation, constructor injection with `@RequiredArgsConstructor`
- Repository classes: `@Repository` annotation
- Configuration classes: `@Configuration` annotation
- Mapper classes: `@Mapper(componentModel = "spring")` annotation (MapStruct)

**Methods:**
- HTTP methods: Verb-based (upload, download, create, edit, delete, get)
- Service methods: Verb-Noun pattern (uploadOntology, getOntologyDetail)
- Repository query methods: Spring Data naming convention (findById, findByOntologyMetadata, etc.)

**Packages:**
- Layered by function: controller, service, repository, entity, config, exception, utility
- Domain-specific grouping within utility layer: creator, editor, exporter, detail, published, security
- Specialized subdomains: models/concept, config/security, config/cors, utility/exporter/json

## Where to Add New Code

**New REST Endpoint:**
1. Create/modify controller in `src/main/java/com/dia/ismdtoolbackend/controller/`
2. Create request/response DTO in `src/main/java/com/dia/ismdtoolbackend/controller/dto/`
3. Add service method interface in `src/main/java/com/dia/ismdtoolbackend/service/`
4. Implement service method in `src/main/java/com/dia/ismdtoolbackend/service/impl/`
5. Use existing repositories in `src/main/java/com/dia/ismdtoolbackend/repository/`
6. Add endpoint documentation via Swagger annotations on controller method

**New Business Logic/Feature:**
1. Create service interface in `src/main/java/com/dia/ismdtoolbackend/service/`
2. Implement in `src/main/java/com/dia/ismdtoolbackend/service/impl/`
3. Inject into dependent services via constructor
4. Use transactional boundaries at service method level
5. For RDF operations, use `JenaTDB2Repository` and SPARQL queries in `src/main/java/com/dia/ismdtoolbackend/query/`
6. For relational operations, use appropriate JPA repositories

**New Data Entity:**
1. Create JPA entity in `src/main/java/com/dia/ismdtoolbackend/entity/`
2. Create DTO model in `src/main/java/com/dia/ismdtoolbackend/models/`
3. Create MapStruct mapper in `src/main/java/com/dia/ismdtoolbackend/mapper/`
4. Create or extend repository in `src/main/java/com/dia/ismdtoolbackend/repository/`
5. Add Liquibase migration in `src/main/resources/db/changelog/v1/{next-sequence}-{description}.yaml`
6. Reference in service layer for CRUD operations

**New Utility Function:**
1. Determine category: creation, editing, export, extraction, or security
2. Add to appropriate subdirectory in `src/main/java/com/dia/ismdtoolbackend/utility/{category}/`
3. If RDF-related, add SPARQL templates to `src/main/java/com/dia/ismdtoolbackend/query/`
4. Inject utility into service layer via constructor
5. Keep utility stateless where possible

**New Configuration/Bean:**
1. Create configuration class in `src/main/java/com/dia/ismdtoolbackend/config/` or appropriate subdirectory
2. Annotate with `@Configuration`
3. Define beans using `@Bean` methods
4. Reference in application properties via `@Value` for environment-specific settings
5. For domain-specific config (e.g., security), use subdirectory

## Special Directories

**Docker Compose Development Environment (`docker/`):**
- Purpose: Local development infrastructure setup
- Generated: No (committed to repo)
- Contains: Nginx mTLS proxy, Keycloak OAuth provider, database initialization scripts
- Usage: Run `docker-compose up` to start dev environment with PostgreSQL, Fuseki, Keycloak

**Data/TDB2 Storage (`data/tdb2/`):**
- Purpose: Local Jena TDB2 persistent storage (development only)
- Generated: Yes (created by Jena TDB2 at runtime)
- Committed: No (in .gitignore)
- Lifecycle: Created on first run, persists across restarts

**GitHub Workflows (`.github/workflows/`):**
- Purpose: CI/CD pipeline definitions
- Generated: No (committed to repo)
- Contains: Build, test, and deployment automation
- Configuration: YAML workflow files triggered on push/PR events

**IDE Configuration (`.idea/`):**
- Purpose: IntelliJ IDEA project configuration
- Generated: Yes (IDE-generated)
- Committed: Partially (.idea/dataSources for shared database schema)
- Usage: Ignore most files, commit minimal shared config
