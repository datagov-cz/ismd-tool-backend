package com.dia.ismdtoolbackend.rdfmigration;

import java.util.List;

/** The RDF store the changesets are applied to. */
public interface RdfMigrationTarget {

    /** The ISMD vocabulary graphs, in a stable order. */
    List<String> graphNames();

    /** Runs one SPARQL Update request (atomic per request). Throws on failure. */
    void update(String sparqlUpdate);
}
