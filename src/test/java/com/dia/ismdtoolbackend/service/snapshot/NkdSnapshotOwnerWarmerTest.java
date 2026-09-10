package com.dia.ismdtoolbackend.service.snapshot;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotLinkType;
import com.dia.ismdtoolbackend.outbox.OutboxConfig;
import com.dia.ismdtoolbackend.outbox.OutboxRelayTrigger;
import com.dia.ismdtoolbackend.outbox.OutboxWriter;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.NkdSnapshotService;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The warm path's obligation to keep {@code concepts.updated_at} in step with the owner RDF it writes
 * (finding M4). {@code updated_at} is the stale-base fingerprint a staged diagram overlay is validated
 * against, and all four {@link SnapshotLinkType} values map onto stageable overlay fields — so a warm that
 * changes RDF while leaving the column frozen is a live STALE_BASE blind spot.
 *
 * <p>{@link NkdSnapshotWarmerTest} mocks this bean out entirely, so these are the only tests that reach
 * {@code warmOwner}'s body.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NkdSnapshotOwnerWarmerTest {

    private static final String GRAPH = "https://example.org/slovnik/mestys";
    private static final String OWNER_IRI = GRAPH + "/pojem/budova";
    private static final String NKD_IRI = "https://slovník.gov.cz/agendový/104/pojem/adresní-místo";

    @Mock private NkdSnapshotService nkdSnapshotService;
    @Mock private OutboxConfig outboxConfig;
    @Mock private OutboxWriter outboxWriter;
    @Mock private OutboxRelayTrigger outboxRelayTrigger;
    @Mock private JenaTDB2Repository jenaTDB2Repository;
    @Mock private ConceptMetadataRepository conceptMetadataRepository;

    @InjectMocks private NkdSnapshotOwnerWarmer warmer;

    private ConceptMetadataEntity owner;

    @BeforeEach
    void setUp() {
        owner = new ConceptMetadataEntity();
        owner.setId(1L);
        owner.setConceptIri(OWNER_IRI);
        owner.setGraphName(GRAPH);
        when(outboxConfig.isEnabled()).thenReturn(true);
    }

    private List<NkdSnapshotOwnerWarmer.Target> oneTarget() {
        return List.of(new NkdSnapshotOwnerWarmer.Target(NKD_IRI, SnapshotLinkType.BROADER_CLASS.value()));
    }

    /** A first-time materialization produces a delta — the fingerprint must move with it. */
    @Test
    void warmOwner_stampsOwnerUpdatedAt_whenRdfActuallyChanged() {
        LocalDateTime before = LocalDateTime.now().minusDays(1);
        owner.setUpdatedAt(before);
        doAnswer(inv -> {
            OwnerChangeSet cs = inv.getArgument(3);
            cs.toAdd.add(ModelFactory.createDefaultModel().createStatement(
                    ModelFactory.createDefaultModel().createResource(OWNER_IRI),
                    org.apache.jena.vocabulary.RDFS.subClassOf,
                    ModelFactory.createDefaultModel().createResource(NKD_IRI)));
            return null;
        }).when(nkdSnapshotService).refreshOrSeedForWarming(eq(owner), eq(NKD_IRI), anyString(), any());

        boolean delta = warmer.warmOwner(GRAPH, owner, oneTarget());

        assertThat(delta).isTrue();
        verify(outboxWriter).enqueueUpsert(eq(GRAPH), eq(OWNER_IRI), any(), any());
        verify(conceptMetadataRepository).save(owner);
        assertThat(owner.getUpdatedAt())
                .as("stale-base fingerprint moves with the RDF the warm just wrote").isAfter(before);
    }

    /** The common case: re-evaluating an existing copy writes no RDF, so nothing is stamped. */
    @Test
    void warmOwner_noDelta_leavesOwnerUpdatedAtAlone() {
        LocalDateTime before = LocalDateTime.now().minusDays(1);
        owner.setUpdatedAt(before);
        // refreshOrSeedForWarming contributes nothing to the change set (existing copy, re-evaluated only).

        boolean delta = warmer.warmOwner(GRAPH, owner, oneTarget());

        assertThat(delta).isFalse();
        verify(outboxWriter, never()).enqueueUpsert(anyString(), anyString(), any(), any());
        verify(conceptMetadataRepository, never()).save(any());
        assertThat(owner.getUpdatedAt())
                .as("a read that changes no RDF must not invalidate a staged overlay").isEqualTo(before);
    }
}