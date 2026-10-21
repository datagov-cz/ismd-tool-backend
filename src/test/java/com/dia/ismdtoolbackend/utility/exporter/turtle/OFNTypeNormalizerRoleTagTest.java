package com.dia.ismdtoolbackend.utility.exporter.turtle;

import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Role-tag and label inference of {@link OFNTypeNormalizer}, checked as whole-model output, and the
 * argument guards of the upload-path entry points.
 */
@DisplayName("OFNTypeNormalizer - role tags, labels and guards")
class OFNTypeNormalizerRoleTagTest {

    private static final String GRAPH = Ttl.VOCABULARY;

    private static Model normalizedForLocalDetail(String turtle) {
        Model model = Ttl.parse(turtle);
        OFNTypeNormalizer.normalizeForLocalDetail(model);
        return model;
    }

    @Nested
    @DisplayName("normalizeForLocalDetail")
    class LocalDetail {

        @Test
        @DisplayName("tags a /pojem/ owl:Class as skos:Concept, slovníky:pojem and slovníky:třída")
        void owlClassTagged() {
            Model model = Ttl.parse("pojem:osoba a owl:Class .");

            int count = OFNTypeNormalizer.normalizeForLocalDetail(model);

            assertIsomorphic("pojem:osoba a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída .", model);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves a fully tagged class unchanged and counts nothing")
        void taggedClassUnchanged() {
            String input = "pojem:osoba a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída .";
            Model model = Ttl.parse(input);

            int count = OFNTypeNormalizer.normalizeForLocalDetail(model);

            assertIsomorphic(input, model);
            assertThat(count).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "<http://www.w3.org/2002/07/pojem/x>",
                "<https://slovník.gov.cz/veřejný-sektor/pojem/typ-subjektu-práva>",
                "<https://slovník.gov.cz/generický/adresy/pojem/adresa>",
                "<https://example.org/cz:třída/pojem/x>",
                "<https://example.org/cz:pojem/pojem/x>",
                "<https://example.org/model/Osoba>",
        })
        @DisplayName("does not tag base-vocabulary or non-/pojem/ classes")
        void baseVocabularyClassNotTagged(String classIri) {
            String input = classIri + " a owl:Class .";

            assertIsomorphic(input, normalizedForLocalDetail(input));
        }

        @Test
        @DisplayName("ignores anonymous classes and properties")
        void anonymousResourcesIgnored() {
            String input = """
                    [ a owl:Class ] . [ a owl:ObjectProperty ] . [ a owl:DatatypeProperty ] . [ a slovníky:pojem ] .
                    """;

            assertIsomorphic(input, normalizedForLocalDetail(input));
        }

        @Test
        @DisplayName("tags /pojem/ object properties as vztah and datatype properties as vlastnost")
        void propertiesTagged() {
            Model result = normalizedForLocalDetail("""
                    pojem:má-adresu a owl:ObjectProperty .
                    pojem:jméno a owl:DatatypeProperty .
                    pojem:dual a owl:ObjectProperty, owl:DatatypeProperty .
                    pojem:tagged a owl:ObjectProperty, slovníky:vztah .
                    <https://example.org/model/prop> a owl:ObjectProperty, owl:DatatypeProperty .
                    """);

            assertIsomorphic("""
                    pojem:má-adresu a owl:ObjectProperty, slovníky:vztah .
                    pojem:jméno a owl:DatatypeProperty, slovníky:vlastnost .
                    pojem:dual a owl:ObjectProperty, owl:DatatypeProperty, slovníky:vztah .
                    pojem:tagged a owl:ObjectProperty, slovníky:vztah .
                    <https://example.org/model/prop> a owl:ObjectProperty, owl:DatatypeProperty .
                    """, result);
        }

        @Test
        @DisplayName("adds skos:Concept to every named slovníky:pojem and moves its rdfs:label to skos:prefLabel")
        void pojemGetsSkosConceptAndPrefLabel() {
            Model result = normalizedForLocalDetail("""
                    pojem:osoba a slovníky:pojem ; rdfs:label "Osoba"@cs .
                    pojem:adresa a slovníky:pojem, skos:Concept .
                    <https://example.org/model/Thing> rdfs:label "Thing"@en .
                    """);

            assertIsomorphic("""
                    pojem:osoba a slovníky:pojem, skos:Concept ; skos:prefLabel "Osoba"@cs .
                    pojem:adresa a slovníky:pojem, skos:Concept .
                    <https://example.org/model/Thing> rdfs:label "Thing"@en .
                    """, result);
        }
    }

    @Nested
    @DisplayName("normalizeForNkd")
    class Nkd {

        @Test
        @DisplayName("derives inScheme from /pojem/ only, skipping blank nodes, IRIs without /pojem/ and set schemes")
        void derivesInSchemeFromPojemSegment() {
            Model model = Ttl.parse("""
                    pojem:osoba a skos:Concept .
                    pojem:adresa a skos:Concept ; skos:inScheme <https://example.org/jiný> .
                    <https://example.org/model/Thing> a skos:Concept .
                    [ a skos:Concept ] .
                    """);

            OFNTypeNormalizer.normalizeForNkd(model);

            assertIsomorphic("""
                    pojem:osoba a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> .
                    pojem:adresa a skos:Concept ; skos:inScheme <https://example.org/jiný> .
                    <https://example.org/model/Thing> a skos:Concept .
                    [ a skos:Concept ] .
                    """, model);
        }
    }

    @Nested
    @DisplayName("upload path")
    class UploadPath {

        @ParameterizedTest(name = "graphName \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = "  ")
        @DisplayName("every entry point rejects a missing graph name")
        void missingGraphNameRejected(String graphName) {
            Model model = Ttl.parse("pojem:osoba a skos:Concept .");

            assertThatThrownBy(() -> OFNTypeNormalizer.normalize(model, graphName))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OFNTypeNormalizer.normalize(model, graphName, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(model, graphName))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, graphName))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null allow-list stamps no inScheme")
        void nullAllowListStampsNothing() {
            Model model = Ttl.parse("pojem:osoba a skos:Concept .");

            OFNTypeNormalizer.normalize(model, GRAPH, null);

            assertIsomorphic("pojem:osoba a skos:Concept .", model);
        }

        @Test
        @DisplayName("detection ignores anonymous concepts and counts a concept typed twice once")
        void detectionSkipsBlankNodesAndDuplicates() {
            Model model = Ttl.parse("""
                    pojem:osoba a skos:Concept, slovníky:pojem .
                    [ a skos:Concept ] .
                    """);

            assertThat(OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(model, GRAPH))
                    .containsExactly(Ttl.POJEM + "osoba");
        }

        @Test
        @DisplayName("prune removes an excluded concept referenced only by itself")
        void selfReferenceDoesNotKeepExcludedConcept() {
            Model model = Ttl.parse("""
                    pojem:osoba a skos:Concept ; skos:related pojem:osoba .
                    """);

            int pruned = OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, GRAPH);

            assertThat(pruned).isEqualTo(1);
            assertThat(model.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("prune with nothing excluded leaves the model unchanged")
        void pruneWithNothingExcluded() {
            String input = "pojem:osoba a skos:Concept ; skos:inScheme ex: .";
            Model model = Ttl.parse(input);

            assertThat(OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, GRAPH)).isZero();
            assertIsomorphic(input, model);
        }
    }

    @ParameterizedTest(name = "isOwnedConcept({0}, {1}) = {2}")
    @CsvSource(nullValues = "null", value = {
            "https://slovník.gov.cz/datový/test-slovník/pojem/osoba, https://slovník.gov.cz/datový/test-slovník/, true",
            "https://example.org/jiný/pojem/osoba, https://slovník.gov.cz/datový/test-slovník/, false",
            "null, https://slovník.gov.cz/datový/test-slovník/, false",
            "https://slovník.gov.cz/datový/test-slovník/pojem/osoba, null, false",
    })
    @DisplayName("isOwnedConcept is an IRI-prefix check")
    void isOwnedConceptIsPrefixCheck(String conceptIri, String graphName, boolean owned) {
        assertThat(OFNTypeNormalizer.isOwnedConcept(conceptIri, graphName)).isEqualTo(owned);
    }
}
