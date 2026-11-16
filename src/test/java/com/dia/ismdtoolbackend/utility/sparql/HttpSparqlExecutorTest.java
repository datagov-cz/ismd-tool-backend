package com.dia.ismdtoolbackend.utility.sparql;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.literal;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.serverError;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.sparqlJson;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.turtle;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSparqlExecutorTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SELECT = "SELECT ?x WHERE { ?s ?p ?x } LIMIT 1";
    private static final String ASK = "ASK { ?s ?p ?o }";
    private static final String CONSTRUCT = "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }";

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static HttpSparqlExecutor executor(int maxConcurrent, int timeoutMs) {
        return new HttpSparqlExecutor("NKD", WM.baseUrl() + "/sparql", timeoutMs, HTTP, maxConcurrent);
    }

    private static void answer(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        WM.stubFor(any(anyUrl()).willReturn(response));
    }

    @Nested
    class NotConfigured {

        private final HttpSparqlExecutor blank = new HttpSparqlExecutor("NKD", " ", 1000);

        @Test
        void everyStrictCallFailsAsUnavailableWithoutACall() {
            assertThat(blank.isConfigured()).isFalse();
            assertThatThrownBy(() -> blank.select("q", SELECT, rs -> 1))
                    .isInstanceOf(SparqlEndpointUnavailableException.class)
                    .hasMessage("NKD endpoint not configured");
            assertThatThrownBy(() -> blank.ask("q", ASK)).isInstanceOf(SparqlEndpointUnavailableException.class);
            assertThatThrownBy(() -> blank.construct("q", CONSTRUCT)).isInstanceOf(SparqlEndpointUnavailableException.class);
        }

        @Test
        void lenientConstructIsEmpty() {
            assertThat(blank.constructLenient("q", CONSTRUCT)).isEmpty();
            WM.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    @Nested
    class Answers {

        @Test
        void selectHandsTheResultSetToTheMapper() {
            answer(sparqlJson(SparqlJson.select("x").row(literal("x", "hodnota"))));

            String value = executor(0, 2000).select("q", SELECT, rs -> rs.next().getLiteral("x").getString());

            assertThat(value).isEqualTo("hodnota");
        }

        @Test
        void askReturnsTheEndpointsAnswer() {
            answer(okForContentType("application/sparql-results+json", "{\"head\":{},\"boolean\":true}"));
            assertThat(executor(0, 2000).ask("q", ASK)).isTrue();

            answer(okForContentType("application/sparql-results+json", "{\"head\":{},\"boolean\":false}"));
            assertThat(executor(0, 2000).ask("q", ASK)).isFalse();
        }

        @Test
        void constructReturnsTheModelAndEmptyWhenThereAreNoTriples() {
            answer(turtle("<https://example.test/a> <https://example.test/p> \"x\" ."));
            Optional<Model> model = executor(0, 2000).construct("q", CONSTRUCT);
            assertThat(model).hasValueSatisfying(m -> assertThat(m.size()).isEqualTo(1));

            answer(turtle(""));
            assertThat(executor(0, 2000).construct("q", CONSTRUCT)).isEmpty();
            assertThat(executor(0, 2000).constructLenient("q", CONSTRUCT)).isEmpty();
        }
    }

    @Nested
    class Failures {

        @Test
        void httpErrorIsUnavailableNamingTheCall() {
            answer(serverError());

            assertThatThrownBy(() -> executor(0, 2000).select("hledání pojmů", SELECT, rs -> 1))
                    .isInstanceOf(SparqlEndpointUnavailableException.class)
                    .hasMessageStartingWith("hledání pojmů fetch failed");
            assertThatThrownBy(() -> executor(0, 2000).construct("q", CONSTRUCT))
                    .isInstanceOf(SparqlEndpointUnavailableException.class);
        }

        @Test
        void lenientConstructSwallowsAnHttpError() {
            answer(serverError());

            assertThat(executor(0, 2000).constructLenient("q", CONSTRUCT)).isEmpty();
        }

        @Test
        void failingMapperIsUnavailableAsAMappingFailure() {
            answer(sparqlJson(SparqlJson.select("x")));

            assertThatThrownBy(() -> executor(0, 2000).select("q", SELECT, rs -> {
                throw new IllegalStateException("bad row");
            }))
                    .isInstanceOf(SparqlEndpointUnavailableException.class)
                    .hasMessage("q mapping failed: bad row");
        }

        @Test
        void unavailableFromTheMapperIsPassedOnUnwrapped() {
            answer(sparqlJson(SparqlJson.select("x")));
            SparqlEndpointUnavailableException own = new SparqlEndpointUnavailableException("NKD", "own");

            assertThatThrownBy(() -> executor(0, 2000).select("q", SELECT, rs -> {
                throw own;
            })).isSameAs(own);
        }
    }

    @Nested
    class Bulkhead {

        @Test
        void callBeyondTheLimitFailsAsBusyInsteadOfQueueing() throws Exception {
            answer(sparqlJson(SparqlJson.select("x").row(literal("x", "1"))));
            HttpSparqlExecutor bounded = executor(1, 200);
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> bounded.select("first", SELECT, rs -> {
                    holding.countDown();
                    await(release);
                    return 1;
                }), caller);
                assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> bounded.select("second", SELECT, rs -> 2))
                        .isInstanceOf(SparqlEndpointUnavailableException.class)
                        .hasMessage("NKD is busy, try again later");

                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                Integer third = bounded.select("third", SELECT, rs -> 3);
                assertThat(third).isEqualTo(3);
            } finally {
                release.countDown();
                caller.shutdownNow();
            }
        }

        @Test
        void interruptedWaitFailsAndKeepsTheInterrupt() {
            HttpSparqlExecutor bounded = executor(1, 2000);
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> bounded.select("q", SELECT, rs -> 1))
                        .isInstanceOf(SparqlEndpointUnavailableException.class)
                        .hasMessage("Interrupted waiting for NKD capacity");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            WM.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
