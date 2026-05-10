package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EsbirkaSparqlClientTest {

    private static final String LAW_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187";
    private static final String VERSION_IRI =
            "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01";

    private WireMockServer wm;
    private EsbirkaSparqlClient client;

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
        client = new EsbirkaSparqlClient();
        ReflectionTestUtils.setField(client, "endpoint", wm.baseUrl() + "/sparql");
        ReflectionTestUtils.setField(client, "sparqlTimeout", 2000);
    }

    @AfterEach
    void tearDown() {
        wm.resetAll();
    }

    // --- searchLaws ---------------------------------------------------------

    @Test
    void searchLaws_emptyResultSet() {
        stubSparql(emptyLawResults());
        List<LawModel> out = client.searchLaws(null, 20);
        assertEquals(0, out.size());
    }

    @Test
    void searchLaws_mapsAllFields() {
        String json = """
                {
                  "head": { "vars": ["akt", "citace", "cislo", "rok", "sbirka"] },
                  "results": { "bindings": [
                    { "akt":     {"type":"uri","value":"%s"},
                      "citace":  {"type":"literal","value":"187/2006 Sb."},
                      "cislo":   {"type":"literal","value":"187"},
                      "rok":     {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#gYear","value":"2006"},
                      "sbirka":  {"type":"literal","value":"sb"} }
                  ] }
                }
                """.formatted(LAW_IRI);
        stubSparql(json);
        List<LawModel> out = client.searchLaws("187", 20);
        assertEquals(1, out.size());
        LawModel law = out.get(0);
        assertEquals(LAW_IRI, law.getIri());
        assertEquals("187/2006 Sb.", law.getCitace());
        assertEquals("187", law.getCislo());
        assertEquals(Integer.valueOf(2006), law.getRok());
        assertEquals("sb", law.getSbirka());
    }

    @Test
    void searchLaws_skipsRowMissingCitace() {
        String json = """
                {
                  "head": { "vars": ["akt", "citace", "cislo", "rok", "sbirka"] },
                  "results": { "bindings": [
                    { "akt":     {"type":"uri","value":"%1$s"},
                      "cislo":   {"type":"literal","value":"187"},
                      "rok":     {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#gYear","value":"2006"},
                      "sbirka":  {"type":"literal","value":"sb"} },
                    { "akt":     {"type":"uri","value":"%1$s/2025"},
                      "citace":  {"type":"literal","value":"99/2025 Sb."},
                      "cislo":   {"type":"literal","value":"99"},
                      "rok":     {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#gYear","value":"2025"},
                      "sbirka":  {"type":"literal","value":"sb"} }
                  ] }
                }
                """.formatted(LAW_IRI);
        stubSparql(json);
        List<LawModel> out = client.searchLaws(null, 20);
        assertEquals(1, out.size());
        assertEquals("99/2025 Sb.", out.get(0).getCitace());
    }

    @Test
    void searchLaws_unparseableYearBecomesNull() {
        String json = """
                {
                  "head": { "vars": ["akt", "citace", "cislo", "rok", "sbirka"] },
                  "results": { "bindings": [
                    { "akt":     {"type":"uri","value":"%s"},
                      "citace":  {"type":"literal","value":"99/9999 Sb."},
                      "cislo":   {"type":"literal","value":"99"},
                      "rok":     {"type":"literal","value":"not-a-number"},
                      "sbirka":  {"type":"literal","value":"sb"} }
                  ] }
                }
                """.formatted(LAW_IRI);
        stubSparql(json);
        List<LawModel> out = client.searchLaws(null, 20);
        assertEquals(1, out.size());
        assertNull(out.get(0).getRok());
    }

    // --- fetchVersions ------------------------------------------------------

    @Test
    void fetchVersions_mapsAllOptionalFieldsAndLatestFlag() {
        String json = """
                {
                  "head": { "vars": ["zneni", "ucinnostOd", "ucinnostDo", "typ", "isLatest"] },
                  "results": { "bindings": [
                    { "zneni":      {"type":"uri","value":"%1$s"},
                      "ucinnostOd": {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#date","value":"2026-04-01"},
                      "ucinnostDo": {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#date","value":"2026-06-30"},
                      "typ":        {"type":"uri","value":"https://example.org/typ/KONSOL"},
                      "isLatest":   {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#boolean","value":"true"} },
                    { "zneni":      {"type":"uri","value":"%1$s-old"},
                      "ucinnostOd": {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#date","value":"2025-01-01"},
                      "isLatest":   {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#boolean","value":"false"} }
                  ] }
                }
                """.formatted(VERSION_IRI);
        stubSparql(json);
        List<LawVersionModel> out = client.fetchVersions(LAW_IRI);
        assertEquals(2, out.size());

        LawVersionModel latest = out.get(0);
        assertEquals(VERSION_IRI, latest.getIri());
        assertEquals(LocalDate.of(2026, 4, 1), latest.getUcinnostOd());
        assertEquals(LocalDate.of(2026, 6, 30), latest.getUcinnostDo());
        assertEquals("https://example.org/typ/KONSOL", latest.getVersionType());
        assertTrue(latest.isLatest());

        LawVersionModel old = out.get(1);
        assertEquals(LocalDate.of(2025, 1, 1), old.getUcinnostOd());
        assertNull(old.getUcinnostDo());
        assertNull(old.getVersionType());
        assertFalse(old.isLatest());
    }

    @Test
    void fetchVersions_unparseableDateBecomesNull() {
        String json = """
                {
                  "head": { "vars": ["zneni", "ucinnostOd", "isLatest"] },
                  "results": { "bindings": [
                    { "zneni":      {"type":"uri","value":"%s"},
                      "ucinnostOd": {"type":"literal","value":"not-a-date"},
                      "isLatest":   {"type":"typed-literal","datatype":"http://www.w3.org/2001/XMLSchema#boolean","value":"true"} }
                  ] }
                }
                """.formatted(VERSION_IRI);
        stubSparql(json);
        List<LawVersionModel> out = client.fetchVersions(LAW_IRI);
        assertEquals(1, out.size());
        assertNull(out.get(0).getUcinnostOd());
    }

    // --- fetchFragments + parseKindFromIri ----------------------------------

    @Test
    void fetchFragments_parsesAllKnownKindsFromIri() {
        String basePath = VERSION_IRI + "/dokument/norma";
        String json = ("""
                {
                  "head": { "vars": ["fragment", "parent", "citace", "order"] },
                  "results": { "bindings": [
                    """ +
                fragmentRow(basePath + "/cast_5",                      basePath,                "Část 5",          "6AC0") + ",\n" +
                fragmentRow(basePath + "/cast_5/hlava_4",              basePath + "/cast_5",    "Hlava 4",         "6ADA") + ",\n" +
                fragmentRow(basePath + "/cast_5/hlava_4/dil_3",        basePath + "/cast_5/hlava_4", "Díl 3",      "6ADAD0") + ",\n" +
                fragmentRow(basePath + "/cast_5/oddil_2",              basePath + "/cast_5",    "Oddíl 2",         "6ADB") + ",\n" +
                fragmentRow(basePath + "/par_122",                     basePath,                "§ 122",           "6ADE") + ",\n" +
                fragmentRow(basePath + "/par_122/odst_4",              basePath + "/par_122",   "§ 122 odst. 4",   "6ADED5") + ",\n" +
                fragmentRow(basePath + "/par_122/odst_4/pism_g",       basePath + "/par_122/odst_4", "§ 122 odst. 4 písm. g)", "6ADED580") + ",\n" +
                fragmentRow(basePath + "/par_122/odst_4/pism_g/bod_1", basePath + "/par_122/odst_4/pism_g", "bod 1", "6ADED5C0") + ",\n" +
                fragmentRow(basePath + "/ppc_1",                       basePath,                "PPC 1",           "6AEE") + ",\n" +
                fragmentRow(basePath + "/frag_1",                      basePath,                "Frag 1",          "6AEF") +
                """
                  ] }
                }
                """);
        stubSparql(json);
        List<FragmentModel> out = client.fetchFragments(VERSION_IRI);
        assertEquals(10, out.size());

        // Order preserved (server-side ORDER BY ?order); kind parsed from last segment.
        assertEquals("cast", out.get(0).getKind());
        assertEquals("hlava", out.get(1).getKind());
        assertEquals("dil", out.get(2).getKind());
        assertEquals("oddil", out.get(3).getKind());
        assertEquals("par", out.get(4).getKind());
        assertEquals("odst", out.get(5).getKind());
        assertEquals("pism", out.get(6).getKind());
        assertEquals("bod", out.get(7).getKind());
        assertEquals("ppc", out.get(8).getKind());
        assertEquals("frag", out.get(9).getKind());

        // Citation preserved verbatim
        assertEquals("§ 122 odst. 4 písm. g)", out.get(6).getCitation());
        // Order key preserved (string, lex-sortable)
        assertEquals("6ADED580", out.get(6).getOrder());
        // Parent edge preserved
        assertEquals(basePath + "/par_122/odst_4", out.get(6).getParentIri());
    }

    @Test
    void fetchFragments_skipsRowMissingParent() {
        String json = """
                {
                  "head": { "vars": ["fragment", "parent", "citace", "order"] },
                  "results": { "bindings": [
                    { "fragment": {"type":"uri","value":"%1$s/dokument/norma/par_1"},
                      "citace":   {"type":"literal","value":"§ 1"},
                      "order":    {"type":"literal","value":"6AC0"} },
                    { "fragment": {"type":"uri","value":"%1$s/dokument/norma/par_2"},
                      "parent":   {"type":"uri","value":"%1$s/dokument/norma"},
                      "citace":   {"type":"literal","value":"§ 2"},
                      "order":    {"type":"literal","value":"6AC1"} }
                  ] }
                }
                """.formatted(VERSION_IRI);
        stubSparql(json);
        List<FragmentModel> out = client.fetchFragments(VERSION_IRI);
        assertEquals(1, out.size());
        assertEquals("§ 2", out.get(0).getCitation());
    }

    // --- G12: failure modes -------------------------------------------------

    @Test
    void upstream500ThrowsEsbirkaUnavailable() {
        stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
        assertThrows(SparqlEndpointUnavailableException.class, () -> client.searchLaws(null, 20));
    }

    @Test
    void upstreamTimeoutThrowsEsbirkaUnavailable() {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/sparql-results+json")
                .withFixedDelay(5000)
                .withBody(emptyLawResults())));
        SparqlEndpointUnavailableException ex = assertThrows(SparqlEndpointUnavailableException.class,
                () -> client.searchLaws(null, 20));
        assertNotNull(ex.getMessage());
    }

    @Test
    void malformedBodyThrowsEsbirkaUnavailable() {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/sparql-results+json")
                .withBody("{ this is not valid sparql json")));
        assertThrows(SparqlEndpointUnavailableException.class, () -> client.searchLaws(null, 20));
    }

    @Test
    void connectionRefusedThrowsEsbirkaUnavailable() {
        // Point at a port that's not listening — a stopped wiremock instance equivalent.
        ReflectionTestUtils.setField(client, "endpoint", "http://127.0.0.1:1/sparql");
        assertThrows(SparqlEndpointUnavailableException.class, () -> client.searchLaws(null, 20));
    }

    @Test
    void unconfiguredEndpointThrowsEsbirkaUnavailable() {
        ReflectionTestUtils.setField(client, "endpoint", "");
        SparqlEndpointUnavailableException ex = assertThrows(SparqlEndpointUnavailableException.class,
                () -> client.searchLaws(null, 20));
        assertTrue(ex.getMessage().contains("not configured"));
    }

    // --- helpers ------------------------------------------------------------

    private static String fragmentRow(String fragmentIri, String parentIri, String citace, String order) {
        return """
                { "fragment": {"type":"uri","value":"%s"},
                  "parent":   {"type":"uri","value":"%s"},
                  "citace":   {"type":"literal","value":"%s"},
                  "order":    {"type":"literal","value":"%s"} }""".formatted(fragmentIri, parentIri, citace, order);
    }

    private static void stubSparql(String body) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/sparql-results+json")
                .withBody(body)));
    }

    private static String emptyLawResults() {
        return """
                {
                  "head": { "vars": ["akt", "citace", "cislo", "rok", "sbirka"] },
                  "results": { "bindings": [] }
                }
                """;
    }
}
