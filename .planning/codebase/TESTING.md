# Testing Patterns

**Analysis Date:** 2026-03-22

## Test Framework

**Runner:**
- JUnit Jupiter (JUnit 5)
- Spring Boot Test Framework for integration testing
- Mockito for mocking

**Assertion Library:**
- JUnit Jupiter Assertions (`org.junit.jupiter.api.Assertions`)
- Common methods: `assertTrue()`, `assertFalse()`, `assertNotNull()`, `assertNull()`, `assertEquals()`, `assertThrows()`

**Run Commands:**
```bash
mvn test                  # Run all unit tests
mvn test -Dtest=ClassName  # Run specific test class
mvn test -Dgroups=integration  # Run integration tests
mvn clean package         # Build and run all tests
```

**Coverage:**
- Tools: Maven with Jacoco (if configured)
- View coverage: `mvn jacoco:report` (if Jacoco plugin added)
- Current requirement: Not enforced

## Test File Organization

**Location:**
- Mirrored from source: Tests in `src/test/java/com/dia/ismdtoolbackend/` matching structure of `src/main/java/com/dia/ismdtoolbackend/`
- Example: Service `src/main/java/.../service/impl/OntologyServiceImpl.java` tested by `src/test/java/.../service/OntologyServiceImplTest.java`

**Naming:**
- Pattern: `[ClassName]Test.java`
- Examples: `OntologyServiceImplTest.java`, `OntologyControllerTest.java`, `ConceptServiceImplTest.java`

**Structure:**
```
src/test/java/com/dia/ismdtoolbackend/
├── config/
│   ├── CorsConfigLocalhostTest.java
│   ├── CorsConfigStageTest.java
│   ├── CorsConfigProductionTest.java
│   ├── TestJenaConfig.java
│   └── security/
│       ├── TestSecurityConfig.java
│       ├── TestOntologySecurityService.java
│       ├── WithMockSecurityUser.java
│       ├── WithMockSecurityUserSecurityContextFactory.java
│       └── SecurityFilterChainIntegrationTest.java
├── controller/
│   ├── OntologyControllerTest.java
│   ├── ConceptControllerTest.java
│   └── CommentControllerTest.java
├── service/
│   ├── OntologyServiceImplTest.java
│   ├── OntologyUploadServiceImplTest.java
│   ├── OntologyDownloadServiceImplTest.java
│   ├── ConceptServiceImplTest.java
│   ├── CommentServiceImplTest.java
│   ├── ConceptDeviationComparatorTest.java
│   └── OntologyUploadTransactionalTest.java
└── repository/
    └── JenaTDB2RepositorySemaphoreTest.java
```

## Test Structure

**Unit Test Suite Organization:**

**Service Layer Tests** (e.g., `OntologyServiceImplTest.java`):
```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OntologyServiceImplTest {

    // 1. Mock declarations
    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;
    @Mock
    private JenaTDB2Repository jenaTDB2Repository;
    // ... more mocks

    // 2. Service under test (injects mocks)
    @InjectMocks
    private OntologyServiceImpl ontologyService;

    // 3. Test data / fixtures
    private OntologyMetadataEntity testOntologyEntity;
    private Model testModel;
    private static final Long TEST_ONTOLOGY_ID = 1L;
    private static final String TEST_GRAPH_NAME = "http://example.org/test-ontology";

    // 4. Setup
    @BeforeEach
    void setUp() {
        testOntologyEntity = new OntologyMetadataEntity();
        testOntologyEntity.setId(TEST_ONTOLOGY_ID);
        testOntologyEntity.setSlug(TEST_ONTOLOGY_SLUG);
        // ...
        testModel = ModelFactory.createDefaultModel();
    }

    // 5. Test methods grouped by functionality
    // ========== deleteOntology Tests ==========
    @Test
    void deleteOntology_Success() throws OntologyException {
        // Arrange
        when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.of(testOntologyEntity));
        // ...

        // Act
        ontologyService.deleteOntology(TEST_ONTOLOGY_ID);

        // Assert
        verify(ontologyMetadataRepository).deleteById(TEST_ONTOLOGY_ID);
    }

    // ... more test methods
}
```

**Controller Layer Tests** (e.g., `OntologyControllerTest.java`):
```java
@WebMvcTest(controllers = OntologyController.class,
    excludeAutoConfiguration = {
        JpaRepositoriesAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
    })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class OntologyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OntologyUploadService ontologyUploadService;
    // ... more mocks

    @BeforeEach
    void setUp() {
        TestOntologySecurityService.reset();
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testUploadFromFile_Success() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile("file", "test.ttl", "text/turtle", content.getBytes());
        when(ontologyUploadService.uploadFromFile(any(), eq(providedName), eq(userId)))
            .thenReturn(expectedMetadata);

        // Act & Assert
        mockMvc.perform(multipart("/api/ontology/upload").file(file).param("providedName", providedName))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.data.graphName").value(providedName));
    }
}
```

**Patterns:**

**Setup Pattern (AAA - Arrange, Act, Assert):**
1. **Arrange:** Configure mocks using `when(...).thenReturn(...)` or `doThrow(...)`
2. **Act:** Call the method under test
3. **Assert:** Verify results using `assertEquals()`, `verify()`, or MockMvc result matchers

**Teardown Pattern:**
- `@BeforeEach` runs before each test
- `@AfterEach` runs after each test (not common in these tests)
- Mocks are automatically reset by `@ExtendWith(MockitoExtension.class)`

**Assertion Pattern:**
```java
// Verify exceptions
OntologyException exception = assertThrows(OntologyException.class,
    () -> ontologyService.deleteOntology(TEST_ONTOLOGY_ID));
assertTrue(exception.getMessage().contains("nebyl nalezen"));

// Verify mock interactions
verify(validationReportRepository).delete(validationReport);
verify(jenaTDB2Repository, never()).deleteGraph(anyString());
verify(conceptMetadataRepository, times(1)).save(any());

// Verify return values
assertNotNull(result);
assertEquals(expectedValue, actualValue);
assertFalse(result.isEmpty());
```

## Mocking

**Framework:** Mockito 4.x

**Patterns:**

**Mock Declaration and Injection:**
```java
@ExtendWith(MockitoExtension.class)
class OntologyServiceImplTest {
    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    @InjectMocks
    private OntologyServiceImpl ontologyService;
}
```

**Mock Setup - Return Values:**
```java
// Simple return value
when(repository.findById(id)).thenReturn(Optional.of(entity));

// Multiple calls with different behaviors
when(repository.findById(1L)).thenReturn(Optional.of(entity1));
when(repository.findById(2L)).thenReturn(Optional.of(entity2));

// Default behavior for any argument
when(repository.save(any(Entity.class))).thenReturn(savedEntity);

// Matching specific arguments
when(mapper.toDto(eq(testEntity))).thenReturn(expectedDto);

// Exception throwing
doThrow(new RuntimeException("Error")).when(repository).save(any());
```

**Verification Pattern:**
```java
// Verify method was called
verify(repository).deleteById(id);

// Verify method was called with specific arguments
verify(mapper).toDto(eq(testEntity));

// Verify method was never called
verify(repository, never()).save(any());

// Verify interaction count
verify(repository, times(2)).findAll();

// Verify argument captor for complex assertions
ArgumentCaptor<Model> captor = ArgumentCaptor.forClass(Model.class);
verify(repository).save(captor.capture());
Model captured = captor.getValue();
assertEquals(expected, captured.getProperty());
```

**What to Mock:**
- Repository interfaces (database access)
- External service clients (API calls)
- Mapper interfaces (dependency injection)
- Configuration classes (environment-specific behavior)

**What NOT to Mock:**
- Entity/DTO/Model classes (data containers)
- Enum types
- Local utility methods (create as private helpers)
- Business logic being tested (the class under @InjectMocks)
- Apache Jena Model objects (use real `ModelFactory.createDefaultModel()`)

## Fixtures and Factories

**Test Data Pattern:**

**Inline Entity Creation:**
```java
@BeforeEach
void setUp() {
    testOntologyEntity = new OntologyMetadataEntity();
    testOntologyEntity.setId(TEST_ONTOLOGY_ID);
    testOntologyEntity.setSlug(TEST_ONTOLOGY_SLUG);
    testOntologyEntity.setGraphName(TEST_GRAPH_NAME);
    testOntologyEntity.setUserId(TEST_USER_ID);
    testOntologyEntity.setIsPublished(false);
}
```

**Factory Methods in Test Class:**
```java
private OntologyCreateModel createValidOntologyCreateModel() {
    OntologyCreateModel createModel = new OntologyCreateModel();
    createModel.setNamespace("http://example.org/test");
    NameModel nameModel = new NameModel();
    nameModel.setName(Map.of("cs", "Testovací ontologie"));
    createModel.setNameModel(nameModel);
    // ...
    return createModel;
}

private Model createModelWithOntologyData() {
    Model model = ModelFactory.createDefaultModel();
    Resource resource = model.createResource(TEST_GRAPH_NAME);
    Property property = model.createProperty("http://example.org/prop");
    resource.addProperty(property, "value");
    return model;
}
```

**Location:**
- Defined at end of test class or in helper methods
- Constants for frequently used test values declared at class level

## Coverage

**Requirements:** Not enforced (no target specified in pom.xml)

**Current Test Coverage Areas:**
- Service layer: Comprehensive (positive and negative paths)
- Controller layer: Main endpoints with security context
- Repository layer: Concurrency testing (JenaTDB2RepositorySemaphoreTest)
- Config layer: Profile-based configuration (CorsConfig tests)
- Security: Authentication and authorization (SecurityFilterChainIntegrationTest)

## Test Types

**Unit Tests:**
- Scope: Single class in isolation with mocked dependencies
- Approach: `@ExtendWith(MockitoExtension.class)` with `@Mock` and `@InjectMocks`
- Example: `OntologyServiceImplTest.java`
- Tests all code paths: success, validation failures, exceptions, null handling

**Integration Tests:**
- Scope: Multiple components working together
- Approach: `@WebMvcTest` for controller-layer integration
- Example: `OntologyControllerTest.java` testing controller + security + exception handler
- Include: Custom authentication (via `@WithMockSecurityUser`), authorization checks

**Configuration Tests:**
- Scope: Environment-specific configuration
- Approach: `@SpringBootTest` or simple Spring Boot slice tests
- Example: `CorsConfigLocalhostTest`, `CorsConfigStageTest`, `CorsConfigProductionTest`
- Verify: CORS headers, allowed origins differ by profile

**Concurrency Tests:**
- Scope: Thread-safety of shared resources
- Example: `JenaTDB2RepositorySemaphoreTest` tests Jena TDB2 graph access with semaphore
- Verify: Multiple threads can safely access graphs

**E2E Tests:**
- Status: Not found in codebase
- Note: Could be in separate integration test suite or CI/CD pipeline

## Common Patterns

**Async Testing:**
Not heavily used in current codebase. Pattern would be:
```java
@Test
void asyncOperation() throws Exception {
    @Test
    void testAsyncMethod() throws Exception {
        // Use CountDownLatch for wait
        CountDownLatch latch = new CountDownLatch(1);

        asyncService.performAsync(() -> {
            // assertion
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
```

**Error Testing Pattern:**
```java
@Test
void deleteOntology_OntologyNotFound() {
    when(ontologyMetadataRepository.findById(TEST_ONTOLOGY_ID)).thenReturn(Optional.empty());

    OntologyException exception = assertThrows(OntologyException.class,
        () -> ontologyService.deleteOntology(TEST_ONTOLOGY_ID));

    assertTrue(exception.getMessage().contains("nebyl nalezen"));
    verify(jenaTDB2Repository, never()).deleteGraph(anyString());
    verify(ontologyMetadataRepository, never()).deleteById(any());
}
```

**Cleanup/Rollback Pattern (for failed operations):**
```java
@Test
void createOntology_MetadataSaveFails_CleanupTDB2() {
    OntologyCreateModel createModel = createValidOntologyCreateModel();

    when(ontologyMetadataRepository.findByGraphName(anyString())).thenReturn(Optional.empty());
    when(ontologyMetadataRepository.save(any(OntologyMetadataEntity.class)))
        .thenThrow(new RuntimeException("DB error"));

    OntologyException exception = assertThrows(OntologyException.class,
        () -> ontologyService.createOntology(createModel, TEST_USER_ID));

    assertTrue(exception.getMessage().contains("Nepodařilo se uložit metadata"));
    // Verify cleanup happened
    verify(jenaTDB2Repository).deleteGraph(anyString());
}
```

**Security Context Testing:**
```java
@Test
@WithMockSecurityUser(userId = "user123")
void testUploadFromFile_Success() throws Exception {
    // Test runs with authenticated user context
    mockMvc.perform(multipart("/api/ontology/upload")
            .file(file)
            .param("providedName", providedName))
        .andExpect(status().isOk());
}

@Test
void testUploadFromFile_Unauthenticated() throws Exception {
    // Test without @WithMockSecurityUser will fail auth
    mockMvc.perform(multipart("/api/ontology/upload")
            .file(file))
        .andExpect(status().isUnauthorized());
}
```

**MockMvc Response Assertion Pattern:**
```java
mockMvc.perform(multipart("/api/ontology/upload").file(file))
    .andExpect(status().isOk())
    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
    .andExpect(jsonPath("$.data.graphName").value(providedName))
    .andExpect(jsonPath("$.data.user.userId").value(userId))
    .andExpect(jsonPath("$.message").isString())
    .andExpect(jsonPath("$.data").exists());
```

## Strictness Mode

**Mockito Strictness:**
- Setting: `@MockitoSettings(strictness = Strictness.LENIENT)` in service tests
- Purpose: Allows unused stubs (some mock setups may not be needed for all test cases)
- Note: LENIENT mode can hide overly-configured mocks; STRICT is preferred but used judiciously here

---

*Testing analysis: 2026-03-22*
