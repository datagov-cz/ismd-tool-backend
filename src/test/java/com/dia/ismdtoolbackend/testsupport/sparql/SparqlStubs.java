package com.dia.ismdtoolbackend.testsupport.sparql;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/** WireMock responses shaped like a SPARQL endpoint's. */
public final class SparqlStubs {

    public static final String RESULTS_JSON = "application/sparql-results+json";

    private SparqlStubs() {
    }

    /** A SELECT result body ({@link SparqlJson#toJson()} or a literal string). */
    public static ResponseDefinitionBuilder sparqlJson(String body) {
        return aResponse().withHeader("Content-Type", RESULTS_JSON).withBody(body);
    }

    public static ResponseDefinitionBuilder sparqlJson(SparqlJson result) {
        return sparqlJson(result.toJson());
    }

    /** A CONSTRUCT result body. */
    public static ResponseDefinitionBuilder turtle(String body) {
        return aResponse().withHeader("Content-Type", "text/turtle").withBody(body);
    }

    public static ResponseDefinitionBuilder serverError() {
        return aResponse().withStatus(500);
    }
}
