package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.models.diagram.EdgeWaypoint;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The PostgreSQL side of one publication, read in one transaction. Carries no user or comment fields, so
 * excluded data cannot reach the published files through a shared model.
 *
 * @param excludedConceptIris concepts left out by the draft filter; their RDF is still in the vocabulary's
 *                            graph, so the vocabulary writer removes them from the downloaded content
 */
public record PublicationSnapshot(List<Ontology> ontologies, List<Diagram> diagrams, Set<String> excludedConceptIris) {

    public PublicationSnapshot {
        ontologies = List.copyOf(ontologies);
        diagrams = List.copyOf(diagrams);
        excludedConceptIris = Set.copyOf(excludedConceptIris);
    }

    /**
     * @param id internal id, used only to download the vocabulary's content; never published
     * @param iri the vocabulary's graph name
     */
    public record Ontology(Long id, String iri, String slug, boolean published,
                           OntologyValidationStatus lastValidationStatus, Instant lastValidationAt,
                           LocalDateTime createdAt, LocalDateTime updatedAt, List<Concept> concepts) {

        public Ontology {
            concepts = List.copyOf(concepts);
        }

        public Ontology withConcepts(List<Concept> retained) {
            return new Ontology(id, iri, slug, published, lastValidationStatus, lastValidationAt, createdAt,
                    updatedAt, retained);
        }
    }

    public record Concept(String iri, String slug, boolean published, boolean inTezaurus,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** Saved layout only; viewport fields are null until the canvas is first saved. */
    public record Diagram(UUID iriId, String name, String ontologyIri, LocalDateTime createdAt,
                          LocalDateTime updatedAt, Double viewportX, Double viewportY, Double viewportZoom,
                          List<Node> nodes, List<Edge> edges) {

        public Diagram {
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
        }

        public Diagram withLayout(List<Node> retainedNodes, List<Edge> retainedEdges) {
            return new Diagram(iriId, name, ontologyIri, createdAt, updatedAt, viewportX, viewportY, viewportZoom,
                    retainedNodes, retainedEdges);
        }
    }

    /** @param parentConceptIri concept of the node this one is grouped under, or null */
    public record Node(String conceptIri, double x, double y, boolean collapsed, String parentConceptIri,
                       boolean foreign, List<String> visibleProperties) {

        public Node {
            visibleProperties = List.copyOf(visibleProperties);
        }
    }

    /** @param edgeKey a VZTAH's concept IRI, or the client-assembled {@code edge|KIND|source|target} */
    public record Edge(String edgeKey, List<EdgeWaypoint> segments) {

        public Edge {
            segments = List.copyOf(segments);
        }
    }
}
