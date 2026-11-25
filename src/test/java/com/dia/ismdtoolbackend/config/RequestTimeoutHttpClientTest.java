package com.dia.ismdtoolbackend.config;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;

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

/** The Fuseki client's default request timeout, over real HTTP against WireMock playing Fuseki. */
class RequestTimeoutHttpClientTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int STALL_MS = 3000;

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static RDFConnection connection(HttpClient http) {
        return RDFConnectionRemote.newBuilder()
                .destination(WM.baseUrl() + "/ds")
                .queryEndpoint("sparql").gspEndpoint("data").updateEndpoint("update")
                .httpClient(http)
                .build();
    }

    private static HttpClient client(String user, String password) {
        return new RequestTimeoutHttpClient(JenaConfig.buildFusekiHttpClient(5000, user, password), TIMEOUT);
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
        RequestTimeoutHttpClient http = new RequestTimeoutHttpClient(HttpClient.newHttpClient(), TIMEOUT);
        HttpRequest own = HttpRequest.newBuilder(URI.create(WM.baseUrl())).timeout(Duration.ofSeconds(7)).build();
        HttpRequest bare = HttpRequest.newBuilder(URI.create(WM.baseUrl())).header("X-Test", "1").build();

        assertThat(http.withDefaultTimeout(own).timeout()).contains(Duration.ofSeconds(7));
        assertThat(http.withDefaultTimeout(bare).timeout()).contains(TIMEOUT);
        assertThat(http.withDefaultTimeout(bare).headers().firstValue("X-Test")).contains("1");
    }
}
