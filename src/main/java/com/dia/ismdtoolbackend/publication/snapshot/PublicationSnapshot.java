package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The PostgreSQL side of one publication. The vocabulary list is small and held whole; concepts are
 * loaded per vocabulary and diagrams visited one at a time, so the writers keep memory flat in the
 * number of vocabularies and diagrams. Carries no user or comment fields, so excluded data cannot reach
 * the published files through a shared model.
 */
public interface PublicationSnapshot {

    /** Every vocabulary by id; its {@link Ontology#concepts()} may be empty until {@link #withConcepts}. */
    List<Ontology> ontologies();

    /** The vocabulary with its concepts by id. Called from several threads at once. */
    Ontology withConcepts(Ontology ontology);

    /** Visits every diagram by id, with its nodes and edges by id. */
    void forEachDiagram(Consumer<Diagram> action);

    /** Concepts of draft vocabularies and draft concepts of published ones: what the draft filter leaves out. */
    Set<String> draftConceptIris();

    /**
     * Concepts left out of this snapshot; their RDF is still in the vocabulary's graph, so the vocabulary
     * writer removes them from the downloaded content. Empty unless the snapshot is filtered.
     */
    Set<String> excludedConceptIris();

    /** A snapshot held in memory; its vocabularies carry their concepts. */
    static PublicationSnapshot of(List<Ontology> ontologies, List<Diagram> diagrams, Set<String> excludedConceptIris) {
        return new InMemory(ontologies, diagrams, excludedConceptIris);
    }

    /**
     * @param id internal id, used only to download the vocabulary's content; never published
     * @param iri the vocabulary's graph name
     */
    record Ontology(Long id, String iri, String slug, boolean published,
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

    record Concept(String iri, String slug, boolean published, LocalDateTime createdAt,
                   LocalDateTime updatedAt) {
    }

    /** Saved layout only; viewport fields are null until the canvas is first saved. */
    record Diagram(UUID iriId, String name, String ontologyIri, LocalDateTime createdAt,
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
    record Node(String conceptIri, double x, double y, String parentConceptIri, boolean foreign) {
    }

    /** @param edgeKey a VZTAH's concept IRI, or the client-assembled {@code edge|KIND|source|target} */
    record Edge(String edgeKey) {
    }

    record InMemory(List<Ontology> ontologies, List<Diagram> diagrams, Set<String> excludedConceptIris)
            implements PublicationSnapshot {

        public InMemory {
            ontologies = List.copyOf(ontologies);
            diagrams = List.copyOf(diagrams);
            excludedConceptIris = Collections.unmodifiableSet(new HashSet<>(excludedConceptIris));
        }

        @Override
        public Ontology withConcepts(Ontology ontology) {
            return ontology;
        }

        @Override
        public void forEachDiagram(Consumer<Diagram> action) {
            diagrams.forEach(action);
        }

        @Override
        public Set<String> draftConceptIris() {
            Set<String> drafts = new HashSet<>();
            for (Ontology o : ontologies) {
                for (Concept c : o.concepts()) {
                    if (!o.published() || !c.published()) {
                        drafts.add(c.iri());
                    }
                }
            }
            return drafts;
        }
    }
}
