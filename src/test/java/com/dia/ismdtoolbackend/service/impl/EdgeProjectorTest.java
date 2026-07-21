package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for edge projection: edges are re-derived from each node's {@code live ⊕ overlay}, never read
 * from storage. Verifies the overlay overrides the live endpoint (flagged {@code pending}), that an edge to
 * a concept not on the canvas is dropped, that a stale (null-detail) node projects nothing, and that an
 * empty-list overlay clears a hierarchy edge.
 */
class EdgeProjectorTest {

    private final EdgeProjector projector = new EdgeProjector(new DiagramMapper());

    private DiagramNodeEntity node(String iri) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setConceptIri(iri);
        return n;
    }

    @Test
    void projectsLiveDomainRange_bothEndpointsOnCanvas() {
        DiagramNodeEntity vztah = node("https://x/pojem/rel");
        DiagramNodeEntity a = node("https://x/pojem/a");
        DiagramNodeEntity b = node("https://x/pojem/b");

        ConceptDetailModel relDetail = ConceptDetailModel.builder()
                .iri("https://x/pojem/rel")
                .domain("https://x/pojem/a")
                .range("https://x/pojem/b")
                .build();
        Map<String, ConceptDetailModel> live = Map.of(
                "https://x/pojem/rel", relDetail,
                "https://x/pojem/a", ConceptDetailModel.builder().iri("https://x/pojem/a").build(),
                "https://x/pojem/b", ConceptDetailModel.builder().iri("https://x/pojem/b").build());

        List<DiagramDto.Edge> edges = projector.project(List.of(vztah, a, b), live);

        assertThat(edges).extracting(e -> e.data().edgeKind())
                .containsExactlyInAnyOrder(DiagramEdgeKind.DOMAIN, DiagramEdgeKind.RANGE);
        assertThat(edges).allMatch(e -> !e.data().pending());       // all live, none pending
    }

    @Test
    void overlayRangeOverridesLiveAndIsFlaggedPending() {
        DiagramNodeEntity vztah = node("https://x/pojem/rel");
        DiagramNodeEntity organizace = node("https://x/pojem/organizace");

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setRange("https://x/pojem/organizace");     // repointed, not yet in RDF
        vztah.setPendingEdit(overlay);

        ConceptDetailModel relDetail = ConceptDetailModel.builder()
                .iri("https://x/pojem/rel")
                .range("https://x/pojem/old-range")          // live range differs from overlay
                .build();
        Map<String, ConceptDetailModel> live = Map.of(
                "https://x/pojem/rel", relDetail,
                "https://x/pojem/organizace",
                ConceptDetailModel.builder().iri("https://x/pojem/organizace").build());

        List<DiagramDto.Edge> edges = projector.project(List.of(vztah, organizace), live);

        DiagramDto.Edge range = edges.stream()
                .filter(e -> e.data().edgeKind() == DiagramEdgeKind.RANGE).findFirst().orElseThrow();
        assertThat(range.target()).isEqualTo("iri:https://x/pojem/organizace");   // overlay wins
        assertThat(range.data().pending()).isTrue();
    }

    @Test
    void edgeToConceptNotOnCanvas_isDropped() {
        DiagramNodeEntity vztah = node("https://x/pojem/rel");
        // domain target 'a' is NOT added as a node → edge must be suppressed
        ConceptDetailModel relDetail = ConceptDetailModel.builder()
                .iri("https://x/pojem/rel")
                .domain("https://x/pojem/off-canvas")
                .build();
        Map<String, ConceptDetailModel> live = Map.of("https://x/pojem/rel", relDetail);

        assertThat(projector.project(List.of(vztah), live)).isEmpty();
    }

    @Test
    void staleNode_projectsNoEdges() {
        DiagramNodeEntity stale = node("https://x/pojem/gone");
        // no live detail for the node → null-detail branch, nothing projected
        assertThat(projector.project(List.of(stale), Map.of())).isEmpty();
    }

    @Test
    void emptyListOverlay_clearsHierarchyEdge() {
        DiagramNodeEntity cls = node("https://x/pojem/child");
        DiagramNodeEntity broader = node("https://x/pojem/broader");

        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of());          // "remove all superclasses"
        cls.setPendingEdit(overlay);

        ConceptDetailModel childDetail = ConceptDetailModel.builder()
                .iri("https://x/pojem/child")
                .broaderClasses(List.of("https://x/pojem/broader"))   // live still has the broader
                .build();
        Map<String, ConceptDetailModel> live = Map.of(
                "https://x/pojem/child", childDetail,
                "https://x/pojem/broader", ConceptDetailModel.builder().iri("https://x/pojem/broader").build());

        // overlay clears broader → no SUBCLASS_OF edge despite the live broader
        assertThat(projector.project(List.of(cls, broader), live)).isEmpty();
    }
}
