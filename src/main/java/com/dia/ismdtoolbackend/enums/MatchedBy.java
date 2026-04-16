package com.dia.ismdtoolbackend.enums;

/**
 * Indicates which search backend matched a given result within the ISMD source.
 * Useful for debugging and understanding search behavior.
 */
public enum MatchedBy {
    PG,
    SPARQL,
    BOTH
}