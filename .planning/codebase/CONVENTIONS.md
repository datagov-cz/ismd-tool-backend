# Coding Conventions

**Analysis Date:** 2026-03-22

## Naming Patterns

**Files:**
- Entity classes: `[EntityName]Entity.java` (e.g., `OntologyMetadataEntity.java`)
- Service interfaces: `[ServiceName]Service.java` (e.g., `OntologyService.java`)
- Service implementations: `[ServiceName]Impl.java` (e.g., `OntologyServiceImpl.java`)
- Mapper interfaces: `[EntityName]Mapper.java` (e.g., `OntologyMetadataMapper.java`)
- DTOs: `[Entity][Purpose]Dto.java` (e.g., `GetOntologyDto.java`, `ApiResponseDto.java`)
- Model classes: `[Entity][Purpose]Model.java` (e.g., `OntologyMetadataModel.java`, `OntologyCreateModel.java`)
- Controllers: `[Entity]Controller.java` (e.g., `OntologyController.java`)
- Repository interfaces: `[Entity]Repository.java` (e.g., `OntologyMetadataRepository.java`)
- Test classes: `[ClassName]Test.java` (e.g., `OntologyServiceImplTest.java`)
- Utility classes: `[Concept][Utility].java` (e.g., `OntologyEditor.java`, `OntologyDetailExtractor.java`)

**Functions/Methods:**
- camelCase (e.g., `deleteOntology()`, `createOntologyMetadata()`, `getOntologyDetailModel()`)
- Action verbs as prefix: `get`, `set`, `create`, `delete`, `save`, `fetch`, `update`, `handle`, `validate`, `enrich`, `check`
- Private helper methods typically prefixed with action verb (e.g., `validateOntologyCreateModel()`, `enrichMetadataFromRDF()`)

**Variables:**
- camelCase (e.g., `ontologyMetadataOpt`, `testOntologyEntity`, `testModel`)
- Boolean variables follow pattern: `isPublished`, `isAdmin`, `allowModify`
- Constants: UPPER_SNAKE_CASE (e.g., `TEST_ONTOLOGY_ID`, `DEFAULT_LANG`, `LOG_REQUEST_ID`)
- Test data/fixtures: Prefix with `test` or `create` (e.g., `testOntologyEntity`, `createValidOntologyCreateModel()`)

**Types/Classes:**
- Entity classes use `Entity` suffix for persistence objects
- Data Transfer Objects (DTOs) use `Dto` suffix
- Model classes use `Model` suffix for business domain objects
- PascalCase for class names (e.g., `OntologyMetadataEntity`, `OntologyController`)

## Code Style

**Formatting:**
- Tool: Maven with Spring Boot conventions
- Line width: No specific enforced limit, but files kept under 700 lines generally
- Indentation: 4 spaces (standard Java)
- Brace style: Opening brace on same line (e.g., `public void method() {`)

**Linting:**
- Tool: No explicit ESLint/Checkstyle configuration detected
- Configuration: Spring Boot defaults applied

**Annotation Usage:**
- Entity annotations: `@Entity`, `@Table`, `@Id`, `@GeneratedValue`, `@Column`, `@OneToMany`, `@ManyToOne`, `@JsonIgnore`
- Service annotations: `@Service`, `@RequiredArgsConstructor`, `@Transactional`, `@Transactional(readOnly = true)`
- Controller annotations: `@RestController`, `@RequestMapping`, `@PostMapping`, `@DeleteMapping`, `@GetMapping`, `@PreAuthorize`
- Mapper annotations: `@Mapper(componentModel = "spring")`, `@Mapping`, `@Named`
- Lombok annotations: `@Getter`, `@Setter`, `@Data`, `@NoArgsConstructor`, `@RequiredArgsConstructor`
- Logging: `@Slf4j` for SLF4J logging capability

## Import Organization

**Order:**
1. `java.*` standard library imports
2. `jakarta.*` Jakarta EE imports (persistence, validation, etc.)
3. Third-party imports: `org.springframework.*`, `org.apache.*`, `com.fasterxml.*`, `com.dia.*`
4. Local project imports: `com.dia.ismdtoolbackend.*`

**Path Aliases:**
- No path aliases configured
- Full package paths used throughout: `com.dia.ismdtoolbackend.*`

**Wildcard Imports:**
- Avoided; explicit imports used (e.g., `import java.util.Optional;` rather than `import java.util.*;`)

## Error Handling

**Patterns:**

**Custom Exceptions:**
The codebase uses a family of custom exceptions extending `RuntimeException`:
- `OntologyException` - Generic ontology operation failures
- `OntologyNotFoundException` - Ontology not found in database
- `OntologyValidationException` - Ontology validation failures
- `OntologyStorageException` - RDF storage failures
- `OntologyUploadException` - Upload-specific failures
- `ConceptNotFoundException` - Concept lookup failures
- `ConceptValidationException` - Concept validation failures
- `ConceptStorageException` - Concept persistence failures
- `CommentException` - Comment operation failures
- `EmptyFileException` - Empty file upload
- `UnsupportedRdfFormatException` - Unsupported RDF format
- `OntologyAlreadyExistsException` - Duplicate ontology (includes reference to existing metadata for redirect)

**Exception Handling Pattern in Services:**
```java
// Check preconditions, throw immediately with descriptive message
Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
if (ontologyMetadataOpt.isEmpty()) {
    log.error("ontologyId {} not found", ontologyId);
    throw new OntologyException("Slovník s id " + ontologyId + "nebyl nalezen.");
}

// Try-catch for operations that may fail
try {
    saveOntologyModel(ontologyIRI, model);
    log.info("Successfully saved RDF model to TDB2 with graph name: {}", ontologyIRI);
} catch (Exception e) {
    log.error("Failed to save RDF model to TDB2", e);
    throw new OntologyException("Nepodařilo se uložit RDF model: " + e.getMessage());
}

// For complex operations with cleanup on failure
try {
    performOperation();
} catch (Exception e) {
    log.warn("Operation failed, attempting cleanup...");
    try {
        cleanupResource();
    } catch (Exception cleanupException) {
        log.error("Cleanup also failed: {}", cleanupException.getMessage());
    }
    throw new OntologyException("Operation failed: " + e.getMessage());
}
```

**Global Exception Handler:**
File: `com.dia.ismdtoolbackend.config.GlobalExceptionHandler`
- Uses `@RestControllerAdvice` for centralized exception handling
- Maps custom exceptions to appropriate HTTP status codes (400/404/500)
- Returns `ApiResponseDto` with error message and optional Location header (for redirects)
- Logs errors at appropriate levels (error/warn)

**Czech Error Messages:**
Error messages to users are in Czech (e.g., "Slovník s id ... nebyl nalezen", "Přístup odepřen")
This is intentional for a Czech government system.

## Logging

**Framework:** SLF4J with Logback (Spring Boot default)

**Patterns:**
- Use `@Slf4j` annotation for logger injection (Lombok)
- All public service methods include entry logging with parameters
- Failed operations logged at `error` level with full stack trace: `log.error("message", exception)`
- Validation failures logged at `warn` level: `log.warn("Validation failed: {}", message)`
- Successful operations logged at `info` level: `log.info("Operation completed: {}", identifier)`
- MDC (Mapped Diagnostic Context) used for request tracking: `MDC.put(LOG_REQUEST_ID, requestId)`

**Logging Examples from `OntologyServiceImpl`:**
```java
log.error("ontologyId {} not found", ontologyId);
log.info("Successfully saved RDF model to TDB2 with graph name: {}", ontologyIRI);
log.warn("PostgreSQL save failed, cleaning up TDB2 data for graph: {}", ontologyIRI);
log.info("Ontology edit completed: IRI changed={}", editResult.iriChanged);
log.debug("Updated concept IRI and graphName: {} -> {}", oldConceptIRI, newConceptIRI);
```

## Comments

**When to Comment:**
- Complex business logic that's not self-explanatory
- Non-obvious algorithm choices or workarounds
- TODO items for future improvements
- Preconditions or postconditions that aren't clear from code

**JavaDoc/Documentation:**
- Used on controller endpoints via `@Operation` annotation (SpringDoc OpenAPI)
- Example from `OntologyController`:
```java
@Operation(
    summary = "Nahrání slovníku ze souboru",
    description = "Umožňuje nahrát a importovat slovník z RDF souboru (Turtle, JSON-LD). Soubor je validován a uložen do RDF úložiště. Vyžaduje autentizaci."
)
```

**TODO Comments:**
Location: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfigUtil.java`
- `// TODO: change to relevant domain before production release`
- `// TODO: switch to 1 day when this setting is verified`

## Function Design

**Size:**
- Most service methods: 30-100 lines
- Larger complex methods (e.g., `createOntology`, `getOntologyDetailModel`): up to 150 lines
- Extraction of private helper methods common for repetitive tasks

**Parameters:**
- Use dependency injection (`@RequiredArgsConstructor` + final fields) rather than parameter passing
- Method parameters kept to 3-5 items; beyond that indicates need for refactoring
- Path variables, request parameters, authentication principal pass through method signature

**Return Values:**
- Single model objects returned directly (e.g., `OntologyMetadataModel`)
- Collections returned as `List<T>` (not Optional)
- DTOs used for complex responses: `GetOntologyDto` containing multiple nested objects
- Void for state-changing operations that may throw exceptions instead of returning null

**Private Methods:**
Used extensively for:
- Validation: `validateOntologyCreateModel()`
- Enrichment: `enrichMetadataFromRDF()`, `enrichMetadataFromModel()`
- Extraction: `extractNameFromGraphName()`, `extractNameFromURI()`
- Entity/Model conversion: `createOntologyMetadata()`, `createOFNBaseModel()`
- Database operations: `fetchOntologyMetadata()`, `fetchOntologyModel()`
- Cleanup: `cleanupTDB2Graph()`

## Module Design

**Exports (Package Structure):**
- `config/` - Configuration classes (@Configuration, @Bean definitions)
- `entity/` - JPA entity classes (database models)
- `repository/` - Spring Data repository interfaces
- `service/` - Public service interfaces
- `service/impl/` - Service implementations
- `service/security/` - Security-related services
- `controller/` - REST controller classes
- `controller/dto/` - Data Transfer Objects
- `mapper/` - MapStruct mapper interfaces
- `models/` - Business domain models (DTO-like but internal)
- `enums/` - Enumeration types
- `exception/` - Custom exception classes
- `utility/` - Utility classes for specific domains (editor, creator, exporter, etc.)
- `query/` - Query-related utilities
- `client/` - External service clients

**Barrel Files:**
- Not used; explicit imports from specific files required

**Transactionality:**
- Service layer methods use `@Transactional` for database operations
- Read-heavy methods use `@Transactional(readOnly = true)` for optimization
- Complex multi-step operations manage transactions at method level

**Dependency Injection:**
- Constructor injection with `@RequiredArgsConstructor` (Lombok)
- Final fields prevent mutation
- Spring automatically wires dependencies
- Example:
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyServiceImpl implements OntologyService {
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    // ...
}
```

---

*Convention analysis: 2026-03-22*
