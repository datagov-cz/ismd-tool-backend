package com.dia.ismdtoolbackend.repository;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the two read methods the PG↔TDB2 reconciler adds to JenaTDB2Repository:
 * {@code listNamedGraphs} and {@code listOwnedConceptIrisInGraph}. The owned-concept query
 * is byte-shared with the resolver via {@link JenaTDB2Repository#OWNED_CONCEPT_PATTERN}.
 */
class JenaTDB2RepositoryReconcilerReadsTest {

    private TestableJenaTDB2Repository repository;
    private RDFConnection mockConnection;

    static class TestableJenaTDB2Repository extends JenaTDB2Repository {
        private final RDFConnection mockConn;

        TestableJenaTDB2Repository(HttpClient httpClient, Semaphore semaphore, int timeout, RDFConnection mockConn) {
            super(httpClient, semaphore, timeout, new MockEnvironment());
            this.mockConn = mockConn;
        }

        @Override
        protected RDFConnection createConnection() {
            return mockConn;
        }
    }

    @BeforeEach
    void setUp() {
        mockConnection = mock(RDFConnection.class);
        repository = new TestableJenaTDB2Repository(HttpClient.newHttpClient(), new Semaphore(4), 5000, mockConnection);
    }

    private ResultSet resultSetOf(String var, List<String> uris) {
        // Build all nested mocks FIRST, fully, before any outer stub — creating a stub
        // inside another stub's answer trips Mockito's "unfinished stubbing".
        List<QuerySolution> sols = uris.stream().map(uri -> {
            QuerySolution sol = mock(QuerySolution.class);
            Resource r = mock(Resource.class);
            when(r.isURIResource()).thenReturn(true);
            when(r.getURI()).thenReturn(uri);
            when(sol.getResource(var)).thenReturn(r);
            return sol;
        }).toList();

        ResultSet rs = mock(ResultSet.class);
        java.util.Iterator<QuerySolution> it = sols.iterator();
        when(rs.hasNext()).thenAnswer(inv -> it.hasNext());
        when(rs.next()).thenAnswer(inv -> it.next());
        return rs;
    }

    @Test
    void listNamedGraphs_returnsDistinctGraphUris() {
        // Build the stubbed ResultSet fully BEFORE the when(...).thenReturn(...) — resultSetOf
        // does its own stubbing, so it must not run mid-stub.
        ResultSet rs = resultSetOf("g", List.of("https://slovník.gov.cz/a", "https://slovník.gov.cz/b"));
        QueryExecution qExec = mock(QueryExecution.class);
        when(qExec.execSelect()).thenReturn(rs);
        when(mockConnection.query(anyString())).thenReturn(qExec);

        List<String> graphs = repository.listNamedGraphs();

        assertEquals(List.of("https://slovník.gov.cz/a", "https://slovník.gov.cz/b"), graphs);
    }

    @Test
    void listNamedGraphs_empty_returnsEmptyList() {
        ResultSet rs = resultSetOf("g", List.of());
        QueryExecution qExec = mock(QueryExecution.class);
        when(qExec.execSelect()).thenReturn(rs);
        when(mockConnection.query(anyString())).thenReturn(qExec);

        assertTrue(repository.listNamedGraphs().isEmpty());
    }

    @Test
    void listOwnedConceptIris_returnsConceptSubjects() {
        String iri = "https://slovník.gov.cz/g/pojem/x";
        ResultSet rs = resultSetOf("concept", List.of(iri));
        QueryExecution qExec = mock(QueryExecution.class);
        when(qExec.execSelect()).thenReturn(rs);
        when(mockConnection.query(any(Query.class))).thenReturn(qExec);

        List<String> owned = repository.listOwnedConceptIrisInGraph("https://slovník.gov.cz/g");

        assertEquals(List.of(iri), owned);
    }

    @Test
    void listOwnedConceptIris_unsafeGraphIri_returnsEmptyWithoutQuerying() {
        List<String> owned = repository.listOwnedConceptIrisInGraph("not a valid iri with spaces");
        assertTrue(owned.isEmpty());
        verify(mockConnection, never()).query(any(Query.class));
    }
}
