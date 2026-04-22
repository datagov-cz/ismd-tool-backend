package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
