package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for edge and property-row projection. An edge's existence, kind and endpoints are re-derived
 * from {@code live ⊕ overlay}, never read from storage; only waypoints are joined on by projected edge id.
 *
 * <p>Pins the model: a VZTAH is ONE edge between its two classes carrying its own concept identity, a
 * VLASTNOST is a row inside its domain class, and a concept missing an endpoint is simply not drawn.
 */
class EdgeProjectorTest {

    private static final String REL = "https://x/pojem/rel";
    private static final String A = "https://x/pojem/a";
    private static final String B = "https://x/pojem/b";
    private static final String C = "https://x/pojem/c";
    private static final String PROP = "https://x/pojem/prop";

    private final EdgeProjector projector = new EdgeProjector(new DiagramMapper(), Map.of());

    private DiagramNodeEntity node(String iri) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setConceptIri(iri);
        return n;
    }

    /**
     * A class node that renders the given property rows. Rows are curated — the canvas is a subset of the
     * ontology — so a class renders a property only once the user has placed it here.
     */
    private DiagramNodeEntity nodeWith(String iri, String... visibleProperties) {
        DiagramNodeEntity n = node(iri);
        n.setVisibleProperties(List.of(visibleProperties));
        return n;
    }

    private ConceptDetailModel concept(String iri) {
        return ConceptDetailModel.builder().iri(iri).build();
    }

    /** A VZTAH from {@code domain} to {@code range}, plus classes a/b/c, all present in the graph. */
    private Map<String, ConceptDetailModel> live(String domain, String range) {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(REL, ConceptDetailModel.builder().iri(REL).domain(domain).range(range).build());
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(C, concept(C));
        return live;
    }

    private Map<String, ConceptType> types(Map<String, ConceptType> extra) {
        Map<String, ConceptType> types = new HashMap<>(Map.of(
                A, ConceptType.TRIDA, B, ConceptType.TRIDA, C, ConceptType.TRIDA));
        types.putAll(extra);
        return types;
    }

    // ---- VZTAH as a single edge -----------------------------------------------------------------

    @Test
    void vztahProjectsOneEdgeBetweenItsClasses_carryingItsOwnIdentity() {
        List<DiagramDto.Edge> edges = projector.project(
                List.of(node(A), node(B)), live(A, B),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of(REL, "x-rel"));

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(REL);                  // the concept IRI IS the edge id
            assertThat(e.source()).isEqualTo("iri:" + A);
            assertThat(e.target()).isEqualTo("iri:" + B);
            assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.VZTAH);
            assertThat(e.data().iri()).isEqualTo(REL);
            assertThat(e.data().slug()).isEqualTo("x-rel");
            assertThat(e.data().pending()).isFalse();
        });
    }

    /** The relationship itself need not be a canvas node — only its two endpoint classes. */
    @Test
    void vztahProjects_evenThoughItIsNotItselfANode() {
        List<DiagramDto.Edge> edges = projector.project(
                List.of(node(A), node(B)), live(A, B),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).hasSize(1);
    }

    @Test
    void overlayRangeOverridesLiveAndIsFlaggedPending() {
        DiagramNodeEntity a = node(A);
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setRange(C);
        // the overlay lives on the VZTAH, which is not a node — staged via its own row
        DiagramNodeEntity relRow = node(REL);
        relRow.setPendingEdit(overlay);

        List<DiagramDto.Edge> edges = projector.project(
                List.of(a, node(B), node(C), relRow), live(A, B),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.target()).isEqualTo("iri:" + C);      // overlay wins over the live range
            assertThat(e.data().pending()).isTrue();
        });
    }

    /** Incomplete concepts live off-canvas: no endpoint, no edge. */
    @Test
    void vztahMissingAnEndpoint_isNotDrawn() {
        Map<String, ConceptDetailModel> live = live(A, null);

        assertThat(projector.project(List.of(node(A), node(B)), live,
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    @Test
    void vztahPointingOffCanvas_isNotDrawn() {
        Map<String, ConceptDetailModel> live = live(A, "https://x/pojem/off-canvas");

        assertThat(projector.project(List.of(node(A), node(B)), live,
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    // ---- hierarchy / equivalence: bare triples, no backing concept -------------------------------

    @Test
    void subclassEdgeCarriesNoConceptPayload() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = projector.project(
                List.of(node(A), node(B)), live, types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("edge|SUBCLASS_OF|" + A + "|" + B);
            assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.SUBCLASS_OF);
            assertThat(e.data().iri()).isNull();               // the FE's concept-backed discriminator
            assertThat(e.data().label()).isNull();
        });
    }

    @Test
    void emptyListOverlay_clearsHierarchyEdge() {
        DiagramNodeEntity child = node(A);
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of());                  // "remove all superclasses"
        child.setPendingEdit(overlay);

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        assertThat(projector.project(List.of(child, node(B)), live, types(Map.of()), Map.of()))
                .isEmpty();
    }

    @Test
    void staleNode_projectsNoEdges() {
        assertThat(projector.project(List.of(node("https://x/pojem/gone")), Map.of(),
                types(Map.of()), Map.of())).isEmpty();
    }

    // ---- waypoints ------------------------------------------------------------------------------

    @Test
    void persistedWaypointsAreJoinedOntoTheProjectedEdge() {
        EdgeProjector withGeometry = new EdgeProjector(new DiagramMapper(),
                Map.of(REL, List.of(new EdgeWaypoint(40, 80))));

        List<DiagramDto.Edge> edges = withGeometry.project(
                List.of(node(A), node(B)), live(A, B),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement()
                .satisfies(e -> assertThat(e.segments()).containsExactly(new EdgeWaypoint(40, 80)));
    }

    /** Geometry drawn for an endpoint the edge no longer has must not follow it to the new one. */
    @Test
    void repointedEndpoint_dropsTheSavedWaypoints() {
        String staleKey = EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B);
        EdgeProjector withGeometry = new EdgeProjector(new DiagramMapper(),
                Map.of(staleKey, List.of(new EdgeWaypoint(40, 80))));

        DiagramNodeEntity child = node(A);
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(C));                 // repointed from B to C
        child.setPendingEdit(overlay);

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));

        List<DiagramDto.Edge> edges = withGeometry.project(
                List.of(child, node(B), node(C)), live, types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.target()).isEqualTo("iri:" + C);
            assertThat(e.data().pending()).isTrue();
            assertThat(e.segments()).isNull();
        });
    }

    // ---- VLASTNOST as a row inside its class ----------------------------------------------------

    @Test
    void propertyBecomesARowInsideItsDomainClass_notAnEdge() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(A)
                .name(Map.of("cs", "datum narození")).build());
        Map<String, ConceptType> types = types(Map.of(PROP, ConceptType.VLASTNOST));

        assertThat(projector.project(List.of(node(A)), live, types, Map.of())).isEmpty();

        Map<String, List<DiagramDto.PropertyRow>> rows =
                projector.propertyRows(List.of(nodeWith(A, PROP)), live, types, Map.of(PROP, "x-prop"));

        assertThat(rows.get(A)).singleElement().satisfies(r -> {
            assertThat(r.iri()).isEqualTo(PROP);
            assertThat(r.slug()).isEqualTo("x-prop");
            assertThat(r.label()).containsEntry("cs", "datum narození");
        });
    }

    @Test
    void propertyRowsAreOrderedByLabel() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put("https://x/pojem/p1", ConceptDetailModel.builder().iri("https://x/pojem/p1")
                .domain(A).name(Map.of("cs", "zebra")).build());
        live.put("https://x/pojem/p2", ConceptDetailModel.builder().iri("https://x/pojem/p2")
                .domain(A).name(Map.of("cs", "abeceda")).build());
        Map<String, ConceptType> types = types(Map.of(
                "https://x/pojem/p1", ConceptType.VLASTNOST,
                "https://x/pojem/p2", ConceptType.VLASTNOST));

        Map<String, List<DiagramDto.PropertyRow>> rows = projector.propertyRows(
                List.of(nodeWith(A, "https://x/pojem/p1", "https://x/pojem/p2")), live, types, Map.of());

        assertThat(rows.get(A)).extracting(r -> r.label().get("cs"))
                .containsExactly("abeceda", "zebra");
    }

    /** A domainless property has no class to sit in; it is placed by being dragged in from the detail. */
    @Test
    void domainlessProperty_hasNoRow() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).build());

        assertThat(projector.propertyRows(List.of(node(A)), live,
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of())).isEmpty();
    }

    @Test
    void overlayDomainMovesThePropertyToAnotherClass() {
        DiagramNodeEntity propRow = node(PROP);
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(B);
        propRow.setPendingEdit(overlay);

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(A).build());

        Map<String, List<DiagramDto.PropertyRow>> rows = projector.propertyRows(
                List.of(nodeWith(A, PROP), nodeWith(B, PROP), propRow), live,
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of());

        assertThat(rows).doesNotContainKey(A);
        assertThat(rows.get(B)).singleElement()
                .satisfies(r -> assertThat(r.hasPendingEdits()).isTrue());
    }
}
