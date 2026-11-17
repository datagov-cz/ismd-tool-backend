package com.dia.ismdtoolbackend.repository;

/**
 * Statement timeout of the PG text-search queries, matching {@code search.pg-timeout-ms}'s default. The
 * search gives up waiting at its own timeout; this makes PostgreSQL stop the query too, so an abandoned
 * search does not keep its connection busy.
 */
public final class SearchQueries {

    public static final String TIMEOUT_HINT = "jakarta.persistence.query.timeout";
    public static final String TIMEOUT_MS = "10000";

    private SearchQueries() {
    }
}
