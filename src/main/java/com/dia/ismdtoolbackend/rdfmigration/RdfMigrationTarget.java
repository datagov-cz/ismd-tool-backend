package com.dia.ismdtoolbackend.rdfmigration;

import java.util.List;

/** The RDF store the changesets are applied to, and the write queue that feeds it. */
public interface RdfMigrationTarget {

    /** The ISMD vocabulary graphs (PostgreSQL), in a stable order. */
    List<String> graphNames();

    /** The named graphs that exist in the store. Throws when the store cannot be read. */
    List<String> storedGraphNames();

    /**
     * Outbox rows not yet applied (PENDING or FAILED). They hold finished pre-migration RDF that the relay
     * would write on top of migrated data, so a migration must not run while any exist.
     */
    long unsettledWrites();

    /** Runs one SPARQL Update request (atomic per request). Throws on failure. */
    void update(String sparqlUpdate);
}
