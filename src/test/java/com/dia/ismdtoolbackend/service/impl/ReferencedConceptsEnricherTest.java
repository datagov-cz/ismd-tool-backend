package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferencedConceptsEnricherTest {

    private static final String EXACT_MATCH_IRI = "https://slovník.gov.cz/legislativní/sbírka/128/2000/pojem/obecní-úřad-obce";
    private static final String BROADER_CLASS_IRI = "https://slovník.gov.cz/legislativní/sbírka/128/2000/pojem/úřad";
    private static final String BROADER_REL_IRI = "https://slovník.gov.cz/example/pojem/nadrazeny-vztah";
    private static final String BROADER_PROP_IRI = "https://slovník.gov.cz/example/pojem/nadrazena-vlastnost";
    private static final String DOMAIN_IRI = "https://slovník.gov.cz/example/pojem/domena";
    private static final String RANGE_IRI = "https://slovník.gov.cz/example/pojem/rozsah";
    private static final String PROPERTY_IRI = "https://slovník.gov.cz/example/pojem/vlastnost";
    private static final String RELATIONSHIP_IRI = "https://slovník.gov.cz/example/pojem/vztah";

    @Mock private ReferencedConceptResolutionEngine resolutionEngine;

    @InjectMocks
    private ReferencedConceptsEnricher enricher;

    private static ResolvedConceptDto dto(String iri, SearchSource source) {
        return ResolvedConceptDto.builder()
                .iri(iri)
                .conceptName(Map.of("cs", "Název"))
                .ontologyIri("https://slovník.gov.cz/example")
                .ontologyName(Map.of("cs", "Příklad"))
                .source(source)
                .build();
    }

    private static ConceptPropertiesModel property(String iri, String ref, String name) {
        ConceptPropertiesModel p = new ConceptPropertiesModel();
        p.setIri(iri);
        p.setRef(ref);
        p.setName(name);
        return p;
    }

    private static ConceptRelationshipsModel relationship(String iri, String ref, String name) {
        ConceptRelationshipsModel r = new ConceptRelationshipsModel();
        r.setIri(iri);
        r.setRef(ref);
        r.setName(name);
        return r;
    }

    @Test
    @DisplayName("Null detail → no-op, no resolver call")
    void nullDetail() {
        enricher.enrich(null);
        verifyNoInteractions(resolutionEngine);
    }

    @Test
    @DisplayName("Detail with no referenced IRIs → no resolver call, map stays null")
    void noReferencedIris() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder().build();

        enricher.enrich(detail);

        assertThat(detail.getReferencedConceptsResolved()).isNull();
        verifyNoInteractions(resolutionEngine);
    }

    @Test
    @DisplayName("Collects IRIs from every supported field, dedupes them, and stamps the result")
    void collectsFromAllFields() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .exactMatches(List.of(EXACT_MATCH_IRI, EXACT_MATCH_IRI)) // intentional duplicate
                .broaderClasses(List.of(BROADER_CLASS_IRI))
                .broaderRelations(List.of(BROADER_REL_IRI))
                .broaderProperties(List.of(BROADER_PROP_IRI))
                .domain(DOMAIN_IRI)
                .range(RANGE_IRI)
                .conceptProperties(List.of(property(PROPERTY_IRI, "vocab-vlastnost", "Vlastnost")))
                .conceptRelationships(List.of(relationship(RELATIONSHIP_IRI, "vocab-vztah", "Vztah")))
                .build();

        Map<String, ResolvedConceptDto> resolved = Map.of(
                EXACT_MATCH_IRI, dto(EXACT_MATCH_IRI, SearchSource.NKD),
                BROADER_CLASS_IRI, dto(BROADER_CLASS_IRI, SearchSource.NKD),
                BROADER_REL_IRI, dto(BROADER_REL_IRI, SearchSource.ISMD),
                BROADER_PROP_IRI, dto(BROADER_PROP_IRI, SearchSource.ISMD),
                DOMAIN_IRI, dto(DOMAIN_IRI, SearchSource.ISMD),
                RANGE_IRI, dto(RANGE_IRI, SearchSource.ISMD),
                PROPERTY_IRI, dto(PROPERTY_IRI, SearchSource.ISMD),
                RELATIONSHIP_IRI, dto(RELATIONSHIP_IRI, SearchSource.ISMD));
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        when(resolutionEngine.resolveAll(captor.capture(), isNull())).thenReturn(resolved);

        enricher.enrich(detail);

        assertThat(captor.getValue())
                .as("All bare-IRI fields contribute, duplicates removed")
                .containsExactlyInAnyOrder(
                        EXACT_MATCH_IRI, BROADER_CLASS_IRI, BROADER_REL_IRI, BROADER_PROP_IRI,
                        DOMAIN_IRI, RANGE_IRI, PROPERTY_IRI, RELATIONSHIP_IRI);
        assertThat(detail.getReferencedConceptsResolved()).isEqualTo(resolved);
    }

    @Test
    @DisplayName("Partial resolver map → bare-IRI fields untouched, only resolved entries appear")
    void partialResolution() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .exactMatches(List.of(EXACT_MATCH_IRI, BROADER_CLASS_IRI))
                .build();
        when(resolutionEngine.resolveAll(anyList(), any()))
                .thenReturn(Map.of(EXACT_MATCH_IRI, dto(EXACT_MATCH_IRI, SearchSource.NKD)));

        enricher.enrich(detail);

        assertThat(detail.getExactMatches())
                .as("Bare-IRI field is preserved verbatim")
                .containsExactly(EXACT_MATCH_IRI, BROADER_CLASS_IRI);
        assertThat(detail.getReferencedConceptsResolved())
                .containsOnlyKeys(EXACT_MATCH_IRI);
    }

    @Test
    @DisplayName("Property/relationship with null iri is skipped")
    void nullIrisOnPropertiesSkipped() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .conceptProperties(List.of(property(null, "slug-only", "Lokální vlastnost")))
                .conceptRelationships(List.of(relationship(null, "slug-only-rel", "Lokální vztah")))
                .build();

        enricher.enrich(detail);

        // Nothing to resolve → resolver never called
        verify(resolutionEngine, never()).resolveAll(anyList(), any());
        assertThat(detail.getReferencedConceptsResolved()).isNull();
    }

    @Test
    @DisplayName("Resolver throws SparqlEndpointUnavailableException → propagates to caller")
    void resolverThrowsPropagates() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .exactMatches(List.of(EXACT_MATCH_IRI))
                .build();
        when(resolutionEngine.resolveAll(anyList(), any()))
                .thenThrow(new SparqlEndpointUnavailableException("ISMD", "boom"));

        assertThatThrownBy(() -> enricher.enrich(detail))
                .isInstanceOf(SparqlEndpointUnavailableException.class)
                .hasMessage("boom");
    }

    @Test
    @DisplayName("Blank IRIs in lists are filtered")
    void blankIrisFiltered() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .exactMatches(List.of("", "   ", EXACT_MATCH_IRI))
                .build();
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        when(resolutionEngine.resolveAll(captor.capture(), isNull())).thenReturn(Map.of());

        enricher.enrich(detail);

        assertThat(captor.getValue()).containsExactly(EXACT_MATCH_IRI);
    }

    @Test
    @DisplayName("enrich(detail) uses the default (ISMD-first) path — passes null source")
    void defaultOverloadPassesNullSource() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .exactMatches(List.of(EXACT_MATCH_IRI))
                .build();
        when(resolutionEngine.resolveAll(anyList(), isNull())).thenReturn(Map.of());

        enricher.enrich(detail);

        verify(resolutionEngine).resolveAll(List.of(EXACT_MATCH_IRI), null);
    }

    @Test
    @DisplayName("enrich(detail, NKD) gates the referenced-concept resolution to NKD-only")
    void nkdSourceGatesResolution() {
        OntologyDetailModel.ConceptDetailModel detail = OntologyDetailModel.ConceptDetailModel.builder()
                .exactMatches(List.of(EXACT_MATCH_IRI))
                .build();
        when(resolutionEngine.resolveAll(anyList(), eq(SearchSource.NKD)))
                .thenReturn(Map.of(EXACT_MATCH_IRI, dto(EXACT_MATCH_IRI, SearchSource.NKD)));

        enricher.enrich(detail, SearchSource.NKD);

        verify(resolutionEngine).resolveAll(List.of(EXACT_MATCH_IRI), SearchSource.NKD);
        assertThat(detail.getReferencedConceptsResolved().get(EXACT_MATCH_IRI).source())
                .isEqualTo(SearchSource.NKD);
    }
}
