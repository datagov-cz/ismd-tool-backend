package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;

/** Role-tag, label and inScheme inference of {@link DetailModelNormalizer}, checked as whole-model output. */
@DisplayName("DetailModelNormalizer - role tags, labels, NKD inScheme")
class DetailModelNormalizerTest {

    private static Model normalizedForLocalDetail(String turtle) {
        Model model = Ttl.parse(turtle);
        DetailModelNormalizer.normalizeForLocalDetail(model);
        return model;
    }

    @Nested
    @DisplayName("normalizeForLocalDetail")
    class LocalDetail {

        @Test
        @DisplayName("tags a /pojem/ owl:Class as skos:Concept, slovníky:pojem and slovníky:třída")
        void owlClassTagged() {
            Model model = Ttl.parse("pojem:osoba a owl:Class .");

            int count = DetailModelNormalizer.normalizeForLocalDetail(model);

            assertIsomorphic("pojem:osoba a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída .", model);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves a fully tagged class unchanged and counts nothing")
        void taggedClassUnchanged() {
            String input = "pojem:osoba a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída .";
            Model model = Ttl.parse(input);

            int count = DetailModelNormalizer.normalizeForLocalDetail(model);

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

    @Test
    @DisplayName("normalizeForLocalDetail never derives skos:inScheme; normalizeForNkd does, for every OWL kind")
    void inSchemeDerivedOnlyForNkd() {
        String input = """
                pojem:třída a owl:Class, slovníky:pojem .
                pojem:vlastnost a owl:DatatypeProperty, slovníky:pojem .
                pojem:vztah a owl:ObjectProperty, slovníky:pojem .
                """;
        Model local = normalizedForLocalDetail(input);
        Model nkd = Ttl.parse(input);
        DetailModelNormalizer.normalizeForNkd(nkd);

        assertThat(local.listSubjectsWithProperty(SKOS.inScheme).toList()).isEmpty();
        assertThat(nkd.listSubjectsWithProperty(SKOS.inScheme).toList()).hasSize(3);
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

            DetailModelNormalizer.normalizeForNkd(model);

            assertIsomorphic("""
                    pojem:osoba a skos:Concept ; skos:inScheme <https://slovník.gov.cz/datový/test-slovník> .
                    pojem:adresa a skos:Concept ; skos:inScheme <https://example.org/jiný> .
                    <https://example.org/model/Thing> a skos:Concept .
                    [ a skos:Concept ] .
                    """, model);
        }
    }
}
