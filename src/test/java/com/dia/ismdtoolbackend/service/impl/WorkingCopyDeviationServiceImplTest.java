package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto;
import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single source of truth for a working copy's deviation. Both ontology detail and concept detail
 * route here, so this is where the comparison behaviour (match / not-found / endpoint-down) and the
 * canonical local read live. The @Cacheable behaviour itself is a Spring concern verified elsewhere;
 * these are the plain-logic tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkingCopyDeviationServiceImplTest {

    private static final String IRI = "https://slovník.gov.cz/test/pojem/obec";
    private static final String GRAPH = "https://slovník.gov.cz/test";

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private OntologyDetailExtractor detailExtractor;
    @Mock private ConceptDeviationComparator conceptDeviationComparator;
    @Mock private NkdSparqlClient nkdSparqlClient;
    @Mock private ReferencedConceptResolutionEngine resolutionEngine;
    @Mock private com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder rppSnapshotHolder;

    private WorkingCopyDeviationServiceImpl service;

    private ConceptDetailModel local;

    private WorkingCopyDeviationServiceImpl newService(WorkingCopyDeviationServiceImpl self) {
        return new WorkingCopyDeviationServiceImpl(conceptMetadataRepository, jenaTDB2Repository,
                detailExtractor, conceptDeviationComparator, nkdSparqlClient,
                resolutionEngine, rppSnapshotHolder, self);
    }

    @BeforeEach
    void setUp() {
        // Pass the instance as its own `self` — in production Spring injects the @Lazy proxy so
        // canonicalLocalConcept is cached; the unit test just needs the internal call to resolve. Build a
        // throwaway first so the real instance can reference itself.
        service = newService(null);
        service = newService(service);

        ConceptMetadataEntity metadata = new ConceptMetadataEntity();
        metadata.setConceptIri(IRI);
        metadata.setGraphName(GRAPH);
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.of(metadata));

        Model rawModel = ModelFactory.createDefaultModel();
        // A bare resource adds no statement; add one so rawModel.isEmpty() is false.
        rawModel.add(rawModel.createResource(IRI),
                org.apache.jena.vocabulary.RDF.type,
                rawModel.createResource("http://www.w3.org/2002/07/owl#Class"));
        Model processedModel = ModelFactory.createDefaultModel();
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(rawModel);
        when(detailExtractor.applyOFNTransformations(rawModel)).thenReturn(processedModel);

        local = ConceptDetailModel.builder().iri(IRI).build();
        when(detailExtractor.extractConceptDetail(processedModel, IRI)).thenReturn(local);
    }

    @Test
    void canonicalLocal_readsOfnTransformedModel() {
        ConceptDetailModel result = service.canonicalLocalConcept(IRI);

        assertThat(result).isSameAs(local);
        // The canonical read is the OFN-transformed one — the divergence source between the two surfaces.
        verify(detailExtractor).applyOFNTransformations(any());
        verify(detailExtractor).extractConceptDetail(any(), any());
    }

    @Test
    void canonicalLocal_noMetadataRow_returnsNull() {
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.empty());
        assertThat(service.canonicalLocalConcept(IRI)).isNull();
    }

    @Test
    void deviation_nkdMatches_runsComparatorWithWorkingCopyOrigin() {
        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));
        PublishedConceptDeviationModel expected = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.NO_DEVIATION).build();
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(expected);

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result).isSameAs(expected);
        verify(conceptDeviationComparator).compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI);
    }

    @Test
    void deviation_enrichesResolvedCharacteristics_bothDiffSides() {
        String domainLocal = "https://slovník.gov.cz/a/pojem/mistni-domena";
        String domainPublished = "https://slovník.gov.cz/a/pojem/nkd-domena";
        String rangeConcept = "https://slovník.gov.cz/a/pojem/cilova-trida";
        String agendaIri = "https://rpp/agenda/A123";
        String aisIri = "https://rpp/isvs/S456";

        ConceptDetailModel published = ConceptDetailModel.builder().iri(IRI).build();
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.of(published));

        // The comparator's output carries the diffs; the service resolves the IRIs on top.
        PublishedConceptDeviationModel deviation = PublishedConceptDeviationModel.builder()
                .status(DeviationStatus.HAS_DEVIATIONS)
                .source(NkdConceptRefDto.builder().iri(IRI).build())
                .domain(diff(domainLocal, domainPublished))
                .range(diff("xsd:string", rangeConcept))      // one datatype side, one concept side
                .agenda(diff(agendaIri, agendaIri))
                .ais(diff(aisIri, null))
                .build();
        when(conceptDeviationComparator.compareConceptDetails(local, published, SnapshotOrigin.WORKING_COPY, IRI))
                .thenReturn(deviation);

        // Concept resolution covers source + both domains + the concept-typed range value.
        when(resolutionEngine.resolveAll(anyList())).thenReturn(Map.of(
                IRI, resolvedConcept(IRI),
                domainLocal, resolvedConcept(domainLocal),
                domainPublished, resolvedConcept(domainPublished),
                rangeConcept, resolvedConcept(rangeConcept)));
        RppAgenda agenda = new com.dia.ismdtoolbackend.models.rpp.RppAgenda();
        agenda.setIri(agendaIri);
        when(rppSnapshotHolder.findAgendaByIri(agendaIri)).thenReturn(Optional.of(agenda));
        com.dia.ismdtoolbackend.models.rpp.RppIsvs isvs = new RppIsvs();
        isvs.setIri(aisIri);
        when(rppSnapshotHolder.findIsvsByIri(aisIri)).thenReturn(Optional.of(isvs));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        // Concept refs: source + both domain sides + the concept-typed range side, keyed by IRI.
        assertThat(result.getReferencedConceptsResolved())
                .containsKeys(IRI, domainLocal, domainPublished, rangeConcept);
        // The datatype side of range resolves to a DataTypeDto, not a concept.
        assertThat(result.getRangeResolved()).containsKey("xsd:string");
        assertThat(result.getReferencedConceptsResolved()).doesNotContainKey("xsd:string");
        // RPP maps, keyed by IRI, only the sides that were present.
        assertThat(result.getAgendaResolved()).containsKey(agendaIri);
        assertThat(result.getAisResolved()).containsKey(aisIri);
    }

    private static PublishedConceptDeviationModel.PropertyDeviation<String> diff(String local, String published) {
        return PublishedConceptDeviationModel.PropertyDeviation.<String>builder()
                .localValue(local).publishedValue(published).isDifferent(true).build();
    }

    private static ResolvedConceptDto resolvedConcept(String iri) {
        return ResolvedConceptDto.builder().iri(iri).build();
    }

    @Test
    void deviation_notInNkd_returnsConceptNotFound_noComparison() {
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenReturn(Optional.empty());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        assertThat(result.getOrigin()).isEqualTo(SnapshotOrigin.WORKING_COPY);
        assertThat(result.getSource().getIri()).isEqualTo(IRI);
        verify(conceptDeviationComparator, never()).compareConceptDetails(any(), any(), any(), any());
    }

    @Test
    void deviation_nkdThrows_returnsEndpointUnavailable() {
        when(nkdSparqlClient.fetchPublishedConcept(IRI)).thenThrow(new RuntimeException("NKD timeout"));

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.ENDPOINT_UNAVAILABLE);
    }

    @Test
    void deviation_localMissing_returnsQueryError() {
        when(conceptMetadataRepository.findByConceptIri(IRI)).thenReturn(Optional.empty());

        PublishedConceptDeviationModel result = service.deviationFor(IRI);

        assertThat(result.getStatus()).isEqualTo(DeviationStatus.QUERY_ERROR);
        verify(nkdSparqlClient, never()).fetchPublishedConcept(any());
    }
}
