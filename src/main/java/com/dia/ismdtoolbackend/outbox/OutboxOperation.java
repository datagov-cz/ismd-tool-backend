package com.dia.ismdtoolbackend.outbox;

/**
 * The kind of TDB2/Fuseki mutation a single {@link OutboxEntry} represents. Each is idempotent so
 * the relay can apply it at-least-once and converge (see the outbox plan §Relay).
 */
public enum OutboxOperation {

    /**
     * Concept-scoped delta: remove {@code delete_triples}, add {@code insert_triples}, applied as
     * one {@code DELETE DATA; INSERT DATA} request. Covers create (empty delete set) and edit
     * (incl. rename, whose sets relocate the old IRI's outgoing AND incoming edges onto the new
     * IRI). Deliberately NOT a whole-graph PUT — that would lost-update concurrent edits to other
     * concepts in the same graph under an async relay.
     */
    UPSERT_CONCEPT,

    /** Initial whole-graph PUT; later graph mutations wait until this row is DONE. */
    CREATE_GRAPH,

    /** Delete a set of concept IRIs (and their related triples) from a graph. */
    DELETE_CONCEPTS,

    /**
     * Delete an entire named graph. Graph-scoped: the relay applies it only after every pending
     * row for the same graph has applied (a barrier), never reordering around it.
     */
    DELETE_GRAPH
}
