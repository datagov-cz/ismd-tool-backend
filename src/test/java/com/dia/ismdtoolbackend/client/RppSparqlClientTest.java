package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.util.List;

import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.literal;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlJson.uri;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.serverError;
import static com.dia.ismdtoolbackend.testsupport.sparql.SparqlStubs.sparqlJson;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RppSparqlClientTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @RegisterExtension
    static final WireMockExtension WM = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private RppSparqlClient client;

    @BeforeEach
    void setUp() {
        client = new RppSparqlClient(HTTP);
        ReflectionTestUtils.setField(client, "rppEndpoint", WM.baseUrl() + "/sparql");
        ReflectionTestUtils.setField(client, "rppSparqlTimeout", 2000);
    }

    @Test
    void emptyResultSetReturnsEmptyList() {
        stubSparql(emptyResults());
        List<RppAgenda> out = client.fetchAllAgendas();
        assertEquals(0, out.size());
    }

    @Test
    void isvsCollapseZeroAgendas() {
        stubSparql(SparqlJson.select("isvs", "code", "nazev", "agenda")
                .row(uri("isvs", "http://example.org/i/1"), literal("code", "100"), literal("nazev", "Alfa")));
        List<RppIsvs> out = client.fetchAllIsvs();
        assertEquals(1, out.size());
        assertEquals("http://example.org/i/1", out.get(0).getIri());
        assertEquals(0, out.get(0).getAgendaIris().size());
    }

    @Test
    void isvsCollapseOneAgenda() {
        stubSparql(SparqlJson.select("isvs", "code", "nazev", "agenda")
                .row(isvsAlfa("http://example.org/a/A1")));
        List<RppIsvs> out = client.fetchAllIsvs();
        assertEquals(1, out.size());
        assertEquals(List.of("http://example.org/a/A1"), out.get(0).getAgendaIris());
    }

    @Test
    void isvsCollapseMultipleAgendas() {
        stubSparql(SparqlJson.select("isvs", "code", "nazev", "agenda")
                .row(isvsAlfa("http://example.org/a/A1"))
                .row(isvsAlfa("http://example.org/a/A2"))
                .row(isvsAlfa("http://example.org/a/A1")));
        List<RppIsvs> out = client.fetchAllIsvs();
        assertEquals(1, out.size());
        assertEquals(List.of("http://example.org/a/A1", "http://example.org/a/A2"), out.get(0).getAgendaIris());
    }

    @Test
    void malformedRowIsSkipped() {
        stubSparql(SparqlJson.select("agenda", "code", "nazev")
                .row(uri("agenda", "http://example.org/a/A1"), literal("nazev", "Missing code"))
                .row(uri("agenda", "http://example.org/a/A2"), literal("code", "2"), literal("nazev", "Valid")));
        List<RppAgenda> out = client.fetchAllAgendas();
        assertEquals(1, out.size());
        assertEquals("2", out.get(0).getCode());
    }

    @Test
    void upstream500ThrowsRppUnavailable() {
        WM.stubFor(any(anyUrl()).willReturn(serverError()));
        assertThrows(SparqlEndpointUnavailableException.class, () -> client.fetchAllAgendas());
    }

    @Test
    void upstreamTimeoutThrowsRppUnavailable() {
        ReflectionTestUtils.setField(client, "rppSparqlTimeout", 250);
        WM.stubFor(any(anyUrl()).willReturn(sparqlJson(emptyResults()).withFixedDelay(1_500)));
        SparqlEndpointUnavailableException ex = assertThrows(SparqlEndpointUnavailableException.class, () -> client.fetchAllAgendas());
        assertNotNull(ex.getMessage());
    }

    @Test
    void unconfiguredEndpointThrowsRppUnavailable() {
        ReflectionTestUtils.setField(client, "rppEndpoint", "");
        SparqlEndpointUnavailableException ex = assertThrows(SparqlEndpointUnavailableException.class, () -> client.fetchAllAgendas());
        assertTrue(ex.getMessage().contains("not configured"));
    }

    private static SparqlJson.Binding[] isvsAlfa(String agendaIri) {
        return new SparqlJson.Binding[] {
                uri("isvs", "http://example.org/i/1"), literal("code", "100"), literal("nazev", "Alfa"),
                uri("agenda", agendaIri)};
    }

    private static void stubSparql(String body) {
        WM.stubFor(any(anyUrl()).willReturn(sparqlJson(body)));
    }

    private static void stubSparql(SparqlJson result) {
        stubSparql(result.toJson());
    }

    private static String emptyResults() {
        return SparqlJson.select("agenda", "code", "nazev").toJson();
    }
}
