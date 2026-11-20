package com.dia.ismdtoolbackend.exception;

/**
 * A PostgreSQL record exists but its RDF data does not: the vocabulary graph is empty, or the concept is absent
 * from it. A PG↔TDB2 inconsistency, so 500 rather than 404 — the resource exists, its data is missing.
 */
public class RdfDataMissingException extends RuntimeException {
    public RdfDataMissingException(String message) {
        super(message);
    }
}
