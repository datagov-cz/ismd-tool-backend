package com.dia.ismdtoolbackend.publication.snapshot;

import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Concept;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Diagram;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Edge;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Node;
import com.dia.ismdtoolbackend.publication.snapshot.PublicationSnapshot.Ontology;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Reads the PostgreSQL side of a publication as scalar rows, never as managed entities, each step in a
 * short read-only transaction: the vocabulary list when the snapshot is made, then one vocabulary's
 * concepts or one page of diagrams with their nodes and edges at a time. Pending diagram edits,
 * comments and user ids are never read.
 */
@Component
public class PublicationSnapshotReader {

    static final int DIAGRAM_PAGE_SIZE = 100;
    private static final String FETCH_SIZE_HINT = "org.hibernate.fetchSize";
    private static final int FETCH_SIZE = 1000;

    private final EntityManager em;
    private final TransactionTemplate readOnly;

    public PublicationSnapshotReader(EntityManager em, PlatformTransactionManager transactionManager) {
        this.em = em;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** Reads the vocabulary list now; concepts and diagrams are read when the writers ask for them. */
    public PublicationSnapshot read() {
        return new Database(inTransaction(this::ontologies));
    }

    private final class Database implements PublicationSnapshot {

        private final List<Ontology> ontologies;

        private Database(List<Ontology> ontologies) {
            this.ontologies = List.copyOf(ontologies);
        }

        @Override
        public List<Ontology> ontologies() {
            return ontologies;
        }

        @Override
        public Ontology withConcepts(Ontology ontology) {
            return ontology.withConcepts(inTransaction(() -> concepts(ontology.id())));
        }

        @Override
        public void forEachDiagram(Consumer<Diagram> action) {
            long after = Long.MIN_VALUE;
            while (true) {
                long from = after;
                DiagramPage page = inTransaction(() -> diagramPage(from));
                page.diagrams().forEach(action);
                if (page.diagrams().size() < DIAGRAM_PAGE_SIZE) {
                    return;
                }
                after = page.lastId();
            }
        }

        @Override
        public Set<String> draftConceptIris() {
            return inTransaction(PublicationSnapshotReader.this::drafts);
        }

        @Override
        public Set<String> excludedConceptIris() {
            return Set.of();
        }
    }

    private record DiagramPage(List<Diagram> diagrams, long lastId) {
    }

    private List<Ontology> ontologies() {
        List<Ontology> ontologies = new ArrayList<>();
        for (Object[] r : rows("""
                select o.id, o.graphName, o.slug, o.isPublished, o.lastValidationStatus, o.lastValidationAt,
                       o.createdAt, o.updatedAt
                from OntologyMetadataEntity o order by o.id""")) {
            ontologies.add(new Ontology((Long) r[0], (String) r[1], (String) r[2], Boolean.TRUE.equals(r[3]),
                    (OntologyValidationStatus) r[4], (Instant) r[5], (LocalDateTime) r[6], (LocalDateTime) r[7],
                    List.of()));
        }
        return ontologies;
    }

    private List<Concept> concepts(Long ontologyId) {
        return em.createQuery("""
                        select c.conceptIri, c.slug, c.isPublished, c.createdAt, c.updatedAt
                        from ConceptMetadataEntity c where c.ontologyMetadata.id = :ontologyId order by c.id""",
                        Object[].class)
                .setParameter("ontologyId", ontologyId)
                .getResultList().stream()
                .map(r -> new Concept((String) r[0], (String) r[1], Boolean.TRUE.equals(r[2]),
                        (LocalDateTime) r[3], (LocalDateTime) r[4]))
                .toList();
    }

    /** One projection query; a null publish flag reads as draft, on the concept and on its vocabulary. */
    private Set<String> drafts() {
        Set<String> drafts = new HashSet<>();
        try (Stream<String> iris = em.createQuery("""
                        select c.conceptIri from ConceptMetadataEntity c join c.ontologyMetadata o
                        where c.isPublished is null or c.isPublished = false
                           or o.isPublished is null or o.isPublished = false""", String.class)
                .setHint(FETCH_SIZE_HINT, FETCH_SIZE)
                .getResultStream()) {
            iris.forEach(drafts::add);
        }
        return drafts;
    }

    private DiagramPage diagramPage(long after) {
        List<Object[]> diagramRows = em.createQuery("""
                        select d.id, d.iriId, d.name, d.ontologyMetadata.graphName, d.createdAt, d.updatedAt,
                               d.viewportX, d.viewportY, d.viewportZoom
                        from DiagramEntity d where d.id > :after order by d.id""", Object[].class)
                .setParameter("after", after)
                .setMaxResults(DIAGRAM_PAGE_SIZE)
                .getResultList();
        if (diagramRows.isEmpty()) {
            return new DiagramPage(List.of(), after);
        }
        List<Long> ids = diagramRows.stream().map(r -> (Long) r[0]).toList();

        Map<Long, List<Object[]>> nodesByDiagram = new HashMap<>();
        for (Object[] r : em.createQuery("""
                        select n.diagram.id, n.id, n.conceptIri, n.posX, n.posY, n.parentNodeId, n.isForeign
                        from DiagramNodeEntity n where n.diagram.id in :ids order by n.id""", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            nodesByDiagram.computeIfAbsent((Long) r[0], id -> new ArrayList<>()).add(r);
        }
        Map<Long, List<Edge>> edgesByDiagram = new HashMap<>();
        for (Object[] r : em.createQuery("""
                        select e.diagram.id, e.edgeKey
                        from DiagramEdgeEntity e where e.diagram.id in :ids order by e.id""", Object[].class)
                .setParameter("ids", ids).getResultList()) {
            edgesByDiagram.computeIfAbsent((Long) r[0], id -> new ArrayList<>()).add(new Edge((String) r[1]));
        }

        Map<Long, Diagram> diagrams = new LinkedHashMap<>();
        for (Object[] r : diagramRows) {
            Long id = (Long) r[0];
            diagrams.put(id, new Diagram((UUID) r[1], (String) r[2], (String) r[3], (LocalDateTime) r[4],
                    (LocalDateTime) r[5], (Double) r[6], (Double) r[7], (Double) r[8],
                    nodes(nodesByDiagram.getOrDefault(id, List.of())), edgesByDiagram.getOrDefault(id, List.of())));
        }
        return new DiagramPage(List.copyOf(diagrams.values()), ids.get(ids.size() - 1));
    }

    /**
     * Resolves each node's {@code parentNodeId} to the parent's concept IRI within the same diagram; ids
     * are not published.
     */
    private static List<Node> nodes(List<Object[]> rows) {
        Map<Long, String> conceptByNodeId = new HashMap<>();
        rows.forEach(r -> conceptByNodeId.put((Long) r[1], (String) r[2]));
        return rows.stream()
                .map(r -> new Node((String) r[2], (Double) r[3], (Double) r[4],
                        r[5] == null ? null : conceptByNodeId.get((Long) r[5]), (Boolean) r[6]))
                .toList();
    }

    private List<Object[]> rows(String jpql) {
        return em.createQuery(jpql, Object[].class).getResultList();
    }

    private <T> T inTransaction(Supplier<T> read) {
        return readOnly.execute(status -> read.get());
    }
}
