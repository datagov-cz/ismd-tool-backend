package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Request preconditions shared by the NKD detail reads. */
@Component
@RequiredArgsConstructor
class NkdRequestGuard {

    private final NkdSparqlClient nkdSparqlClient;

    void ensureEndpointConfigured() {
        if (!nkdSparqlClient.isEndpointConfigured()) {
            throw new NkdEndpointException("NKD SPARQL endpoint není nakonfigurován.");
        }
    }

    void validateIri(String iri) {
        if (!SparqlIriValidator.isSafeHttpIri(iri)) {
            throw new IllegalArgumentException("IRI není platné http(s) URI: " + iri);
        }
    }
}
