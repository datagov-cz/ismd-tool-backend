package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.NkdOntologyListItemDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.service.impl.NkdDetailServiceImpl;
import com.dia.ismdtoolbackend.service.impl.ReferencedConceptsEnricher;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
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

    @Mock
    private JsonExporter jsonExporter;

    @Mock
    private RppSnapshotHolder rppSnapshotHolder;

    @Mock
    private ReferencedConceptsEnricher referencedConceptsEnricher;

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
    void getConceptDetail_success_queryParamWinsOverDerivedOntologyIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        // Derived ontologyIri is intentionally different to prove the query param wins.
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenReturn(Optional.of(new NkdSparqlClient.PublishedConcept(model, "https://example.org/ontology/derived")));

        GetNkdConceptDto dto = service.getConceptDetail(CONCEPT_IRI, ONTOLOGY_IRI);

        assertSame(model, dto.getConceptDetail());
        assertEquals(ONTOLOGY_IRI, dto.getOntologyIri());
    }

    @Test
    void getConceptDetail_nullOntologyIriParam_fallsBackToDerivedOntologyIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenReturn(Optional.of(new NkdSparqlClient.PublishedConcept(model, ONTOLOGY_IRI)));

        GetNkdConceptDto dto = service.getConceptDetail(CONCEPT_IRI, null);

        assertSame(model, dto.getConceptDetail());
        assertEquals(ONTOLOGY_IRI, dto.getOntologyIri());
    }

    @Test
    void getConceptDetail_nullOntologyIriParamAndNoInScheme_returnsNullOntologyIri() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenReturn(Optional.of(new NkdSparqlClient.PublishedConcept(model, null)));

        GetNkdConceptDto dto = service.getConceptDetail(CONCEPT_IRI, null);

        assertSame(model, dto.getConceptDetail());
        assertNull(dto.getOntologyIri());
    }

    @Test
    void getConceptDetail_resolvesAgendaAndAisFromRpp() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        String agendaIri = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A1";
        String aisIri = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/I1";
        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder()
                        .iri(CONCEPT_IRI)
                        .agenda(agendaIri)
                        .ais(aisIri)
                        .build();
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenReturn(Optional.of(new NkdSparqlClient.PublishedConcept(model, ONTOLOGY_IRI)));

        RppAgenda agenda = org.mockito.Mockito.mock(RppAgenda.class);
        RppIsvs ais = org.mockito.Mockito.mock(RppIsvs.class);
        when(rppSnapshotHolder.findAgendaByIri(agendaIri)).thenReturn(Optional.of(agenda));
        when(rppSnapshotHolder.findIsvsByIri(aisIri)).thenReturn(Optional.of(ais));

        GetNkdConceptDto dto = service.getConceptDetail(CONCEPT_IRI, null);

        assertSame(agenda, dto.getConceptDetail().getAgendaResolved());
        assertSame(ais, dto.getConceptDetail().getAisResolved());
    }

    @Test
    void getConceptDetail_invokesReferencedConceptsEnricherOnDetail() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder()
                        .iri(CONCEPT_IRI)
                        .exactMatches(List.of("https://slovník.gov.cz/example/pojem/equiv"))
                        .build();
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenReturn(Optional.of(new NkdSparqlClient.PublishedConcept(model, ONTOLOGY_IRI)));

        service.getConceptDetail(CONCEPT_IRI, null);

        // Enricher runs on the same ConceptDetailModel the FE receives — no extra round-trip from the FE
        verify(referencedConceptsEnricher).enrich(model);
    }

    @Test
    void getConceptDetail_missingAgendaAndAis_doesNotCallRpp() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        OntologyDetailModel.ConceptDetailModel model =
                OntologyDetailModel.ConceptDetailModel.builder().iri(CONCEPT_IRI).build();
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenReturn(Optional.of(new NkdSparqlClient.PublishedConcept(model, ONTOLOGY_IRI)));

        service.getConceptDetail(CONCEPT_IRI, null);

        verify(rppSnapshotHolder, org.mockito.Mockito.never()).findAgendaByIri(anyString());
        verify(rppSnapshotHolder, org.mockito.Mockito.never()).findIsvsByIri(anyString());
    }

    @Test
    void getConceptDetail_notFoundInNkd_throwsNkdResourceNotFound() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI)).thenReturn(Optional.empty());

        assertThrows(NkdResourceNotFoundException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, null));
    }

    @Test
    void getConceptDetail_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, null));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConceptWithScheme(anyString());
    }

    @Test
    void getConceptDetail_sparqlError_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedConceptWithScheme(CONCEPT_IRI))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        assertThrows(NkdEndpointException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, null));
    }

    @Test
    void getConceptDetail_blankIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getConceptDetail("", null));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConceptWithScheme(anyString());
    }

    @Test
    void getConceptDetail_malformedIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getConceptDetail("not an iri", null));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConceptWithScheme(anyString());
    }

    @Test
    void getConceptDetail_malformedOntologyIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.getConceptDetail(CONCEPT_IRI, "not an iri"));
        verify(nkdSparqlClient, org.mockito.Mockito.never()).fetchPublishedConceptWithScheme(anyString());
    }

    // ── Ontology list ──────────────────────────────────────────────────

    // Unique fragment of the batched list-item metadata SELECT (no other query has ?descLang).
    private static final String META_QUERY_MARKER = "?descLang";

    /** Builds one executeSelect result row for the batched list-item metadata query. */
    private static Map<String, String> metaRow(String iri, String label, String descLang,
                                               String desc, String cDateTime, String mDateTime) {
        Map<String, String> row = new java.util.HashMap<>();
        row.put("ontology", iri);
        if (label != null) row.put("label", label);
        if (desc != null) {
            row.put("desc", desc);
            if (descLang != null) row.put("descLang", descLang);
        }
        if (cDateTime != null) row.put("cDateTime", cDateTime);
        if (mDateTime != null) row.put("mDateTime", mDateTime);
        return row;
    }

    @Test
    void getOntologyList_nullInput_returnsEmpty() {
        // Empty/null input is a no-op — don't even check endpoint config so tests
        // and FE empty-cart calls don't fail when NKD is offline.
        GetNkdOntologyListDto result = service.getOntologyList(null);
        assertTrue(result.getOntologies().isEmpty());
        verify(nkdSparqlClient, org.mockito.Mockito.never()).executeSelect(anyString());
    }

    @Test
    void getOntologyList_emptyInput_returnsEmpty() {
        GetNkdOntologyListDto result = service.getOntologyList(List.of());
        assertTrue(result.getOntologies().isEmpty());
        verify(nkdSparqlClient, org.mockito.Mockito.never()).executeSelect(anyString());
    }

    @Test
    void getOntologyList_singleValidIri_mapsAllFields() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(metaRow(ONTOLOGY_IRI, "Test slovník", "cs", "Popis",
                        "2024-01-02", "2024-03-04")));

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
        // Metadata rows returned out of input order — service must restore input order.
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(
                        metaRow(iri3, null, null, null, null, null),
                        metaRow(iri1, null, null, null, null, null),
                        metaRow(iri2, null, null, null, null, null)));

        GetNkdOntologyListDto result = service.getOntologyList(List.of(iri1, iri2, iri3));

        assertEquals(List.of(iri1, iri2, iri3),
                result.getOntologies().stream().map(NkdOntologyListItemDto::getIri).toList());
    }

    @Test
    void getOntologyList_oneIriNotFound_skippedOthersReturned() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        String missingIri = "https://example.org/ontology/missing";
        String foundIri = "https://example.org/ontology/found";
        // Metadata query returns rows only for the found IRI; missing one is skipped.
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(metaRow(foundIri, null, null, null, null, null)));

        GetNkdOntologyListDto result = service.getOntologyList(List.of(missingIri, foundIri));

        assertEquals(1, result.getOntologies().size());
        assertEquals(foundIri, result.getOntologies().get(0).getIri());
    }

    @Test
    void getOntologyList_metadataQueryErrors_returnsEmptyNotFatal() {
        // A SPARQL failure on the batched metadata query degrades to an empty list
        // (every IRI skipped) rather than 500ing the "last accessed" tile row.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        GetNkdOntologyListDto result = service.getOntologyList(
                List.of("https://example.org/ontology/ok"));

        assertTrue(result.getOntologies().isEmpty());
    }

    @Test
    void getOntologyList_invalidIri_failsBatchBeforeAnyFetch() {
        // Validate up front so a single bad IRI doesn't waste a round-trip.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyList(List.of(ONTOLOGY_IRI, "not an iri")));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).executeSelect(anyString());
    }

    @Test
    void getOntologyList_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.getOntologyList(List.of(ONTOLOGY_IRI)));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).executeSelect(anyString());
    }

    @Test
    void getOntologyList_overLimit_throwsIllegalArgumentException() {
        // Cap is 50; 51 must be rejected before we ever check endpoint config or fetch.
        List<String> tooMany = IntStream.range(0, 51)
                .mapToObj(i -> "https://example.org/ontology/" + i)
                .collect(Collectors.toList());

        assertThrows(IllegalArgumentException.class,
                () -> service.getOntologyList(tooMany));

        verify(nkdSparqlClient, org.mockito.Mockito.never()).executeSelect(anyString());
    }

    @Test
    void getOntologyList_atLimit_isAccepted() {
        // 50 must work — exactly at the boundary, all in ONE batched metadata query.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of());

        List<String> exactly50 = IntStream.range(0, 50)
                .mapToObj(i -> "https://example.org/ontology/" + i)
                .collect(Collectors.toList());

        GetNkdOntologyListDto result = service.getOntologyList(exactly50);

        assertTrue(result.getOntologies().isEmpty());
        // One batched round-trip for all 50 IRIs, not 50 per-IRI fetches.
        verify(nkdSparqlClient, org.mockito.Mockito.times(1))
                .executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER));
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
        // Empty page must NOT trigger the batched metadata query or concept-count batch.
        verify(nkdSparqlClient, org.mockito.Mockito.never())
                .executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER));
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

        // 4. Batched list-item metadata (replaces the per-IRI fetch loop)
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(
                        metaRow(iri1, "A slovník", null, null, null, null),
                        metaRow(iri2, "B slovník", null, null, null, null)));

        GetNkdOntologyListDto result = service.listAllOntologies(20, 0, "cs");

        assertEquals(2, result.getOntologies().size());
        assertEquals(iri1, result.getOntologies().get(0).getIri());
        assertEquals(Map.of("cs", "A slovník"), result.getOntologies().get(0).getName());
        assertEquals(7, result.getOntologies().get(0).getConceptCount());
        assertEquals(iri2, result.getOntologies().get(1).getIri());
        // Missing from concept-count batch result → defaulted to 0 (not null).
        assertEquals(0, result.getOntologies().get(1).getConceptCount());

        assertEquals(2, result.getOntologyCount());
        assertEquals(2, result.getTotalCount());
        assertEquals(7, result.getConceptCount());
    }

    @Test
    void listAllOntologies_foldsMultiLangDescription_andPrefersDateTimeAndLabel() {
        // Assembly parity: multiple description rows (one per lang) fold into one map;
        // rdfs:label wins over skos:prefLabel; čas:datum-a-čas (dateTime) wins over čas:datum.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        String iri = "https://example.org/ontology/multi";

        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of(Map.of("ontology", iri)));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("GROUP BY ?ontology")))
                .thenReturn(List.of(Map.of("ontology", iri, "cnt", "3")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "1")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "3")));

        // Two description rows (cs + en), both label and prefLabel present (label must win),
        // both dateTime and date present for creation (dateTime must win).
        Map<String, String> rowCs = new java.util.HashMap<>();
        rowCs.put("ontology", iri);
        rowCs.put("label", "Štítek");        // rdfs:label — wins
        rowCs.put("prefLabel", "PrefLabel"); // skos:prefLabel — loses
        rowCs.put("desc", "Český popis");
        rowCs.put("descLang", "cs");
        rowCs.put("cDateTime", "2024-01-02T10:00:00");
        rowCs.put("cDate", "2024-01-02");
        rowCs.put("mDate", "2024-03-04");    // only date for modification
        Map<String, String> rowEn = new java.util.HashMap<>();
        rowEn.put("ontology", iri);
        rowEn.put("desc", "English description");
        rowEn.put("descLang", "en");
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(rowCs, rowEn));

        GetNkdOntologyListDto result = service.listAllOntologies(20, 0, "cs");

        assertEquals(1, result.getOntologies().size());
        var item = result.getOntologies().get(0);
        assertEquals(Map.of("cs", "Štítek"), item.getName());
        assertEquals(Map.of("cs", "Český popis", "en", "English description"), item.getDescription());
        assertEquals("2024-01-02T10:00:00", item.getCreationDate());  // dateTime preferred
        assertEquals("2024-03-04", item.getModificationDate());       // date fallback
        assertEquals(3, item.getConceptCount());
    }

    @Test
    void listAllOntologies_unlabelledOntology_emitsEmptyNameMap() {
        // No rdfs:label and no skos:prefLabel → name is an empty map (not null, not {cs:null}).
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        String iri = "https://example.org/ontology/bare";

        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of(Map.of("ontology", iri)));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("GROUP BY ?ontology")))
                .thenReturn(List.of());
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "1")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "0")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(metaRow(iri, null, null, null, null, null)));

        GetNkdOntologyListDto result = service.listAllOntologies(20, 0, "cs");

        assertEquals(1, result.getOntologies().size());
        assertTrue(result.getOntologies().get(0).getName().isEmpty());
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
    void listAllOntologies_ontologyMissingFromMetadata_skippedNotFatal() {
        // An ontology that paged in but has no metadata rows (e.g. vanished between the
        // list query and the metadata query) is skipped, not fatal to the page.
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);

        String iriOk = "https://example.org/ontology/ok";
        String iriGone = "https://example.org/ontology/gone";

        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("ORDER BY ?orderKey")))
                .thenReturn(List.of(Map.of("ontology", iriOk), Map.of("ontology", iriGone)));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("GROUP BY ?ontology")))
                .thenReturn(List.of());
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?ontology) AS ?total")))
                .thenReturn(List.of(Map.of("total", "2")));
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains("COUNT(DISTINCT ?concept) AS ?total")))
                .thenReturn(List.of(Map.of("total", "0")));

        // Metadata query returns a row only for the OK ontology; the gone one is absent.
        when(nkdSparqlClient.executeSelect(org.mockito.ArgumentMatchers.contains(META_QUERY_MARKER)))
                .thenReturn(List.of(metaRow(iriOk, null, null, null, null, null)));

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

    // ── Detail with concept count ─────────────────────────────────────

    @Test
    void getOntologyDetail_populatesConceptCountFromConceptsList() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        OntologyDetailModel.ConceptDetailModel c1 = OntologyDetailModel.ConceptDetailModel.builder().iri("c1").build();
        OntologyDetailModel.ConceptDetailModel c2 = OntologyDetailModel.ConceptDetailModel.builder().iri("c2").build();
        OntologyDetailModel.ConceptDetailModel c3 = OntologyDetailModel.ConceptDetailModel.builder().iri("c3").build();
        OntologyDetailModel model = OntologyDetailModel.builder()
                .iri(ONTOLOGY_IRI)
                .concepts(List.of(c1, c2, c3))
                .build();
        when(nkdSparqlClient.fetchPublishedOntology(ONTOLOGY_IRI)).thenReturn(Optional.of(model));

        GetNkdOntologyDto dto = service.getOntologyDetail(ONTOLOGY_IRI);

        assertEquals(3, dto.getOntologyDetail().getConceptCount());
    }

    @Test
    void getOntologyDetail_nullConceptsList_leavesConceptCountNull() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        OntologyDetailModel model = OntologyDetailModel.builder().iri(ONTOLOGY_IRI).build();
        when(nkdSparqlClient.fetchPublishedOntology(ONTOLOGY_IRI)).thenReturn(Optional.of(model));

        GetNkdOntologyDto dto = service.getOntologyDetail(ONTOLOGY_IRI);

        assertNull(dto.getOntologyDetail().getConceptCount());
    }

    // ── Download ──────────────────────────────────────────────────────

    @Test
    void downloadOntology_endpointNotConfigured_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(false);

        assertThrows(NkdEndpointException.class,
                () -> service.downloadOntology(ONTOLOGY_IRI, "ttl"));
    }

    @Test
    void downloadOntology_invalidIri_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadOntology("not an iri", "ttl"));
    }

    @Test
    void downloadOntology_blankFormat_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadOntology(ONTOLOGY_IRI, ""));
    }

    @Test
    void downloadOntology_nullFormat_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadOntology(ONTOLOGY_IRI, null));
    }

    @Test
    void downloadOntology_unsupportedFormat_throwsIllegalArgumentException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        assertThrows(IllegalArgumentException.class,
                () -> service.downloadOntology(ONTOLOGY_IRI, "rdf-xml"));
    }

    @Test
    void downloadOntology_notFoundInNkd_throwsNkdResourceNotFound() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedOntologyRaw(ONTOLOGY_IRI)).thenReturn(Optional.empty());

        assertThrows(NkdResourceNotFoundException.class,
                () -> service.downloadOntology(ONTOLOGY_IRI, "ttl"));
    }

    @Test
    void downloadOntology_sparqlError_throwsNkdEndpointException() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedOntologyRaw(ONTOLOGY_IRI))
                .thenThrow(new QueryExceptionHTTP(503, "Service unavailable"));

        assertThrows(NkdEndpointException.class,
                () -> service.downloadOntology(ONTOLOGY_IRI, "ttl"));
    }

    @Test
    void downloadOntology_ttl_serializesViaModelWrite() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        when(nkdSparqlClient.fetchPublishedOntologyRaw(ONTOLOGY_IRI))
                .thenAnswer(inv -> Optional.of(buildSimpleModel()));

        byte[] result = service.downloadOntology(ONTOLOGY_IRI, "ttl");

        String body = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains(ONTOLOGY_IRI), "Turtle should include the ontology IRI: " + body);
        assertTrue(body.contains("Test"), "Turtle should include the label: " + body);

        // Format string is case-insensitive — second call gets a fresh model
        // because the service closes whatever model was passed in.
        byte[] result2 = service.downloadOntology(ONTOLOGY_IRI, "TTL");
        assertTrue(new String(result2, java.nio.charset.StandardCharsets.UTF_8).contains(ONTOLOGY_IRI));
    }

    private static Model buildSimpleModel() {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource(NkdDetailServiceImplTest.ONTOLOGY_IRI),
                m.createProperty("http://www.w3.org/2000/01/rdf-schema#label"),
                m.createLiteral("Test", "cs"));
        return m;
    }

    @Test
    void downloadOntology_jsonLd_delegatesToJsonExporter() {
        when(nkdSparqlClient.isEndpointConfigured()).thenReturn(true);
        Model model = ModelFactory.createDefaultModel();
        when(nkdSparqlClient.fetchPublishedOntologyRaw(ONTOLOGY_IRI)).thenReturn(Optional.of(model));
        when(jsonExporter.exportToJson(model)).thenReturn("{\"ok\":true}");

        byte[] result = service.downloadOntology(ONTOLOGY_IRI, "json-ld");

        assertEquals("{\"ok\":true}", new String(result, java.nio.charset.StandardCharsets.UTF_8));
        verify(jsonExporter).exportToJson(model);
    }
}
