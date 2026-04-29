package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.service.impl.NkdDetailServiceImpl;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NkdDetailServiceImplTest {

    private static final String ONTOLOGY_IRI = "https://example.org/ontology/1";
    private static final String CONCEPT_IRI = "https://example.org/concept/1";

    @Mock
    private NkdSparqlClient nkdSparqlClient;

    @InjectMocks
    private NkdDetailServiceImpl service;

    // ── Ontology ───────────────────────────────────────────────────────

    @Test
    void getOntologyDetail_success_returnsDto() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel model = OntologyDetailModel.builder().iri(ONTOLOGY_IRI).build();
        when(nkdSparqlClient.fetchPublishedOntology(ONTOLOGY_IRI)).thenReturn(Optional.of(model));

        GetNkdOntologyDto dto = service.getOntologyDetail(ONTOLOGY_IRI);

        assertSame(model, dto.getOntologyDetail());
    }

    @Test
    void getOntologyDetail_notFoundInNkd_throwsNkdResourceNotFound() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedOntology(ONTOLOGY_IRI)).thenReturn(Optional.empty());

        assertThrows(NkdResourceNotFoundException.class,
                () -> service.getOntologyDetail(ONTOLOGY_IRI));
    }

    @Test
    void getOntologyDetail_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.getOntologyDetail(ONTOLOGY_IRI));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyDetail_sparqlError_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedOntology(ONTOLOGY_IRI))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        assertThrows(NkdEndpointException.class,
                () -> service.getOntologyDetail(ONTOLOGY_IRI));
    }

    @Test
    void getOntologyDetail_blankIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyDetail("   "));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyDetail_nullIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyDetail(null));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyDetail_malformedIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyDetail("not an iri"));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyDetail_relativeIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyDetail("/relative/path"));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    // ── Concept ────────────────────────────────────────────────────────

    @Test
    void getConceptDetail_success_returnsDtoWithOntologyIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(CONCEPT_IRI)).thenReturn(Optional.of(model));

        GetNkdConceptDto dto = service.getConceptDetail(CONCEPT_IRI, ONTOLOGY_IRI);

        assertSame(model, dto.getConceptDetail());
        assertEquals(ONTOLOGY_IRI, dto.getOntologyIri());
    }

    @Test
    void getConceptDetail_nullOntologyIri_returnsDtoWithNullOntologyIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(CONCEPT_IRI)).thenReturn(Optional.of(model));

        GetNkdConceptDto dto = service.getConceptDetail(CONCEPT_IRI, null);

        assertSame(model, dto.getConceptDetail());
        assertNull(dto.getOntologyIri());
    }

    @Test
    void getConceptDetail_notFoundInNkd_throwsNkdResourceNotFound() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedConcept(CONCEPT_IRI)).thenReturn(Optional.empty());

        assertThrows(NkdResourceNotFoundException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, null));
    }

    @Test
    void getConceptDetail_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, null));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConcept(anyString());
    }

    @Test
    void getConceptDetail_sparqlError_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedConcept(CONCEPT_IRI))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        assertThrows(NkdEndpointException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, null));
    }

    @Test
    void getConceptDetail_blankIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getConceptDetail("", null));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConcept(anyString());
    }

    @Test
    void getConceptDetail_malformedIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getConceptDetail("not an iri", null));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConcept(anyString());
    }

    @Test
    void getConceptDetail_malformedOntologyIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, "not an iri"));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConcept(anyString());
    }

    // ── Ontology list ──────────────────────────────────────────────────

    @Test
    void getOntologyList_nullInput_returnsEmpty() {
        // Empty/null input is a no-op — don't even check endpoint config so tests
        // and FE empty-cart calls don't fail when NKD is offline.
        GetNkdOntologyListDto result = service.getOntologyList(null);
        assertTrue(result.getOntologies().isEmpty());
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyList_emptyInput_returnsEmpty() {
        GetNkdOntologyListDto result = service.getOntologyList(List.of());
        assertTrue(result.getOntologies().isEmpty());
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyList_singleValidIri_mapsAllFields() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        OntologyDetailModel model = OntologyDetailModel.builder()
                .iri(ONTOLOGY_IRI)
                .name(Map.of("cs", "Test slovník"))
                .description(Map.of("cs", "Popis"))
                .creationDate("2024-01-02")
                .modificationDate("2024-03-04")
                .build();
        when(nkdSparqlClient.fetchPublishedOntology(ONTOLOGY_IRI)).thenReturn(Optional.of(model));

        GetNkdOntologyListDto result = service.getOntologyList(List.of(ONTOLOGY_IRI));

        assertEquals(1, result.getOntologies().size());
        var item = result.getOntologies().get(0);
        assertEquals(ONTOLOGY_IRI, item.getIri());
        assertEquals(Map.of("cs", "Test slovník"), item.getName());
        assertEquals(Map.of("cs", "Popis"), item.getDescription());
        assertEquals("2024-01-02", item.getCreationDate());
        assertEquals("2024-03-04", item.getModificationDate());
    }

    @Test
    void getOntologyList_multipleValid_returnsInInputOrder() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        String iri1 = "https://example.org/ontology/a";
        String iri2 = "https://example.org/ontology/b";
        String iri3 = "https://example.org/ontology/c";
        when(nkdSparqlClient.fetchPublishedOntology(iri1))
                .thenReturn(Optional.of(OntologyDetailModel.builder().iri(iri1).build()));
        when(nkdSparqlClient.fetchPublishedOntology(iri2))
                .thenReturn(Optional.of(OntologyDetailModel.builder().iri(iri2).build()));
        when(nkdSparqlClient.fetchPublishedOntology(iri3))
                .thenReturn(Optional.of(OntologyDetailModel.builder().iri(iri3).build()));

        GetNkdOntologyListDto result = service.getOntologyList(List.of(iri1, iri2, iri3));

        assertEquals(List.of(iri1, iri2, iri3),
                result.getOntologies().stream().map(i -> i.getIri()).toList());
    }

    @Test
    void getOntologyList_oneIriNotFound_skippedOthersReturned() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        String missingIri = "https://example.org/ontology/missing";
        String foundIri = "https://example.org/ontology/found";
        when(nkdSparqlClient.fetchPublishedOntology(missingIri)).thenReturn(Optional.empty());
        when(nkdSparqlClient.fetchPublishedOntology(foundIri))
                .thenReturn(Optional.of(OntologyDetailModel.builder().iri(foundIri).build()));

        GetNkdOntologyListDto result = service.getOntologyList(List.of(missingIri, foundIri));

        assertEquals(1, result.getOntologies().size());
        assertEquals(foundIri, result.getOntologies().get(0).getIri());
    }

    @Test
    void getOntologyList_oneIriSparqlErrors_skippedOthersReturned() {
        // A single stale FE bookmark mustn't blank the whole "last accessed" tile row.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        String erroringIri = "https://example.org/ontology/err";
        String okIri = "https://example.org/ontology/ok";
        when(nkdSparqlClient.fetchPublishedOntology(erroringIri))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));
        when(nkdSparqlClient.fetchPublishedOntology(okIri))
                .thenReturn(Optional.of(OntologyDetailModel.builder().iri(okIri).build()));

        GetNkdOntologyListDto result = service.getOntologyList(List.of(erroringIri, okIri));

        assertEquals(1, result.getOntologies().size());
        assertEquals(okIri, result.getOntologies().get(0).getIri());
    }

    @Test
    void getOntologyList_invalidIri_failsBatchBeforeAnyFetch() {
        // Validate up front so a single bad IRI doesn't waste N-1 round-trips.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyList(List.of(ONTOLOGY_IRI, "not an iri")));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyList_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.getOntologyList(List.of(ONTOLOGY_IRI)));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyList_overLimit_throwsIllegalArgumentException() {
        // Cap is 50; 51 must be rejected before we ever check endpoint config or fetch.
        List<String> tooMany = IntStream.range(0, 51)
                .mapToObj(i -> "https://example.org/ontology/" + i)
                .collect(Collectors.toList());

        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyList(tooMany));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void getOntologyList_atLimit_isAccepted() {
        // 50 must work — exactly at the boundary.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedOntology(anyString())).thenReturn(Optional.empty());

        List<String> exactly50 = IntStream.range(0, 50)
                .mapToObj(i -> "https://example.org/ontology/" + i)
                .collect(Collectors.toList());

        GetNkdOntologyListDto result = service.getOntologyList(exactly50);

        assertTrue(result.getOntologies().isEmpty());
        verify(nkdSparqlClient, org.mockito.Mockito.times(50)).fetchPublishedOntology(anyString());
    }

    // ── List-all (catalog browse) ──────────────────────────────────────

    @Test
    void listAllOntologies_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.listAllOntologies(20, 0, "cs"));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).executeSelect(anyString());
    }

    @Test
    void listAllOntologies_invalidLimit_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listAllOntologies(0, 0, "cs"));
        assertThrows(IllegalArgumentException.class,
                () -> service.listAllOntologies(101, 0, "cs"));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).isEndpointConfigured();
    }

    @Test
    void listAllOntologies_negativeOffset_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listAllOntologies(20, -1, "cs"));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).isEndpointConfigured();
    }

    @Test
    void listAllOntologies_emptyPage_returnsZeroItemsButPopulatesGlobalCounts() {
        // Even if the page is empty (e.g. offset past the end), the global
        // totals should still be populated so the FE can render pagination.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of());
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "42")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "9001")));

        GetNkdOntologyListDto result = service.listAllOntologies(20, 100, "cs");

        assertTrue(result.getOntologies().isEmpty());
        assertEquals(42, result.getOntologyCount());
        assertEquals(42, result.getTotalCount());
        assertEquals(9001, result.getConceptCount());
        // Empty page must NOT trigger per-IRI fetches or concept-count batch query.
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedOntology(anyString());
    }

    @Test
    void listAllOntologies_happyPath_mergesIrisDetailAndConceptCounts() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        String iri1 = "https://example.org/ontology/a";
        String iri2 = "https://example.org/ontology/b";

        // 1. Page query
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of(
                        Map.of("ontology", iri1),
                        Map.of("ontology", iri2)));
        // 2. Concept-counts batched query — ontology b has zero concepts (not returned)
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("GROUP BY ?ontology")))
                .thenReturn(List.of(Map.of("ontology", iri1, "cnt", "7")));
        // 3. Global counts
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "2")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "7")));

        when(nkdSparqlClient.fetchPublishedOntology(iri1)).thenReturn(Optional.of(
                OntologyDetailModel.builder().iri(iri1).name(Map.of("cs", "A slovník")).build()));
        when(nkdSparqlClient.fetchPublishedOntology(iri2)).thenReturn(Optional.of(
                OntologyDetailModel.builder().iri(iri2).name(Map.of("cs", "B slovník")).build()));

        GetNkdOntologyListDto result = service.listAllOntologies(20, 0, "cs");

        assertEquals(2, result.getOntologies().size());
        assertEquals(iri1, result.getOntologies().get(0).getIri());
        assertEquals(7, result.getOntologies().get(0).getConceptCount());
        assertEquals(iri2, result.getOntologies().get(1).getIri());
        // Missing from concept-count batch result → defaulted to 0 (not null).
        assertEquals(0, result.getOntologies().get(1).getConceptCount());

        assertEquals(2, result.getOntologyCount());
        assertEquals(2, result.getTotalCount());
        assertEquals(7, result.getConceptCount());
    }

    @Test
    void listAllOntologies_pageQueryFails_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(anyString()))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        assertThrows(NkdEndpointException.class,
                () -> service.listAllOntologies(20, 0, "cs"));
    }

    @Test
    void listAllOntologies_perOntologyFetchFails_skippedNotFatal() {
        // A single bad ontology must not blank the whole catalog page.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        String iriOk = "https://example.org/ontology/ok";
        String iriErr = "https://example.org/ontology/err";

        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of(Map.of("ontology", iriOk), Map.of("ontology", iriErr)));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("GROUP BY ?ontology")))
                .thenReturn(List.of());
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "2")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "0")));

        when(nkdSparqlClient.fetchPublishedOntology(iriOk)).thenReturn(Optional.of(
                OntologyDetailModel.builder().iri(iriOk).build()));
        when(nkdSparqlClient.fetchPublishedOntology(iriErr))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        GetNkdOntologyListDto result = service.listAllOntologies(20, 0, "cs");

        assertEquals(1, result.getOntologies().size());
        assertEquals(iriOk, result.getOntologies().get(0).getIri());
    }

    @Test
    void listAllOntologies_secondCallWithinTtl_servesCachedCounts() {
        // The two global COUNT queries should run exactly once across two
        // back-to-back calls — second call must hit the in-heap cache.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of());
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "5")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "50")));

        service.listAllOntologies(20, 0, "cs");
        service.listAllOntologies(20, 0, "cs");

        // Page query runs each call; each COUNT runs only once.
        verify(nkdSparqlClient, org.mockito.Mockito.times(2))
                .executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey"));
        verify(nkdSparqlClient, org.mockito.Mockito.times(1))
                .executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total"));
        verify(nkdSparqlClient, org.mockito.Mockito.times(1))
                .executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total"));
    }
}
