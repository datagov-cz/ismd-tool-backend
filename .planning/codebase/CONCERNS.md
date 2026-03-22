# Codebase Concerns

**Analysis Date:** 2026-03-22

## Tech Debt

**CORS Configuration Not Production-Ready:**
- Issue: Hardcoded placeholder domain in production CORS configuration
- Files: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfigUtil.java` (line 38-39)
- Impact: Production deployment will fail to serve requests from actual domain, allowing only `https://www.domain.com`
- Fix approach: Extract production domain to environment variable `CORS_ALLOWED_ORIGINS_PROD`, validate on startup

**CORS Max-Age Cache Time Too Short:**
- Issue: CORS preflight cache set to 600 seconds (10 minutes), with TODO to increase to 1 day but currently commented out
- Files: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfigUtil.java` (line 44-46)
- Impact: Increased browser preflight requests, reduced performance for frontend clients
- Fix approach: After verifying settings stability, enable 86400L (1 day) max age in production configuration

**@MockBean Deprecation Warning:**
- Issue: Spring Boot 3.4+ deprecated @MockBean but tests use it without migration strategy documented
- Files: `src/test/java/com/dia/ismdtoolbackend/controller/*Test.java` (multiple test classes)
- Impact: Tests may break with future Spring Boot upgrades; comment notes "clear migration path is provided" but none exists yet
- Fix approach: Monitor Spring Boot 3.5+ releases for MockBean replacement; create migration guide before upgrade

**ExecutorService Resource Management Inconsistency:**
- Issue: Different patterns for executor shutdown - `shutdownNow()` vs `shutdown()` across codebase
- Files:
  - `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyUploadServiceImpl.java` (line 300, uses `shutdownNow()`)
  - `src/main/java/com/dia/ismdtoolbackend/client/NkdSparqlClient.java` (line 140, uses `shutdown()`)
- Impact: Potential hanging tasks or abrupt termination of ongoing work; inconsistent behavior
- Fix approach: Standardize on pattern: `shutdown()` with `awaitTermination()` wrapper, or document why `shutdownNow()` is appropriate in OntologyUploadServiceImpl

**Test Execution Skipped in CI/CD:**
- Issue: Tests disabled in release workflow and reusable test workflow
- Files: `.github/workflows/release-version.yml` (line 105), `.github/workflows/reusable-test.yml` (line 40)
- Impact: Production builds deployed without test verification; bugs reach production undetected
- Fix approach: Fix failing tests and re-enable in CI pipeline; establish test pass requirement for releases

## Known Bugs

**Fuseki Connection Semaphore Never Awaited After Timeout:**
- Issue: When semaphore acquisition times out, exception thrown without `awaitTermination()` on executor wait
- Files: `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` (lines 55-73, 75-93)
- Trigger: Connection load spike causing timeout; multiple concurrent requests exceeding semaphore permits
- Workaround: Increase `jena.fuseki.semaphore-timeout` configuration value

**Broad Exception Catching in Repository:**
- Issue: Generic `catch(Exception e)` blocks don't distinguish between query errors and HTTP errors
- Files: `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` (multiple catch blocks)
- Symptoms: Loss of original exception context; unclear root cause in logs
- Impact: Difficult debugging of Fuseki connection issues

## Security Considerations

**CORS Allows All Headers:**
- Risk: `config.setAllowedHeaders(List.of("*"))` permits any header in CORS requests
- Files: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfigUtil.java` (lines 41, 59, 81)
- Current mitigation: Restricted origins limit scope of vulnerability
- Recommendations: Whitelist specific headers needed (e.g., Authorization, Content-Type) instead of wildcard

**Hardcoded Placeholder Domain in Production:**
- Risk: If domain not updated before deployment, production will serve wrong CORS policy, potentially bypassing security
- Files: `src/main/java/com/dia/ismdtoolbackend/config/cors/CorsConfigUtil.java` (line 39)
- Current mitigation: None; relies on manual configuration update
- Recommendations: Add startup validation that checks CORS domain is not placeholder; fail fast on startup if misconfigured

**Thread Interruption Without Safe Resource Cleanup:**
- Risk: Thread interruption in service calls can leave resources in inconsistent state
- Files:
  - `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` (lines 60, 80)
  - `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyUploadServiceImpl.java` (line 297)
  - `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyDownloadServiceImpl.java` (line 58)
- Current mitigation: `Thread.currentThread().interrupt()` resets interrupt flag after catching InterruptedException
- Recommendations: Ensure all resources are released before interrupt flag reset; use try-with-resources consistently

## Performance Bottlenecks

**Full Ontology Model Loaded into Memory:**
- Problem: `file.getBytes()` loads entire RDF file into byte array before parsing
- Files: `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyUploadServiceImpl.java` (line 276)
- Cause: Streaming parser replaced with timeout wrapper requiring full buffering
- Impact: Large RDF files (>100MB) cause memory spikes and potential OOM errors
- Improvement path: Implement streaming RDF parser with timeout using scheduled cancellation instead of full buffering

**N+1 Concept Verification Queries:**
- Problem: Each resource IRI in `getPublishedResourcesList()` triggers separate NKD SPARQL query
- Files: `src/main/java/com/dia/ismdtoolbackend/client/NkdSparqlClient.java` (lines 109-142)
- Cause: Loop-based verification without batch query support
- Impact: 1000 resources = 1000 HTTP requests to NKD SPARQL endpoint; can exceed rate limits
- Improvement path: Implement batch SPARQL query `VALUES` clause to fetch multiple resources in single request

**Unnecessary Model Transformations:**
- Problem: RDF model transformed multiple times during download flow
- Files: `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyDownloadServiceImpl.java` (line 77)
- Cause: `applyAllOFNTransformations()` iterates model multiple times; no caching of transformation results
- Impact: Download service becomes slower with larger ontologies
- Improvement path: Cache transformation results per model; implement incremental transformation instead of full reprocessing

**No Query Timeout on SPARQL Construct:**
- Problem: SPARQL construct queries on NKD lack timeout, can hang indefinitely on slow endpoint
- Files: `src/main/java/com/dia/ismdtoolbackend/client/NkdSparqlClient.java` (lines 50-53, 85-88, 148)
- Impact: Service requests hang, consuming thread pool, eventually degrading availability
- Improvement path: Implement HTTP client timeout and circuit breaker for NKD endpoint

## Fragile Areas

**Concept IRI Renaming Logic:**
- Files: `src/main/java/com/dia/ismdtoolbackend/utility/editor/ConceptEditor.java` (lines 38-100)
- Why fragile: Complex transaction logic with multiple points of failure (IRI collision, statement removal/addition, model state)
- Safe modification: Must test thoroughly with rename scenarios; ensure transaction rollback works correctly
- Test coverage: Limited coverage of IRI collision scenarios; edge cases around predicate exclusion untested

**Model Transaction Handling:**
- Files: `src/main/java/com/dia/ismdtoolbackend/utility/editor/ConceptEditor.java` (lines 72-100)
- Why fragile: Transaction support checked at runtime (`supportsTransactions()`); commit/rollback logic depends on model type
- Safe modification: Always test with both transactional and non-transactional models; verify behavior on transaction support=false
- Test coverage: No explicit test for non-transactional model behavior

**Concept Deviation Comparison:**
- Files: `src/main/java/com/dia/ismdtoolbackend/service/impl/ConceptDeviationComparator.java` (multiple comparison methods)
- Why fragile: 23+ property comparisons in sequence; each missing a null check could cause NPE
- Safe modification: Add defensive null handling to each comparison method; verify comparison output matches semantics
- Test coverage: Basic comparison tested, but edge cases with null/missing properties untested

**RDF Format Detection:**
- Files: `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyUploadServiceImpl.java` (lines 80-105)
- Why fragile: Fallback format detection by content-type can misidentify files (e.g., JSON-LD as JSON)
- Safe modification: Add explicit format parameter to upload endpoint; make auto-detection secondary
- Test coverage: Only basic file extension tested; content-type misidentification not covered

## Scaling Limits

**Semaphore-Based Connection Pooling:**
- Current capacity: 10 concurrent Fuseki connections (configurable `jena.fuseki.max-concurrent-requests`)
- Limit: Semaphore becomes bottleneck if `fusekiSemaphoreTimeout` exceeded regularly; requests start queuing/failing
- Scaling path: Monitor semaphore timeout metrics; increase timeout or permits; consider connection pool instead of semaphore

**RDF Parsing Timeout Single-Threaded:**
- Current capacity: 60 seconds timeout (configurable `rdf.parsing.timeout`)
- Limit: Large files >1GB will exceed timeout; creates new executor per upload request
- Scaling path: Implement thread pool for RDF parsing; adjust timeout based on file size; implement streaming parser

**NKD Client Thread Pool:**
- Current capacity: Fixed pool of 4 threads (configurable `nkd.sparql.max-concurrent-requests`)
- Limit: 4 threads saturate quickly with bulk resource verification; no queuing
- Scaling path: Use `ForkJoinPool.commonPool()` or sized `ThreadPoolExecutor` with queue; implement adaptive sizing based on load

## Dependencies at Risk

**Apache Jena 5.3.0:**
- Risk: Jena 5.x requires Java 17+; version 5.3.0 is March 2023 release (recent versions exist)
- Impact: Security vulnerabilities in older Jena; limited community support
- Migration plan: Upgrade to Jena 5.3.1+ (latest 5.x patch) or 6.x when Java 21+ available

**Keycloak Integration Dependency:**
- Risk: `spring-boot-starter-oauth2-resource-server` tightly coupled; no fallback auth mechanism
- Impact: If Keycloak down, all endpoints require auth fail; no guest/public access possible
- Migration plan: Implement auth provider abstraction; support multiple auth backends

## Missing Critical Features

**No API Rate Limiting:**
- Problem: No rate limiting on public endpoints; vulnerable to DOS
- Blocks: Production deployment security hardening
- Recommendation: Add Spring Cloud Gateway or implement custom rate limiter per endpoint

**No Request Logging/Audit Trail:**
- Problem: HTTP requests not logged with request/response details; security incidents hard to investigate
- Blocks: Compliance with data governance requirements
- Recommendation: Implement request logger with body logging (masked for sensitive fields)

**No Graceful Shutdown:**
- Problem: Server stops immediately without flushing pending requests
- Blocks: Long-running operations (large file uploads/exports) lost on deployment
- Recommendation: Implement `ApplicationListener<ContextClosedEvent>` to wait for in-flight requests

## Test Coverage Gaps

**Untested Error Paths in RDF Parsing:**
- What's not tested: Timeout handling during RDF parsing; malformed RDF recovery
- Files: `src/main/java/com/dia/ismdtoolbackend/service/impl/OntologyUploadServiceImpl.java` (lines 273-304)
- Risk: TimeoutException and ExecutionException not properly distinguished; error messages may leak implementation details
- Priority: High - affects file upload reliability

**Untested Semaphore Timeout Scenarios:**
- What's not tested: Behavior when semaphore timeout occurs; request queuing behavior
- Files: `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` (lines 55-93)
- Risk: Undiscovered edge cases in concurrent access; timeout race conditions
- Priority: High - affects concurrent request handling

**Missing Integration Tests for NKD Client:**
- What's not tested: Actual NKD endpoint communication; network failure scenarios
- Files: `src/main/java/com/dia/ismdtoolbackend/client/NkdSparqlClient.java`
- Risk: Endpoint URL changes, authentication changes, or endpoint downtime discovered in production
- Priority: Medium - can add contract tests with mock SPARQL endpoint

**No Load Tests:**
- What's not tested: System behavior under sustained high load; resource exhaustion scenarios
- Risk: Performance degradation or OOM discovered during production spike
- Priority: Medium - should establish baseline before release

---

*Concerns audit: 2026-03-22*
