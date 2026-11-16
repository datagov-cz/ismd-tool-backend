package com.dia.ismdtoolbackend.config;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.http.HttpClient;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fuseki basic auth on the shared HTTP client, over real HTTP against WireMock playing Fuseki. */
class JenaConfigTest {

    private static final String UPDATE = "INSERT DATA { <urn:s> <urn:p> <urn:o> }";
    private static final String USER = "ismd";
    private static final String PASSWORD = "test-only-password";
    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes());

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static RDFConnection connection(HttpClient http) {
        return RDFConnectionRemote.newBuilder()
                .destination(WM.baseUrl() + "/ismd-tool-dataset")
                .queryEndpoint("sparql").gspEndpoint("data").updateEndpoint("update")
                .httpClient(http)
                .build();
    }

    /** Fuseki with Shiro: 401 with a challenge unless the right Authorization header is present. */
    private static void stubEnforcingFuseki() {
        WM.stubFor(post(urlEqualTo("/ismd-tool-dataset/update")).atPriority(2)
                .willReturn(aResponse().withStatus(401).withHeader("WWW-Authenticate", "BASIC realm=\"application\"")));
        WM.stubFor(post(urlEqualTo("/ismd-tool-dataset/update")).atPriority(1)
                .withHeader("Authorization", equalTo(BASIC))
                .willReturn(aResponse().withStatus(204)));
    }

    @Test
    void noCredentialsBuildsAClientWithoutAuthenticator() {
        assertThat(JenaConfig.buildFusekiHttpClient(5000, "", "").authenticator()).isEmpty();
        assertThat(JenaConfig.buildFusekiHttpClient(5000, null, null).authenticator()).isEmpty();
    }

    @Test
    void credentialsBuildAClientWithAuthenticator() {
        assertThat(JenaConfig.buildFusekiHttpClient(5000, USER, PASSWORD).authenticator()).isPresent();
    }

    @Test
    void onlyOneOfUsernameAndPasswordFailsStartup() {
        assertThatThrownBy(() -> JenaConfig.buildFusekiHttpClient(5000, USER, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be set together");
        assertThatThrownBy(() -> JenaConfig.buildFusekiHttpClient(5000, " ", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void credentialsAnswerFusekisChallenge() {
        stubEnforcingFuseki();
        try (RDFConnection conn = connection(JenaConfig.buildFusekiHttpClient(5000, USER, PASSWORD))) {
            conn.update(UPDATE);
        }
        WM.verify(postRequestedFor(urlEqualTo("/ismd-tool-dataset/update")).withHeader("Authorization", equalTo(BASIC)));
    }

    @Test
    void credentialsStillWorkAgainstAnOpenFuseki() {
        WM.stubFor(post(urlEqualTo("/ismd-tool-dataset/update")).willReturn(aResponse().withStatus(204)));
        try (RDFConnection conn = connection(JenaConfig.buildFusekiHttpClient(5000, USER, PASSWORD))) {
            conn.update(UPDATE);
        }
        // Open Fuseki never challenges, so the password is never sent.
        WM.verify(postRequestedFor(urlEqualTo("/ismd-tool-dataset/update")).withHeader("Authorization", absent()));
    }

    @Test
    void wrongPasswordSurfacesAs401() {
        stubEnforcingFuseki();
        try (RDFConnection conn = connection(JenaConfig.buildFusekiHttpClient(5000, USER, "wrong"))) {
            assertThatThrownBy(() -> conn.update(UPDATE))
                    .isInstanceOf(HttpException.class)
                    .satisfies(e -> assertThat(((HttpException) e).getStatusCode()).isEqualTo(401));
        }
    }

    @Test
    void noCredentialsAgainstEnforcingFusekiSurfacesAs401() {
        stubEnforcingFuseki();
        try (RDFConnection conn = connection(JenaConfig.buildFusekiHttpClient(5000, "", ""))) {
            assertThatThrownBy(() -> conn.update(UPDATE))
                    .isInstanceOf(HttpException.class)
                    .satisfies(e -> assertThat(((HttpException) e).getStatusCode()).isEqualTo(401));
        }
    }
}
