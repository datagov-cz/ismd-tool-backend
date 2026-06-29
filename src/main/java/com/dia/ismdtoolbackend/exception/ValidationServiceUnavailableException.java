package com.dia.ismdtoolbackend.exception;

import lombok.Getter;

/**
 * The external validator service is unreachable, timing out, returning a 5xx, or returning
 * a body we can't use. Mapped to HTTP 503 by
 * {@link com.dia.ismdtoolbackend.config.GlobalExceptionHandler}.
 *
 * <p>Distinct from {@link OntologyValidationException} (HTTP 400), which the validator path
 * uses when the validator <em>answered</em> with a 4xx rejection (e.g. malformed TTL) — that
 * is a client-input problem, not an outage. This separation lets the tool surface "validator
 * is down" (503, retryable) differently from "your ontology was rejected" (400, with the
 * validator's own reason), and lets the circuit breaker count only genuine outages.
 *
 * <p>The validator-flavoured analogue of {@link SparqlEndpointUnavailableException}; kept a
 * separate type so no SPARQL-named exception leaks into the validator integration.
 */
@Getter
public class ValidationServiceUnavailableException extends RuntimeException {

    private final String endpointLabel;

    public ValidationServiceUnavailableException(String endpointLabel, String message) {
        super(message);
        this.endpointLabel = endpointLabel;
    }

    public ValidationServiceUnavailableException(String endpointLabel, String message, Throwable cause) {
        super(message, cause);
        this.endpointLabel = endpointLabel;
    }
}
