package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DraftFilterTest {

    private static final String PUB = "https://x/pub";
    private static final String DRAFT = "https://x/draft";
    private static final String NKD = "https://nkd/pojem/foreign";
    private static final LocalDateTime T = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static final String KEPT = PUB + "/pojem/kept";
    private static final String DRAFT_IN_PUB = PUB + "/pojem/draft-concept";
    private static final String IN_DRAFT = DRAFT + "/pojem/any";

    private static Concept concept(String iri, boolean published) {
        return new Concept(iri, iri.substring(iri.lastIndexOf('/') + 1), published, false, T, T);
    }

    private static Ontology ontology(String iri, boolean published, Concept... concepts) {
        return new Ontology(1L, iri, iri.substring(iri.lastIndexOf('/') + 1), published, null, null, T, T,
                List.of(concepts));
    }

    private static Node node(String conceptIri, String parent, String... visible) {
        return new Node(conceptIri, 0, 0, false, parent, false, List.of(visible));
    }

    private static Edge edge(String key) {
        return new Edge(key, List.of(new EdgeWaypoint(1, 2)));
    }

    private static Diagram diagram(String ontologyIri, List<Node> nodes, List<Edge> edges) {
        return new Diagram(UUID.randomUUID(), "d", ontologyIri, T, T, null, null, null, nodes, edges);
    }

    private static PublicationSnapshot snapshot(List<Diagram> diagrams) {
        return new PublicationSnapshot(List.of(
                ontology(PUB, true, concept(KEPT, true), concept(DRAFT_IN_PUB, false)),
                ontology(DRAFT, false, concept(IN_DRAFT, true))), diagrams, Set.of());
    }

    @Test
    void dropsDraftVocabulariesAndDraftConcepts_recordingEveryExcludedIri() {
        PublicationSnapshot filtered = DraftFilter.withoutDrafts(snapshot(List.of()));

        assertThat(filtered.ontologies()).extracting(Ontology::iri).containsExactly(PUB);
        assertThat(filtered.ontologies().get(0).concepts()).extracting(Concept::iri).containsExactly(KEPT);
        // A published-flag concept of a draft vocabulary is still a draft for publication.
        assertThat(filtered.excludedConceptIris()).containsExactlyInAnyOrder(DRAFT_IN_PUB, IN_DRAFT);
    }

    @Test
    void dropsDiagramsOfDraftVocabularies() {
        PublicationSnapshot filtered = DraftFilter.withoutDrafts(snapshot(List.of(
                diagram(PUB, List.of(), List.of()), diagram(DRAFT, List.of(), List.of()))));

        assertThat(filtered.diagrams()).extracting(Diagram::ontologyIri).containsExactly(PUB);
    }

    @Test
    void dropsNodesOfExcludedConcepts_fromAnyVocabulary_andUngroupsTheirChildren() {
        Diagram d = diagram(PUB, List.of(
                node(KEPT, DRAFT_IN_PUB, DRAFT_IN_PUB, KEPT),
                node(DRAFT_IN_PUB, null),
                node(IN_DRAFT, null),
                node(NKD, KEPT)), List.of());

        List<Node> nodes = DraftFilter.withoutDrafts(snapshot(List.of(d))).diagrams().get(0).nodes();

        assertThat(nodes).extracting(Node::conceptIri).containsExactly(KEPT, NKD);
        assertThat(nodes.get(0).parentConceptIri()).isNull();
        assertThat(nodes.get(0).visibleProperties()).containsExactly(KEPT);
        assertThat(nodes.get(1).parentConceptIri()).as("foreign NKD node and its grouping stay").isEqualTo(KEPT);
    }

    @Test
    void dropsEdgesKeyedByOrJoiningAnExcludedConcept() {
        Diagram d = diagram(PUB, List.of(), List.of(
                edge(DRAFT_IN_PUB),
                edge(KEPT),
                edge("edge|SUBCLASS|" + KEPT + "|" + DRAFT_IN_PUB),
                edge("edge|SUBCLASS|" + IN_DRAFT + "|" + KEPT),
                edge("edge|SUBCLASS|" + KEPT + "|" + NKD)));

        List<Edge> edges = DraftFilter.withoutDrafts(snapshot(List.of(d))).diagrams().get(0).edges();

        assertThat(edges).extracting(Edge::edgeKey).containsExactly(KEPT, "edge|SUBCLASS|" + KEPT + "|" + NKD);
    }

    @Test
    void compositeKeyMatchesAnEndContainingThePipeCharacter() {
        String piped = "https://x/pojem/a|b";

        assertThat(DraftFilter.touches("edge|BROADER|" + piped + "|" + KEPT, Set.of(piped))).isTrue();
        assertThat(DraftFilter.touches("edge|BROADER|" + KEPT + "|" + piped, Set.of(piped))).isTrue();
        assertThat(DraftFilter.touches("edge|BROADER|" + KEPT + "|" + NKD, Set.of(piped))).isFalse();
        assertThat(DraftFilter.touches(piped, Set.of("https://x/pojem/a"))).isFalse();
    }
}
