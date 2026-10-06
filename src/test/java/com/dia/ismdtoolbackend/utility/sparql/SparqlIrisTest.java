package com.dia.ismdtoolbackend.utility.sparql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the normalization that decides whether a concept-IRI lookup matches anything at all.
 *
 * <p>Virtuoso compares IRIs as strings, so the percent-encoded and raw forms are different
 * IRIs and only the raw one is stored. Verified against the live catalogue: an ASK for
 * {@code …/zdroj/datové-sady/00006947/095ba5ff…} is true, and the same ASK with
 * {@code datov%C3%A9-sady} is false.
 */
class SparqlIrisTest {

    /** The case that motivates the class: a FE {@code encodeURIComponent} link must still match. */
    @Test
    void decodesPercentEncodedCzechIri() {
        assertThat(SparqlSolutions.toRawUtf8(
                "https://slovn%C3%ADk.gov.cz/generick%C3%BD/pojem/%C4%8D%C3%ADslo"))
                .isEqualTo("https://slovník.gov.cz/generický/pojem/číslo");
    }

    /** Applied unconditionally on the request path, so it must not disturb an already-raw IRI. */
    @Test
    void leavesRawUtf8IriUnchanged() {
        String raw = "https://slovník.gov.cz/generický/pojem/číslo";

        assertThat(SparqlSolutions.toRawUtf8(raw)).isEqualTo(raw);
    }

    @Test
    void isIdempotent() {
        String encoded = "https://data.gov.cz/zdroj/datov%C3%A9-sady/00006947/095ba5ff";

        String once = SparqlSolutions.toRawUtf8(encoded);

        assertThat(SparqlSolutions.toRawUtf8(once)).isEqualTo(once);
    }

    /**
     * Decoding twice would unwrap {@code %2520} to a literal space and produce an IRI the
     * caller never sent. One pass must leave the inner escape intact.
     */
    @Test
    void decodesOnlyOnce() {
        assertThat(SparqlSolutions.toRawUtf8("https://example.org/a%2520b"))
                .isEqualTo("https://example.org/a%20b");
    }

    /**
     * {@link java.net.URLDecoder} is a form decoder and turns {@code +} into a space; in an IRI
     * path a {@code +} is a literal plus, and mangling it would break the match.
     */
    @Test
    void preservesLiteralPlus() {
        assertThat(SparqlSolutions.toRawUtf8("https://example.org/a+b/%C4%8D"))
                .isEqualTo("https://example.org/a+b/č");
    }

    /** A malformed escape is matched as given rather than throwing onto the request path. */
    @Test
    void passesThroughMalformedEscape() {
        assertThat(SparqlSolutions.toRawUtf8("https://example.org/100%")).isEqualTo("https://example.org/100%");
        assertThat(SparqlSolutions.toRawUtf8("https://example.org/%zz")).isEqualTo("https://example.org/%zz");
    }

    @Test
    void toleratesNull() {
        assertThat(SparqlSolutions.toRawUtf8(null)).isNull();
    }
}