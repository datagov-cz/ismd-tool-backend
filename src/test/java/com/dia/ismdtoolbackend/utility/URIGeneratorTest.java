package com.dia.ismdtoolbackend.utility;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.DEFAULT_NS;
import static org.assertj.core.api.Assertions.assertThat;

class URIGeneratorTest {

    private static final String NS = "https://example.com/slovnik";

    private final URIGenerator generator = new URIGenerator();

    @BeforeEach
    void setUp() {
        generator.setEffectiveNamespace(NS + "/");
    }

    @Test
    void defaultNamespace() {
        assertThat(new URIGenerator().getEffectiveNamespace()).isEqualTo(DEFAULT_NS);
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.com/slovnik/pojem/adresa/, " + NS + "/pojem/adresa",
            "https://jinde.cz/slovnik/pojem/adresa, https://jinde.cz/slovnik/pojem/adresa",
            "https://jinde.cz/adresa/, " + NS + "/pojem/adresa",
            "urn:adresa, " + NS + "/pojem/urn:adresa",
            "' Adresa Bydliště ', " + NS + "/pojem/adresa-bydliště"
    })
    void generateConceptURI_fromIdentifier(String identifier, String expected) {
        assertThat(generator.generateConceptURI("Ignorováno", identifier)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void generateConceptURI_withoutIdentifier_usesName(String identifier) {
        assertThat(generator.generateConceptURI("Adresa Bydliště", identifier))
                .isEqualTo(NS + "/pojem/adresa-bydliště");
    }

    @Test
    void generateConceptURI_namespaceWithoutTrailingSlash() {
        generator.setEffectiveNamespace(NS);
        assertThat(generator.generateConceptURI("Adresa", null)).isEqualTo(NS + "/pojem/adresa");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void generateVocabularyURI_blankName_isEffectiveNamespace(String name) {
        assertThat(generator.generateVocabularyURIFromGivenNamespace(name, "https://jinde.cz"))
                .isEqualTo(NS + "/");
    }

    @ParameterizedTest
    @CsvSource({
            "https://jinde.cz/, https://jinde.cz/můj-slovník",
            "https://jinde.cz, https://jinde.cz/můj-slovník",
            "'   ', " + NS + "/můj-slovník"
    })
    void generateVocabularyURI_givenNamespace(String namespace, String expected) {
        assertThat(generator.generateVocabularyURIFromGivenNamespace("Můj slovník", namespace)).isEqualTo(expected);
    }

    @Test
    void generateVocabularyURI_nullNamespace_usesEffectiveNamespace() {
        assertThat(generator.generateVocabularyURIFromGivenNamespace("Můj slovník", null))
                .isEqualTo(NS + "/můj-slovník");
    }
}
