package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.exception.JenaTDB2Exception;
import com.dia.ismdtoolbackend.utility.sparql.FusekiSparqlExecutor;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Fuseki client's request and body-idle timeouts, over real HTTP against WireMock playing Fuseki. */
class RequestTimeoutHttpClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int STALL_MS = 3000;

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static RDFConnection connection(HttpClient http) {
        return connection(http, WM.baseUrl());
    }

    private static RDFConnection connection(HttpClient http, String baseUrl) {
        return RDFConnectionRemote.newBuilder()
                .destination(baseUrl + "/ds")
                .queryEndpoint("sparql").gspEndpoint("data").updateEndpoint("update")
                .httpClient(http)
                .build();
    }

    private static HttpClient client(String user, String password) {
        return new RequestTimeoutHttpClient(JenaConfig.buildFusekiHttpClient(5000, user, password), TIMEOUT,
                TIMEOUT);
    }

    @Test
    void aStalledUpdateFailsAtTheTimeout() {
        WM.stubFor(post(urlEqualTo("/ds/update")).willReturn(aResponse().withStatus(204).withFixedDelay(STALL_MS)));

        long start = System.nanoTime();
        try (RDFConnection conn = connection(client("", ""))) {
            assertThatThrownBy(() -> conn.update("INSERT DATA { <urn:s> <urn:p> <urn:o> }"))
                    .hasRootCauseInstanceOf(HttpTimeoutException.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(STALL_MS));
    }

    @Test
    void aStalledGraphReadFailsAtTheTimeout() {
        WM.stubFor(get(urlPathEqualTo("/ds/data")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/turtle").withBody("").withFixedDelay(STALL_MS)));

        try (RDFConnection conn = connection(client("", ""))) {
            assertThatThrownBy(() -> conn.fetch("urn:g")).hasRootCauseInstanceOf(HttpTimeoutException.class);
        }
    }

    @Test
    void aTimelyAnswerIsUnaffected_andBasicAuthStillAnswersTheChallenge() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("ismd:pw".getBytes());
        WM.stubFor(post(urlEqualTo("/ds/update")).atPriority(2)
                .willReturn(aResponse().withStatus(401).withHeader("WWW-Authenticate", "BASIC realm=\"application\"")));
        WM.stubFor(post(urlEqualTo("/ds/update")).atPriority(1)
                .withHeader("Authorization", equalTo(basic))
                .willReturn(aResponse().withStatus(204)));

        try (RDFConnection conn = connection(client("ismd", "pw"))) {
            assertThatCode(() -> conn.update("INSERT DATA { <urn:s> <urn:p> <urn:o> }")).doesNotThrowAnyException();
        }
    }

    @Test
    void aRequestThatSetsItsOwnTimeoutKeepsIt() {
        RequestTimeoutHttpClient http = new RequestTimeoutHttpClient(HttpClient.newHttpClient(), TIMEOUT, TIMEOUT);
        HttpRequest own = HttpRequest.newBuilder(URI.create(WM.baseUrl())).timeout(Duration.ofSeconds(7)).build();
        HttpRequest bare = HttpRequest.newBuilder(URI.create(WM.baseUrl())).header("X-Test", "1").build();

        assertThat(http.withDefaultTimeout(own).timeout()).contains(Duration.ofSeconds(7));
        assertThat(http.withDefaultTimeout(bare).timeout()).contains(TIMEOUT);
        assertThat(http.withDefaultTimeout(bare).headers().firstValue("X-Test")).contains("1");
    }

    @Test
    void aBodyThatStallsAfterTheHeadersFailsAtTheIdleTimeout() throws Exception {
        try (StallingServer server = new StallingServer()) {
            long start = System.nanoTime();
            try (RDFConnection conn = connection(client("", ""), server.baseUrl())) {
                assertThatThrownBy(() -> conn.queryResultSet("SELECT * { ?s ?p ?o }", rs -> rs.forEachRemaining(r -> { })))
                        .hasRootCauseInstanceOf(HttpTimeoutException.class);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(STALL_MS));
        }
    }

    @Test
    void aStalledBodyReleasesTheExecutorPermit() throws Exception {
        Semaphore permits = new Semaphore(1);
        try (StallingServer server = new StallingServer()) {
            FusekiSparqlExecutor executor = new FusekiSparqlExecutor(permits, 1000,
                    () -> connection(client("", ""), server.baseUrl()));

            assertThatThrownBy(() -> executor.execute("stalled select", "Dotaz selhal.",
                    conn -> {
                        conn.queryResultSet("SELECT * { ?s ?p ?o }", rs -> rs.forEachRemaining(r -> { }));
                        return null;
                    }))
                    .isInstanceOf(JenaTDB2Exception.class)
                    .hasRootCauseInstanceOf(HttpTimeoutException.class);
        }
        assertThat(permits.availablePermits()).isEqualTo(1);
    }

    @Test
    void aSlowBodyThatKeepsArrivingIsNotCutOff() {
        String body = "<urn:s> <urn:p> \"" + "x".repeat(4000) + "\" .\n";
        WM.stubFor(get(urlPathEqualTo("/ds/data")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/n-triples").withBody(body)
                .withChunkedDribbleDelay(8, (int) TIMEOUT.toMillis() * 3)));

        try (RDFConnection conn = connection(client("", ""))) {
            assertThat(conn.fetch("urn:g").size()).isEqualTo(1);
        }
    }

    @Test
    void aSlowReaderIsNotMistakenForAStalledServer() throws Exception {
        byte[] large = new byte[8 * 1024 * 1024];
        WM.stubFor(get(urlEqualTo("/big")).willReturn(aResponse().withStatus(200).withBody(large)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(WM.baseUrl() + "/big")).build();

        HttpResponse<InputStream> response = client("", "").send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            in.read(new byte[1024]);
            Thread.sleep(TIMEOUT.toMillis() * 3);
            assertThat(in.readAllBytes()).hasSize(large.length - 1024);
        }
    }

    /** Answers with SPARQL-results headers and the start of a body, then sends nothing until closed. */
    private static final class StallingServer implements AutoCloseable {

        private final ServerSocket socket = new ServerSocket(0);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final Thread acceptor = new Thread(this::serve, "stalling-fuseki");

        StallingServer() throws Exception {
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String baseUrl() {
            return "http://localhost:" + socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    client.getInputStream().read(new byte[8192]);
                    OutputStream out = client.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/sparql-results+json\r\n"
                            + "Content-Length: 100000\r\n\r\n{\"head\":{\"vars\":[\"s\"]},\"results\":{\"bindings\":[")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    closed.await(STALL_MS * 2L, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws Exception {
            closed.countDown();
            socket.close();
        }
    }
}
