package com.dia.ismdtoolbackend.repository;

import com.dia.ismdtoolbackend.enums.RelationType;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JenaTDB2RepositorySearchTest {

    private TestableJenaTDB2Repository repository;
    private RDFConnection mockConnection;
    private QueryExecution mockQueryExecution;

    /**
     * Subclass that overrides createConnection to return our mock.
     * This avoids needing to change the visibility of createConnection in production code.
     */
    static class TestableJenaTDB2Repository extends JenaTDB2Repository {
        private final RDFConnection mockConn;

        TestableJenaTDB2Repository(HttpClient httpClient, Semaphore semaphore, int timeout, RDFConnection mockConn) {
            super(httpClient, semaphore, timeout);
            this.mockConn = mockConn;
        }

        @Override
        RDFConnection createConnection() {
            return mockConn;
        }
    }

    @BeforeEach
    void setUp() {
        HttpClient httpClient = HttpClient.newHttpClient();
        Semaphore semaphore = new Semaphore(4);
        mockConnection = mock(RDFConnection.class);
        mockQueryExecution = mock(QueryExecution.class);
        repository = new TestableJenaTDB2Repository(httpClient, semaphore, 5000, mockConnection);
    }

    // --- searchByText ---

    @Test
    void searchByText_emptyGraphNames_returnsEmptyList() {
        List<Map<String, String>> results = repository.searchByText("test", List.of(), 100);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchByText_nullGraphNames_returnsEmptyList() {
        List<Map<String, String>> results = repository.searchByText("test", null, 100);
        assertTrue(results.isEmpty());
    }

    @Test
    void searchByText_returnsMatchedConcepts() {
        ResultSet mockResultSet = mock(ResultSet.class);
        QuerySolution mockSolution = mock(QuerySolution.class);

        Resource conceptResource = mock(Resource.class);
        when(conceptResource.getURI()).thenReturn("https://example.org/concept/osoba");

        Resource graphResource = mock(Resource.class);
        when(graphResource.getURI()).thenReturn("https://example.org/ontology/1");

        Literal prefLabel = mock(Literal.class);
        when(prefLabel.getString()).thenReturn("Osoba");

        Literal prefLabelLang = mock(Literal.class);
        when(prefLabelLang.getString()).thenReturn("cs");

        when(mockSolution.getResource("concept")).thenReturn(conceptResource);
        when(mockSolution.getResource("g")).thenReturn(graphResource);
        when(mockSolution.getLiteral("prefLabel")).thenReturn(prefLabel);
        when(mockSolution.getLiteral("prefLabelLang")).thenReturn(prefLabelLang);
        when(mockSolution.getLiteral("altLabel")).thenReturn(null);
        when(mockSolution.getLiteral("description")).thenReturn(null);
        when(mockSolution.getLiteral("definition")).thenReturn(null);

        when(mockResultSet.hasNext()).thenReturn(true, false);
        when(mockResultSet.next()).thenReturn(mockSolution);

        when(mockConnection.query(anyString())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execSelect()).thenReturn(mockResultSet);

        List<Map<String, String>> results = repository.searchByText("osoba",
                List.of("https://example.org/ontology/1"), 100);

        assertEquals(1, results.size());
        assertEquals("https://example.org/concept/osoba", results.get(0).get("conceptIri"));
        assertEquals("https://example.org/ontology/1", results.get(0).get("graphName"));
        assertEquals("Osoba", results.get(0).get("prefLabel"));
        assertEquals("cs", results.get(0).get("prefLabelLang"));
    }

    @Test
    void searchByText_queryContainsSingleQuote_isEscaped() {
        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.hasNext()).thenReturn(false);

        ArgumentCaptor<String> sparqlCaptor = ArgumentCaptor.forClass(String.class);
        when(mockConnection.query(sparqlCaptor.capture())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execSelect()).thenReturn(mockResultSet);

        repository.searchByText("test'injection", List.of("https://example.org/ontology/1"), 100);

        String executedSparql = sparqlCaptor.getValue();
        assertTrue(executedSparql.contains("test\\'injection"),
                "Single quotes should be escaped in SPARQL query");
    }

    // --- fetchConceptLabels ---

    @Test
    void fetchConceptLabels_emptyIris_returnsEmptyModel() {
        Model result = repository.fetchConceptLabels(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void fetchConceptLabels_nullIris_returnsEmptyModel() {
        Model result = repository.fetchConceptLabels(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void fetchConceptLabels_returnsConstructedModel() {
        Model expectedModel = ModelFactory.createDefaultModel();
        Resource concept = expectedModel.createResource("https://example.org/concept/osoba");
        concept.addProperty(
                expectedModel.createProperty("http://www.w3.org/2004/02/skos/core#prefLabel"),
                expectedModel.createLiteral("Osoba", "cs"));

        when(mockConnection.query(anyString())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execConstruct()).thenReturn(expectedModel);

        Model result = repository.fetchConceptLabels(
                List.of("https://example.org/concept/osoba"));

        assertFalse(result.isEmpty());
        assertEquals(1, result.size());
    }

    @Test
    void fetchConceptLabels_queryContainsAllIris() {
        Model emptyModel = ModelFactory.createDefaultModel();

        ArgumentCaptor<String> sparqlCaptor = ArgumentCaptor.forClass(String.class);
        when(mockConnection.query(sparqlCaptor.capture())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execConstruct()).thenReturn(emptyModel);

        repository.fetchConceptLabels(List.of(
                "https://example.org/concept/1",
                "https://example.org/concept/2"));

        String executedSparql = sparqlCaptor.getValue();
        assertTrue(executedSparql.contains("<https://example.org/concept/1>"));
        assertTrue(executedSparql.contains("<https://example.org/concept/2>"));
    }

    // --- filterByRelationTypes ---

    @Test
    void filterByRelationTypes_emptyIris_returnsEmptySet() {
        Set<String> result = repository.filterByRelationTypes(List.of(), List.of(RelationType.SUBCLASS));
        assertTrue(result.isEmpty());
    }

    @Test
    void filterByRelationTypes_emptyRelationTypes_returnsEmptySet() {
        Set<String> result = repository.filterByRelationTypes(
                List.of("https://example.org/concept/1"), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void filterByRelationTypes_subclass_generatesSubClassOfPattern() {
        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.hasNext()).thenReturn(false);

        ArgumentCaptor<String> sparqlCaptor = ArgumentCaptor.forClass(String.class);
        when(mockConnection.query(sparqlCaptor.capture())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execSelect()).thenReturn(mockResultSet);

        repository.filterByRelationTypes(
                List.of("https://example.org/concept/1"),
                List.of(RelationType.SUBCLASS));

        assertTrue(sparqlCaptor.getValue().contains("rdfs:subClassOf"));
    }

    @Test
    void filterByRelationTypes_multipleTypes_generatesUnionPattern() {
        ResultSet mockResultSet = mock(ResultSet.class);
        when(mockResultSet.hasNext()).thenReturn(false);

        ArgumentCaptor<String> sparqlCaptor = ArgumentCaptor.forClass(String.class);
        when(mockConnection.query(sparqlCaptor.capture())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execSelect()).thenReturn(mockResultSet);

        repository.filterByRelationTypes(
                List.of("https://example.org/concept/1"),
                List.of(RelationType.SUBCLASS, RelationType.EXACT_MATCH));

        String sparql = sparqlCaptor.getValue();
        assertTrue(sparql.contains("UNION"));
        assertTrue(sparql.contains("rdfs:subClassOf"));
        assertTrue(sparql.contains("skos:exactMatch"));
    }

    @Test
    void filterByRelationTypes_returnsMatchingIris() {
        ResultSet mockResultSet = mock(ResultSet.class);
        QuerySolution mockSolution = mock(QuerySolution.class);

        Resource conceptResource = mock(Resource.class);
        when(conceptResource.isURIResource()).thenReturn(true);
        when(conceptResource.getURI()).thenReturn("https://example.org/concept/1");

        when(mockSolution.getResource("concept")).thenReturn(conceptResource);
        when(mockResultSet.hasNext()).thenReturn(true, false);
        when(mockResultSet.next()).thenReturn(mockSolution);

        when(mockConnection.query(anyString())).thenReturn(mockQueryExecution);
        when(mockQueryExecution.execSelect()).thenReturn(mockResultSet);

        Set<String> result = repository.filterByRelationTypes(
                List.of("https://example.org/concept/1", "https://example.org/concept/2"),
                List.of(RelationType.SUBCLASS));

        assertEquals(1, result.size());
        assertTrue(result.contains("https://example.org/concept/1"));
    }
}
