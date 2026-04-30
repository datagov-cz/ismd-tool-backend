package com.dia.ismdtoolbackend.utility.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlIriValidatorTest {

    @Test
    void acceptsHttpAndHttps() {
        assertTrue(SparqlIriValidator.isSafeHttpIri("https://slovnik.gov.cz/datovy/osoby"));
        assertTrue(SparqlIriValidator.isSafeHttpIri("http://example.org/ontology#Thing"));
    }

    @Test
    void rejectsInjectionAttempts() {
        assertFalse(SparqlIriValidator.isSafeHttpIri(
                "http://example.org/ont } SELECT * WHERE { ?s ?p ?o"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("http://example.org/>malicious"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("http://example.org/a\nb"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("http://example.org/a b"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertFalse(SparqlIriValidator.isSafeHttpIri("file:///etc/passwd"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("javascript:alert(1)"));
        assertFalse(SparqlIriValidator.isSafeHttpIri("urn:isbn:0451450523"));
    }

    @Test
    void rejectsNullAndBlank() {
        assertFalse(SparqlIriValidator.isSafeHttpIri(null));
        assertFalse(SparqlIriValidator.isSafeHttpIri(""));
        assertFalse(SparqlIriValidator.isSafeHttpIri("   "));
    }
}
