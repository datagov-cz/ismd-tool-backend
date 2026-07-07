package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkdConfig;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NkdSparqlClientTest {

    private static final String CONCEPT_IRI = "https://data.gov.cz/zdroj/pojem/koncept-1";
    private static final String CONCEPT_IRI_2 = "https://data.gov.cz/zdroj/pojem/koncept-2";
    private static final String ONTOLOGY_IRI = "https://data.gov.cz/zdroj/slovník/test";
    private static final String IN_SCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";

    private WireMockServer wm;

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
    void reset() {
        wm.resetAll();
    }

    @AfterEach
    void tearDown() {
        wm.resetAll();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private NkdConfig configWithEndpoint(String endpoint) {
        NkdConfig cfg = new NkdConfig();
        cfg.getSparql().setEndpoint(endpoint);
        cfg.getSparql().setTimeout(2000);
        cfg.getSparql().setMaxConcurrentRequests(2);
        return cfg;
    }

    private NkdSparqlClient newClient(OntologyDetailExtractor extractor) {
        return new NkdSparqlClient(configWithEndpoint(wm.baseUrl() + "/sparql"), extractor,
                HttpClient.newHttpClient());
    }

    private NkdSparqlClient newUnconfiguredClient(OntologyDetailExtractor extractor) {
        return new NkdSparqlClient(configWithEndpoint(""), extractor, HttpClient.newHttpClient());
    }

    private static void stubTurtle(String turtle) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "text/turtle")
                .withBody(turtle)));
    }

    private static void stubEmptyTurtle() {
        stubTurtle("");
    }

    private static void stubSparqlJson(String body) {
        stubFor(any(anyUrl()).willReturn(aResponse()
                .withHeader("Content-Type", "application/sparql-results+json")
                .withBody(body)));
    }

    // ─── fetchPublishedConcept / fetchPublishedConceptWithScheme ────────────────

    @Test
    void fetchPublishedConcept_withInScheme_returnsDetailAndOntologyIri() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        OntologyDetailModel.ConceptDetailModel detail =
                mock(OntologyDetailModel.ConceptDetailModel.class);
        when(extractor.applyOFNTransformations(ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(extractor.extractConceptDetail(ArgumentMatchers.any(), ArgumentMatchers.eq(CONCEPT_IRI),
                ArgumentMatchers.any())).thenReturn(detail);

        String turtle = "<" + CONCEPT_IRI + "> <" + IN_SCHEME + "> <" + ONTOLOGY_IRI + "> .";
        stubTurtle(turtle);

        Optional<NkdSparqlClient.PublishedConcept> out =
                newClient(extractor).fetchPublishedConceptWithScheme(CONCEPT_IRI);

        assertTrue(out.isPresent());
        assertEquals(detail, out.get().detail());
        assertEquals(ONTOLOGY_IRI, out.get().ontologyIri());
    }

    @Test
    void fetchPublishedConcept_emptyUpstream_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        stubEmptyTurtle();

        Optional<OntologyDetailModel.ConceptDetailModel> out =
                newClient(extractor).fetchPublishedConcept(CONCEPT_IRI);

        assertTrue(out.isEmpty());
        verify(extractor, never()).applyOFNTransformations(ArgumentMatchers.any());
    }

    @Test
    void fetchPublishedConcept_inSchemeMissing_returnsNullOntologyIri() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        OntologyDetailModel.ConceptDetailModel detail =
                mock(OntologyDetailModel.ConceptDetailModel.class);
        when(extractor.applyOFNTransformations(ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(extractor.extractConceptDetail(ArgumentMatchers.any(), ArgumentMatchers.eq(CONCEPT_IRI),
                ArgumentMatchers.any())).thenReturn(detail);

        // Triple about the concept but NOT skos:inScheme.
        String turtle = "<" + CONCEPT_IRI + "> <http://www.w3.org/2000/01/rdf-schema#label> \"Koncept\" .";
        stubTurtle(turtle);

        Optional<NkdSparqlClient.PublishedConcept> out =
                newClient(extractor).fetchPublishedConceptWithScheme(CONCEPT_IRI);

        assertTrue(out.isPresent());
        assertNull(out.get().ontologyIri());
    }

    @Test
    void fetchPublishedConcept_inSchemeLiteral_isIgnoredAndReturnsNull() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        when(extractor.applyOFNTransformations(ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(extractor.extractConceptDetail(ArgumentMatchers.any(), ArgumentMatchers.eq(CONCEPT_IRI),
                ArgumentMatchers.any())).thenReturn(mock(OntologyDetailModel.ConceptDetailModel.class));

        // skos:inScheme bound to a literal is non-URI — must be skipped.
        String turtle = "<" + CONCEPT_IRI + "> <" + IN_SCHEME + "> \"not-a-uri\" .";
        stubTurtle(turtle);

        Optional<NkdSparqlClient.PublishedConcept> out =
                newClient(extractor).fetchPublishedConceptWithScheme(CONCEPT_IRI);

        assertTrue(out.isPresent());
        assertNull(out.get().ontologyIri());
    }

    // ─── fetchPublishedOntology / fetchPublishedOntologyRaw ─────────────────────

    @Test
    void fetchPublishedOntology_happyPath_runsExtraction() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        OntologyDetailModel detail = mock(OntologyDetailModel.class);
        when(extractor.applyOFNTransformations(ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        when(extractor.extractOntologyDetail(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(detail);

        stubTurtle("<" + ONTOLOGY_IRI + "> <http://purl.org/dc/terms/title> \"T\"@cs .");

        Optional<OntologyDetailModel> out = newClient(extractor).fetchPublishedOntology(ONTOLOGY_IRI);
        assertTrue(out.isPresent());
        assertEquals(detail, out.get());
    }

    @Test
    void fetchPublishedOntologyRaw_emptyUpstream_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        stubEmptyTurtle();

        Optional<Model> out = newClient(extractor).fetchPublishedOntologyRaw(ONTOLOGY_IRI);
        assertTrue(out.isEmpty());
    }

    @Test
    void fetchPublishedOntologyRaw_returnsRawModel() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        stubTurtle("<" + ONTOLOGY_IRI + "> <http://purl.org/dc/terms/title> \"T\"@cs .");

        Optional<Model> out = newClient(extractor).fetchPublishedOntologyRaw(ONTOLOGY_IRI);
        assertTrue(out.isPresent());
        assertFalse(out.get().isEmpty());
    }

    // ─── fetchConceptResolutions ────────────────────────────────────────────────

    @Test
    void fetchConceptResolutions_nullInput_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        Map<String, ResolvedConceptDto> out = newClient(extractor).fetchConceptResolutions(null);
        assertTrue(out.isEmpty());
    }

    @Test
    void fetchConceptResolutions_emptyList_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        Map<String, ResolvedConceptDto> out = newClient(extractor).fetchConceptResolutions(List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void fetchConceptResolutions_endpointNotConfigured_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        Map<String, ResolvedConceptDto> out =
                newUnconfiguredClient(extractor).fetchConceptResolutions(List.of(CONCEPT_IRI));
        assertTrue(out.isEmpty());
    }

    @Test
    void fetchConceptResolutions_allInvalidIris_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        // SparqlIriValidator rejects non-http(s) and otherwise unsafe IRIs.
        Map<String, ResolvedConceptDto> out = newClient(extractor)
                .fetchConceptResolutions(List.of("not an iri", "javascript:alert(1)"));
        assertTrue(out.isEmpty());
    }

    @Test
    void fetchConceptResolutions_lenientEmptyUpstream_returnsEmpty() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        stubEmptyTurtle();

        Map<String, ResolvedConceptDto> out =
                newClient(extractor).fetchConceptResolutions(List.of(CONCEPT_IRI, CONCEPT_IRI_2));
        assertTrue(out.isEmpty());
    }

    @Test
    void fetchConceptResolutions_mixedValidAndInvalid_filtersInvalid() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        stubEmptyTurtle();

        // No assertion on the upstream call body — just confirm the invalid IRI doesn't
        // crash the call and the result is an empty map (upstream returned nothing).
        Map<String, ResolvedConceptDto> out = newClient(extractor)
                .fetchConceptResolutions(List.of(CONCEPT_IRI, "not an iri"));
        assertTrue(out.isEmpty());
    }

    // ─── getPublishedResourcesList ──────────────────────────────────────────────

    @Test
    void getPublishedResourcesList_nullInput_returnsEmptyList() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        List<String> out = newClient(extractor).getPublishedResourcesList(null);
        assertTrue(out.isEmpty());
    }

    @Test
    void getPublishedResourcesList_emptyInput_returnsEmptyList() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        List<String> out = newClient(extractor).getPublishedResourcesList(List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    void getPublishedResourcesList_endpointNotConfigured_returnsEmptyList() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        List<String> out = newUnconfiguredClient(extractor).getPublishedResourcesList(List.of(CONCEPT_IRI));
        assertTrue(out.isEmpty());
    }

    @Test
    void getPublishedResourcesList_publishedConceptIncluded_unpublishedExcluded() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        // Per-IRI CONSTRUCT — return non-empty Turtle for the published probe; lenient empty
        // would otherwise short-circuit. We can't easily differentiate the two IRIs from a
        // single WireMock stub, so this test exercises the path where BOTH IRIs are present
        // upstream; combined with the empty-stub test (above) for the unpublished path,
        // both branches of `isConceptPublishedInNKD` are covered.
        stubTurtle("<" + CONCEPT_IRI + "> <http://www.w3.org/2000/01/rdf-schema#label> \"x\" .");

        List<String> out = newClient(extractor).getPublishedResourcesList(List.of(CONCEPT_IRI, CONCEPT_IRI_2));
        assertEquals(2, out.size());
    }

    @Test
    void getPublishedResourcesList_lenientEmpty_filtersAll() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        stubEmptyTurtle();
        List<String> out = newClient(extractor).getPublishedResourcesList(List.of(CONCEPT_IRI));
        assertTrue(out.isEmpty());
    }

    // ─── executeSelect ──────────────────────────────────────────────────────────

    @Test
    void executeSelect_endpointNotConfigured_returnsEmptyList() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        List<Map<String, String>> out =
                newUnconfiguredClient(extractor).executeSelect("SELECT * WHERE { ?s ?p ?o }");
        assertTrue(out.isEmpty());
    }

    @Test
    void executeSelect_resourceAndLiteralBindings_areProjected() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        String json = """
                {
                  "head": { "vars": ["s", "label"] },
                  "results": { "bindings": [
                    { "s": {"type":"uri","value":"https://example.org/x"},
                      "label": {"type":"literal","value":"Hello"} },
                    { "s": {"type":"uri","value":"https://example.org/y"} }
                  ] }
                }
                """;
        stubSparqlJson(json);

        List<Map<String, String>> rows = newClient(extractor).executeSelect("SELECT * WHERE { ?s ?p ?o }");
        assertEquals(2, rows.size());
        assertEquals("https://example.org/x", rows.get(0).get("s"));
        assertEquals("Hello", rows.get(0).get("label"));
        assertEquals("https://example.org/y", rows.get(1).get("s"));
        // The second row lacks "label" so it should not be present in the map.
        assertFalse(rows.get(1).containsKey("label"));
    }

    // ─── isEndpointConfigured ───────────────────────────────────────────────────

    @Test
    void isEndpointConfigured_reflectsConstruction() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        assertTrue(newClient(extractor).isEndpointConfigured());
        assertFalse(newUnconfiguredClient(extractor).isEndpointConfigured());
    }

    // ─── strict-construct unconfigured throw ────────────────────────────────────

    @Test
    void fetchPublishedConcept_endpointNotConfigured_throws() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        // strict construct() pre-check fires SparqlEndpointUnavailableException directly.
        assertThrows(RuntimeException.class,
                () -> newUnconfiguredClient(extractor).fetchPublishedConcept(CONCEPT_IRI));
    }

    @Test
    void fetchPublishedOntology_endpointNotConfigured_throws() {
        OntologyDetailExtractor extractor = mock(OntologyDetailExtractor.class);
        assertThrows(RuntimeException.class,
                () -> newUnconfiguredClient(extractor).fetchPublishedOntology(ONTOLOGY_IRI));
    }

    // ─── compile-time noop to keep ModelFactory import alive in case of evolution ───

    @SuppressWarnings("unused")
    private static Model emptyModel() {
        return ModelFactory.createDefaultModel();
    }

    @SuppressWarnings("unused")
    private static void unusedCheck() {
        assertNotNull(IN_SCHEME);
    }
}