package com.dia.ismdtoolbackend.utility.exporter.turtle;

import com.dia.ismdtoolbackend.exception.TurtleExportException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TurtleFormatterUtil#transformToOFNFormat} copies the model and applies the OFN rewrites:
 * {@code dct:conformsTo} becomes {@code slovníky:definující-ustanovení}, the internal privacy
 * provision property becomes the canonical one, each named superclass is mirrored as
 * {@code nadřazená-třída}, and the vocabulary is typed as a concept scheme and {@code slovník}.
 * Each test compares the whole output with the expected triples.
 */
@DisplayName("TurtleFormatterUtil.transformToOFNFormat")
class TurtleFormatterUtilTest {

    static {
        Ttl.initJena();
    }

    private static final String VOCABULARY = """
            ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník .
            """;

    private static Model transform(String turtle) {
        return TurtleFormatterUtil.transformToOFNFormat(Ttl.parse(turtle));
    }

    @Nested
    @DisplayName("passes through")
    class Passthrough {

        @Test
        @DisplayName("concepts, labels, descriptions and types unchanged")
        void conceptsUnchanged() {
            String input = VOCABULARY + """
                    pojem:osoba a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída ;
                        skos:prefLabel "Osoba"@cs, "Person"@en ;
                        dct:description "Popis osoby"@cs ;
                        skos:inScheme ex: .
                    pojem:má-adresu a owl:ObjectProperty, slovníky:vztah .
                    pojem:jméno a owl:DatatypeProperty, slovníky:vlastnost .
                    pojem:dual a owl:ObjectProperty, owl:DatatypeProperty .
                    <https://example.org/model/SomeClass> a owl:Class ;
                        rdfs:label "Some Class"@en .
                    owl:Thing a owl:Class .
                    """;

            assertIsomorphic(input, transform(input));
        }

        @Test
        @DisplayName("without changing the caller's model")
        void inputModelUnchanged() {
            String input = VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        dct:conformsTo <https://zakon.example.org/sb/89-2012> ;
                        rdfs:subClassOf pojem:subjekt .
                    """;
            Model model = Ttl.parse(input);

            TurtleFormatterUtil.transformToOFNFormat(model);

            assertIsomorphic(input, model);
        }

        @Test
        @DisplayName("and serializes to Turtle that parses back to the same triples")
        void outputSurvivesTurtleRoundTrip() {
            Model result = transform(VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        skos:prefLabel "Osoba"@cs ;
                        rdfs:subClassOf pojem:subjekt .
                    """);

            assertIsomorphic(result, Ttl.roundTrip(result));
        }
    }

    @Nested
    @DisplayName("rewrites")
    class Rewrites {

        @Test
        @DisplayName("every dct:conformsTo to slovníky:definující-ustanovení")
        void conformsToBecomesDefiningProvision() {
            Model result = transform(VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        dct:conformsTo <https://zakon.example.org/sb/89-2012/par/23>,
                                       <https://zakon.example.org/sb/89-2012/par/24> .
                    """);

            assertIsomorphic(VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        slovníky:definující-ustanovení <https://zakon.example.org/sb/89-2012/par/23>,
                                                       <https://zakon.example.org/sb/89-2012/par/24> .
                    """, result);
        }

        @Test
        @DisplayName("every internal privacy provision to the canonical OFN property")
        void privacyProvisionBecomesCanonical() {
            Model result = transform(VOCABULARY + """
                    pojem:údaj a owl:Class ;
                        l111:ustanovení-dokládající-neveřejnost-údaje
                            <https://zakon.example.org/sb/2001/56/par/4>,
                            <https://zakon.example.org/sb/2001/56/par/4/bod_1> .
                    """);

            assertIsomorphic(VOCABULARY + """
                    pojem:údaj a owl:Class ;
                        l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost
                            <https://zakon.example.org/sb/2001/56/par/4>,
                            <https://zakon.example.org/sb/2001/56/par/4/bod_1> .
                    """, result);
        }

        @Test
        @DisplayName("the canonical privacy property under the l111-2009: prefix")
        void privacyProvisionRendersAsL111Curie() {
            Model result = transform(VOCABULARY);

            assertThat(result.shortForm(OFN_NAMESPACE_LEGAL + USTANOVENI_LONG))
                    .isEqualTo("l111-2009:" + USTANOVENI_LONG);
        }
    }

    @Nested
    @DisplayName("mirrors rdfs:subClassOf as nadřazená-třída")
    class SuperClass {

        @Test
        @DisplayName("for each named superclass, keeping the original triple")
        void namedSuperclassMirrored() {
            Model result = transform(VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        rdfs:subClassOf pojem:subjekt, pojem:entita .
                    """);

            assertIsomorphic(VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        rdfs:subClassOf pojem:subjekt, pojem:entita ;
                        <https://slovník.gov.cz/nadřazená-třída> pojem:subjekt, pojem:entita .
                    """, result);
        }

        @Test
        @DisplayName("but not for rdfs:Resource")
        void rdfsResourceNotMirrored() {
            String input = VOCABULARY + """
                    pojem:osoba a owl:Class ; rdfs:subClassOf rdfs:Resource .
                    """;

            assertIsomorphic(input, transform(input));
        }

        @Test
        @DisplayName("but not for an anonymous superclass such as an OWL restriction")
        void anonymousSuperclassNotMirrored() {
            String input = VOCABULARY + """
                    pojem:osoba a owl:Class ;
                        rdfs:subClassOf [ a owl:Restriction ;
                                          owl:onProperty pojem:má-jméno ;
                                          owl:cardinality 1 ] .
                    """;

            assertIsomorphic(input, transform(input));
        }
    }

    @Nested
    @DisplayName("types the vocabulary")
    class VocabularyTypes {

        @Test
        @DisplayName("as skos:ConceptScheme and slovníky:slovník when it is an owl:Ontology")
        void ontologyGetsSchemeTypes() {
            Model result = transform("ex: a owl:Ontology .");

            assertIsomorphic("ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník .", result);
        }

        @Test
        @DisplayName("not at all when there is no owl:Ontology")
        void noOntologyNoTypes() {
            String input = "ex: a skos:ConceptScheme . pojem:osoba a skos:Concept .";

            assertIsomorphic(input, transform(input));
        }
    }

    @Nested
    @DisplayName("keeps the číselník a standalone subject")
    class CodeList {

        @Test
        @DisplayName("with its type and NKOD dataset after a Turtle round trip")
        void codeListSurvivesRoundTrip() {
            String input = VOCABULARY + """
                    pojem:typ-cíle a owl:Class, skos:Concept, slovníky:pojem, slovníky:třída ;
                        slovníky:má-instance-definované-číselníkem <https://data.mvcr.gov.cz/zdroj/číselníky/typy-cílů> .
                    <https://data.mvcr.gov.cz/zdroj/číselníky/typy-cílů> a l111:číselník ;
                        l111:má-v-nkod-zastřešující-datovou-sadu <https://data.gov.cz/zdroj/datové-sady/1> .
                    """;

            Model exported = Ttl.roundTrip(
                    TurtleFormatterUtil.transformToOFNFormat(TurtleFilterUtil.createFilteredModel(Ttl.parse(input))));

            assertIsomorphic(input, exported);
        }
    }

    @ParameterizedTest(name = "{0}: → {1}")
    @CsvSource({
            "dct, http://purl.org/dc/terms/",
            "owl, http://www.w3.org/2002/07/owl#",
            "rdf, http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "rdfs, http://www.w3.org/2000/01/rdf-schema#",
            "skos, http://www.w3.org/2004/02/skos/core#",
            "slovníky, https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/",
            "vsgov, https://slovník.gov.cz/veřejný-sektor/pojem/",
            "xsd, http://www.w3.org/2001/XMLSchema#",
            "čas, https://slovník.gov.cz/generický/čas/pojem/",
            "a104, https://slovník.gov.cz/agendový/104/pojem/",
            "l111-2009, https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/",
            "schema, http://schema.org/",
            "typ-obsahu-údajů, https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-typ-obsahu-údaje",
            "způsoby-sdílení-údajů, https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-způsob-sdílení-údaje",
            "způsoby-získání-údajů, https://slovník.gov.cz/legislativní/sbírka/360/2023/pojem/má-způsob-získání-údaje",
    })
    @DisplayName("declares the OFN prefix")
    void declaresOfnPrefix(String prefix, String namespace) {
        assertThat(transform(VOCABULARY).getNsPrefixMap()).containsEntry(prefix, namespace);
    }

    @Test
    @DisplayName("rejects a null model")
    void nullModelRejected() {
        assertThatThrownBy(() -> TurtleFormatterUtil.transformToOFNFormat(null))
                .isInstanceOf(TurtleExportException.class);
    }
}
