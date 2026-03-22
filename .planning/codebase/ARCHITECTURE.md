# Architecture

**Analysis Date:** 2026-03-22

## Pattern Overview

**Overall:** Spring Boot REST API with layered architecture (Controller → Service → Repository), integrated with Apache Jena TDB2 for RDF/semantic data management and PostgreSQL for relational metadata

**Key Characteristics:**
- Multi-data-store design: PostgreSQL for operational metadata, Apache Jena Fuseki TDB2 for RDF ontology graphs
- Service-oriented business logic with clear separation between upload, download, and editing workflows
- Utility layer providing specialized operations for extraction, formatting, and transformation
- OAuth2/JWT-based security with role-based access control
- Transactional consistency across relational and RDF stores

## Layers

**Controller Layer:**
- Purpose: REST API endpoints handling HTTP requests and responses
- Location: `src/main/java/com/dia/ismdtoolbackend/controller/`
- Contains: REST controllers (`OntologyController`, `ConceptController`, `CommentController`, `UserController`, `ValidationController`), DTOs for request/response
- Depends on: Service layer, Security layer, Exception handling
- Used by: REST clients, frontend applications
- Pattern: `@RestController` with `@RequestMapping` for endpoint grouping, `@PreAuthorize` for method-level security

**Service Layer:**
- Purpose: Business logic orchestration and transaction management
- Location: `src/main/java/com/dia/ismdtoolbackend/service/` (interfaces) and `src/main/java/com/dia/ismdtoolbackend/service/impl/` (implementations)
- Contains: Service interfaces and implementations (`OntologyService`, `ConceptService`, `ValidationService`, `OntologyUploadService`, `OntologyDownloadService`, `CommentService`)
- Depends on: Repository layer, Mapper layer, Utility layer, external clients (ValidationClient)
- Used by: Controller layer
- Pattern: Transactional boundary definitions with `@Transactional`, dependency injection via constructor `@RequiredArgsConstructor`, specialized service implementations for specific workflows

**Repository Layer:**
- Purpose: Data persistence abstraction for both relational and RDF stores
- Location: `src/main/java/com/dia/ismdtoolbackend/repository/`
- Contains: Spring Data JPA repositories (`OntologyMetadataRepository`, `ConceptMetadataRepository`, `CommentRepository`, `ValidationReportRepository`) and custom RDF repository (`JenaTDB2Repository`)
- Depends on: Entity layer, JPA/Jena frameworks
- Used by: Service layer
- Pattern: JPA interface extending `JpaRepository` for relational data, custom repository with HTTP client connection to Fuseki server for RDF data with semaphore-based concurrency control

**Data Access Layer - Entity/Model:**
- Purpose: Data model definitions and ORM mapping
- Location: `src/main/java/com/dia/ismdtoolbackend/entity/` (JPA entities), `src/main/java/com/dia/ismdtoolbackend/models/` (DTO/transfer models)
- Contains: JPA entities (`OntologyMetadataEntity`, `ConceptMetadataEntity`, `CommentEntity`, `ValidationReportEntity`), domain models with MapStruct mappers
- Depends on: JPA/Lombok annotations
- Used by: Repository and Service layers
- Pattern: Entities with `@Entity` annotation, audit support via `@EntityListeners(AuditingEntityListener.class)`, models with MapStruct `@Mapper` for entity-to-DTO conversion

**Utility/Helper Layer:**
- Purpose: Specialized operations for ontology processing and transformation
- Location: `src/main/java/com/dia/ismdtoolbackend/utility/`
- Contains: Subdomain utilities: `creator/` (ontology/concept creation), `editor/` (ontology/concept editing), `exporter/` (JSON/Turtle export formatting), `detail/` (RDF detail extraction), `published/` (deviation checking for published resources), `security/` (security utilities)
- Depends on: Apache Jena, Entity/Model layer, external validators
- Used by: Service layer
- Pattern: Static utility methods and instance-based processors for complex transformations

**Configuration Layer:**
- Purpose: Spring application configuration and infrastructure setup
- Location: `src/main/java/com/dia/ismdtoolbackend/config/`
- Contains: Configuration classes for Jena (`JenaConfig`), CORS (`CorsConfig`), Security (`SecurityConfig`, `JwtAuthenticationConverter`), JPA auditing (`JpaAuditingConfig`), global exception handling (`GlobalExceptionHandler`), validation, exporter, and REST client setup
- Depends on: Spring Framework, external service configurations
- Used by: Spring auto-configuration, entire application
- Pattern: `@Configuration` classes with `@Bean` definitions, `@RestControllerAdvice` for centralized exception handling

**Security Layer:**
- Purpose: Authentication, authorization, and security enforcement
- Location: `src/main/java/com/dia/ismdtoolbackend/config/security/` and `src/main/java/com/dia/ismdtoolbackend/service/security/`
- Contains: OAuth2 security configuration, JWT token conversion, security user principal, ontology-level access control
- Depends on: Spring Security, OAuth2 framework
- Used by: Controller layer (via annotations), Service layer (for access checks)
- Pattern: OAuth2 resource server with JWT bearer tokens, method-level authorization with `@PreAuthorize`, ontology ownership verification in `OntologySecurityService`

## Data Flow

**Ontology Upload Flow:**
1. Client uploads RDF file via `POST /api/ontology/upload` with multipart form data
2. `OntologyController.uploadFromFile()` receives request, extracts security context
3. `OntologyUploadService` (service layer) orchestrates:
   - File format detection and parsing (with timeout protection)
   - RDF model parsing using Apache Jena with OntModel
   - Concept extraction and classification (Class vs Property vs Relationship)
   - Validation via external `ValidationClient`
   - Slug generation from ontology name
4. Creates `OntologyMetadataEntity` in PostgreSQL via repository
5. Stores RDF graph in Jena Fuseki TDB2 via `JenaTDB2Repository` (HTTP connection)
6. Stores concept metadata in PostgreSQL via `ConceptMetadataRepository`
7. Stores validation report via `ValidationReportRepository`
8. Returns `OntologyMetadataModel` (DTO) with success response

**Ontology Detail Retrieval Flow:**
1. Client requests `GET /api/ontology/{slug}` or `/api/ontology/{id}/detail`
2. `OntologyController` routes to `OntologyService.getOntologyDetailModel(slug)`
3. Service queries PostgreSQL for metadata via `OntologyMetadataRepository`
4. `OntologyDetailExtractor` (utility) constructs SPARQL CONSTRUCT query via `NKDSPARQLConstructQuery`
5. `JenaTDB2Repository` executes SPARQL against Fuseki server with semaphore concurrency control
6. Jena RDF Model parsed into domain objects (concepts, properties, relationships)
7. `ConceptMetadataMapper` converts concept entities to DTOs
8. Comments retrieved via `CommentRepository` and mapped to DTO
9. Deviation comparison via `PublishedResourceUtil` for published ontologies
10. Returns `GetOntologyDto` with complete detail hierarchy

**Concept Edit Flow:**
1. Client sends `PATCH /api/concept/{id}` with concept changes
2. Security check verifies ontology ownership via `OntologySecurityService`
3. `ConceptService` delegates to `OntologyEditor` utility
4. `OntologyEditor.updateConcept()` creates SPARQL DELETE/INSERT queries
5. Updates entity in PostgreSQL for metadata consistency
6. Executes SPARQL modification against Fuseki TDB2
7. Returns updated concept model

**State Management:**
- Relational state (metadata, user associations, comments): PostgreSQL via Spring Data JPA
- Semantic state (RDF triples, ontology graphs): Jena Fuseki TDB2 via HTTP SPARQL endpoints
- Consistency maintained at service layer with transactional boundaries
- Fuseki connection pooling with semaphore-based concurrency limits (configurable max concurrent requests)
- MDC logging for request tracing across service boundaries

## Key Abstractions

**Ontology (Semantic Web):**
- Purpose: Represents RDF ontology graphs containing concepts, properties, and relationships
- Examples: Graph stored in Fuseki with URI like `https://slovník.gov.cz/nkd/concept/...`
- Pattern: Apache Jena OntModel for in-memory representation, SPARQL CONSTRUCT queries for retrieval, metadata cached in relational DB

**Concept (Domain Model):**
- Purpose: Semantic entity within ontology (Class, Property, or Relationship)
- Examples: ConceptMetadataEntity in DB, RDF resource in Jena model
- Pattern: Polymorphic via ConceptType enum (CLASS, PROPERTY, RELATIONSHIP), stored as both RDF triples and relational metadata

**Graph (RDF Storage):**
- Purpose: Named graph in Fuseki holding RDF statements
- Examples: Graph name follows pattern `urn:uuid:...` or generated from ontology slug
- Pattern: Graph identified by string name, queried via SPARQL with GRAPH clause

**Mapper (DTO Conversion):**
- Purpose: Convert between entities (JPA) and models (API/service)
- Examples: `OntologyMetadataMapper`, `ConceptMetadataMapper`, `CommentMapper`
- Pattern: MapStruct interface-based mappers with Spring component model, custom methods for complex mappings

**Exporter (Format Transformation):**
- Purpose: Transform RDF models to specific output formats
- Examples: `JsonExporter`, `TurtleFormatterUtil`, `ConceptProcessor`
- Pattern: Visitor/processor pattern iterating through RDF statements, format-specific output builders

**Query Builder (SPARQL):**
- Purpose: Construct parameterized SPARQL queries
- Examples: `NKDSPARQLConstructQuery.buildConstructQuery()`, `ParameterizedSparqlString`
- Pattern: Template method with parameter binding to prevent injection

**Utility Creator/Editor:**
- Purpose: Specialized builders and modifiers for ontology/concept operations
- Examples: `ConceptCreator`, `OntologyEditor`, `ConceptEditor`
- Pattern: Method chaining or dedicated operation methods encapsulating Jena/RDF complexity

## Entry Points

**Application Bootstrap:**
- Location: `src/main/java/com/dia/ismdtoolbackend/IsmdToolBackendApplication.java`
- Triggers: JVM startup with `mvn spring-boot:run` or JAR execution
- Responsibilities: Spring Boot application initialization, component scanning, bean creation

**REST API - Ontology Endpoints:**
- Location: `src/main/java/com/dia/ismdtoolbackend/controller/OntologyController.java`
- Triggers: HTTP requests to `/api/ontology/*`
- Responsibilities: Upload, download, list, edit, delete ontologies; manages auth context

**REST API - Concept Endpoints:**
- Location: `src/main/java/com/dia/ismdtoolbackend/controller/ConceptController.java`
- Triggers: HTTP requests to `/api/concept/*`
- Responsibilities: Create, read, edit, delete concepts within ontologies

**REST API - Comment Endpoints:**
- Location: `src/main/java/com/dia/ismdtoolbackend/controller/CommentController.java`
- Triggers: HTTP requests to `/api/comment/*`
- Responsibilities: Comment management on ontologies/concepts

**REST API - Validation Endpoints:**
- Location: `src/main/java/com/dia/ismdtoolbackend/controller/ValidationController.java` (inferred from references)
- Triggers: Validation requests during upload or explicit validation calls
- Responsibilities: Trigger validation, retrieve validation reports

**REST API - User Endpoints:**
- Location: `src/main/java/com/dia/ismdtoolbackend/controller/UserController.java`
- Triggers: HTTP requests to `/api/user/*`
- Responsibilities: User information and profile management

## Error Handling

**Strategy:** Centralized exception handling with custom exception hierarchy and HTTP status mapping

**Patterns:**

1. **Custom Exception Hierarchy:**
   - `OntologyValidationException` (400) - ontology validation failed
   - `OntologyNotFoundException` (404) - ontology not found
   - `OntologyStorageException` (500) - storage operation failed
   - `OntologyUploadException` (400) - upload-specific errors
   - `EmptyFileException` (400) - empty file uploaded
   - `UnsupportedRdfFormatException` (400) - unsupported RDF format
   - `JenaTDB2Exception` (500) - RDF store operation failure
   - Standard Spring exceptions: `EntityNotFoundException`, `AccessDeniedException`, `MaxUploadSizeExceededException`

2. **Centralized Exception Handler:**
   - Location: `src/main/java/com/dia/ismdtoolbackend/config/GlobalExceptionHandler.java`
   - Pattern: `@RestControllerAdvice` intercepting exceptions from all controllers
   - Response: `ApiResponseDto` wrapper with error message and appropriate HTTP status

3. **Logging:**
   - Exceptions logged at appropriate levels: `WARN` for validation failures, `ERROR` for infrastructure failures
   - Request ID via MDC for distributed tracing

4. **Security Exceptions:**
   - `AccessDeniedException` handled separately returning 403 Forbidden
   - JWT validation failures handled by Spring Security returning 401 Unauthorized

## Cross-Cutting Concerns

**Logging:**
- Framework: SLF4J with Lombok `@Slf4j` annotation
- Pattern: Structured logging with MDC request IDs (LOG_REQUEST_ID)
- Coverage: Service layer operations, authentication events, validation results

**Validation:**
- Pattern: External validation via `ValidationClient` calling dedicated validation service
- Scope: RDF ontologies validated against ISMD/OFN standards
- Result: `ValidationReport` persisted in PostgreSQL

**Authentication:**
- Pattern: OAuth2 bearer token (JWT) via Spring Security
- Extraction: `JwtAuthenticationConverter` converts JWT claims to `SecurityUser` principal with userId and roles
- Scope: All endpoints except public metadata reads (enforced via `@PreAuthorize` annotations)

**Authorization:**
- Pattern: Method-level (`@PreAuthorize`), ontology-level (via `OntologySecurityService`)
- Model: User must be ontology owner or have admin role for mutations
- Enforcement: Service layer verifies ownership before edit/delete operations

**CORS:**
- Configuration: `CorsConfig` and `CorsConfigUtil` manage allowed origins, methods, credentials
- Scope: Configurable per environment (dev, stage, production)

**Transaction Management:**
- Pattern: `@Transactional` on service methods managing both PostgreSQL and external Fuseki calls
- Scope: Service layer methods, not pushed to repositories
- Limitation: Fuseki operations cannot be rolled back if PostgreSQL transaction fails (eventual consistency model)

**Rate Limiting:**
- Pattern: Semaphore in `JenaTDB2Repository` (configurable `jena.fuseki.max-concurrent-requests`)
- Purpose: Protect against concurrent Fuseki request overload
- Timeout: Configurable per request (default 30s)
