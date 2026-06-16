package com.dia.ismdtoolbackend.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Statement;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Enqueues a TDB2 mutation as an {@link OutboxEntry} row. This is the write half of the
 * transactional outbox: it is called from inside a business {@code @Transactional} method, so the
 * outbox row and the Postgres metadata change commit atomically — either both land or neither does.
 *
 * <p><b>Invariant — this class is deliberately NOT {@code @Transactional} and must never become so.</b>
 * It persists via {@link OutboxEntryRepository#save} into the caller's transaction. Annotating it
 * (or calling it outside a transaction) would break the atomicity guarantee the whole pattern rests
 * on — the row could commit independently of the metadata change. The caller owns the transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OutboxEntryRepository repository;

    /**
     * Enqueues a concept upsert: remove {@code statementsToRemove}, add {@code statementsToAdd}.
     * Covers create (empty remove set) and edit/rename (the editor's exact change sets). The
     * triples are serialized to N-Triples so the relay applies precisely these and nothing else —
     * NOT a whole-graph replace.
     */
    public OutboxEntry enqueueUpsert(String graphName, String aggregateIri,
                                     Set<Statement> statementsToRemove, Set<Statement> statementsToAdd) {
        OutboxEntry entry = newEntry(graphName, aggregateIri, OutboxOperation.UPSERT_CONCEPT);
        entry.setDeleteTriples(OutboxTriples.toNTriples(statementsToRemove));
        entry.setInsertTriples(OutboxTriples.toNTriples(statementsToAdd));
        return persist(entry);
    }

    /**
     * Enqueues deletion of a set of concept IRIs (and their related triples) from a graph.
     */
    public void enqueueDeleteConcepts(String graphName, String aggregateIri, List<String> conceptIris) {
        OutboxEntry entry = newEntry(graphName, aggregateIri, OutboxOperation.DELETE_CONCEPTS);
        entry.setTargetIris(writeIriListJson(conceptIris));
        persist(entry);
    }

    /**
     * Enqueues deletion of an entire named graph (aggregate = the graph itself; no payload).
     */
    public void enqueueDeleteGraph(String graphName) {
        persist(newEntry(graphName, graphName, OutboxOperation.DELETE_GRAPH));
    }

    private OutboxEntry newEntry(String graphName, String aggregateIri, OutboxOperation operation) {
        OutboxEntry entry = new OutboxEntry();
        entry.setGraphName(graphName);
        entry.setAggregateIri(aggregateIri);
        entry.setOperation(operation);
        entry.setStatus(OutboxStatus.PENDING);
        entry.setCreatedAt(Instant.now());
        entry.setSeq(repository.nextSeq());
        return entry;
    }

    private OutboxEntry persist(OutboxEntry entry) {
        OutboxEntry saved = repository.save(entry);
        log.debug("Outbox enqueued: op={} aggregate={} graph={} seq={}",
                saved.getOperation(), saved.getAggregateIri(), saved.getGraphName(), saved.getSeq());
        return saved;
    }

    private String writeIriListJson(List<String> conceptIris) {
        try {
            return OBJECT_MAPPER.writeValueAsString(conceptIris == null ? List.of() : conceptIris);
        } catch (JsonProcessingException e) {
            // A plain list of IRI strings cannot realistically fail to serialize; treat as a bug.
            throw new IllegalStateException("Failed to serialize concept IRI list for outbox", e);
        }
    }
}
