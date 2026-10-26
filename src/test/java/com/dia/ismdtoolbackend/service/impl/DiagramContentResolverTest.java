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
class DiagramContentResolverTest {

    private static final String REL = "https://x/pojem/rel";
    private static final String A = "https://x/pojem/a";
    private static final String B = "https://x/pojem/b";
    private static final String C = "https://x/pojem/c";
    private static final String D = "https://x/pojem/d";
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
        return Set.of(DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, source, target));
    }

    /** A projector over the given live graph, with everything placed and nothing staged. */
    private DiagramContentResolver resolving(Map<String, ConceptDetailModel> live) {
        return builder(live).build();
    }

    /** A projector whose staged edits are the given (conceptIri -> overlay) pair. */
    private DiagramContentResolver resolverStaging(String conceptIri, DiagramPendingEdit overlay,
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

        Fixture overlays(Map<String, DiagramPendingEdit> overlays) {
            this.overlays = overlays;
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

        DiagramContentResolver build() {
            return new DiagramContentResolver(new DiagramMapper(), waypoints, overlays, foreign, placed,
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
        List<DiagramDto.Edge> edges = resolving(live(A, B)).project(
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
            assertThat(e.data().asserted())
                    .as("a VZTAH exists because its concept does; absence is reported as stale instead")
                    .isTrue();
        });
    }

    /** The relationship itself need not be a canvas node — only its two endpoint classes. */
    @Test
    void vztahProjects_evenThoughItIsNotItselfANode() {
        List<DiagramDto.Edge> edges = resolving(live(A, B)).project(
                List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).hasSize(1);
    }

    @Test
    void overlayRangeOverridesLiveAndIsFlaggedPending() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setRange(C);
        // The overlay is keyed by the VZTAH's IRI and supplied to the projector; the VZTAH is not a node.
        List<DiagramDto.Edge> edges = resolverStaging(REL, overlay, live(A, B)).project(
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
        assertThat(resolving(live(A, null)).project(List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    @Test
    void vztahPointingOffCanvas_isNotDrawn() {
        assertThat(resolving(live(A, "https://x/pojem/off-canvas")).project(List.of(node(A), node(B)),
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

    /**
     * A staged clear does not un-draw a placed edge: membership alone decides that. Removal reaches us as
     * the row's omission from {@code edges[]} — the same client state change that stages the clear — so the
     * row's continued presence means the edge is still on the canvas, and the overlay only annotates it.
     *
     * <p>This used to assert the opposite. The read-time suppression it guarded also discarded hierarchy
     * edges that RDF fully asserted, because that gate ran before the live targets were consulted; see
     * {@code .planning/diagram-hierarchy-edge-projection-FINDINGS.md}.
     */
    @Test
    void emptyListOverlay_doesNotUnDrawAPlacedHierarchyEdge() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of());                  // "remove all superclasses"

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).as("the row still places the edge; the staged clear applies at materialize")
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.data().pending())
                            .as("RDF still asserts the link, so it is settled rather than pending").isFalse();
                    assertThat(e.data().asserted())
                            .as("the clear has not been materialized yet, so the triple is still there")
                            .isTrue();
                });
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
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, C));

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(placed).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).hasSize(2);
        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + B)).singleElement()
                .satisfies(e -> {
                    assertThat(e.data().pending())
                            .as("B is already in RDF, so it is settled, not pending").isFalse();
                    assertThat(e.data().asserted()).as("B is in RDF").isTrue();
                });
        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + C)).singleElement()
                .satisfies(e -> {
                    assertThat(e.data().pending())
                            .as("C is staged and not yet asserted").isTrue();
                    assertThat(e.data().asserted()).as("C is not in RDF yet").isFalse();
                });
    }

    /**
     * Op 6's marker sits on the VZTAH, not on the class gaining the parent, so looking up only the source's
     * own overlay reported the converted edge as neither pending nor asserted — drawn, backed by nothing.
     */
    @Test
    void hierarchyEdgeStagedByAConversion_isPending() {
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(B);
        marker.setBroader(A);
        DiagramPendingEdit convert = new DiagramPendingEdit();
        convert.setConvertToHierarchy(marker);

        List<DiagramDto.Edge> edges = builder(live(A, B)).overlay(REL, convert).placed(placedSubclass(B, A))
                .build()
                .project(List.of(node(A), node(B)), types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.data().pending()).as("the conversion will write B ⊂ A").isTrue();
            assertThat(e.data().asserted()).as("not in RDF until Převzít").isFalse();
        });
    }

    /**
     * A newly drawn edge renders from its row alone, before anything asserts the triple. The user put it on
     * the canvas; that is what a row means. RDF says only whether the link is settled yet.
     *
     * <p>This used to assert the opposite — a row nothing asserted was discarded as orphaned — which meant a
     * hierarchy edge drawn without an accompanying overlay never appeared at all.
     */
    @Test
    void placedRowForATripleNothingAssertsYet_isStillDrawn() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));                               // A asserts no broader at all
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = builder(live).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(
                    DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B));
            assertThat(e.data().pending())
                    .as("nothing asserts or stages it, so it is drawn but not marked pending").isFalse();
            assertThat(e.data().asserted())
                    .as("and nothing backs it in RDF either — the flag that says so").isFalse();
        });
    }

    /**
     * A settled edge and one whose triple was deleted underneath the canvas must not read alike. Both are
     * drawn from their rows, both have live endpoints so neither is {@code stale}, and neither is staged so
     * neither is {@code pending} — {@code asserted} is the only thing separating them.
     *
     * <p>This is the live reproduction of 2026-09-14 reduced to a unit test. A bad overlay cleared every
     * superclass of a class that had two, and afterwards the canvas rendered the surviving edges exactly as
     * it had rendered the intact ones: {@code pending: false, stale: false}. The user's hierarchy was gone
     * and the diagram looked unchanged. Deleting {@code ORPHANED_ROW} was right — suppressing those rows
     * lost the user's work silently — but it left no way to say "drawn, backed by nothing" until this flag.
     */
    @Test
    void anEdgeWhoseTripleWasDeleted_isDistinguishableFromASettledOne() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));                               // A ⊐ C was deleted; C itself survives

        Set<String> placed = Set.of(
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, C));

        List<DiagramDto.Edge> edges = builder(live).placed(placed).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).as("membership still draws both").hasSize(2);

        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + C)).singleElement()
                .satisfies(e -> {
                    assertThat(e.data().asserted()).as("the triple is gone").isFalse();
                    assertThat(e.data().stale())
                            .as("C the concept is alive, so staleness cannot report this").isFalse();
                    assertThat(e.data().pending())
                            .as("nothing is staged, so pending cannot report it either").isFalse();
                });

        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + B)).singleElement()
                .satisfies(e -> assertThat(e.data().asserted())
                        .as("the intact edge, identical on every other flag").isTrue());
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
            assertThat(e.id()).isEqualTo(DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B));
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

    // ---- membership decides existence: N rows in, N edges out ------------------------------------
    //
    // These four were written as diagnostics for "three hierarchy edges sent, two returned" and passed
    // against the un-fixed code, characterizing the two gates that let content decide existence. They now
    // assert the fixed contract: a placed row with both endpoints on the canvas is drawn, and RDF ⊕ overlay
    // only sets `pending`. See .planning/diagram-hierarchy-edge-projection-FINDINGS.md.

    /**
     * Three brand-new hierarchy edges sharing one source, with an overlay naming one target. All three are
     * drawn — the overlay speaks for the source's RDF, not for its edges' membership.
     *
     * <p>Previously only the named target survived: {@code stagedTargets} is looked up per SOURCE, so one
     * overlay on C spoke for every edge leaving C and un-drew the siblings it did not repeat.
     */
    @Test
    void threeNewEdgesFromOneSource_overlayStagingOneTarget_drawsAllThree() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(A));                 // stages only C -> A

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(C, concept(C));                               // brand-new hierarchy: nothing asserted
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(D, concept(D));

        Set<String> placed = Set.of(
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, A),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, B),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, D));

        List<DiagramDto.Edge> edges = builder(live).overlay(C, overlay).placed(placed).build()
                .project(List.of(node(C), node(A), node(B), node(D)),
                        types(Map.of(D, ConceptType.TRIDA)), Map.of());

        assertThat(edges).as("three placed rows in, three edges out").hasSize(3);
        assertThat(edges).extracting(DiagramDto.Edge::id).containsExactlyInAnyOrderElementsOf(placed);
        assertThat(edges)
                .filteredOn(e -> e.id().endsWith("|" + A)).singleElement()
                .satisfies(e -> assertThat(e.data().pending())
                        .as("the staged target is pending until materialize").isTrue());
        assertThat(edges)
                .filteredOn(e -> !e.id().endsWith("|" + A))
                .allSatisfy(e -> assertThat(e.data().pending())
                        .as("drawn from their rows, neither asserted nor staged").isFalse());
    }

    /**
     * The same three new edges, each from a DIFFERENT source, one overlay among them. All three are drawn:
     * an edge no overlay mentions is still on the canvas because its row says so.
     *
     * <p>Previously the two unstaged edges vanished as "orphaned" — nothing asserted them yet.
     */
    @Test
    void threeNewEdgesFromThreeSources_overlayStagingOne_drawsAllThree() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(D));                 // stages only A -> D

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));                               // all three children assert nothing
        live.put(B, concept(B));
        live.put(C, concept(C));
        live.put(D, concept(D));

        Set<String> placed = Set.of(
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, D),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, B, D),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, D));

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(placed).build()
                .project(List.of(node(A), node(B), node(C), node(D)),
                        types(Map.of(D, ConceptType.TRIDA)), Map.of());

        assertThat(edges).hasSize(3);
        assertThat(edges).extracting(DiagramDto.Edge::id).containsExactlyInAnyOrderElementsOf(placed);
    }

    /**
     * THE REPORTED BUG, at its core. A class with a parent asserted in RDF gains a second one, and the
     * overlay names only the new parent — the shape the client actually sends. Both edges must be drawn:
     * the asserted one settled, the staged one pending.
     *
     * <p>Previously the asserted edge was discarded, because the gate that compared the overlay's targets
     * ran <em>before</em> the live targets were read. Backing RDF gave the edge no protection, and the same
     * overlay was staged to delete that triple at materialize.
     */
    @Test
    void classGainingASecondParent_keepsTheOneAssertedInRdf() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(C));                 // names only the newly drawn parent

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));

        Set<String> placed = Set.of(
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, C));

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(placed).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).as("two placed rows in, two edges out").hasSize(2);
        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + B)).singleElement()
                .satisfies(e -> assertThat(e.data().pending())
                        .as("asserted in RDF and not named by the overlay — still drawn, settled").isFalse());
        assertThat(edges).filteredOn(e -> e.target().equals("iri:" + C)).singleElement()
                .satisfies(e -> assertThat(e.data().pending())
                        .as("staged, not yet asserted").isTrue());
    }

    /**
     * Live RDF asserts all three parents and the overlay names one. All three stay drawn: an overlay that
     * omits a target states an intent to remove it at materialize, not that it has left the canvas.
     */
    @Test
    void threeAssertedEdgesFromOneSource_overlayNamingOne_keepsAllThreeDrawn() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(A));                 // names only A of the three live parents

        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(C, ConceptDetailModel.builder().iri(C)
                .broaderClasses(List.of(A, B, D)).build());    // all three asserted in RDF
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(D, concept(D));

        Set<String> placed = Set.of(
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, A),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, B),
                DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, C, D));

        List<DiagramDto.Edge> edges = builder(live).overlay(C, overlay).placed(placed).build()
                .project(List.of(node(C), node(A), node(B), node(D)),
                        types(Map.of(D, ConceptType.TRIDA)), Map.of());

        assertThat(edges).hasSize(3);
        assertThat(edges).extracting(DiagramDto.Edge::id).containsExactlyInAnyOrderElementsOf(placed);
        assertThat(edges).allSatisfy(e -> assertThat(e.data().pending())
                .as("every target is asserted in RDF, so none is pending").isFalse());
    }

    // ---- waypoints ------------------------------------------------------------------------------

    @Test
    void persistedWaypointsAreJoinedOntoTheProjectedEdge() {
        DiagramContentResolver withGeometry = builder(live(A, B))
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
            assertThat(e.id()).isEqualTo(DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, C));
            assertThat(e.target()).isEqualTo("iri:" + C);
            assertThat(e.data().pending()).isTrue();
        });
    }

    /** Geometry drawn for an endpoint the edge no longer has must not follow it to the new one. */
    @Test
    void repointedEndpoint_dropsTheSavedWaypoints() {
        String staleKey = DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, A, B);
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

        assertThat(resolving(live).project(List.of(node(A)), types, Map.of())).isEmpty();

        Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live)
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

        Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live).propertyRows(
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

        Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live)
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

        assertThat(resolving(live).propertyRows(List.of(node(A)),
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

        Map<String, List<DiagramDto.PropertyRow>> rows = resolverStaging(PROP, overlay, live)
                .propertyRows(List.of(nodeWith(A, PROP), nodeWith(B, PROP)),
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of());

        assertThat(rows).doesNotContainKey(A);
        assertThat(rows.get(B)).singleElement()
                .satisfies(r -> assertThat(r.hasPendingEdits()).isTrue());
    }

    // ---- defaults and malformed rows ------------------------------------------------------------

    private static String placedEdge(DiagramEdgeKind kind, String source, String target) {
        return DiagramContentResolver.projectedEdgeId(kind, source, target);
    }

    /** Every optional input may be absent: nothing placed, nothing staged, every concept stale. */
    @Test
    void nullInputs_defaultToAnEmptyCanvasWithEveryConceptStale() {
        DiagramContentResolver resolver =
                new DiagramContentResolver(new DiagramMapper(), null, null, null, null, null, null);

        assertThat(resolver.project(List.of(node(A)), types(Map.of()), Map.of())).isEmpty();
        assertThat(resolver.propertyRows(List.of(nodeWith(A, PROP)), types(Map.of()), Map.of()).get(A))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.stale()).isTrue();
                    assertThat(r.hasPendingEdits()).isFalse();
                });
    }

    @Test
    void malformedOrUnknownKindEdgeKeys_areIgnored() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(B)).build());
        live.put(B, concept(B));
        String good = placedSubclass(A, B).iterator().next();

        List<DiagramDto.Edge> edges = builder(live)
                .placed(Set.of(good, "edge|SUBCLASS_OF|" + A, "edge|PART_OF|" + A + "|" + B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).extracting(DiagramDto.Edge::id).containsExactly(good);
    }

    // ---- triple edges: exact match, deleted source, combined staging -----------------------------

    @Test
    void exactMatchEdge_isAssertedFromTheSourcesExactMatches() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).exactMatches(List.of(B)).broaderClasses(List.of(C)).build());
        live.put(B, concept(B));
        live.put(C, concept(C));
        String exactAB = placedEdge(DiagramEdgeKind.EXACT_MATCH, A, B);
        String exactAC = placedEdge(DiagramEdgeKind.EXACT_MATCH, A, C);

        List<DiagramDto.Edge> edges = builder(live).placed(Set.of(exactAB, exactAC)).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).filteredOn(e -> e.id().equals(exactAB)).singleElement().satisfies(e -> {
            assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.EXACT_MATCH);
            assertThat(e.data().asserted()).isTrue();
        });
        assertThat(edges).filteredOn(e -> e.id().equals(exactAC)).singleElement()
                .satisfies(e -> assertThat(e.data().asserted())
                        .as("a broader link is not an exact match").isFalse());
    }

    @Test
    void exactMatchStagedOnTheSource_isPending_whileBroaderStagingIsIgnored() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setExactMatch(List.of(B));
        overlay.setBroaderConcept(List.of(C));
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(C, concept(C));
        String exactAB = placedEdge(DiagramEdgeKind.EXACT_MATCH, A, B);
        String exactAC = placedEdge(DiagramEdgeKind.EXACT_MATCH, A, C);

        List<DiagramDto.Edge> edges = builder(live).overlay(A, overlay).placed(Set.of(exactAB, exactAC)).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of()), Map.of());

        assertThat(edges).filteredOn(e -> e.id().equals(exactAB)).singleElement()
                .satisfies(e -> assertThat(e.data().pending()).isTrue());
        assertThat(edges).filteredOn(e -> e.id().equals(exactAC)).singleElement()
                .satisfies(e -> assertThat(e.data().pending()).isFalse());
    }

    /** A source deleted underneath the canvas asserts nothing, yet its placed edge still renders. */
    @Test
    void placedHierarchyEdge_whoseSourceWasDeleted_isDrawnUnasserted() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(B, concept(B));

        List<DiagramDto.Edge> edges = builder(live).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.data().asserted()).isFalse();
            assertThat(e.data().stale()).as("staleness reports the target").isFalse();
        });
    }

    @Test
    void sourceWithNullBroaderList_assertsNothing() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(null).build());
        live.put(B, concept(B));

        assertThat(builder(live).placed(placedSubclass(A, B)).build()
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of()))
                .singleElement()
                .satisfies(e -> assertThat(e.data().asserted()).isFalse());
    }

    /** The source's own staged parents and an op-6 conversion onto it are both pending. */
    @Test
    void ownStagedParentAndAConversion_areBothPending() {
        DiagramPendingEdit own = new DiagramPendingEdit();
        own.setBroaderConcept(List.of(C));
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(B);
        marker.setBroader(A);
        DiagramPendingEdit convert = new DiagramPendingEdit();
        convert.setConvertToHierarchy(marker);
        String ba = placedEdge(DiagramEdgeKind.SUBCLASS_OF, B, A);
        String bc = placedEdge(DiagramEdgeKind.SUBCLASS_OF, B, C);

        List<DiagramDto.Edge> edges = builder(live(A, B)).overlays(Map.of(B, own, REL, convert))
                .placed(Set.of(ba, bc)).build()
                .project(List.of(node(A), node(B), node(C)), types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).hasSize(2).allSatisfy(e -> assertThat(e.data().pending()).isTrue());
    }

    /** A conversion marker missing either end stages nothing. */
    @Test
    void incompleteConversionMarkers_stageNoParent() {
        DiagramPendingEdit.ConvertToHierarchy noTarget = new DiagramPendingEdit.ConvertToHierarchy();
        noTarget.setBroader(A);
        DiagramPendingEdit.ConvertToHierarchy noBroader = new DiagramPendingEdit.ConvertToHierarchy();
        noBroader.setAddBroaderOn(B);
        DiagramPendingEdit first = new DiagramPendingEdit();
        first.setConvertToHierarchy(noTarget);
        DiagramPendingEdit second = new DiagramPendingEdit();
        second.setConvertToHierarchy(noBroader);

        List<DiagramDto.Edge> edges = builder(live(A, B)).overlays(Map.of(REL, first, C, second))
                .placed(placedSubclass(B, A)).build()
                .project(List.of(node(A), node(B)), types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> assertThat(e.data().pending()).isFalse());
    }

    @Test
    void edgeToANonClassNode_isNotDrawn() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, ConceptDetailModel.builder().iri(A).broaderClasses(List.of(PROP)).build());
        live.put(PROP, concept(PROP));

        assertThat(builder(live).placed(placedSubclass(A, PROP)).build()
                .project(List.of(node(A), node(PROP)), types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of()))
                .as("a VLASTNOST node is not a canvas member, so nothing can attach to it")
                .isEmpty();
    }

    // ---- VZTAH typing and endpoints -------------------------------------------------------------

    /** With no PG type, a concept carrying both a domain and a range is still a relationship. */
    @Test
    void untypedConceptWithDomainAndRange_isDrawnAsAVztah() {
        List<DiagramDto.Edge> edges = resolving(live(A, B))
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(REL);
            assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.VZTAH);
        });
    }

    @Test
    void untypedConceptMissingAnEndpoint_isNotARelationship() {
        Map<String, ConceptDetailModel> live = live(A, B);
        live.put(REL, ConceptDetailModel.builder().iri(REL).range(B).build());
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(A);

        assertThat(resolverStaging(REL, overlay, live)
                .project(List.of(node(A), node(B)), types(Map.of()), Map.of()))
                .as("no domain in RDF: the overlay cannot make it a relationship").isEmpty();
        live.put(REL, ConceptDetailModel.builder().iri(REL).domain(A).build());
        assertThat(resolving(live).project(List.of(node(A), node(B)), types(Map.of()), Map.of())).isEmpty();
    }

    @Test
    void conceptTypedAsAnotherRole_isNeverAVztahEdge() {
        assertThat(resolving(live(A, B))
                .project(List.of(node(A), node(B)), types(Map.of(REL, ConceptType.VLASTNOST)), Map.of()))
                .isEmpty();
    }

    @Test
    void overlayDomainOnly_repointsTheSourceAndIsPending() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(C);

        List<DiagramDto.Edge> edges = resolverStaging(REL, overlay, live(A, B))
                .project(List.of(node(A), node(B), node(C)), types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo("iri:" + C);
            assertThat(e.target()).isEqualTo("iri:" + B);
            assertThat(e.data().pending()).isTrue();
        });
    }

    /** An overlay that stages no endpoint leaves the live ones in place and the edge settled. */
    @Test
    void overlayWithoutEndpoints_keepsLiveEndpoints_notPending() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setExactMatch(List.of(C));

        List<DiagramDto.Edge> edges = resolverStaging(REL, overlay, live(A, B))
                .project(List.of(node(A), node(B)), types(Map.of(REL, ConceptType.VZTAH)), Map.of());

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo("iri:" + A);
            assertThat(e.target()).isEqualTo("iri:" + B);
            assertThat(e.data().pending()).isFalse();
            assertThat(e.data().hasPendingEdits()).isTrue();
        });
    }

    @Test
    void deletedVztahWithoutATombstone_isNotDrawn() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, concept(B));

        assertThat(resolving(live).project(List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    @Test
    void vztahWithABlankEndpoint_isNotDrawn() {
        assertThat(resolving(live(" ", B)).project(List.of(node(A), node(B)),
                types(Map.of(REL, ConceptType.VZTAH)), Map.of())).isEmpty();
    }

    // ---- property rows --------------------------------------------------------------------------

    @Test
    void curatedIriThatIsNotAProperty_rendersNoRow() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, ConceptDetailModel.builder().iri(B).domain(A).build());

        assertThat(resolving(live).propertyRows(List.of(nodeWith(A, B)), types(Map.of()), Map.of())).isEmpty();
    }

    @Test
    void overlayWithoutDomain_keepsThePropertyInItsLiveClass() {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setExactMatch(List.of(C));
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(A).build());

        Map<String, List<DiagramDto.PropertyRow>> rows = resolverStaging(PROP, overlay, live)
                .propertyRows(List.of(nodeWith(A, PROP)), types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of());

        assertThat(rows.get(A)).singleElement().satisfies(r -> assertThat(r.hasPendingEdits()).isTrue());
    }

    /** Two classes curating the same property render it once, inside its domain. */
    @Test
    void propertyCuratedOnTwoNodes_rendersOnceInItsDomain() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(B, concept(B));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(A).build());

        Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live).propertyRows(
                List.of(nodeWith(A, PROP), nodeWith(B, PROP)),
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of());

        assertThat(rows).containsOnlyKeys(A);
        assertThat(rows.get(A)).hasSize(1);
    }

    @Test
    void propertyWhoseDomainIsOffCanvas_rendersNoRow() {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(PROP, ConceptDetailModel.builder().iri(PROP).domain(D).build());

        assertThat(resolving(live).propertyRows(List.of(nodeWith(A, PROP)),
                types(Map.of(PROP, ConceptType.VLASTNOST)), Map.of())).isEmpty();
    }

    /**
     * Sort key fallbacks: the Czech label first, else any label, else the IRI — a blank or missing label
     * sorts by IRI rather than first.
     */
    @Test
    void propertyRowOrdering_fallsBackFromCzechToAnyLabelToIri() {
        String p1 = "https://x/pojem/p1-bez-nazvu";
        String p2 = "https://x/pojem/p2-prazdny";
        String p3 = "https://x/pojem/p3-anglicky";
        String p4 = "https://x/pojem/p4-cesky";
        String p5 = "https://x/pojem/p5-mezera";
        Map<String, ConceptDetailModel> live = new HashMap<>();
        live.put(A, concept(A));
        live.put(p1, ConceptDetailModel.builder().iri(p1).domain(A).build());
        live.put(p2, ConceptDetailModel.builder().iri(p2).domain(A).name(Map.of()).build());
        live.put(p3, ConceptDetailModel.builder().iri(p3).domain(A).name(Map.of("en", "Zeta")).build());
        live.put(p4, ConceptDetailModel.builder().iri(p4).domain(A).name(Map.of("cs", "Alfa", "en", "Zzz")).build());
        live.put(p5, ConceptDetailModel.builder().iri(p5).domain(A).name(Map.of("cs", " ")).build());
        String p6 = "https://x/pojem/p6-null";
        Map<String, String> nullValued = new HashMap<>();
        nullValued.put("cs", null);
        live.put(p6, ConceptDetailModel.builder().iri(p6).domain(A).name(nullValued).build());
        Map<String, ConceptType> types = types(Map.of(p1, ConceptType.VLASTNOST, p2, ConceptType.VLASTNOST,
                p3, ConceptType.VLASTNOST, p4, ConceptType.VLASTNOST, p5, ConceptType.VLASTNOST,
                p6, ConceptType.VLASTNOST));

        Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live)
                .propertyRows(List.of(nodeWith(A, p6, p5, p3, p2, p4, p1)), types, Map.of());

        // "alfa" < "https://…p1" < "…p2" < "…p5" < "…p6" < "zeta"
        assertThat(rows.get(A)).extracting(DiagramDto.PropertyRow::iri)
                .containsExactly(p4, p1, p2, p5, p6, p3);
    }
}
