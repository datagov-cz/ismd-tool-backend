package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes drafts from a snapshot for {@code ismd.publication.include-drafts=false}.
 *
 * <ul>
 *   <li>a draft vocabulary is dropped with all its concepts and diagrams;</li>
 *   <li>a draft concept of a published vocabulary is dropped and recorded in
 *       {@link PublicationSnapshot#excludedConceptIris()};</li>
 *   <li>a diagram node whose concept is any excluded ISMD concept is dropped, whichever vocabulary owns
 *       it; a node grouped under a dropped node is ungrouped; foreign nodes not known to ISMD stay;</li>
 *   <li>an edge is dropped when its key is, or joins, an excluded concept.</li>
 * </ul>
 */
public final class DraftFilter {

    private static final String COMPOSITE_EDGE_PREFIX = "edge|";

    private DraftFilter() {
    }

    public static PublicationSnapshot withoutDrafts(PublicationSnapshot snapshot) {
        Set<String> excluded = new HashSet<>();
        Set<String> retainedOntologyIris = new HashSet<>();
        List<Ontology> ontologies = new ArrayList<>();
        for (Ontology o : snapshot.ontologies()) {
            List<Concept> retained = new ArrayList<>();
            for (Concept c : o.concepts()) {
                if (o.published() && c.published()) {
                    retained.add(c);
                } else {
                    excluded.add(c.iri());
                }
            }
            if (o.published()) {
                retainedOntologyIris.add(o.iri());
                ontologies.add(o.withConcepts(retained));
            }
        }

        List<Diagram> diagrams = snapshot.diagrams().stream()
                .filter(d -> retainedOntologyIris.contains(d.ontologyIri()))
                .map(d -> withoutExcluded(d, excluded))
                .toList();

        return new PublicationSnapshot(ontologies, diagrams, excluded);
    }

    private static Diagram withoutExcluded(Diagram diagram, Set<String> excluded) {
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
