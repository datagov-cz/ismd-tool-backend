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

    // --- isEsbirkaEliIri ---------------------------------------------------

    @Test
    void esbirkaEli_acceptsCanonicalLawIri() {
        assertTrue(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"));
    }

    @Test
    void esbirkaEli_acceptsCanonicalVersionIri() {
        assertTrue(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01"));
    }

    @Test
    void esbirkaEli_acceptsCanonicalFragmentIri() {
        assertTrue(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01/dokument/norma/cast_5/hlava_4/dil_3/par_122/odst_4/pism_g"));
    }

    @Test
    void esbirkaEli_rejectsBrokenLegacyHost() {
        // Pre-#106 host (missing .gov), used by the deleted ELI_PATTERN constant.
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2006/187"));
    }

    @Test
    void esbirkaEli_rejectsHttpScheme() {
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "http://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"));
    }

    @Test
    void esbirkaEli_rejectsForeignHost() {
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://attacker.example.com/esel-esb/eli/cz/sb/2006/187"));
    }

    @Test
    void esbirkaEli_rejectsPrefixInQueryString() {
        // Prefix appears as a path/query segment, not as the IRI prefix — must reject.
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://attacker.example.com/?u=https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"));
    }

    @Test
    void esbirkaEli_rejectsNonEliPathOnCorrectHost() {
        // Same host but different path tree.
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/something-else/cz/sb/2006/187"));
    }

    @Test
    void esbirkaEli_rejectsControlChars() {
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187\n"));
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/ 187"));
    }

    @Test
    void esbirkaEli_rejectsInjectionChars() {
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187> ?p ?o ; <"));
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187\""));
    }

    @Test
    void esbirkaEli_rejectsNonHttpSchemes() {
        assertFalse(SparqlIriValidator.isEsbirkaEliIri("javascript:alert(1)"));
        assertFalse(SparqlIriValidator.isEsbirkaEliIri("file:///etc/passwd"));
    }

    @Test
    void esbirkaEli_rejectsNullAndBlank() {
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(null));
        assertFalse(SparqlIriValidator.isEsbirkaEliIri(""));
        assertFalse(SparqlIriValidator.isEsbirkaEliIri("   "));
    }
}
