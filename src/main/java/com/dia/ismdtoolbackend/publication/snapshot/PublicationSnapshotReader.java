package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.DiagramEntity;
import com.dia.ismdtoolbackend.entity.DiagramNodeEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads every vocabulary, concept and diagram into a {@link PublicationSnapshot} in one read-only
 * transaction, with one query per table. Pending diagram edits, comments and user ids are never read.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicationSnapshotReader {

    private final EntityManager em;

    public PublicationSnapshot read() {
        Map<Long, List<Concept>> conceptsByOntology = new HashMap<>();
        for (Object[] row : rows("select c, c.ontologyMetadata.id from ConceptMetadataEntity c order by c.id")) {
            ConceptMetadataEntity c = (ConceptMetadataEntity) row[0];
            conceptsByOntology.computeIfAbsent((Long) row[1], id -> new ArrayList<>()).add(new Concept(
                    c.getConceptIri(), c.getSlug(), Boolean.TRUE.equals(c.getIsPublished()), c.getCreatedAt(),
                    c.getUpdatedAt()));
        }

        Map<Long, String> ontologyIris = new HashMap<>();
        List<Ontology> ontologies = new ArrayList<>();
        for (OntologyMetadataEntity o : em.createQuery(
                "select o from OntologyMetadataEntity o order by o.id", OntologyMetadataEntity.class).getResultList()) {
            ontologyIris.put(o.getId(), o.getGraphName());
            ontologies.add(new Ontology(o.getId(), o.getGraphName(), o.getSlug(),
                    Boolean.TRUE.equals(o.getIsPublished()), o.getLastValidationStatus(), o.getLastValidationAt(),
                    o.getCreatedAt(), o.getUpdatedAt(), conceptsByOntology.getOrDefault(o.getId(), List.of())));
        }

        return new PublicationSnapshot(ontologies, readDiagrams(ontologyIris), Set.of());
    }

    private List<Diagram> readDiagrams(Map<Long, String> ontologyIris) {
        Map<Long, List<DiagramNodeEntity>> nodesByDiagram = new HashMap<>();
        for (Object[] row : rows("select n, n.diagram.id from DiagramNodeEntity n order by n.id")) {
            nodesByDiagram.computeIfAbsent((Long) row[1], id -> new ArrayList<>()).add((DiagramNodeEntity) row[0]);
        }
        Map<Long, List<Edge>> edgesByDiagram = new HashMap<>();
        for (Object[] row : rows("select e.edgeKey, e.diagram.id from DiagramEdgeEntity e order by e.id")) {
            edgesByDiagram.computeIfAbsent((Long) row[1], id -> new ArrayList<>()).add(new Edge((String) row[0]));
        }

        List<Diagram> diagrams = new ArrayList<>();
        for (Object[] row : rows("select d, d.ontologyMetadata.id from DiagramEntity d order by d.id")) {
            DiagramEntity d = (DiagramEntity) row[0];
            diagrams.add(new Diagram(d.getIriId(), d.getName(), ontologyIris.get((Long) row[1]), d.getCreatedAt(),
                    d.getUpdatedAt(), d.getViewportX(), d.getViewportY(), d.getViewportZoom(),
                    nodes(nodesByDiagram.getOrDefault(d.getId(), List.of())),
                    edgesByDiagram.getOrDefault(d.getId(), List.of())));
        }
        return diagrams;
    }

    /** Resolves each node's {@code parentNodeId} to the parent's concept IRI; ids are not published. */
    private static List<Node> nodes(List<DiagramNodeEntity> entities) {
        Map<Long, String> conceptByNodeId = new HashMap<>();
        entities.forEach(n -> conceptByNodeId.put(n.getId(), n.getConceptIri()));
        return entities.stream()
                .map(n -> new Node(n.getConceptIri(), n.getPosX(), n.getPosY(),
                        n.getParentNodeId() == null ? null : conceptByNodeId.get(n.getParentNodeId()),
                        n.isForeign()))
                .toList();
    }

    private List<Object[]> rows(String jpql) {
        return em.createQuery(jpql, Object[].class).getResultList();
    }
}
