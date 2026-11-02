package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_ID;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.CONCEPT_IRI;
import static com.dia.ismdtoolbackend.service.impl.ConceptServiceTestData.GRAPH_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs on a post-edit graph with the fixture's real link detector. A working copy is a locally owned
 * concept whose IRI is in NKD: the detector calls it external, the reconciler treats it as ours.
 */
@ExtendWith(MockitoExtension.class)
class NkdLinkReconcilerTest {

    private static final String NKD_SUPERCLASS = "https://slovník.gov.cz/agendový/104/pojem/nadrazena";
    private static final String FOREIGN = "https://slovník.gov.cz/agendový/999/pojem/foreign";

    private ConceptMetadataRepository conceptMetadataRepository;
    private NkdSparqlClient nkdSparqlClient;
    private NkdSnapshotService nkdSnapshotService;
    private NkdLinkReconciler reconciler;

    @Captor private ArgumentCaptor<List<String>> askedCaptor;

    private ConceptMetadataEntity owner;
    private Model graph;
    private Resource ownerResource;

    @BeforeEach
    void setUp() {
        ConceptServiceFixture fixture = new ConceptServiceFixture();
        conceptMetadataRepository = fixture.conceptMetadataRepository();
        nkdSparqlClient = fixture.nkdSparqlClient();
        nkdSnapshotService = fixture.nkdSnapshotService();
        reconciler = fixture.buildNkdLinkReconciler();

        owner = ConceptServiceTestData.concept();
        graph = ConceptServiceTestData.graphWithConcept();
        ownerResource = graph.getResource(CONCEPT_IRI);
    }

    private void link(Property predicate, String target) {
        graph.add(ownerResource, predicate, graph.createResource(target));
    }

    private void givenLocallyOwned(String... iris) {
        List<ConceptMetadataEntity> owned = Arrays.stream(iris).map(iri -> {
            ConceptMetadataEntity entity = new ConceptMetadataEntity();
            entity.setConceptIri(iri);
            entity.setGraphName(GRAPH_NAME);
            entity.setIsPublished(true);
            return entity;
        }).toList();
        when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(owned);
    }

    private void givenExistingSnapshotOf(String nkdIri, NkdConceptSnapshotEntity snapshot) {
        snapshot.setNkdIri(nkdIri);
        snapshot.setGraphName(GRAPH_NAME);
        when(nkdSnapshotService.findForConcept(CONCEPT_ID)).thenReturn(List.of(snapshot));
    }

    @Nested
    class Snapshots {

        @Test
        void snapshotsAnExternalSuperclassAsBroaderClass() {
            link(RDFS.subClassOf, NKD_SUPERCLASS);

            reconciler.reconcileNkdLinks(owner, graph);

            verify(nkdSnapshotService).createOrRefreshSnapshot(
                    eq(owner), eq(NKD_SUPERCLASS), eq(SnapshotLinkType.BROADER_CLASS.value()), any());
        }

        @Test
        void neverSnapshotsASuperclassInTheOwnerGraph() {
            link(RDFS.subClassOf, GRAPH_NAME + "/pojem/local-parent");

            reconciler.reconcileNkdLinks(owner, graph);

            verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
        }

        @Test
        void neverSnapshotsALocallyOwnedWorkingCopy() {
            link(RDFS.subClassOf, NKD_SUPERCLASS);
            givenLocallyOwned(NKD_SUPERCLASS);

            reconciler.reconcileNkdLinks(owner, graph);

            verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
        }

        @Test
        void removesTheSnapshotOfATargetThatBecameOwned() {
            link(RDFS.subClassOf, NKD_SUPERCLASS);
            givenLocallyOwned(NKD_SUPERCLASS);
            NkdConceptSnapshotEntity stale = new NkdConceptSnapshotEntity();
            givenExistingSnapshotOf(NKD_SUPERCLASS, stale);

            reconciler.reconcileNkdLinks(owner, graph);

            verify(nkdSnapshotService).removeSnapshotAndLink(eq(stale), anySet(), any());
            verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
        }

        @Test
        void removesTheSnapshotOfADroppedLink() {
            NkdConceptSnapshotEntity stale = new NkdConceptSnapshotEntity();
            givenExistingSnapshotOf(NKD_SUPERCLASS, stale);

            reconciler.reconcileNkdLinks(owner, graph);

            verify(nkdSnapshotService).removeSnapshotAndLink(eq(stale), anySet(), any());
            verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
        }
    }

    /** rdfs:domain and rdfs:range may not point at a published NKD concept. */
    @Nested
    class DomainRange {

        @Test
        void rejectsADomainOnAPublishedNkdConcept() {
            link(RDFS.domain, NKD_SUPERCLASS);
            when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of(NKD_SUPERCLASS));

            assertThatThrownBy(() -> reconciler.reconcileNkdLinks(owner, graph))
                    .isInstanceOf(OntologyValidationException.class)
                    .hasMessageContaining(NKD_SUPERCLASS);
            verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
        }

        @Test
        void allowsTheLinkWhenNkdIsDown() {
            link(RDFS.domain, NKD_SUPERCLASS);
            when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenThrow(new RuntimeException("NKD down"));

            OwnerChangeSet delta = reconciler.reconcileNkdLinks(owner, graph);

            assertThat(delta.toAdd).isEmpty();
            assertThat(delta.toRemove).isEmpty();
        }

        @Test
        void allowsALocallyOwnedWorkingCopyWithoutAskingNkd() {
            link(RDFS.domain, NKD_SUPERCLASS);
            givenLocallyOwned(NKD_SUPERCLASS);

            reconciler.reconcileNkdLinks(owner, graph);

            verify(nkdSparqlClient, never()).getPublishedResourcesList(anyList());
        }

        @Test
        void rejectsOnlyTheForeignTargetWhenMixedWithAnOwnedOne() {
            link(RDFS.domain, NKD_SUPERCLASS);
            link(RDFS.range, FOREIGN);
            givenLocallyOwned(NKD_SUPERCLASS);
            when(nkdSparqlClient.getPublishedResourcesList(anyList())).thenReturn(List.of(FOREIGN));

            assertThatThrownBy(() -> reconciler.reconcileNkdLinks(owner, graph))
                    .isInstanceOf(OntologyValidationException.class)
                    .hasMessageContaining(FOREIGN)
                    .hasMessageNotContaining(NKD_SUPERCLASS);

            verify(nkdSparqlClient).getPublishedResourcesList(askedCaptor.capture());
            assertThat(Set.copyOf(askedCaptor.getValue())).containsExactly(FOREIGN);
        }
    }
}
