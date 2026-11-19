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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge and property-row projection. An edge's existence comes from its membership row; its kind, endpoints
 * and flags are derived from {@code live ⊕ overlay}, never read from storage; waypoints are joined on by
 * projected edge id.
 *
 * <p>A VZTAH is one edge between its two classes carrying its own concept identity, a VLASTNOST is a row
 * inside its domain class, and a concept missing an endpoint is not drawn.
 */
class DiagramContentResolverTest {

    private static final String REL = "https://x/pojem/rel";
    private static final String A = "https://x/pojem/a";
    private static final String B = "https://x/pojem/b";
    private static final String C = "https://x/pojem/c";
    private static final String D = "https://x/pojem/d";
    private static final String PROP = "https://x/pojem/prop";

    /** The default membership: the VZTAH row only. A hierarchy edge is drawn only once its row is placed. */
    private static final Set<String> ALL_PLACED = Set.of(REL);

    @Nested
    class VztahEdges {

        @Test
        void vztahProjectsOneEdgeBetweenItsClasses_carryingItsOwnIdentity() {
            List<DiagramDto.Edge> edges = resolving(liveVztah(A, B)).project(
                    List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of(REL, "x-rel"));

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).as("the concept IRI is the edge id").isEqualTo(REL);
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

        /** The relationship itself need not be a canvas node, only its two endpoint classes. */
        @Test
        void vztahProjects_evenThoughItIsNotItselfANode() {
            assertThat(resolving(liveVztah(A, B)).project(
                    List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of())).hasSize(1);
        }

        /** With no PG type, a concept carrying both a domain and a range is still a relationship. */
        @Test
        void untypedConceptWithDomainAndRange_isDrawnAsAVztah() {
            List<DiagramDto.Edge> edges = resolving(liveVztah(A, B))
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo(REL);
                assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.VZTAH);
            });
        }

        @Test
        void untypedConceptMissingAnEndpoint_isNotARelationship() {
            Map<String, ConceptDetailModel> live = liveVztah(A, B);
            live.put(REL, ConceptDetailModel.builder().iri(REL).range(B).build());

            assertThat(resolverStaging(REL, domainOverlay(A), live)
                    .project(List.of(node(A), node(B)), types(), Map.of()))
                    .as("no domain in RDF: the overlay cannot make it a relationship").isEmpty();

            live.put(REL, ConceptDetailModel.builder().iri(REL).domain(A).build());
            assertThat(resolving(live).project(List.of(node(A), node(B)), types(), Map.of())).isEmpty();
        }

        @Test
        void conceptTypedAsAnotherRole_isNeverAVztahEdge() {
            assertThat(resolving(liveVztah(A, B))
                    .project(List.of(node(A), node(B)), types(REL, ConceptType.VLASTNOST), Map.of()))
                    .isEmpty();
        }

        /** Incomplete concepts live off-canvas: a missing, blank or off-canvas endpoint draws no edge. */
        @ParameterizedTest
        @CsvSource({
                "https://x/pojem/a,",
                "https://x/pojem/a, https://x/pojem/off-canvas",
                "' ', https://x/pojem/b"})
        void vztahWithoutTwoOnCanvasEndpoints_isNotDrawn(String domain, String range) {
            assertThat(resolving(liveVztah(domain, range)).project(List.of(node(A), node(B)),
                    types(REL, ConceptType.VZTAH), Map.of())).isEmpty();
        }

        @Test
        void deletedVztahWithoutATombstone_isNotDrawn() {
            assertThat(resolving(live(concept(A), concept(B))).project(List.of(node(A), node(B)),
                    types(REL, ConceptType.VZTAH), Map.of())).isEmpty();
        }

        /** The overlay is keyed by the VZTAH's IRI; the VZTAH is not a node. */
        @Test
        void overlayRangeOverridesLiveAndIsFlaggedPending() {
            DiagramPendingEdit overlay = new DiagramPendingEdit();
            overlay.setRange(C);

            List<DiagramDto.Edge> edges = resolverStaging(REL, overlay, liveVztah(A, B)).project(
                    List.of(node(A), node(B), node(C)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.target()).as("the overlay wins over the live range").isEqualTo("iri:" + C);
                assertThat(e.data().pending()).isTrue();
            });
        }

        @Test
        void overlayDomainOnly_repointsTheSourceAndIsPending() {
            List<DiagramDto.Edge> edges = resolverStaging(REL, domainOverlay(C), liveVztah(A, B))
                    .project(List.of(node(A), node(B), node(C)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.source()).isEqualTo("iri:" + C);
                assertThat(e.target()).isEqualTo("iri:" + B);
                assertThat(e.data().pending()).isTrue();
            });
        }

        /** An overlay that stages no endpoint leaves the live ones in place and the edge settled. */
        @Test
        void overlayWithoutEndpoints_keepsLiveEndpoints_notPending() {
            List<DiagramDto.Edge> edges = resolverStaging(REL, exactMatchOverlay(C), liveVztah(A, B))
                    .project(List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.source()).isEqualTo("iri:" + A);
                assertThat(e.target()).isEqualTo("iri:" + B);
                assertThat(e.data().pending()).isFalse();
                assertThat(e.data().hasPendingEdits()).isTrue();
            });
        }
    }

    /** Hierarchy and equivalence edges are bare triples: a membership row, no backing concept. */
    @Nested
    class HierarchyMembership {

        @Test
        void subclassEdgeCarriesNoConceptPayload() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B)))
                    .placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo("edge|SUBCLASS_OF|" + A + "|" + B);
                assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.SUBCLASS_OF);
                assertThat(e.data().iri()).as("the FE's concept-backed discriminator").isNull();
                assertThat(e.data().label()).isNull();
            });
        }

        /**
         * A foreign concept is an edge target, never a source: its outgoing triples belong to the graph that
         * owns them, which this canvas can neither stage nor reroute.
         */
        @Test
        void foreignSource_doesNotProjectItsOwnHierarchy() {
            assertThat(fixture(live(subclassOf(A, B), concept(B)))
                    .foreign(Set.of(A)).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of())).isEmpty();
        }

        @Test
        void foreignTarget_ofAnOwnedSource_stillProjects() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B)))
                    .foreign(Set.of(B)).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().extracting(DiagramDto.Edge::id).isEqualTo(subclass(A, B));
        }

        /** A placed row is drawn before anything asserts its triple; RDF says only whether it is settled. */
        @Test
        void placedRowForATripleNothingAssertsYet_isStillDrawn() {
            List<DiagramDto.Edge> edges = fixture(live(concept(A), concept(B)))
                    .placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo(subclass(A, B));
                assertThat(e.data().pending()).as("nothing stages it").isFalse();
                assertThat(e.data().asserted()).as("nothing backs it in RDF").isFalse();
            });
        }

        /** Membership governs: a link RDF and the overlay both support is not drawn without a row. */
        @Test
        void repointDoesNotDrawAHierarchyEdgeThatWasNeverPlaced() {
            assertThat(fixture(live(subclassOf(A, B), concept(B), concept(C)))
                    .overlay(A, stagedParents(C)).placed(Set.of()).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of())).isEmpty();
        }

        /** A placed node with no edge rows draws nothing; membership, not the graph, is walked. */
        @Test
        void staleNode_withNoEdgeRows_projectsNoEdges() {
            assertThat(fixture(Map.of()).placed(Set.of()).build()
                    .project(List.of(node("https://x/pojem/gone")), types(), Map.of())).isEmpty();
        }

        /** Three new edges from one source, an overlay naming one target: the overlay does not decide membership. */
        @Test
        void threeNewEdgesFromOneSource_overlayStagingOneTarget_drawsAllThree() {
            Set<String> placed = Set.of(subclass(C, A), subclass(C, B), subclass(C, D));

            List<DiagramDto.Edge> edges = fixture(live(concept(C), concept(A), concept(B), concept(D)))
                    .overlay(C, stagedParents(A)).placed(placed).build()
                    .project(List.of(node(C), node(A), node(B), node(D)), types(D, ConceptType.TRIDA), Map.of());

            assertThat(edges).extracting(DiagramDto.Edge::id).containsExactlyInAnyOrderElementsOf(placed);
            assertThat(edges).filteredOn(e -> e.id().endsWith("|" + A)).singleElement()
                    .satisfies(e -> assertThat(e.data().pending())
                            .as("the staged target is pending until materialize").isTrue());
            assertThat(edges).filteredOn(e -> !e.id().endsWith("|" + A))
                    .allSatisfy(e -> assertThat(e.data().pending())
                            .as("drawn from their rows, neither asserted nor staged").isFalse());
        }

        /** An edge no overlay mentions is on the canvas because its row says so. */
        @Test
        void threeNewEdgesFromThreeSources_overlayStagingOne_drawsAllThree() {
            Set<String> placed = Set.of(subclass(A, D), subclass(B, D), subclass(C, D));

            List<DiagramDto.Edge> edges = fixture(live(concept(A), concept(B), concept(C), concept(D)))
                    .overlay(A, stagedParents(D)).placed(placed).build()
                    .project(List.of(node(A), node(B), node(C), node(D)), types(D, ConceptType.TRIDA), Map.of());

            assertThat(edges).extracting(DiagramDto.Edge::id).containsExactlyInAnyOrderElementsOf(placed);
        }

        /** An overlay that omits a live target states an intent to remove it at materialize, not that it left. */
        @Test
        void threeAssertedEdgesFromOneSource_overlayNamingOne_keepsAllThreeDrawn() {
            Set<String> placed = Set.of(subclass(C, A), subclass(C, B), subclass(C, D));

            List<DiagramDto.Edge> edges = fixture(live(subclassOf(C, A, B, D), concept(A), concept(B), concept(D)))
                    .overlay(C, stagedParents(A)).placed(placed).build()
                    .project(List.of(node(C), node(A), node(B), node(D)), types(D, ConceptType.TRIDA), Map.of());

            assertThat(edges).extracting(DiagramDto.Edge::id).containsExactlyInAnyOrderElementsOf(placed);
            assertThat(edges).allSatisfy(e -> assertThat(e.data().pending())
                    .as("every target is asserted in RDF, so none is pending").isFalse());
        }

        @Test
        void edgeToANonClassNode_isNotDrawn() {
            assertThat(fixture(live(subclassOf(A, PROP), concept(PROP)))
                    .placed(Set.of(subclass(A, PROP))).build()
                    .project(List.of(node(A), node(PROP)), types(PROP, ConceptType.VLASTNOST), Map.of()))
                    .as("a VLASTNOST node is not a canvas member, so nothing can attach to it")
                    .isEmpty();
        }

        @Test
        void malformedOrUnknownKindEdgeKeys_areIgnored() {
            String good = subclass(A, B);

            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B)))
                    .placed(Set.of(good, "edge|SUBCLASS_OF|" + A, "edge|PART_OF|" + A + "|" + B)).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).extracting(DiagramDto.Edge::id).containsExactly(good);
        }
    }

    /** {@code pending} = staged and not yet in RDF; {@code asserted} = the triple is in RDF. */
    @Nested
    class HierarchyPendingFlags {

        /**
         * A staged clear does not un-draw a placed edge: removal arrives as the row's omission from
         * {@code edges[]}, so a present row means the edge is still on the canvas.
         */
        @Test
        void emptyListOverlay_doesNotUnDrawAPlacedHierarchyEdge() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B)))
                    .overlay(A, stagedParents()).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).as("the row still places the edge; the staged clear applies at materialize")
                    .singleElement().satisfies(e -> {
                        assertThat(e.data().pending()).as("RDF still asserts the link").isFalse();
                        assertThat(e.data().asserted()).as("the clear is not materialized yet").isTrue();
                    });
        }

        /** Staging {@code [B, C]} on a class whose live broader is {@code [B]} draws both. */
        @Test
        void stagingASecondParent_keepsTheFirstOneDrawn() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B), concept(C)))
                    .overlay(A, stagedParents(B, C)).placed(Set.of(subclass(A, B), subclass(A, C))).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edges).hasSize(2);
            assertThat(edgeTo(edges, B)).satisfies(e -> {
                assertThat(e.data().pending()).as("B is already in RDF").isFalse();
                assertThat(e.data().asserted()).isTrue();
            });
            assertThat(edgeTo(edges, C)).satisfies(e -> {
                assertThat(e.data().pending()).as("C is staged").isTrue();
                assertThat(e.data().asserted()).as("C is not in RDF yet").isFalse();
            });
        }

        /** The overlay names only the new parent, the shape the client sends; the asserted one stays settled. */
        @Test
        void classGainingASecondParent_keepsTheOneAssertedInRdf() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B), concept(C)))
                    .overlay(A, stagedParents(C)).placed(Set.of(subclass(A, B), subclass(A, C))).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edges).as("two placed rows in, two edges out").hasSize(2);
            assertThat(edgeTo(edges, B).data().pending()).as("asserted and not named by the overlay").isFalse();
            assertThat(edgeTo(edges, C).data().pending()).as("staged, not yet asserted").isTrue();
        }

        /** Op 6's marker sits on the VZTAH, not on the class gaining the parent. */
        @Test
        void hierarchyEdgeStagedByAConversion_isPending() {
            List<DiagramDto.Edge> edges = fixture(liveVztah(A, B))
                    .overlay(REL, conversion(B, A)).placed(Set.of(subclass(B, A))).build()
                    .project(List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.data().pending()).as("the conversion will write B ⊂ A").isTrue();
                assertThat(e.data().asserted()).as("not in RDF until Převzít").isFalse();
            });
        }

        /** The source's own staged parents and an op-6 conversion onto it are both pending. */
        @Test
        void ownStagedParentAndAConversion_areBothPending() {
            List<DiagramDto.Edge> edges = fixture(liveVztah(A, B))
                    .overlays(Map.of(B, stagedParents(C), REL, conversion(B, A)))
                    .placed(Set.of(subclass(B, A), subclass(B, C))).build()
                    .project(List.of(node(A), node(B), node(C)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).hasSize(2).allSatisfy(e -> assertThat(e.data().pending()).isTrue());
        }

        /** A conversion marker missing either end stages nothing. */
        @Test
        void incompleteConversionMarkers_stageNoParent() {
            List<DiagramDto.Edge> edges = fixture(liveVztah(A, B))
                    .overlays(Map.of(REL, conversion(null, A), C, conversion(B, null)))
                    .placed(Set.of(subclass(B, A))).build()
                    .project(List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement().satisfies(e -> assertThat(e.data().pending()).isFalse());
        }

        /**
         * A settled edge and one whose triple was deleted underneath the canvas differ only in
         * {@code asserted}: both have live endpoints (not stale) and neither is staged (not pending).
         */
        @Test
        void anEdgeWhoseTripleWasDeleted_isDistinguishableFromASettledOne() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B), concept(C)))
                    .placed(Set.of(subclass(A, B), subclass(A, C))).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edges).as("membership still draws both").hasSize(2);
            assertThat(edgeTo(edges, C)).satisfies(e -> {
                assertThat(e.data().asserted()).as("the triple is gone").isFalse();
                assertThat(e.data().stale()).as("C the concept is alive").isFalse();
                assertThat(e.data().pending()).as("nothing is staged").isFalse();
            });
            assertThat(edgeTo(edges, B).data().asserted()).as("the intact edge").isTrue();
        }

        /** A source deleted underneath the canvas asserts nothing, yet its placed edge still renders. */
        @Test
        void placedHierarchyEdge_whoseSourceWasDeleted_isDrawnUnasserted() {
            List<DiagramDto.Edge> edges = fixture(live(concept(B))).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.data().asserted()).isFalse();
                assertThat(e.data().stale()).as("staleness reports the target").isFalse();
            });
        }

        @Test
        void sourceWithNullBroaderList_assertsNothing() {
            ConceptDetailModel noBroader = ConceptDetailModel.builder().iri(A).broaderClasses(null).build();

            assertThat(fixture(live(noBroader, concept(B))).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of()))
                    .singleElement()
                    .satisfies(e -> assertThat(e.data().asserted()).isFalse());
        }
    }

    @Nested
    class ExactMatchEdges {

        @Test
        void exactMatchEdge_isAssertedFromTheSourcesExactMatches() {
            ConceptDetailModel a = ConceptDetailModel.builder().iri(A)
                    .exactMatches(List.of(B)).broaderClasses(List.of(C)).build();
            String exactAB = exactMatch(A, B);
            String exactAC = exactMatch(A, C);

            List<DiagramDto.Edge> edges = fixture(live(a, concept(B), concept(C)))
                    .placed(Set.of(exactAB, exactAC)).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edgeWithId(edges, exactAB)).satisfies(e -> {
                assertThat(e.data().edgeKind()).isEqualTo(DiagramEdgeKind.EXACT_MATCH);
                assertThat(e.data().asserted()).isTrue();
            });
            assertThat(edgeWithId(edges, exactAC).data().asserted())
                    .as("a broader link is not an exact match").isFalse();
        }

        @Test
        void exactMatchStagedOnTheSource_isPending_whileBroaderStagingIsIgnored() {
            DiagramPendingEdit overlay = exactMatchOverlay(B);
            overlay.setBroaderConcept(List.of(C));
            String exactAB = exactMatch(A, B);
            String exactAC = exactMatch(A, C);

            List<DiagramDto.Edge> edges = fixture(live(concept(A), concept(B), concept(C)))
                    .overlay(A, overlay).placed(Set.of(exactAB, exactAC)).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edgeWithId(edges, exactAB).data().pending()).isTrue();
            assertThat(edgeWithId(edges, exactAC).data().pending()).isFalse();
        }
    }

    /** Content deleted or unreadable underneath the canvas keeps its place and is flagged. */
    @Nested
    class StaleAndUnavailable {

        /** The row's own key supplies both endpoints, so there is still something to draw between. */
        @Test
        void placedHierarchyEdge_whoseTargetWasDeleted_rendersStale() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B))).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo(subclass(A, B));
                assertThat(e.data().stale()).isTrue();
                assertThat(e.data().unavailable()).isFalse();
            });
        }

        /** An unreadable graph is presumed intact and never reported as a deletion. */
        @Test
        void placedHierarchyEdge_whoseTargetGraphIsUnreadable_rendersUnavailableNotStale() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B)))
                    .unavailable(Set.of(B)).placed(Set.of(subclass(A, B))).build()
                    .project(List.of(node(A), node(B)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.data().unavailable()).isTrue();
                assertThat(e.data().stale()).isFalse();
            });
        }

        /** A deleted VZTAH loses its domain and range with its concept; the row's tombstone supplies both ends. */
        @Test
        void placedVztah_whoseConceptWasDeleted_rendersStaleFromItsTombstone() {
            List<DiagramDto.Edge> edges = fixture(live(concept(A), concept(B))).tombstone(REL, A, B).build()
                    .project(List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo(REL);
                assertThat(e.source()).isEqualTo("iri:" + A);
                assertThat(e.target()).isEqualTo("iri:" + B);
                assertThat(e.data().stale()).isTrue();
            });
        }

        /**
         * A curated property whose concept was deleted keeps its row, flagged. It has no PG type and no domain
         * of its own, so the curating node is its home.
         */
        @Test
        void curatedProperty_whoseConceptWasDeleted_rendersStaleRow() {
            Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live(concept(A)))
                    .propertyRows(List.of(nodeWith(A, PROP)), types(), Map.of());

            assertThat(rows.get(A)).singleElement().satisfies(r -> {
                assertThat(r.iri()).isEqualTo(PROP);
                assertThat(r.stale()).isTrue();
                assertThat(r.unavailable()).isFalse();
                assertThat(r.label()).as("no live content to show").isNull();
            });
        }

        /** Every optional input may be absent: nothing placed, nothing staged, every concept stale. */
        @Test
        void nullInputs_defaultToAnEmptyCanvasWithEveryConceptStale() {
            DiagramContentResolver resolver =
                    new DiagramContentResolver(new DiagramMapper(), null, null, null, null, null, null);

            assertThat(resolver.project(List.of(node(A)), types(), Map.of())).isEmpty();
            assertThat(resolver.propertyRows(List.of(nodeWith(A, PROP)), types(), Map.of()).get(A))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.stale()).isTrue();
                        assertThat(r.hasPendingEdits()).isFalse();
                    });
        }
    }

    @Nested
    class Waypoints {

        @Test
        void persistedWaypointsAreJoinedOntoTheProjectedEdge() {
            List<DiagramDto.Edge> edges = fixture(liveVztah(A, B))
                    .waypoints(Map.of(REL, List.of(new EdgeWaypoint(40, 80)))).build()
                    .project(List.of(node(A), node(B)), types(REL, ConceptType.VZTAH), Map.of());

            assertThat(edges).singleElement()
                    .satisfies(e -> assertThat(e.segments()).containsExactly(new EdgeWaypoint(40, 80)));
        }

        /**
         * A repointed edge keeps the row and id it was placed under until materialize rekeys it. The overlay
         * drops B, so the link to B is a staged removal; the link to C is pending.
         */
        @Test
        void repointedHierarchyEdge_drawsAtItsNewTargetUnderThatTargetsRow() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B), concept(C)))
                    .overlay(A, stagedParents(C)).placed(Set.of(subclass(A, C))).build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.id()).isEqualTo(subclass(A, C));
                assertThat(e.target()).isEqualTo("iri:" + C);
                assertThat(e.data().pending()).isTrue();
            });
        }

        /** Geometry drawn for an endpoint the edge no longer has does not follow it to the new one. */
        @Test
        void repointedEndpoint_dropsTheSavedWaypoints() {
            List<DiagramDto.Edge> edges = fixture(live(subclassOf(A, B), concept(B), concept(C)))
                    .overlay(A, stagedParents(C))
                    .placed(Set.of(subclass(A, C)))
                    .waypoints(Map.of(subclass(A, B), List.of(new EdgeWaypoint(40, 80))))
                    .build()
                    .project(List.of(node(A), node(B), node(C)), types(), Map.of());

            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e.target()).isEqualTo("iri:" + C);
                assertThat(e.data().pending()).isTrue();
                assertThat(e.segments()).as("geometry drawn for B does not follow the edge to C").isNull();
            });
        }
    }

    /** A VLASTNOST is a row inside its domain class, rendered once the user has curated it onto the node. */
    @Nested
    class PropertyRows {

        @Test
        void propertyBecomesARowInsideItsDomainClass_notAnEdge() {
            Map<String, ConceptDetailModel> live = live(concept(A), property(PROP, A, "datum narození"));
            Map<String, ConceptType> types = types(PROP, ConceptType.VLASTNOST);

            assertThat(resolving(live).project(List.of(node(A)), types, Map.of())).isEmpty();
            assertThat(resolving(live).propertyRows(List.of(nodeWith(A, PROP)), types, Map.of(PROP, "x-prop"))
                    .get(A)).singleElement().satisfies(r -> {
                        assertThat(r.iri()).isEqualTo(PROP);
                        assertThat(r.slug()).isEqualTo("x-prop");
                        assertThat(r.label()).containsEntry("cs", "datum narození");
                    });
        }

        /** A domainless property has no class to sit in; it is placed by dragging it in from the detail. */
        @Test
        void domainlessProperty_hasNoRow() {
            assertThat(resolving(live(concept(A), concept(PROP))).propertyRows(List.of(node(A)),
                    types(PROP, ConceptType.VLASTNOST), Map.of())).isEmpty();
        }

        @Test
        void curatedIriThatIsNotAProperty_rendersNoRow() {
            ConceptDetailModel notAProperty = ConceptDetailModel.builder().iri(B).domain(A).build();

            assertThat(resolving(live(concept(A), notAProperty))
                    .propertyRows(List.of(nodeWith(A, B)), types(), Map.of())).isEmpty();
        }

        @Test
        void propertyWhoseDomainIsOffCanvas_rendersNoRow() {
            assertThat(resolving(live(concept(A), property(PROP, D)))
                    .propertyRows(List.of(nodeWith(A, PROP)), types(PROP, ConceptType.VLASTNOST), Map.of()))
                    .isEmpty();
        }

        /** Two classes curating the same property render it once, inside its domain. */
        @Test
        void propertyCuratedOnTwoNodes_rendersOnceInItsDomain() {
            Map<String, List<DiagramDto.PropertyRow>> rows = resolving(live(concept(A), concept(B), property(PROP, A)))
                    .propertyRows(List.of(nodeWith(A, PROP), nodeWith(B, PROP)),
                            types(PROP, ConceptType.VLASTNOST), Map.of());

            assertThat(rows).containsOnlyKeys(A);
            assertThat(rows.get(A)).hasSize(1);
        }

        @Test
        void overlayDomainMovesThePropertyToAnotherClass() {
            Map<String, List<DiagramDto.PropertyRow>> rows = resolverStaging(PROP, domainOverlay(B),
                    live(concept(A), concept(B), property(PROP, A)))
                    .propertyRows(List.of(nodeWith(A, PROP), nodeWith(B, PROP)),
                            types(PROP, ConceptType.VLASTNOST), Map.of());

            assertThat(rows).doesNotContainKey(A);
            assertThat(rows.get(B)).singleElement()
                    .satisfies(r -> assertThat(r.hasPendingEdits()).isTrue());
        }

        @Test
        void overlayWithoutDomain_keepsThePropertyInItsLiveClass() {
            Map<String, List<DiagramDto.PropertyRow>> rows = resolverStaging(PROP, exactMatchOverlay(C),
                    live(concept(A), property(PROP, A)))
                    .propertyRows(List.of(nodeWith(A, PROP)), types(PROP, ConceptType.VLASTNOST), Map.of());

            assertThat(rows.get(A)).singleElement().satisfies(r -> assertThat(r.hasPendingEdits()).isTrue());
        }
    }

    @Nested
    class PropertyRowSorting {

        @Test
        void propertyRowsAreOrderedByLabel() {
            String p1 = "https://x/pojem/p1";
            String p2 = "https://x/pojem/p2";

            Map<String, List<DiagramDto.PropertyRow>> rows = resolving(
                    live(concept(A), property(p1, A, "zebra"), property(p2, A, "abeceda")))
                    .propertyRows(List.of(nodeWith(A, p1, p2)),
                            types(Map.of(p1, ConceptType.VLASTNOST, p2, ConceptType.VLASTNOST)), Map.of());

            assertThat(rows.get(A)).extracting(r -> r.label().get("cs")).containsExactly("abeceda", "zebra");
        }

        /** The Czech label first, else any label, else the IRI: a blank or missing label sorts by IRI. */
        @Test
        void propertyRowOrdering_fallsBackFromCzechToAnyLabelToIri() {
            String p1 = "https://x/pojem/p1-bez-nazvu";
            String p2 = "https://x/pojem/p2-prazdny";
            String p3 = "https://x/pojem/p3-anglicky";
            String p4 = "https://x/pojem/p4-cesky";
            String p5 = "https://x/pojem/p5-mezera";
            String p6 = "https://x/pojem/p6-null";
            Map<String, String> nullValued = new HashMap<>();
            nullValued.put("cs", null);
            Map<String, ConceptDetailModel> live = live(concept(A),
                    property(p1, A),
                    ConceptDetailModel.builder().iri(p2).domain(A).name(Map.of()).build(),
                    ConceptDetailModel.builder().iri(p3).domain(A).name(Map.of("en", "Zeta")).build(),
                    ConceptDetailModel.builder().iri(p4).domain(A).name(Map.of("cs", "Alfa", "en", "Zzz")).build(),
                    property(p5, A, " "),
                    ConceptDetailModel.builder().iri(p6).domain(A).name(nullValued).build());
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

    // ---- fixtures ----

    /** A projector over the given live graph, with the default membership and nothing staged. */
    private DiagramContentResolver resolving(Map<String, ConceptDetailModel> live) {
        return fixture(live).build();
    }

    /** A projector whose staged edits are the given (conceptIri -> overlay) pair. */
    private DiagramContentResolver resolverStaging(String conceptIri, DiagramPendingEdit overlay,
                                                   Map<String, ConceptDetailModel> live) {
        return fixture(live).overlay(conceptIri, overlay).build();
    }

    private Fixture fixture(Map<String, ConceptDetailModel> live) {
        return new Fixture(live);
    }

    /** Assembles a projector; every knob defaults to "VZTAH placed, nothing staged, no geometry". */
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

    /** The membership row key of a hierarchy link. */
    private static String subclass(String source, String target) {
        return DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.SUBCLASS_OF, source, target);
    }

    /** The membership row key of an exact-match link. */
    private static String exactMatch(String source, String target) {
        return DiagramContentResolver.projectedEdgeId(DiagramEdgeKind.EXACT_MATCH, source, target);
    }

    private static DiagramNodeEntity node(String iri) {
        DiagramNodeEntity n = new DiagramNodeEntity();
        n.setConceptIri(iri);
        return n;
    }

    /** A class node that renders the given curated property rows. */
    private static DiagramNodeEntity nodeWith(String iri, String... visibleProperties) {
        DiagramNodeEntity n = node(iri);
        n.setVisibleProperties(List.of(visibleProperties));
        return n;
    }

    private static ConceptDetailModel concept(String iri) {
        return ConceptDetailModel.builder().iri(iri).build();
    }

    /** A class whose RDF asserts the given broader classes. */
    private static ConceptDetailModel subclassOf(String iri, String... broader) {
        return ConceptDetailModel.builder().iri(iri).broaderClasses(List.of(broader)).build();
    }

    private static ConceptDetailModel property(String iri, String domain) {
        return ConceptDetailModel.builder().iri(iri).domain(domain).build();
    }

    private static ConceptDetailModel property(String iri, String domain, String czechName) {
        return ConceptDetailModel.builder().iri(iri).domain(domain).name(Map.of("cs", czechName)).build();
    }

    /** A mutable live graph keyed by concept IRI. */
    private static Map<String, ConceptDetailModel> live(ConceptDetailModel... concepts) {
        Map<String, ConceptDetailModel> live = new HashMap<>();
        for (ConceptDetailModel concept : concepts) {
            live.put(concept.getIri(), concept);
        }
        return live;
    }

    /** A VZTAH from {@code domain} to {@code range}, plus classes A, B and C. */
    private static Map<String, ConceptDetailModel> liveVztah(String domain, String range) {
        return live(ConceptDetailModel.builder().iri(REL).domain(domain).range(range).build(),
                concept(A), concept(B), concept(C));
    }

    /** A, B and C typed TRIDA, plus the given types. */
    private static Map<String, ConceptType> types(Map<String, ConceptType> extra) {
        Map<String, ConceptType> types = new HashMap<>(Map.of(
                A, ConceptType.TRIDA, B, ConceptType.TRIDA, C, ConceptType.TRIDA));
        types.putAll(extra);
        return types;
    }

    private static Map<String, ConceptType> types() {
        return types(Map.of());
    }

    private static Map<String, ConceptType> types(String iri, ConceptType type) {
        return types(Map.of(iri, type));
    }

    /** An overlay staging the complete broader set of its concept. */
    private static DiagramPendingEdit stagedParents(String... broader) {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setBroaderConcept(List.of(broader));
        return overlay;
    }

    private static DiagramPendingEdit domainOverlay(String domain) {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setDomain(domain);
        return overlay;
    }

    private static DiagramPendingEdit exactMatchOverlay(String... targets) {
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setExactMatch(List.of(targets));
        return overlay;
    }

    /** An op-6 overlay converting a VZTAH into {@code addBroaderOn ⊂ broader}; either end may be null. */
    private static DiagramPendingEdit conversion(String addBroaderOn, String broader) {
        DiagramPendingEdit.ConvertToHierarchy marker = new DiagramPendingEdit.ConvertToHierarchy();
        marker.setAddBroaderOn(addBroaderOn);
        marker.setBroader(broader);
        DiagramPendingEdit overlay = new DiagramPendingEdit();
        overlay.setConvertToHierarchy(marker);
        return overlay;
    }

    private static DiagramDto.Edge edgeTo(List<DiagramDto.Edge> edges, String target) {
        List<DiagramDto.Edge> matching = edges.stream().filter(e -> e.target().equals("iri:" + target)).toList();
        assertThat(matching).as("edges to %s", target).hasSize(1);
        return matching.get(0);
    }

    private static DiagramDto.Edge edgeWithId(List<DiagramDto.Edge> edges, String id) {
        List<DiagramDto.Edge> matching = edges.stream().filter(e -> e.id().equals(id)).toList();
        assertThat(matching).as("edges with id %s", id).hasSize(1);
        return matching.get(0);
    }
}
