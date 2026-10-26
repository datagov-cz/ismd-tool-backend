package com.dia.ismdtoolbackend.utility.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SparqlIriValidatorTest {

    @Test
    void acceptsHttpAndHttps() {
        assertThat(SparqlIriValidator.isSafeHttpIri("https://slovnik.gov.cz/datovy/osoby")).isTrue();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/ontology#Thing")).isTrue();
    }

    @Test
    void rejectsInjectionAttempts() {
        assertThat(SparqlIriValidator.isSafeHttpIri(
                "http://example.org/ont } SELECT * WHERE { ?s ?p ?o"))
                .isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/>malicious")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/a\nb")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("http://example.org/a b")).isFalse();
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThat(SparqlIriValidator.isSafeHttpIri("file:///etc/passwd")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("javascript:alert(1)")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("urn:isbn:0451450523")).isFalse();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(SparqlIriValidator.isSafeHttpIri(null)).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("")).isFalse();
        assertThat(SparqlIriValidator.isSafeHttpIri("   ")).isFalse();
    }

    // --- isEsbirkaEliIri ---------------------------------------------------

    @Test
    void esbirkaEli_acceptsCanonicalLawIri() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"))
                .isTrue();
    }

    @Test
    void esbirkaEli_acceptsCanonicalVersionIri() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01"))
                .isTrue();
    }

    @Test
    void esbirkaEli_acceptsCanonicalFragmentIri() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187/2026-04-01/dokument/norma/cast_5/hlava_4/dil_3/par_122/odst_4/pism_g"))
                .isTrue();
    }

    @Test
    void esbirkaEli_rejectsCombinedSemicolonValue() {
        // Two ELIs joined by ';' into one URI (malformed upstream in NKD). Its first half matches the
        // canonical prefix, so without the ';' guard it would slip through and be stored/resolved as junk.
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2013/256/2022-09-01/dokument/norma/cast_1/par_2/pism_a"
                        + ";https://www.e-sbirka.cz/eli/cz/sb/2013/256/2022-09-01/dokument/norma/cast_1/par_2/pism_b"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsBrokenLegacyHost() {
        // Pre-#106 host (missing .gov), used by the deleted ELI_PATTERN constant.
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2006/187"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsHttpScheme() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "http://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsForeignHost() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://attacker.example.com/esel-esb/eli/cz/sb/2006/187"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsPrefixInQueryString() {
        // Prefix appears as a path/query segment, not as the IRI prefix — must reject.
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://attacker.example.com/?u=https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsNonEliPathOnCorrectHost() {
        // Same host but different path tree.
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/something-else/cz/sb/2006/187"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsControlChars() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187\n"))
                .isFalse();
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/ 187"))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsInjectionChars() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187> ?p ?o ; <"))
                .isFalse();
        assertThat(SparqlIriValidator.isEsbirkaEliIri(
                "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187\""))
                .isFalse();
    }

    @Test
    void esbirkaEli_rejectsNonHttpSchemes() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri("javascript:alert(1)")).isFalse();
        assertThat(SparqlIriValidator.isEsbirkaEliIri("file:///etc/passwd")).isFalse();
    }

    @Test
    void esbirkaEli_rejectsNullAndBlank() {
        assertThat(SparqlIriValidator.isEsbirkaEliIri(null)).isFalse();
        assertThat(SparqlIriValidator.isEsbirkaEliIri("")).isFalse();
        assertThat(SparqlIriValidator.isEsbirkaEliIri("   ")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"<", ">", "\"", "{", "}", "|", "^", "`", "\\", " ", "\t", "\u0001"})
    void rejectsEveryCharacterThatCanCloseAnIriRef(String c) {
        assertThat(SparqlIriValidator.isSafeHttpIri("https://example.org/a" + c + "b")).isFalse();
    }

    @Test
    void acceptsRawUtf8Iri() {
        assertThat(SparqlIriValidator.isSafeHttpIri("https://slovník.gov.cz/datový/řidič")).isTrue();
    }

    @Test
    void rejectsRelativeIri() {
        assertThat(SparqlIriValidator.isSafeHttpIri("/pojem/osoba")).isFalse();
    }

    // --- registry facade ----------------------------------------------------

    @Test
    void esbirkaChecksFollowThePublishedRegistry() {
        KnownSparqlEndpoint moved = new KnownSparqlEndpoint(SparqlEndpointRegistry.ESBIRKA,
                "https://esbirka.example.org", "https://esbirka.example.org/esb",
                "https://esbirka.example.org/esb/eli/");
        SparqlIriValidator.setRegistry(new SparqlEndpointRegistry() {
            @Override
            public Optional<KnownSparqlEndpoint> findByName(String name) {
                return Optional.of(moved);
            }
        });
        try {
            assertThat(SparqlIriValidator.isEsbirkaEliIri("https://esbirka.example.org/esb/eli/cz/sb/2006/187")).isTrue();
            assertThat(SparqlIriValidator.esbirkaDomain()).isEqualTo("https://esbirka.example.org");
        } finally {
            SparqlIriValidator.setRegistry(null);
        }
    }

    @Test
    void registryWithoutEsbirkaFallsBackToCanonicalHost() {
        SparqlIriValidator.setRegistry(new SparqlEndpointRegistry() {
            @Override
            public Optional<KnownSparqlEndpoint> findByName(String name) {
                return Optional.empty();
            }
        });
        try {
            assertThat(SparqlIriValidator.esbirkaDomain()).isEqualTo("https://opendata.eselpoint.gov.cz");
            assertThat(SparqlIriValidator.extractEsbirkaEliPath(
                    "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2006/187")).isEqualTo("/eli/cz/sb/2006/187");
        } finally {
            SparqlIriValidator.setRegistry(null);
        }
    }
}
