package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Removes drafts from a snapshot for {@code ismd.publication.include-drafts=false}, as the snapshot is read.
 *
 * <ul>
 *   <li>a draft vocabulary is dropped with all its concepts and diagrams;</li>
 *   <li>a draft concept of a published vocabulary is dropped and recorded in
 *       {@link PublicationSnapshot#excludedConceptIris()};</li>
 *   <li>a diagram node whose concept is any excluded ISMD concept is dropped, whichever vocabulary owns
 *       it; a node grouped under a dropped node is ungrouped; foreign nodes not known to ISMD stay;</li>
 *   <li>an edge is dropped when its key is, or joins, an excluded concept.</li>
 * </ul>
 *
 * The excluded set is read once, when the filter is made; a concept is kept only when it is published
 * and not in that set, so the vocabulary content and its concept count agree.
 */
public final class DraftFilter implements PublicationSnapshot {

    private static final String COMPOSITE_EDGE_PREFIX = "edge|";

    private final PublicationSnapshot source;
    private final Set<String> excluded;
    private final List<Ontology> ontologies;
    private final Set<String> retainedOntologyIris;

    private DraftFilter(PublicationSnapshot source) {
        this.source = source;
        // a HashSet view, not Set.copyOf: DRAFT nodes and ungrouped nodes look up null
        this.excluded = Collections.unmodifiableSet(new HashSet<>(source.draftConceptIris()));
        this.ontologies = source.ontologies().stream()
                .filter(Ontology::published)
                .map(this::retained)
                .toList();
        this.retainedOntologyIris = ontologies.stream().map(Ontology::iri).collect(Collectors.toUnmodifiableSet());
    }

    public static PublicationSnapshot withoutDrafts(PublicationSnapshot snapshot) {
        return new DraftFilter(snapshot);
    }

    @Override
    public List<Ontology> ontologies() {
        return ontologies;
    }

    @Override
    public Ontology withConcepts(Ontology ontology) {
        return retained(source.withConcepts(ontology));
    }

    @Override
    public void forEachDiagram(Consumer<Diagram> action) {
        source.forEachDiagram(d -> {
            if (retainedOntologyIris.contains(d.ontologyIri())) {
                action.accept(withoutExcluded(d));
            }
        });
    }

    @Override
    public Set<String> draftConceptIris() {
        return excluded;
    }

    @Override
    public Set<String> excludedConceptIris() {
        return excluded;
    }

    private Ontology retained(Ontology ontology) {
        List<Concept> retained = ontology.concepts().stream()
                .filter(c -> c.published() && !excluded.contains(c.iri()))
                .toList();
        return ontology.withConcepts(retained);
    }

    private Diagram withoutExcluded(Diagram diagram) {
        List<Node> nodes = diagram.nodes().stream()
                .filter(n -> !excluded.contains(n.conceptIri()))
                .map(n -> excluded.contains(n.parentConceptIri())
                        ? new Node(n.conceptIri(), n.x(), n.y(), null, n.foreign())
                        : n)
                .toList();
        List<Edge> edges = diagram.edges().stream()
                .filter(e -> !touches(e.edgeKey(), excluded))
                .toList();
        return diagram.withLayout(nodes, edges);
    }

    /**
     * True when the key is an excluded IRI, or is a composite {@code edge|KIND|source|target} with an
     * excluded end. IRIs may themselves contain {@code |}, so every split point of {@code source|target}
     * is tried.
     */
    static boolean touches(String edgeKey, Set<String> excluded) {
        if (excluded.contains(edgeKey)) {
            return true;
        }
        if (!edgeKey.startsWith(COMPOSITE_EDGE_PREFIX)) {
            return false;
        }
        int kindEnd = edgeKey.indexOf('|', COMPOSITE_EDGE_PREFIX.length());
        if (kindEnd < 0) {
            return false;
        }
        String ends = edgeKey.substring(kindEnd + 1);
        for (int i = ends.indexOf('|'); i >= 0; i = ends.indexOf('|', i + 1)) {
            if (excluded.contains(ends.substring(0, i)) || excluded.contains(ends.substring(i + 1))) {
                return true;
            }
        }
        return false;
    }
}
