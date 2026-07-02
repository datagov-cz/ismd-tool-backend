package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.LinkSnapshotDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.NkdConceptSnapshotEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.NkdConceptSnapshotRepository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import com.dia.ismdtoolbackend.service.snapshot.OwnerChangeSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NkdSnapshotEndpointServiceImplTest {

    private static final Long CONCEPT_ID = 1L;
    private static final Long SNAPSHOT_ID = 7L;
    private static final String GRAPH = "https://example.org/slovnik/mestys";
    private static final String OWNER_IRI = GRAPH + "/pojem/budova";
    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";

    @Mock private NkdConceptSnapshotRepository snapshotRepository;
    @Mock private NkdSnapshotService nkdSnapshotService;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private OutboxConfig outboxConfig;
    @Mock private OutboxWriter outboxWriter;
    @Mock private OutboxRelayTrigger outboxRelayTrigger;

    @InjectMocks private NkdSnapshotEndpointServiceImpl service;

    private ConceptMetadataEntity owner;
    private NkdConceptSnapshotEntity snapshot;

    @BeforeEach
    void setUp() {
        owner = new ConceptMetadataEntity();
        owner.setId(CONCEPT_ID);
        owner.setConceptIri(OWNER_IRI);
        owner.setGraphName(GRAPH);

        snapshot = new NkdConceptSnapshotEntity();
        snapshot.setNkdIri(NKD_IRI);
        snapshot.setGraphName(GRAPH);
        snapshot.setOrigin(SnapshotOrigin.LINK_TARGET);
        snapshot.setLinkPredicate(SnapshotLinkType.BROADER_CLASS.value());
        snapshot.setOwningConcept(owner);

        when(outboxConfig.isEnabled()).thenReturn(true);
    }

    @Test
    void update_refreshesAndReturnsDto_withOwnerKeyedFlush() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(nkdSnapshotService.createOrRefreshSnapshot(eq(owner), eq(NKD_IRI), anyString(), any()))
                .thenAnswer(inv -> {
                    OwnerChangeSet cs = inv.getArgument(3);
                    cs.toAdd.add(ModelFactory.createDefaultModel().createStatement(
                            ModelFactory.createDefaultModel().createResource(NKD_IRI),
                            org.apache.jena.vocabulary.RDFS.label,
                            "copy"));
                    return snapshot;
                });
        when(nkdSnapshotService.evaluateDeviation(snapshot))
                .thenReturn(PublishedConceptDeviationModel.builder().status(DeviationStatus.HAS_DEVIATIONS).build());

        LinkSnapshotDto dto = service.updateSnapshot(CONCEPT_ID, SNAPSHOT_ID);

        assertThat(dto.getStatus()).isEqualTo(DeviationStatus.HAS_DEVIATIONS);
        // C1: flush is one upsert keyed on the OWNER IRI, never the nkdIri.
        verify(outboxWriter).enqueueUpsert(eq(GRAPH), eq(OWNER_IRI), any(), any());
        verify(outboxRelayTrigger).nudgeAfterCommit();
    }

    @Test
    void update_nkdGone_cascadesUpstreamDeletion_removesLink() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        // Service returns null = NKD confirmed gone; it folded copy-removal into the change set.
        when(nkdSnapshotService.createOrRefreshSnapshot(eq(owner), eq(NKD_IRI), anyString(), any()))
                .thenReturn(null);
        // Owner graph contains the link triple owner -> nkdIri, which the cascade must remove.
        Model g = ModelFactory.createDefaultModel();
        Resource ownerRes = g.getResource(OWNER_IRI);
        g.add(ownerRes, org.apache.jena.vocabulary.RDFS.subClassOf, g.getResource(NKD_IRI));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(g);

        LinkSnapshotDto dto = service.updateSnapshot(CONCEPT_ID, SNAPSHOT_ID);

        assertThat(dto.getStatus()).isEqualTo(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD);
        // Deviation is NOT evaluated for a gone concept (no live compare).
        verify(nkdSnapshotService, never()).evaluateDeviation(any());
        // The link triple removal rides the owner-keyed flush.
        verify(outboxWriter).enqueueUpsert(eq(GRAPH), eq(OWNER_IRI), any(), any());
    }

    @Test
    void remove_unlinksViaOwnerAggregate() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        Model g = ModelFactory.createDefaultModel();
        g.add(g.getResource(OWNER_IRI), org.apache.jena.vocabulary.RDFS.subClassOf, g.getResource(NKD_IRI));
        when(jenaTDB2Repository.fetchGraph(GRAPH)).thenReturn(g);
        // removeSnapshotAndLink contributes to the passed change set so the flush actually fires.
        when(outboxConfig.isEnabled()).thenReturn(true);

        service.removeSnapshot(CONCEPT_ID, SNAPSHOT_ID);

        verify(nkdSnapshotService).removeSnapshotAndLink(eq(snapshot), any(), any());
    }

    @Test
    void load_snapshotNotFound_throws400() {
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateSnapshot(CONCEPT_ID, SNAPSHOT_ID))
                .isInstanceOf(OntologyValidationException.class)
                .hasMessageContaining("nebyla nalezena");
    }

    @Test
    void load_snapshotBelongsToDifferentConcept_throws400() {
        ConceptMetadataEntity otherOwner = new ConceptMetadataEntity();
        otherOwner.setId(999L);
        otherOwner.setConceptIri("https://example.org/slovnik/jiny/pojem/x");
        snapshot.setOwningConcept(otherOwner);
        when(snapshotRepository.findById(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.updateSnapshot(CONCEPT_ID, SNAPSHOT_ID))
                .isInstanceOf(OntologyValidationException.class)
                .hasMessageContaining("nepatří");
        verify(nkdSnapshotService, never()).createOrRefreshSnapshot(any(), anyString(), anyString(), any());
    }
}