package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramLayoutDto;
import com.dia.ismdtoolbackend.controller.dto.diagram.PositionDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEdgeEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.DiagramPendingEditEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.DiagramPendingEditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit companion to {@code diagram/DiagramLayoutReconcilerTest} (real Postgres): the pure decision logic of
 * a save — duplicate entries, op-6 graph checks, parent resolution and edge-id validation — with the two
 * repositories mocked.
 */
@ExtendWith(MockitoExtension.class)
class DiagramLayoutReconcilerUnitTest {

    private static final String GRAPH = "https://x/slovnik";
    private static final String FOREIGN_GRAPH = "https://x/cizi";
    private static final String A = "https://x/pojem/a";
    private static final String B = "https://x/pojem/b";
    private static final String REL = "https://x/pojem/rel";
    private static final PositionDto ORIGIN = new PositionDto(0.0, 0.0);

    @Mock private ConceptMetadataRepository conceptMetadataRepository;
    @Mock private DiagramPendingEditRepository pendingEditRepository;

    private DiagramLayoutReconciler reconciler;
    private DiagramEntity diagram;

    @BeforeEach
    void setUp() {
        reconciler = new DiagramLayoutReconciler(new DiagramMapper(), conceptMetadataRepository,
                pendingEditRepository);
        OntologyMetadataEntity ontology = new OntologyMetadataEntity();
        ontology.setGraphName(GRAPH);
        diagram = new DiagramEntity();
        diagram.setId(5L);
        diagram.setOntologyMetadata(ontology);
    }

    private static ConceptMetadataEntity concept(String iri, String graph) {
        ConceptMetadataEntity c = new ConceptMetadataEntity();
        c.setConceptIri(iri);
        c.setGraphName(graph);
        return c;
    }

    private static DiagramLayoutDto.Node node(String iri, String parentId) {
        return new DiagramLayoutDto.Node("iri:" + iri, ORIGIN, parentId, null, null);
    }

    private static DiagramLayoutDto layout(List<DiagramLayoutDto.Node> nodes, List<DiagramLayoutDto.Edge> edges,
                                           List<DiagramLayoutDto.Overlay> overlays) {
        return new DiagramLayoutDto(0L, null, nodes, edges, overlays);
    }

    private static DiagramLayoutDto.Overlay convert(String iri, String addBroaderOn, String broader) {
        return new DiagramLayoutDto.Overlay(iri, null, null, null, null,
                new DiagramLayoutDto.Overlay.ConvertToHierarchy(addBroaderOn, broader));
    }

    private void finalizeEdges(DiagramLayoutDto.Edge... edges) {
        reconciler.finalizeLayout(diagram, layout(List.of(), List.of(edges), null), Map.of());
    }

    // ---- nodes and overlays ---------------------------------------------------------------------

    @Test
    void emptyCanvas_issuesNoConceptLookup() {
        diagram.addNode(new DiagramNodeEntity());
        diagram.getNodes().get(0).setConceptIri(A);

        Map<String, DiagramNodeEntity> incoming = reconciler.reconcileNodes(diagram, layout(List.of(), null, null));

        assertThat(incoming).isEmpty();
        assertThat(diagram.getNodes()).as("membership is a full replace").isEmpty();
        verifyNoInteractions(conceptMetadataRepository, pendingEditRepository);
    }

    @Test
    void duplicateOverlayEntry_isStagedOnce_firstWins() {
        when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of(concept(REL, GRAPH)));
        when(pendingEditRepository.findByDiagramId(5L)).thenReturn(List.of());

        reconciler.reconcileNodes(diagram, layout(List.of(), null, List.of(
                new DiagramLayoutDto.Overlay(REL, null, A, null, null, null),
                new DiagramLayoutDto.Overlay("iri:" + REL, null, B, null, null, null))));

        ArgumentCaptor<DiagramPendingEditEntity> saved = ArgumentCaptor.forClass(DiagramPendingEditEntity.class);
        verify(pendingEditRepository).save(saved.capture());
        assertThat(saved.getValue().getPendingEdit().getRange()).isEqualTo(A);
    }

    /** Op 6 edits {@code addBroaderOn}, so a foreign one is rejected at stage time. */
    @Test
    void convertOntoAForeignClass_isRejectedAtStageTime() {
        when(conceptMetadataRepository.findByConceptIriIn(anyList()))
                .thenReturn(List.of(concept(REL, GRAPH), concept(B, FOREIGN_GRAPH)));
        when(pendingEditRepository.findByDiagramId(5L)).thenReturn(List.of());

        DiagramLayoutDto save = layout(List.of(), null, List.of(convert(REL, B, A)));

        assertThatThrownBy(() -> reconciler.reconcileNodes(diagram, save))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining(B);
        verify(pendingEditRepository, never()).save(any());
    }

    /** {@code broader} is only referenced, so it is neither prefetched nor checked. */
    @Test
    void convertOntoOwnClass_stagesTheMarker_andPrefetchesOnlyWrittenIris() {
        when(conceptMetadataRepository.findByConceptIriIn(anyList()))
                .thenReturn(List.of(concept(REL, GRAPH), concept(B, GRAPH)));
        when(pendingEditRepository.findByDiagramId(5L)).thenReturn(List.of());

        reconciler.reconcileNodes(diagram, layout(List.of(), null, List.of(convert(REL, B, A))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> prefetched = ArgumentCaptor.forClass(List.class);
        verify(conceptMetadataRepository).findByConceptIriIn(prefetched.capture());
        assertThat(prefetched.getValue()).containsExactlyInAnyOrder(REL, B);
        ArgumentCaptor<DiagramPendingEditEntity> saved = ArgumentCaptor.forClass(DiagramPendingEditEntity.class);
        verify(pendingEditRepository).save(saved.capture());
        assertThat(saved.getValue().getPendingEdit().getConvertToHierarchy().getAddBroaderOn()).isEqualTo(B);
    }

    @Test
    void parentOffTheCanvas_resolvesToNoParent() {
        when(conceptMetadataRepository.findByConceptIriIn(anyList())).thenReturn(List.of());
        DiagramLayoutDto save = layout(List.of(node(A, "iri:" + B)), null, null);

        Map<String, DiagramNodeEntity> incoming = reconciler.reconcileNodes(diagram, save);
        incoming.get(A).setParentNodeId(99L);
        reconciler.finalizeLayout(diagram, save, incoming);

        assertThat(incoming.get(A).getParentNodeId()).isNull();
    }

    // ---- edges ----------------------------------------------------------------------------------

    @Test
    void duplicateEdgeEntry_keepsTheFirstSegments() {
        String key = DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B);

        finalizeEdges(new DiagramLayoutDto.Edge(key, List.of(new EdgeWaypoint(1, 2))),
                new DiagramLayoutDto.Edge(key, List.of(new EdgeWaypoint(3, 4))));

        assertThat(diagram.getEdges()).singleElement().satisfies(e -> {
            assertThat(e.getSegments()).containsExactly(new EdgeWaypoint(1, 2));
            assertThat(e.getLastKnownSource()).isEqualTo(A);
            assertThat(e.getLastKnownTarget()).isEqualTo(B);
        });
    }

    /** A VZTAH's key encodes no endpoints, so a save naming only one keeps the last known pair. */
    @Test
    void vztahEdgeWithOnlyOneEndpoint_keepsTheLastKnownPair() {
        DiagramEdgeEntity row = new DiagramEdgeEntity();
        row.setEdgeKey(REL);
        row.setLastKnownSource(A);
        row.setLastKnownTarget(B);
        diagram.addEdge(row);

        finalizeEdges(new DiagramLayoutDto.Edge(REL, DiagramEdgeKind.VZTAH, "iri:https://x/pojem/c", null, null));

        assertThat(diagram.getEdges()).singleElement().satisfies(e -> {
            assertThat(e.getLastKnownSource()).isEqualTo(A);
            assertThat(e.getLastKnownTarget()).isEqualTo(B);
        });
    }

    @Test
    void vztahEdgeWithBothEndpoints_recordsThemAsConceptIris() {
        finalizeEdges(new DiagramLayoutDto.Edge("http://x/pojem/rel", DiagramEdgeKind.VZTAH,
                "iri:" + A, "iri:" + B, null));

        assertThat(diagram.getEdges()).singleElement().satisfies(e -> {
            assertThat(e.getEdgeKey()).as("an http:// IRI is a valid VZTAH id").isEqualTo("http://x/pojem/rel");
            assertThat(e.getLastKnownSource()).isEqualTo(A);
            assertThat(e.getLastKnownTarget()).isEqualTo(B);
        });
    }

    @Test
    void hierarchyEdgeKeyedByABareIri_isRejected() {
        DiagramLayoutDto.Edge edge = new DiagramLayoutDto.Edge(A, DiagramEdgeKind.SUBCLASS_OF, A, B, null);

        assertThatThrownBy(() -> finalizeEdges(edge))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining("SUBCLASS_OF")
                .hasMessageContaining("složený");
        assertThat(diagram.getEdges()).isEmpty();
    }

    @Test
    void opaqueClientId_isRejected() {
        DiagramLayoutDto.Edge edge = new DiagramLayoutDto.Edge("reactflow-1234", null);

        assertThatThrownBy(() -> finalizeEdges(edge))
                .isInstanceOf(ConceptValidationException.class)
                .hasMessageContaining("reactflow-1234");
    }
}
