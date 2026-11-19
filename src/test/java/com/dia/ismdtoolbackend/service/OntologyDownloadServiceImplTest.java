package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.impl.OntologyDownloadServiceImpl;
import com.dia.ismdtoolbackend.testsupport.rdf.JsonLd;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import com.dia.ismdtoolbackend.exception.OfnConformanceException;
import com.dia.ismdtoolbackend.ofn.ConformanceIssue;
import com.dia.ismdtoolbackend.ofn.OfnJsonLdParser;
import com.dia.ismdtoolbackend.ofn.OfnRdf;
import com.dia.ismdtoolbackend.ofn.OfnSpecification;
import com.dia.ismdtoolbackend.ofn.OfnDates;
import com.dia.ismdtoolbackend.ofn.OfnVocabularyExporter;
import com.dia.ismdtoolbackend.service.impl.OntologyUploadServiceFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.StringReader;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertContainsTriples;
import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OntologyDownloadServiceImpl} fetches the vocabulary graph from Fuseki over GSP and downloads it
 * through the real OFN exporter: blocked with every issue when it does not conform, otherwise JSON-LD and
 * Turtle of the same graph. Fuseki is a {@link FakeFuseki} {@link HttpClient} handed to the real Jena
 * connection, so the request and the parsed response are Jena's own; nothing leaves the JVM.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDownloadServiceImplTest {

    static {
        Ttl.initJena();
    }

    private static final String FUSEKI_ENDPOINT = "http://fuseki.invalid/ds";
    private static final Long ONTOLOGY_ID = 1L;
    private static final String GRAPH_NAME = "https://slovník.gov.cz/datový/test-slovník";

    /** An OFN-conformant vocabulary as Fuseki stores it. */
    private static final String STORED_GRAPH = """
            <https://slovník.gov.cz/datový/test-slovník> a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                skos:prefLabel "Testovací slovník"@cs .
            pojem:osoba a owl:Class, skos:Concept, slovníky:pojem ;
                skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ;
                skos:prefLabel "Osoba"@cs ;
                slovníky:definující-ustanovení <https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2012/89> ;
                rdfs:subClassOf pojem:subjekt .
            """;

    /** {@link #STORED_GRAPH} with a value OFN cannot express and a Vztah without domain and range. */
    private static final String NONCONFORMANT_GRAPH = STORED_GRAPH + """
            pojem:osoba <https://slovník.gov.cz/datový/test-slovník/pojem/inTezaurus> "true" .
            pojem:má-adresu a owl:ObjectProperty, skos:Concept, slovníky:pojem ;
                skos:inScheme <https://slovník.gov.cz/datový/test-slovník> ;
                skos:prefLabel "má adresu"@cs .
            """;

    @Mock
    private OntologyMetadataRepository ontologyMetadataRepository;

    private final FakeFuseki fuseki = new FakeFuseki();
    private final Semaphore semaphore = new Semaphore(1, true);

    private OntologyDownloadServiceImpl service(int semaphoreTimeoutMs) {
        OfnSpecification specification = OfnSpecification.bundled();
        OfnVocabularyExporter exporter = new OfnVocabularyExporter(
                OntologyUploadServiceFixture.ofnWriteGate(), specification, new OfnRdf(specification));
        return new OntologyDownloadServiceImpl(
                FUSEKI_ENDPOINT, fuseki, ontologyMetadataRepository, exporter, semaphore, semaphoreTimeoutMs);
    }

    private OntologyDownloadServiceImpl service() {
        return service(0);
    }

    private void ontologyExists() {
        ontologyExists(null);
    }

    private void ontologyExists(LocalDateTime updatedAt) {
        OntologyMetadataEntity metadata = new OntologyMetadataEntity();
        metadata.setId(ONTOLOGY_ID);
        metadata.setGraphName(GRAPH_NAME);
        metadata.setUpdatedAt(updatedAt);
        when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.of(metadata));
    }

    @Nested
    class Turtle {

        @ParameterizedTest
        @ValueSource(strings = {"ttl", "TTL", "TtL"})
        void exportsTheFilteredOfnGraph(String format) {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            String ttl = service().downloadOntology(ONTOLOGY_ID, format);

            assertIsomorphic(STORED_GRAPH, Ttl.parse(ttl));
        }

        @Test
        void fetchesTheMetadataGraphFromTheGspEndpoint() {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            service().downloadOntology(ONTOLOGY_ID, "ttl");

            assertThat(fuseki.requests).singleElement().satisfies(request -> {
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.uri().getPath()).isEqualTo("/ds/data");
                assertThat(request.uri().getQuery()).isEqualTo("graph=" + GRAPH_NAME);
            });
        }

        @Test
        void writesOfnPrefixes() {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            String ttl = service().downloadOntology(ONTOLOGY_ID, "ttl");

            assertThat(ModelFactory.createDefaultModel().read(new StringReader(ttl), null, "TTL").getNsPrefixMap())
                    .containsEntry("slovníky", "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/")
                    .containsEntry("l111-2009", "https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/");
        }
    }

    @Nested
    class JsonLdExport {

        @ParameterizedTest
        @ValueSource(strings = {"json-ld", "JSON-LD", "Json-Ld"})
        void isTheStoredGraph_inOfnTerms(String format) throws Exception {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            String json = service().downloadOntology(ONTOLOGY_ID, format);

            Model expanded = ModelFactory.createDefaultModel();
            OfnJsonLdParser.parse(json, expanded);
            assertIsomorphic(STORED_GRAPH, expanded);
            JsonNode document = new ObjectMapper().readTree(json);
            assertThat(document.get("@context").asText()).isEqualTo(OfnSpecification.bundled().contextUrl());
            assertThat(document.get("pojmy").get(0).get("typ").toString())
                    .contains("Třída").doesNotContain("slovníky:třída");
        }

        @Test
        void andTurtle_areTheSameGraph() {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            String json = service().downloadOntology(ONTOLOGY_ID, "json-ld");
            String ttl = service().downloadOntology(ONTOLOGY_ID, "ttl");

            Model fromJson = ModelFactory.createDefaultModel();
            OfnJsonLdParser.parse(json, fromJson);
            assertThat(fromJson.isIsomorphicWith(Ttl.parse(ttl))).isTrue();
        }
    }

    @Nested
    class LastChange {

        /** {@link #STORED_GRAPH} with the last-change date an uploaded file carried. */
        private static final String UPLOADED_WITH_LAST_CHANGE = STORED_GRAPH + """
                <https://slovník.gov.cz/datový/test-slovník> slovníky:okamžik-poslední-změny [
                    a čas:časový-okamžik ; čas:datum-a-čas "2020-01-01T00:00:00Z"^^xsd:dateTimeStamp ] .
                """;

        private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 11, 4, 9, 30, 15, 123_000_000);

        private static String expectedLastChange() {
            return UPDATED_AT.atZone(ZoneId.systemDefault()).withZoneSameInstant(OfnDates.ZONE)
                    .toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }

        @Test
        void aktualizovano_isTheMetadataLastChange_notTheUploadedValue() throws Exception {
            ontologyExists(UPDATED_AT);
            fuseki.servesTurtle(UPLOADED_WITH_LAST_CHANGE);

            JsonNode lastChange = new ObjectMapper()
                    .readTree(service().downloadOntology(ONTOLOGY_ID, "json-ld")).get("aktualizováno");

            assertThat(lastChange.get("typ").asText()).isEqualTo("Časový okamžik");
            assertThat(lastChange.get("datum_a_čas").asText()).isEqualTo(expectedLastChange());
        }

        @Test
        void turtle_hasOnlyTheMetadataLastChange() {
            ontologyExists(UPDATED_AT);
            fuseki.servesTurtle(UPLOADED_WITH_LAST_CHANGE);

            Model ttl = Ttl.parse(service().downloadOntology(ONTOLOGY_ID, "ttl"));

            assertIsomorphic(STORED_GRAPH + """
                    <https://slovník.gov.cz/datový/test-slovník> slovníky:okamžik-poslední-změny [
                        a čas:časový-okamžik ; čas:datum-a-čas "%s"^^xsd:dateTimeStamp ] .
                    """.formatted(expectedLastChange()), ttl);
        }

        @Test
        void withoutAMetadataLastChange_theUploadedValueIsDropped() throws Exception {
            ontologyExists(null);
            fuseki.servesTurtle(UPLOADED_WITH_LAST_CHANGE);

            JsonNode document = new ObjectMapper().readTree(service().downloadOntology(ONTOLOGY_ID, "json-ld"));

            assertThat(document.has("aktualizováno")).isFalse();
        }
    }

    @Nested
    class OfnBlock {

        @ParameterizedTest
        @ValueSource(strings = {"ttl", "json-ld"})
        void aNonConformantVocabulary_isBlocked_withEveryIssue(String format) {
            ontologyExists();
            fuseki.servesTurtle(NONCONFORMANT_GRAPH);

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, format))
                    .isInstanceOfSatisfying(OfnConformanceException.class, e -> {
                        assertThat(e.getContext()).isEqualTo(OfnConformanceException.Context.DOWNLOAD);
                        assertThat(e.getIssues()).extracting(issue -> issue.kind())
                                .containsExactlyInAnyOrder(ConformanceIssue.Kind.VALUE_DROPPED,
                                        ConformanceIssue.Kind.CONCEPT_INCOMPLETE);
                    });
            assertThat(semaphore.availablePermits()).isOne();
        }
    }

    @Nested
    class Rejects {

        @Test
        void unknownOntologyWithoutCallingFuseki() {
            when(ontologyMetadataRepository.findById(ONTOLOGY_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, "ttl"))
                    .isInstanceOf(OntologyNotFoundException.class)
                    .hasMessage("Metadata slovníku nebyla nalezena");
            assertThat(fuseki.requests).isEmpty();
            assertThat(semaphore.availablePermits()).isOne();
        }

        @Test
        void anEmptyGraph() {
            ontologyExists();
            fuseki.servesTurtle("");

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, "ttl"))
                    .isInstanceOf(EmptyDataException.class)
                    .hasMessage("Slovník je prázdný, nebo nebyl nalezen.");
            assertThat(semaphore.availablePermits()).isOne();
        }

        @Test
        void aMissingGraph() {
            ontologyExists();
            // Fuseki answers a GSP GET for an unknown graph with 404.
            fuseki.replies(404, "text/plain", "Not Found");

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, "ttl"))
                    .isInstanceOf(EmptyDataException.class)
                    .hasMessage("Slovník je prázdný, nebo nebyl nalezen.");
            assertThat(semaphore.availablePermits()).isOne();
        }

        @ParameterizedTest
        @ValueSource(strings = {"xml", "rdf", "json"})
        void anUnsupportedFormat(String format) {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, format))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Nepodporovaný formát: " + format);
            assertThat(semaphore.availablePermits()).isOne();
        }

        @Test
        void aNullFormat() {
            ontologyExists();
            fuseki.servesTurtle(STORED_GRAPH);

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Nepodporovaný formát: null");
        }

        @Test
        void aFusekiErrorAsJenaHttpException() {
            ontologyExists();
            fuseki.replies(500, "text/plain", "boom");

            assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, "ttl"))
                    .isInstanceOf(HttpException.class);
        }
    }

    @Nested
    class FusekiPermit {

        @Test
        void isReleasedAfterAFailedDownload() {
            ontologyExists();
            fuseki.replies(500, "text/plain", "boom");
            OntologyDownloadServiceImpl service = service();

            assertThatThrownBy(() -> service.downloadOntology(ONTOLOGY_ID, "ttl"))
                    .isInstanceOf(HttpException.class);

            fuseki.servesTurtle(STORED_GRAPH);
            String ttl = service.downloadOntology(ONTOLOGY_ID, "ttl");

            assertIsomorphic(STORED_GRAPH, Ttl.parse(ttl));
            assertThat(semaphore.availablePermits()).isOne();
        }

        @Test
        void busyWhileAnotherDownloadHoldsIt() throws Exception {
            ontologyExists();
            CountDownLatch fetching = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            fuseki.blocksUntil(fetching, finish);
            OntologyDownloadServiceImpl service = service();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> first = executor.submit(() -> service.downloadOntology(ONTOLOGY_ID, "ttl"));
                assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> service.downloadOntology(ONTOLOGY_ID, "ttl"))
                        .isExactlyInstanceOf(RuntimeException.class)
                        .hasMessage("Fuseki server is busy, try again later");
                assertThat(fuseki.requests).hasSize(1);

                finish.countDown();
                assertIsomorphic(STORED_GRAPH, Ttl.parse(first.get(5, TimeUnit.SECONDS)));
            } finally {
                finish.countDown();
                executor.shutdownNow();
            }
            assertThat(semaphore.availablePermits()).isOne();
        }

        @Test
        void busyKeepsTheHolderPermit() throws InterruptedException {
            ontologyExists();
            semaphore.acquire();
            try {
                assertThatThrownBy(() -> service().downloadOntology(ONTOLOGY_ID, "ttl"))
                        .hasMessage("Fuseki server is busy, try again later");
                assertThat(semaphore.availablePermits()).isZero();
                assertThat(fuseki.requests).isEmpty();
            } finally {
                semaphore.release();
            }
        }

        @Test
        void interruptedWaitKeepsTheInterruptFlag() {
            ontologyExists();
            OntologyDownloadServiceImpl service = service(250);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> service.downloadOntology(ONTOLOGY_ID, "ttl"))
                        .isExactlyInstanceOf(RuntimeException.class)
                        .hasMessage("Interrupted while waiting for Fuseki connection")
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(semaphore.availablePermits()).isOne();
            assertThat(fuseki.requests).isEmpty();
        }
    }

    /**
     * {@link HttpClient} that answers every request in-process with a canned reply, so the real
     * {@code RDFConnectionRemote} runs against it.
     */
    private static final class FakeFuseki extends HttpClient {

        @FunctionalInterface
        interface Responder {
            Reply reply(HttpRequest request) throws InterruptedException;
        }

        record Reply(int status, String contentType, String body) {}

        final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private volatile Responder responder;

        void servesTurtle(String turtle) {
            replies(200, "text/turtle", Ttl.PREFIXES + turtle);
        }

        void replies(int status, String contentType, String body) {
            Reply reply = new Reply(status, contentType, body);
            responder = request -> reply;
        }

        /** Signals {@code entered} on the first request, then waits for {@code release} before answering. */
        void blocksUntil(CountDownLatch entered, CountDownLatch release) {
            Reply reply = new Reply(200, "text/turtle", Ttl.PREFIXES + OntologyDownloadServiceImplTest.STORED_GRAPH);
            responder = request -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocked request never released");
                }
                return reply;
            };
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            requests.add(request);
            Reply reply = responder.reply(request);
            HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", List.of(reply.contentType())), (k, v) -> true);
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override public int statusCode() { return reply.status(); }
                @Override public HttpHeaders headers() { return headers; }
                @Override public Version version() { return Version.HTTP_1_1; }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() {}
            });
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
            }
            subscriber.onComplete();
            T body;
            try {
                body = subscriber.getBody().toCompletableFuture().get();
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            }
            return new HttpResponse<>() {
                @Override public int statusCode() { return reply.status(); }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return headers; }
                @Override public T body() { return body; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try {
                return CompletableFuture.completedFuture(send(request, handler));
            } catch (IOException | InterruptedException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }
}
