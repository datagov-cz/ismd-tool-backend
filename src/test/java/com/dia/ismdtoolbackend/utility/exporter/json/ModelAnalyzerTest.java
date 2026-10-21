package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.ModelProcessingException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.dia.constants.VocabularyConstants.DEFAULT_NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ModelAnalyzer.analyzeModel")
class ModelAnalyzerTest {

    private final ModelAnalyzer analyzer = new ModelAnalyzer();

    private ModelStructure analyze(String turtle) {
        return analyzer.analyzeModel(Ttl.parse(turtle));
    }

    @Nested
    @DisplayName("finds the vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("as the owl:Ontology, with its IRI and namespace")
        void ontologyIsVocabulary() {
            ModelStructure structure = analyze("ex: a owl:Ontology .");

            assertThat(structure.getOntologyIRI()).isEqualTo(Ttl.VOCABULARY);
            assertThat(structure.getEffectiveNamespace()).isEqualTo(Ttl.VOCABULARY);
            assertThat(structure.getVocabularyResource().getURI()).isEqualTo(Ttl.VOCABULARY);
            assertThat(structure.getResourceMap()).containsKey("ontology");
        }

        @Test
        @DisplayName("as the skos:ConceptScheme when there is no owl:Ontology")
        void conceptSchemeIsVocabulary() {
            ModelStructure structure = analyze("<https://example.org/slovník/a> a skos:ConceptScheme .");

            assertThat(structure.getOntologyIRI()).isEqualTo("https://example.org/slovník/a");
            assertThat(structure.getEffectiveNamespace()).isEqualTo("https://example.org/slovník/");
            assertThat(structure.getResourceMap()).doesNotContainKey("ontology");
        }

        @Test
        @DisplayName("not at all: no IRI, no name, namespace from the first custom prefix")
        void noVocabularyUsesCustomPrefix() {
            Model model = ModelFactory.createDefaultModel();
            model.setNsPrefix("skos", "http://www.w3.org/2004/02/skos/core#");
            model.setNsPrefix("", "https://example.org/default/");
            model.setNsPrefix("muj", "https://example.org/můj/");

            ModelStructure structure = analyzer.analyzeModel(model);

            assertThat(structure.getOntologyIRI()).isNull();
            assertThat(structure.getVocabularyResource()).isNull();
            assertThat(structure.getModelName()).isNull();
            assertThat(structure.getModelDescription()).isNull();
            assertThat(structure.getCreationDate()).isNull();
            assertThat(structure.getEffectiveNamespace()).isEqualTo("https://example.org/můj/");
        }

        @Test
        @DisplayName("not at all and only standard prefixes: the default namespace")
        void noVocabularyNoCustomPrefixUsesDefault() {
            Model model = ModelFactory.createDefaultModel();
            model.setNsPrefix("owl", "http://www.w3.org/2002/07/owl#");

            assertThat(analyzer.analyzeModel(model).getEffectiveNamespace()).isEqualTo(DEFAULT_NS);
        }

        @Test
        @DisplayName("with an IRI that has no path separator: namespace from prefixes")
        void vocabularyIriWithoutSlash() {
            Model model = Ttl.parse("<urn:slovnik> a owl:Ontology .");
            model.clearNsPrefixMap();
            model.setNsPrefix("muj", "https://example.org/můj/");

            assertThat(analyzer.analyzeModel(model).getEffectiveNamespace()).isEqualTo("https://example.org/můj/");
        }
    }

    @Nested
    @DisplayName("reads the name and description")
    class NameAndDescription {

        @ParameterizedTest(name = "{0} ⇒ {1}")
        @CsvSource(delimiter = '|', nullValues = "null", value = {
                "rdfs:label \"Label\"@cs ; skos:prefLabel \"Pref\"@cs | Label",
                "skos:prefLabel \"Pref\"@cs                            | Pref",
                "rdfs:label \"  \" ; skos:prefLabel \"Pref\"@cs        | Pref",
                "rdfs:label ex:jiný ; skos:prefLabel \"Pref\"@cs       | Pref",
                "dct:title \"Title\"                                   | null",
        })
        @DisplayName("name: rdfs:label first, then skos:prefLabel, skipping blank and non-literal values")
        void name(String properties, String expected) {
            assertThat(analyze("ex: a owl:Ontology ; " + properties + " .").getModelName()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} ⇒ {1}")
        @CsvSource(delimiter = '|', nullValues = "null", value = {
                "dct:description \"Popis\"@cs ; skos:definition \"Definice\"@cs | Popis",
                "skos:definition \"Definice\"@cs                                | Definice",
                "dct:description \"\", ex:jiný ; skos:definition \"Definice\"     | Definice",
                "rdfs:comment \"Komentář\"                                      | null",
        })
        @DisplayName("description: dct:description first, then skos:definition, skipping blank and non-literal values")
        void description(String properties, String expected) {
            assertThat(analyze("ex: a owl:Ontology ; " + properties + " .").getModelDescription()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("reads the timestamps")
    class Timestamps {

        @Test
        @DisplayName("from datum-a-čas, preferring it over datum")
        void dateTimePreferred() {
            ModelStructure structure = analyze("""
                    ex: a owl:Ontology ;
                        slovníky:okamžik-vytvoření [ čas:datum-a-čas "2025-01-01T10:00:00Z"^^xsd:dateTime ;
                                                     čas:datum "2025-01-01"^^xsd:date ] ;
                        slovníky:okamžik-poslední-změny [ čas:datum "2025-02-01"^^xsd:date ] .
                    """);

            assertThat(structure.getCreationDate()).isEqualTo("2025-01-01T10:00:00Z");
            assertThat(structure.getModificationDate()).isEqualTo("2025-02-01");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "\"2025-01-01\"^^xsd:date",
                "[ čas:je-nespecifikovaný true ]",
                "[ čas:datum-a-čas ex:jiný ; čas:datum ex:jiný ]",
        })
        @DisplayName("as absent when the instant is a literal, unspecified or not a literal value")
        void unreadableInstantIsAbsent(String instant) {
            ModelStructure structure = analyze("ex: a owl:Ontology ; slovníky:okamžik-vytvoření " + instant + " .");

            assertThat(structure.getCreationDate()).isNull();
        }
    }

    @Nested
    @DisplayName("collects concepts and vocabulary types")
    class Concepts {

        @Test
        @DisplayName("maps every named resource of a concept type by IRI, skipping blank nodes and other types")
        void resourceMapHoldsNamedConcepts() {
            ModelStructure structure = analyze("""
                    ex: a owl:Ontology .
                    pojem:a a skos:Concept .
                    pojem:b a owl:Class .
                    pojem:c a owl:ObjectProperty .
                    pojem:d a owl:DatatypeProperty .
                    pojem:e a slovníky:pojem .
                    [ a skos:Concept ] .
                    pojem:f a rdfs:Class .
                    pojem:g a "literal" .
                    """);

            assertThat(structure.getResourceMap().keySet()).containsExactlyInAnyOrder(
                    "ontology", Ttl.POJEM + "a", Ttl.POJEM + "b", Ttl.POJEM + "c", Ttl.POJEM + "d", Ttl.POJEM + "e");
        }

        @ParameterizedTest(name = "{0} ⇒ {1}")
        @CsvSource(delimiter = '|', value = {
                "skos:Concept                  | Slovník",
                "slovníky:pojem                | Slovník,Tezaurus",
                "slovníky:třída                | Slovník,Tezaurus,Konceptuální model",
                "slovníky:typ-subjektu-práva   | Slovník,Tezaurus,Konceptuální model",
                "slovníky:typ-objektu-práva    | Slovník,Tezaurus,Konceptuální model",
                "slovníky:vlastnost            | Slovník,Tezaurus,Konceptuální model",
                "slovníky:vztah                | Slovník,Tezaurus,Konceptuální model",
        })
        @DisplayName("vocabulary types follow the concept roles present")
        void vocabularyTypes(String conceptType, String expected) {
            ModelStructure structure = analyze("ex: a owl:Ontology . pojem:x a " + conceptType + " .");

            assertThat(structure.getVocabularyTypes()).containsExactly(expected.split(","));
        }
    }

    @Test
    @DisplayName("rejects a null model")
    void nullModelRejected() {
        assertThatThrownBy(() -> analyzer.analyzeModel(null)).isInstanceOf(ModelProcessingException.class);
    }
}
