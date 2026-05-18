package com.dia.ismdtoolbackend.exception;

import lombok.Getter;

/**
 * External SPARQL endpoint is unreachable, returning errors, or returning data
 * we can't parse. Mapped to HTTP 503 by {@link com.dia.ismdtoolbackend.config.GlobalExceptionHandler}.
 *
 * <p>{@code endpointLabel} identifies which upstream failed (e.g. {@code "e-Sbírka"},
 * {@code "RPP"}); the global handler uses it to pick a Czech user-facing message.
 * Distinct from {@link JenaTDB2Exception}, which represents internal-store failures
 * (HTTP 500), and from {@link NkdEndpointException}, which carries an already-
 * Czech-rendered message rather than a label.
 */
@Getter
public class SparqlEndpointUnavailableException extends RuntimeException {

    private final String endpointLabel;

    public SparqlEndpointUnavailableException(String endpointLabel, String message) {
        super(message);
        this.endpointLabel = endpointLabel;
    }

    public SparqlEndpointUnavailableException(String endpointLabel, String message, Throwable cause) {
        super(message, cause);
        this.endpointLabel = endpointLabel;
    }
}