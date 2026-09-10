package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.diagram.DiagramDto;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.DiagramEdgeKind;
import com.dia.ismdtoolbackend.mapper.DiagramMapper;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.diagram.BackingResolver;
import com.dia.ismdtoolbackend.models.diagram.DiagramPendingEdit;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * The default membership: the VZTAH row only. Membership is what the traversal walks, so a test that
     * expects a hierarchy edge must place its row explicitly via {@link #placedSubclass}. The old
     * "contains() always true" stub cannot be iterated, and it hid the very coupling these tests pin — an
     * edge used to be drawn because RDF supported it, not because a row placed it.
     */
    private static final Set<String> ALL_PLACED = Set.of(REL);

    /** The membership row for one hierarchy link, which a test must place to see it drawn. */
    private static Set<String> placedSubclass(String source, String target) {
        return Set.of(EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, source, target));
    }

    /** A projector over the given live graph, with everything placed and nothing staged. */
    private EdgeProjector projecting(Map<String, ConceptDetailModel> live) {
        return builder(live).build();
    }

    /** A projector whose staged edits are the given (conceptIri -> overlay) pair. */
    private EdgeProjector projectorStaging(String conceptIri, DiagramPendingEdit overlay,
                                           Map<String, ConceptDetailModel> live) {
        return builder(live).overlay(conceptIri, overlay).build();
    }

    private Fixture builder(Map<String, ConceptDetailModel> live) {
        return new Fixture(live);
    }

    /** Assembles a projector; every knob defaults to "placed, nothing staged, no geometry". */
    private static final class Fixture {
        private final Map<String, ConceptDetailModel> live;
        private Map<String, DiagramPendingEdit> overlays = Map.of();
        private Set<String> placed = ALL_PLACED;
        private Set<String> foreign = Set.of();
        private Map<String, List<EdgeWaypoint>> waypoints = Map.of();
        private Set<String> unavailable = Set.of();
        private Map<String, String[]> tombstones = Map.of();

        Fixture(Map<String, ConceptDetailModel> live) {
            this.live = live;
        }

        Fixture overlay(String conceptIri, DiagramPendingEdit overlay) {
            this.overlays = Map.of(conceptIri, overlay);
            return this;
        }

        Fixture placed(Set<String> placed) {
            this.placed = placed;
            return this;
        }

        Fixture foreign(Set<String> foreign) {
            this.foreign = foreign;
            return this;
        }

        Fixture waypoints(Map<String, List<EdgeWaypoint>> waypoints) {
            this.waypoints = waypoints;
            return this;
        }

        Fixture unavailable(Set<String> unavailable) {
            this.unavailable = unavailable;
            return this;
        }

        Fixture tombstone(String edgeKey, String source, String target) {
            this.tombstones = Map.of(edgeKey, new String[]{source, target});
            return this;
        }

        EdgeProjector build() {
            return new EdgeProjector(new DiagramMapper(), waypoints, overlays, foreign, placed,
                    new BackingResolver(live, unavailable), tombstones);
        }
    }

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
        List<DiagramDto.Edge> edges = projecting(live(A, B)).project(
                List.of(node(A), node(B)),
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
        List<DiagramDto.Edge> edges = projecting(live(A, B)).project(
                List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).hasSize(1);
    }

    @Test
    void overlayRangeOverridesLiveAndIsFlaggedPending() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setRange(C);
        // The overlay is keyed by the VZTAH's IRI and supplied to the projector; the VZTAH is not a node.
        List<DiagramDto.Edge> edges = projectorStaging(REL, overlay, live(A, B)).project(
                List.of(node(A), node(B), node(C)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.target()).isEqualTo("iri:" + C);      // overlay wins over the live range
            assertThat(e.data().pending()).isTrue();
        });
    }

    /** Incomplete concepts live off-canvas: no endpoint, no edge. */
    @Test
    void vztahMissingAnEndpoint_isNotDrawn() {
        assertThat(projecting(live(A, null)).project(List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    @Test
    void vztahPointingOffCanvas_isNotDrawn() {
        assertThat(projecting(live(A, "https://x/pojem/off-canvas")).project(List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    // ---- hierarchy / equivalence: bare triples, no backing concept -------------------------------

    @Test
    void subclassEdgeCarriesNoConceptPayload() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = builder(live).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo("edge|SUBCLASS_OF|" + A + "|" + B);
            assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.SUBCLASS_OF);
            assertThat(e.data().iri()).isNull();               // the FE's concept-backed discriminator
            assertThat(e.data().label()).isNull();
        });
    }

    /**
     * A foreign concept is an edge TARGET, never a SOURCE. Its outgoing triples belong to the graph that
     * owns them, and this canvas can neither stage nor reroute them, so drawing one would present another
     * ontology's structure as this diagram's.
     */
    @Test
    void foreignSource_doesNotProjectItsOwnHierarchy() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = builder(live).foreign(Set.of(A)).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).isEmpty();
    }

    /** The converse: a foreign concept as the TARGET of an owned concept's link still draws. */
    @Test
    void foreignTarget_ofAnOwnedSource_stillProjects() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = builder(live).foreign(Set.of(B)).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement()
                .satisfies(e -> assertThat(e.id()).isEqualTo("edge|SUBCLASS_OF|" + A + "|" + B));
    }

    @Test
    void emptyListOverlay_clearsHierarchyEdge() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of());                  // "remove all superclasses"

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        assertThat(builder(live).overlay(A, overlay).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of()))
                .isEmpty();
    }

    /**
     * THE REPORTED BUG. A class gaining a second parent must keep the first: staging {@code [B, C]} on a
     * class whose live broader is {@code [B]} draws both, B settled and C pending.
     *
     * <p>It used to draw only C. The projection chose the overlay's list <em>instead of</em> the live one,
     * so staging any {@code broaderConcept} silently un-drew every parent the overlay did not repeat — an
     * overlay deciding membership, which is the coupling this model forbids.
     */
    @Test
    void stagingASecondParent_keepsTheFirstOneDrawn() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(B, C));

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));

        Set<String> placed = Set.of(
                EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B),
                EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, C));

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(placed).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).hasSize(2);
        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + B)).singleElement()
                .satisfies(e -> assertThat(e.data().pending())
                        .as("B is already in RDF, so it is settled, not pending").isFalse());
        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + C)).singleElement()
                .satisfies(e -> assertThat(e.data().pending())
                        .as("C is staged and not yet asserted").isTrue());
    }

    /**
     * A row for a triple that neither RDF nor an overlay asserts is orphaned — the link is simply not there,
     * and drawing it would invent an edge and label it a pending edit the user never made.
     */
    @Test
    void placedRowForATripleNothingAsserts_isNotDrawn() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));                               // A asserts no broader at all
        live.put(B, concept(B));

        assertThat(builder(live).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of()))
                .isEmpty();
    }

    /**
     * A hierarchy edge whose target was deleted from RDF underneath the canvas keeps its place and is
     * flagged, rather than vanishing. The deletion came from outside; the user did not ask for it and must
     * not lose the edge without being told. The row's own key supplies both endpoints, so there is still
     * something to draw between.
     */
    @Test
    void placedHierarchyEdge_whoseTargetWasDeleted_rendersStale() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        // B is gone from the graph; its node row and the edge row both remain.

        List<DiagramDto.Edge> edges = builder(live).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B));
            assertThat(e.data().stale()).isTrue();
            assertThat(e.data().unavailable()).isFalse();
        });
    }

    /**
     * The other absence: the target's graph could not be read, so it is presumed intact. Never reported as a
     * deletion, which would invite the user to destroy content over a transient upstream failure.
     */
    @Test
    void placedHierarchyEdge_whoseTargetGraphIsUnreadable_rendersUnavailableNotStale() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());

        List<DiagramDto.Edge> edges = builder(live).unavailable(Set.of(B)).placed(placedSubclass(A, B))
                .build().project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.data().unavailable()).isTrue();
            assertThat(e.data().stale()).isFalse();
        });
    }

    /**
     * A deleted VZTAH loses its {@code rdfs:domain} and {@code rdfs:range} with its concept, and its row key
     * is the concept IRI alone — so without the row's tombstone there would be no two ends to draw between
     * and the edge would silently disappear.
     */
    @Test
    void placedVztah_whoseConceptWasDeleted_rendersStaleFromItsTombstone() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, concept(B));
        // REL itself is gone from the graph.

        List<DiagramDto.Edge> edges = builder(live).tombstone(REL, A, B).build()
                .project(List.of(node(A), node(B)), types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(REL);
            assertThat(e.source()).isEqualTo("iri:" + A);
            assertThat(e.target()).isEqualTo("iri:" + B);
            assertThat(e.data().stale()).isTrue();
        });
    }

    /** A placed node with no edge rows draws nothing; membership, not the graph, is what is walked. */
    @Test
    void staleNode_withNoEdgeRows_projectsNoEdges() {
        assertThat(builder(Map.of()).placed(Set.of()).build()
                .project(List.of(node("https://x/pojem/gone")), types(Map.of()), Map.of())).isEmpty();
    }

    // ---- waypoints ------------------------------------------------------------------------------

    @Test
    void persistedWaypointsAreJoinedOntoTheProjectedEdge() {
        EdgeProjector withGeometry = builder(live(A, B))
                .waypoints(Map.of(REL, List.of(new EdgeWaypoint(40, 80)))).build();

        List<DiagramDto.Edge> edges = withGeometry.project(
                List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement()
                .satisfies(e -> assertThat(e.segments()).containsExactly(new EdgeWaypoint(40, 80)));
    }

    /**
     * A repointed edge keeps the row — and therefore the id — it was placed under until materialize rekeys
     * it. The overlay drops B, so the link to B is a staged removal and is not drawn; the link to C is drawn
     * from the row placed under C's id, marked pending because RDF does not assert it yet.
     */
    @Test
    void repointedHierarchyEdge_drawsAtItsNewTargetUnderThatTargetsRow() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(C));                 // repointed from B to C

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(placedSubclass(A, C)).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, C));
            assertThat(e.target()).isEqualTo("iri:" + C);
            assertThat(e.data().pending()).isTrue();
        });
    }

    /** Geometry drawn for an endpoint the edge no longer has must not follow it to the new one. */
    @Test
    void repointedEndpoint_dropsTheSavedWaypoints() {
        String staleKey = EdgeProjector.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B);
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(C));

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));

        List<DiagramDto.Edge> edges = builder(live)
                .overlay(A, overlay)
                .placed(placedSubclass(A, C))
                .waypoints(Map.of(staleKey, List.of(new EdgeWaypoint(40, 80))))
                .build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.target()).isEqualTo("iri:" + C);
            assertThat(e.data().pending()).isTrue();
            assertThat(e.segments()).as("geometry drawn for B does not follow the edge to C").isNull();
        });
    }

    /** Membership governs: a link RDF and the overlay both support is still not drawn without a row. */
    @Test
    void repointDoesNotDrawAHierarchyEdgeThatWasNeverPlaced() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(C));

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));

        assertThat(builder(live).overlay(A, overlay).placed(Set.of()).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of()))
                .isEmpty();
    }

    // ---- VLASTNOST as a row inside its class ----------------------------------------------------

    @Test
    void propertyBecomesARowInsideItsDomainClass_notAnEdge() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(A)
                .name(Map.of("cs", "datum narození")).build());
        Map<String, ConceptType> types = types(Map.of(PROP, ConceptType.VLASTNOST));

        assertThat(projecting(live).project(List.of(node(A)), types, Map.of())).isEmpty();

        Map<String, List<DiagramDto.PropertyRow>> rows = projecting(live)
                .propertyRows(List.of(nodeWith(A, PROP)), types, Map.of(PROP, "x-prop"));

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

        Map<String, List<DiagramDto.PropertyRow>> rows = projecting(live).propertyRows(
                List.of(nodeWith(A, "https://x/pojem/p1", "https://x/pojem/p2")), types, Map.of());

        assertThat(rows.get(A)).extracting(r -> r.label().get("cs"))
                .containsExactly("abeceda", "zebra");
    }

    /**
     * A curated property whose concept was deleted keeps its row inside the class that lists it, flagged.
     * It used to disappear: the traversal ran over live concepts, and a deleted one is not among them, so
     * the class silently lost a row while a deleted class node in the same response was correctly flagged.
     * A deleted property has no PG type and no domain of its own, so the curating node is its fallback home.
     */
    @Test
    void curatedProperty_whoseConceptWasDeleted_rendersStaleRow() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        // PROP is gone from the graph, and with it its PG row: no type, no domain, no label.

        Map<String, List<DiagramDto.PropertyRow>> rows = projecting(live)
                .propertyRows(List.of(nodeWith(A, PROP)), types(Map.of()), Map.of());

        assertThat(rows.get(A)).singleElement().satisfies(r -> {
            assertThat(r.iri()).isEqualTo(PROP);
            assertThat(r.stale()).isTrue();
            assertThat(r.unavailable()).isFalse();
            assertThat(r.label()).as("no live content to show").isNull();
        });
    }

    /** A domainless property has no class to sit in; it is placed by being dragged in from the detail. */
    @Test
    void domainlessProperty_hasNoRow() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).build());

        assertThat(projecting(live).propertyRows(List.of(node(A)),
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of())).isEmpty();
    }

    @Test
    void overlayDomainMovesThePropertyToAnotherClass() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(B);

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(A).build());

        Map<String, List<DiagramDto.PropertyRow>> rows = projectorStaging(PROP, overlay, live)
                .propertyRows(List.of(nodeWith(A, PROP), nodeWith(B, PROP)),
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of());

        assertThat(rows).doesNotContainKey(A);
        assertThat(rows.get(B)).singleElement()
                .satisfies(r -> assertThat(r.hasPendingEdits()).isTrue());
    }
}
