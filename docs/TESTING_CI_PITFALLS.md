# Testing: CI-Only Failures and How to Avoid Them

Tests that pass locally can still fail in the GitHub pipeline (`reusable-test.yml`). This page lists
the differences that cause this, the traps they have produced, and how to reproduce a CI failure
on a laptop.

## 1. Local vs CI

| | Local (typical)                | CI (`ubuntu-latest`) |
|---|--------------------------------|---|
| JDK | whatever `JAVA_HOME` points to | **17** (`setup-java`, temurin) |
| CPUs | N                              | **4** vCPUs |
| ForkJoin common pool | N workers                      | **3** workers |
| Command | `mvn test`                     | `./mvnw clean verify` (JaCoCo agent attached) |
| Test class order | filesystem order on APFS       | filesystem order on ext4, a **different** sequence |
| JVMs | one Surefire fork, reused      | same: every test class shares one JVM |

The last two rows mean that any state one test class leaves behind (rows in a shared database, a
mutated singleton, a busy thread pool) reaches different classes in CI than it does locally.

## 2. Never block on the common pool around an HTTP client

**Rule:** in tests, always pass an explicit executor to `CompletableFuture.supplyAsync` /
`runAsync` when the task calls anything backed by the JDK `HttpClient` (every `RestClient` built on
`JdkClientHttpRequestFactory`, which includes the e-Sbírka REST transport).

```java
// Wrong: the three callers run on the common pool
CompletableFuture.supplyAsync(() -> client.fetchVersionContent(iri));

// Right
ExecutorService callers = Executors.newFixedThreadPool(3);
try {
    CompletableFuture.supplyAsync(() -> client.fetchVersionContent(iri), callers);
    ...
} finally {
    callers.shutdownNow();
}
```

**Why:** Spring's `JdkClientHttpRequest` sends with `HttpClient.sendAsync(...).get()`, and the JDK
completes the future returned by `sendAsync` through a task on the **ForkJoin common pool**. If
the callers themselves occupy every common-pool worker and block, nothing is left to deliver the
response. It has already arrived, but the call still waits until its timeout.

Real case, `EsbirkaRestCachingTest` (fixed 2026-10-20). Three concurrent cold loads ran on the
common pool. One held the `@Cacheable(sync = true)` load and waited for its HTTP response; the
other two were blocked on the cache lock. With 4 CPUs the pool has exactly 3 workers, so the
response was never delivered:

```
e-Sbírka fragmenty timed out after 20000 ms
  Caused by: ResourceAccessException: I/O error on GET request for "http://localhost:…/fragmenty": null
  Caused by: java.net.http.HttpTimeoutException
```

It never fails on a laptop. A large machine has spare workers, and on 1–2 CPUs (pool parallelism
1) `CompletableFuture` starts a new thread per task instead of using the pool. Only a CPU count
where the pool size equals the number of blocked callers hangs.

The same applies to production code. A blocking task on the common pool (e.g. `runAsync(...)`
without an executor) delays every `sendAsync` completion in the JVM. Give blocking work its own
executor.

## 3. The Postgres container is shared by every test class

`PostgresIntegrationTestBase` starts one container for the whole JVM and never stops it. Each
subclass gets its own Spring context, but they all use the same database.

- **Start from a known state.** Delete the tables you assert on in `@BeforeEach`; never assume
  another class left them empty:
  ```java
  @BeforeEach
  void startFromEmpty() {
      txTemplate.executeWithoutResult(tx -> repository.deleteAll());
  }
  ```
- **Delete and insert in separate transactions.** Hibernate flushes inserts before deletes within
  one transaction, so re-creating a row with the same unique key (e.g. an ontology slug) collides
  with the row being deleted.
- **Do not use `@Testcontainers` / `@Container`** on subclasses. The extension stops the container
  after the first class, and every later class gets `connection refused`.

## 4. Cached contexts keep mutated singletons

Spring caches application contexts across test classes in the same JVM. A test that changes a
singleton bean (e.g. `OutboxConfig.setMaxAttempts(...)`) changes it for every later test using that
context. Reset such values in `@BeforeEach`, not `@AfterEach`, so a failed test cannot leak its
change.

## 5. Reading a CI failure

The log contains many `Caused by:` stack traces that are **not** failures: tests that check
error handling inject them on purpose and the code under test logs them (`DB down`, `fuseki down`,
`connection refused`, and the `MismatchedInputException … ArrayList<String>` from
`OutboxRelayTest`'s corrupt `"{not valid json"` payload).

Start from the Surefire summary instead:

```
[ERROR]   EsbirkaRestCachingTest.concurrentColdLoadsShareOneFetchAndResolvesReuseIt » Completion …
```

## 6. Reproducing CI locally

```bash
# CI's JDK and CPU count for one test class
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
  ./mvnw surefire:test -Dtest=EsbirkaRestCachingTest -DargLine="-XX:ActiveProcessorCount=4"

# The full CI command
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw clean verify
```

`-DargLine` replaces the JaCoCo agent argument, so use it only to reproduce failures, not for
coverage. If a test hangs, take a thread dump while it is stuck (`jstack <pid>`; `jps -l` lists
the Surefire fork). Look at what the `ForkJoinPool.commonPool-worker-*` threads are waiting on.
## 7. Tests never call outside services

The `junit` profile (`src/test/resources/application-junit.yml`) blanks `nkd`, `nkod`, `rpp` and
`esbirka` `.sparql.endpoint` and points `esbirka.rest.base-url` at a closed local port. A test
that activates another profile (`local`, `production`) must repeat these overrides in its
`@TestPropertySource`, because those profiles point at live endpoints.

Without them every full Spring context started the NKOD and RPP warm-ups, which downloaded about
30 000 datasets from production data.gov.cz and the live RPP registry. The suite got 40 s slower,
depended on outside uptime, and earned coverage from network data that no test checks.

Stub an outside service with WireMock (or a mocked client) and a recorded response instead.

## 8. Coverage gate

The JaCoCo minimums are set in `pom.xml` (`jacoco.*.minimum`). `pr.yml` overrides one when the
matching repository variable is set (`JACOCO_LINE_MINIMUM`, `_INSTRUCTION_`, `_BRANCH_`,
`_COMPLEXITY_`, `_METHOD_`), so a threshold can change without a PR; an unset variable keeps the
pom value. `check-coverage.yml` takes the same overrides for a manual run. A variable set lower
than the pom silently weakens the gate, so delete it once the pom catches up. Raise a minimum when
coverage grows and never lower it to make a build pass. `push-to-dev.yml` runs the tests without
the gate.

## 9. Mocks that cannot fail a test

- **Strict stubs.** Do not use `Strictness.LENIENT` at class level. Unused stubs then fail the
  test, which exposes mocks that are never wired into the class under test.
- **A mock must reach the code.** A test that stubs a collaborator the class never receives passes
  whatever the code does. Build the class under test from the same mocks you stub.
- **Fail-soft paths need a working baseline.** An unstubbed mock returns `null` or empty, so the
  code takes its degraded path and a "falls back" test passes vacuously. Stub the healthy answer
  first, then break one thing.
- **Mock only what does I/O.** Use real Jena models, real DTOs and real mappers. A mocked mapper
  or `Model` makes the test check what the mock returned.
- **Mock beans in shared contexts.** Declare them with `@MockitoBean`, which Spring resets after
  each test. A plain `Mockito.mock()` returned from a `@Bean` keeps its stubs for every later
  class that shares the context.
- **Check a test by breaking the code.** Change the rule it guards, run the test, see it fail, then
  restore the file. Restore with a new timestamp (`touch` it, or run `./mvnw clean`): `mv` or
  `cp -p` of a backup keeps the old time, Maven's incremental compile then skips the file, and
  later runs test the broken class.
- **Security rules.** An anonymous request rejected by `denyAll` also gets 401, so checking that
  anonymous gets 401 cannot catch a removed rule. Also check that an authenticated request is
  **not** 403.

## 10. Time and concurrency

- Inject a `Clock` instead of reading `System.currentTimeMillis()`, and advance it in the test
  instead of sleeping.
- Coordinate threads with `CountDownLatch` or `CyclicBarrier`, never with `Thread.sleep`.
  "Not finished within 200 ms" is not proof of locking on a loaded runner.
- Restore thread and session state in `finally` (interrupt flag, `SET` on a pooled connection,
  or use `SET LOCAL`), so a failing assertion cannot leak it into the next class.
- Give timeout tests a short timeout of their own (about 250 ms) instead of waiting for the
  production default.
