package com.dia.ismdtoolbackend.utility.exporter.turtle;

import com.dia.ismdtoolbackend.exception.TurtleExportException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TurtleFilterUtil#createFilteredModel} drops subjects whose every {@code rdf:type} is an
 * RDF/RDFS/OWL/SKOS/OFN vocabulary term (vocabulary noise), and statements with an empty literal.
 * The ontology itself and subjects with at least one domain type are kept whole.
 */
@DisplayName("TurtleFilterUtil.createFilteredModel")
class TurtleFilterUtilTest {

    static {
        Ttl.initJena();
    }

    private static Model filter(String turtle) {
        return TurtleFilterUtil.createFilteredModel(Ttl.parse(turtle));
    }

    @Nested
    @DisplayName("keeps")
    class Keeps {

        @Test
        @DisplayName("the ontology, although all its types are vocabulary terms")
        void ontologyKept() {
            String input = """
                    ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                        skos:prefLabel "Slovník"@cs .
                    """;

            assertIsomorphic(input, filter(input));
        }

        @Test
        @DisplayName("a subject with at least one domain type, with all its statements")
        void subjectWithDomainTypeKept() {
            String input = """
                    pojem:osoba a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída ;
                        skos:prefLabel "Osoba"@cs .
                    <https://data.mvcr.gov.cz/zdroj/číselníky/typy> a l111:číselník .
                    """;

            assertIsomorphic(input, filter(input));
        }

        @Test
        @DisplayName("an untyped subject")
        void untypedSubjectKept() {
            String input = """
                    pojem:osoba skos:prefLabel "Osoba"@cs .
                    """;

            assertIsomorphic(input, filter(input));
        }

        @Test
        @DisplayName("an untyped blank node and a blank node with a domain type")
        void blankNodesWithoutVocabularyOnlyTypesKept() {
            String input = """
                    pojem:osoba slovníky:okamžik-vytvoření [ čas:datum "2025-01-01"^^xsd:date ] ;
                        rdfs:subClassOf [ a pojem:omezení ; owl:onProperty pojem:jméno ] .
                    """;

            assertIsomorphic(input, filter(input));
        }

        @Test
        @DisplayName("the source prefixes")
        void prefixesKept() {
            Model result = filter("pojem:osoba a skos:Concept .");

            assertThat(result.getNsPrefixMap()).containsEntry("pojem", Ttl.POJEM);
        }
    }

    @Nested
    @DisplayName("drops")
    class Drops {

        @ParameterizedTest(name = "a subject typed only {0}")
        @ValueSource(strings = {
                "owl:Class",
                "owl:Class, slovníky:pojem",
                "owl:ObjectProperty, owl:DatatypeProperty, rdf:Property",
                "rdfs:Class, rdfs:Datatype",
                "skos:ConceptScheme",
                "vsgov:typ-objektu-práva, vsgov:typ-subjektu-práva",
                "xsd:string",
                "schema:Thing",
                "\"not a resource\"",
        })
        @DisplayName("a subject whose every type is a vocabulary term")
        void vocabularyOnlySubjectDropped(String types) {
            Model result = filter("""
                    pojem:x a %s ; skos:prefLabel "X"@cs .
                    pojem:osoba a skos:Concept .
                    """.formatted(types));

            assertIsomorphic("pojem:osoba a skos:Concept .", result);
        }

        @Test
        @DisplayName("a blank node whose every type is a vocabulary term")
        void vocabularyOnlyBlankNodeDropped() {
            Model result = filter("""
                    [ a owl:Class ; rdfs:label "anonymní" ] .
                    [ a [ rdfs:label "anonymní typ" ] ] .
                    """);

            assertIsomorphic("""
                    [ rdfs:label "anonymní typ" ] .
                    """, result);
        }

        @Test
        @DisplayName("statements with an empty or blank literal")
        void emptyLiteralsDropped() {
            Model result = filter("""
                    pojem:osoba a skos:Concept ;
                        skos:prefLabel "Osoba"@cs, ""@en ;
                        skos:definition "   " ;
                        dct:description "" .
                    """);

            assertIsomorphic("""
                    pojem:osoba a skos:Concept ; skos:prefLabel "Osoba"@cs .
                    """, result);
        }
    }

    @Test
    @DisplayName("shouldFilterStatement answers per statement subject")
    void shouldFilterStatementPerSubject() {
        Model model = Ttl.parse("""
                ex: a owl:Ontology .
                pojem:noise a owl:Class .
                pojem:osoba a skos:Concept .
                """);

        assertThat(model.listStatements().toList())
                .filteredOn(TurtleFilterUtil::shouldFilterStatement)
                .extracting(s -> s.getSubject().getURI())
                .containsExactly(Ttl.POJEM + "noise");
    }

    @Test
    @DisplayName("rejects a null model")
    void nullModelRejected() {
        assertThatThrownBy(() -> TurtleFilterUtil.createFilteredModel(null))
                .isInstanceOf(TurtleExportException.class);
    }
}
