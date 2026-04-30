package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.exception.RppUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RppSparqlClientTest {

    private WireMockServer wm;
    private RppSparqlClient client;

    @BeforeAll
    void startServer() {
        wm = new WireMockServer(wireMockConfig().dynamicPort());
        wm.start();
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("localhost", wm.port());
    }

    @AfterAll
    void stopServer() {
        wm.stop();
    }

    @BeforeEach
    void setUp() {
        wm.resetAll();
        client = new RppSparqlClient();
        ReflectionTestUtils.setField(client, "rppEndpoint", wm.baseUrl() + "/sparql");
        ReflectionTestUtils.setField(client, "rppSparqlTimeout", 2000);
    }

    @AfterEach
    void tearDown() {
        wm.resetAll();
    }

    @Test
    void emptyResultSetReturnsEmptyList() {
        stubSparql(emptyResults());
        List<RppAgenda> out = client.fetchAllAgendas();
        assertEquals(0, out.size());
    }

    @Test
    void isvsCollapseZeroAgendas() {
        String json = """
                {
                  "head": { "vars": ["isvs", "code", "nazev", "agenda"] },
                  "results": { "bindings": [
                    { "isvs": {"type":"uri","value":"http://example.org/i/1"},
                      "code":  {"type":"literal","value":"100"},
                      "nazev":{"type":"literal","value":"Alfa"} }
                  ] }
                }
                """;
        stubSparql(json);
        List<RppIsvs> out = client.fetchAllIsvs();
        assertEquals(1, out.size());
        assertEquals("http://example.org/i/1", out.get(0).getIri());
        assertEquals(0, out.get(0).getAgendaIris().size());
    }

    @Test
    void isvsCollapseOneAgenda() {
        String json = """
                {
                  "head": { "vars": ["isvs", "code", "nazev", "agenda"] },
                  "results": { "bindings": [
                    { "isvs":  {"type":"uri","value":"http://example.org/i/1"},
                      "code":   {"type":"literal","value":"100"},
                      "nazev": {"type":"literal","value":"Alfa"},
                      "agenda":{"type":"uri","value":"http://example.org/a/A1"} }
                  ] }
                }
                """;
        stubSparql(json);
        List<RppIsvs> out = client.fetchAllIsvs();
        assertEquals(1, out.size());
        assertEquals(List.of("http://example.org/a/A1"), out.get(0).getAgendaIris());
    }

    @Test
    void isvsCollapseMultipleAgendas() {
        String json = """
                {
                  "head": { "vars": ["isvs", "code", "nazev", "agenda"] },
                  "results": { "bindings": [
                    { "isvs":  {"type":"uri","value":"http://example.org/i/1"},
                      "code":   {"type":"literal","value":"100"},
                      "nazev": {"type":"literal","value":"Alfa"},
                      "agenda":{"type":"uri","value":"http://example.org/a/A1"} },
                    { "isvs":  {"type":"uri","value":"http://example.org/i/1"},
                      "code":   {"type":"literal","value":"100"},
                      "nazev": {"type":"literal","value":"Alfa"},
                      "agenda":{"type":"uri","value":"http://example.org/a/A2"} },
                    { "isvs":  {"type":"uri","value":"http://example.org/i/1"},
                      "code":   {"type":"literal","value":"100"},
                      "nazev": {"type":"literal","value":"Alfa"},
                      "agenda":{"type":"uri","value":"http://example.org/a/A1"} }
                  ] }
                }
                """;
        stubSparql(json);
        List<RppIsvs> out = client.fetchAllIsvs();
        assertEquals(1, out.size());
        assertEquals(List.of("http://example.org/a/A1", "http://example.org/a/A2"), out.get(0).getAgendaIris());
    }

    @Test
    void malformedRowIsSkipped() {
        String json = """
                {
                  "head": { "vars": ["agenda", "code", "nazev"] },
                  "results": { "bindings": [
                    { "agenda": {"type":"uri","value":"http://example.org/a/A1"},
                      "nazev":  {"type":"literal","value":"Missing code"} },
                    { "agenda": {"type":"uri","value":"http://example.org/a/A2"},
                      "code":    {"type":"literal","value":"2"},
                      "nazev":  {"type":"literal","value":"Valid"} }
                  ] }
                }
                """;
        stubSparql(json);
        List<RppAgenda> out = client.fetchAllAgendas();
        assertEquals(1, out.size());
        assertEquals("2", out.get(0).getCode());
    }

    @Test
    void upstream500ThrowsRppUnavailable() {
        stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
        assertThrows(RppUnavailableException.class, () -> client.fetchAllAgendas());
    }

    @Test
    void upstreamTimeoutThrowsRppUnavailable() {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/sparql-results+json")
                .withFixedDelay(5000)
                .withBody(emptyResults())));
        RppUnavailableException ex = assertThrows(RppUnavailableException.class, () -> client.fetchAllAgendas());
        assertNotNull(ex.getMessage());
    }

    @Test
    void unconfiguredEndpointThrowsRppUnavailable() {
        ReflectionTestUtils.setField(client, "rppEndpoint", "");
        RppUnavailableException ex = assertThrows(RppUnavailableException.class, () -> client.fetchAllAgendas());
        assertTrue(ex.getMessage().contains("not configured"));
    }

    private static void stubSparql(String body) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/sparql-results+json")
                .withBody(body)));
    }

    private static String emptyResults() {
        return """
                {
                  "head": { "vars": ["agenda", "code", "nazev"] },
                  "results": { "bindings": [] }
                }
                """;
    }
}
