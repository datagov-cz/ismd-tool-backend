package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.JsonExportException;
import com.dia.ismdtoolbackend.testsupport.rdf.JsonLd;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static com.dia.ismdtoolbackend.testsupport.rdf.RdfAssert.assertIsomorphic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JSON export is JSON-LD: expanded through the published OFN context it must yield the
 * vocabulary's triples. Each test exports a Turtle fixture, expands the result with the bundled
 * context ({@link JsonLd}) and compares triples, so a field the exporter drops, renames or shapes
 * wrongly fails here.
 */
@DisplayName("JsonExporter - JSON-LD context conformance")
class JsonExporterContextConformanceTest {

    private static final String VOCABULARY_HEADER = """
            ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                skos:prefLabel "Testovací slovník"@cs ;
                dct:description "Popis slovníku"@cs .
            """;

    /** Every field the exporter writes: the vocabulary timestamps and five concepts covering the OFN roles. */
    private static final String FULL_VOCABULARY = VOCABULARY_HEADER + """
            ex: slovníky:okamžik-vytvoření [ a čas:časový-okamžik ; čas:datum-a-čas "2025-01-01T10:00:00Z" ] ;
                slovníky:okamžik-poslední-změny [ a čas:časový-okamžik ; čas:datum "2025-02-01"^^xsd:date ] .

            pojem:subjekt a skos:Concept, slovníky:pojem, owl:Class, slovníky:třída ;
                skos:prefLabel "Subjekt"@cs .

            pojem:osoba a skos:Concept, slovníky:pojem, owl:Class, slovníky:třída ;
                skos:prefLabel "Osoba"@cs, "Person"@en ;
                skos:altLabel "Člověk"@cs, "Fyzická osoba"@cs ;
                skos:definition "Fyzická osoba."@cs ;
                dct:description "Popis osoby."@cs ;
                skos:exactMatch <http://eurovoc.europa.eu/100157> ;
                skos:broader pojem:subjekt ;
                rdfs:subClassOf pojem:subjekt ;
                slovníky:definující-ustanovení <https://zakon.example.org/sb/89-2012/par/23> ;
                slovníky:související-ustanovení <https://zakon.example.org/sb/89-2012/par/24> ;
                slovníky:definující-nelegislativní-zdroj <https://example.org/doc/1> ;
                slovníky:související-nelegislativní-zdroj <https://example.org/doc/2> ;
                slovníky:má-instance-definované-číselníkem <https://example.org/číselníky/osoby> .

            <https://example.org/doc/1> a do:digitální-objekt ;
                dct:title "Norma"@cs ;
                dct:description "Popis normy"@cs ;
                schema:url <https://example.org/files/norma.pdf> .

            <https://example.org/doc/2> a do:digitální-objekt ;
                dct:title "Metodika"@cs .

            <https://example.org/číselníky/osoby> a l111:číselník ;
                l111:má-v-nkod-zastřešující-datovou-sadu <https://data.gov.cz/zdroj/datové-sady/1> .

            pojem:má-sídlo a skos:Concept, slovníky:pojem, owl:ObjectProperty, slovníky:vztah ;
                skos:prefLabel "má sídlo"@cs .

            pojem:má-adresu a skos:Concept, slovníky:pojem, owl:ObjectProperty, slovníky:vztah ;
                skos:prefLabel "má adresu"@cs ;
                rdfs:domain pojem:osoba ;
                rdfs:range pojem:subjekt ;
                rdfs:subPropertyOf pojem:má-sídlo .

            pojem:rodné-číslo a skos:Concept, slovníky:pojem, owl:DatatypeProperty, slovníky:vlastnost,
                    l111:neveřejný-údaj ;
                skos:prefLabel "Rodné číslo"@cs ;
                rdfs:domain pojem:osoba ;
                rdfs:range xsd:string ;
                a104:je-sdílen-v-propojeném-datovém-fondu true ;
                a104:sdružuje-údaje-vedené-nebo-vytvářené-v-rámci-agendy <https://rpp.example.org/agenda/A1> ;
                a104:údaje-jsou-v-ais <https://rpp.example.org/ais/42> ;
                l111:ustanovení-dokládající-neveřejnost-údaje <https://zakon.example.org/sb/133-2000/par/3> ;
                slovníky:má-způsob-sdílení-údaje <https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/ppdf> ;
                slovníky:má-způsob-získání-údaje <https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/vlastní> ;
                slovníky:má-typ-obsahu-údaje <https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/osobní> .
            """;

    /**
     * {@link #FULL_VOCABULARY} as the context reads it back: OFN role classes become their OWL
     * counterparts, concepts join the scheme through {@code skos:inScheme}, a source URL becomes
     * an {@code xsd:anyURI} literal and the timestamps are typed by the context.
     */
    private static final String FULL_VOCABULARY_EXPANDED = """
            ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                skos:prefLabel "Testovací slovník"@cs ;
                dct:description "Popis slovníku"@cs ;
                slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                    čas:datum-a-čas "2025-01-01T10:00:00Z"^^xsd:dateTimeStamp ] ;
                slovníky:okamžik-poslední-změny [ a čas:časový-okamžik ; čas:datum "2025-02-01"^^xsd:date ] .

            pojem:subjekt a skos:Concept, slovníky:pojem, owl:Class ;
                skos:inScheme ex: ;
                skos:prefLabel "Subjekt"@cs .

            pojem:osoba a skos:Concept, slovníky:pojem, owl:Class ;
                skos:inScheme ex: ;
                skos:prefLabel "Osoba"@cs, "Person"@en ;
                skos:altLabel "Člověk"@cs, "Fyzická osoba"@cs ;
                skos:definition "Fyzická osoba."@cs ;
                dct:description "Popis osoby."@cs ;
                skos:exactMatch <http://eurovoc.europa.eu/100157> ;
                skos:broader pojem:subjekt ;
                rdfs:subClassOf pojem:subjekt ;
                slovníky:definující-ustanovení <https://zakon.example.org/sb/89-2012/par/23> ;
                slovníky:související-ustanovení <https://zakon.example.org/sb/89-2012/par/24> ;
                slovníky:definující-nelegislativní-zdroj <https://example.org/doc/1> ;
                slovníky:související-nelegislativní-zdroj <https://example.org/doc/2> ;
                slovníky:má-instance-definované-číselníkem <https://example.org/číselníky/osoby> .

            <https://example.org/doc/1> a do:digitální-objekt ;
                dct:title "Norma"@cs ;
                dct:description "Popis normy"@cs ;
                schema:url "https://example.org/files/norma.pdf"^^xsd:anyURI .

            <https://example.org/doc/2> a do:digitální-objekt ;
                dct:title "Metodika"@cs .

            <https://example.org/číselníky/osoby> a l111:číselník ;
                l111:má-v-nkod-zastřešující-datovou-sadu <https://data.gov.cz/zdroj/datové-sady/1> .

            pojem:má-sídlo a skos:Concept, slovníky:pojem, owl:ObjectProperty ;
                skos:inScheme ex: ;
                skos:prefLabel "má sídlo"@cs .

            pojem:má-adresu a skos:Concept, slovníky:pojem, owl:ObjectProperty ;
                skos:inScheme ex: ;
                skos:prefLabel "má adresu"@cs ;
                rdfs:domain pojem:osoba ;
                rdfs:range pojem:subjekt ;
                rdfs:subPropertyOf pojem:má-sídlo .

            pojem:rodné-číslo a skos:Concept, slovníky:pojem, owl:DatatypeProperty, l111:neveřejný-údaj ;
                skos:inScheme ex: ;
                skos:prefLabel "Rodné číslo"@cs ;
                rdfs:domain pojem:osoba ;
                rdfs:range xsd:string ;
                a104:je-sdílen-v-propojeném-datovém-fondu true ;
                a104:sdružuje-údaje-vedené-nebo-vytvářené-v-rámci-agendy <https://rpp.example.org/agenda/A1> ;
                a104:údaje-jsou-v-ais <https://rpp.example.org/ais/42> ;
                l111:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost <https://zakon.example.org/sb/133-2000/par/3> ;
                slovníky:má-způsob-sdílení-údaje <https://data.dia.gov.cz/zdroj/číselníky/způsoby-sdílení-údajů/položky/ppdf> ;
                slovníky:má-způsob-získání-údaje <https://data.dia.gov.cz/zdroj/číselníky/způsoby-získání-údajů/položky/vlastní> ;
                slovníky:má-typ-obsahu-údaje <https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/osobní> .
            """;

    /**
     * The concept identifier ({@code dct:identifier}) has no term in the OFN context, so a JSON-LD
     * processor drops it.
     */
    private static final Set<String> CONCEPT_KEYS_MISSING_FROM_CONTEXT = Set.of("identifikátor");

    private final JsonExporter exporter = new JsonExporter();

    private static JsonNode read(String json) {
        return JsonMapper.builder().build().readTree(json);
    }

    @Test
    @DisplayName("Every exported field expands to the source triple under the OFN context")
    void fullVocabularyExpandsToItsTriples() {
        String json = exporter.exportToJson(Ttl.parseOnt(FULL_VOCABULARY));

        assertIsomorphic(FULL_VOCABULARY_EXPANDED, JsonLd.expand(json));
    }

    @Test
    @DisplayName("A vocabulary without concepts expands to its header only")
    void emptyVocabularyExpandsToHeader() {
        String json = exporter.exportToJson(Ttl.parseOnt(VOCABULARY_HEADER));

        assertIsomorphic("""
                ex: a slovníky:slovník ;
                    skos:prefLabel "Testovací slovník"@cs ;
                    dct:description "Popis slovníku"@cs .
                """, JsonLd.expand(json));
        assertThat(read(json).get("pojmy")).isEmpty();
    }

    @Test
    @DisplayName("Root keys, timestamps included, are context terms")
    void rootKeysAreContextTerms() {
        String json = exporter.exportToJson(Ttl.parseOnt(VOCABULARY_HEADER + """
                ex: slovníky:okamžik-vytvoření [ a čas:časový-okamžik ;
                        čas:datum-a-čas "2025-01-01T10:00:00Z"^^xsd:dateTimeStamp ] ;
                    slovníky:okamžik-poslední-změny [ a čas:časový-okamžik ;
                        čas:datum "2025-02-01"^^xsd:date ] .
                """));

        Set<String> undefined = new HashSet<>(read(json).propertyNames());
        undefined.removeAll(JsonLd.rootTerms());
        undefined.remove("@context");

        assertThat(read(json).propertyNames()).contains("vytvořeno", "aktualizováno");
        assertThat(undefined).isEmpty();
    }

    @Test
    @DisplayName("Concept keys are terms of the pojmy context, apart from the known identifier gap")
    void conceptKeysAreContextTerms() {
        String json = exporter.exportToJson(Ttl.parseOnt(FULL_VOCABULARY + """
                pojem:osoba dct:identifier "OS-1" .
                """));

        JsonNode concepts = read(json).get("pojmy");
        assertThat(concepts).hasSize(5);
        Set<String> undefined = new HashSet<>();
        concepts.forEach(concept -> undefined.addAll(concept.propertyNames()));
        undefined.removeAll(JsonLd.conceptTerms());
        undefined.removeAll(Set.of("iri", "typ"));

        assertThat(undefined).containsExactlyInAnyOrderElementsOf(CONCEPT_KEYS_MISSING_FROM_CONTEXT);
    }

    @ParameterizedTest(name = "{0} ⇒ vocabulary types {1}")
    @CsvSource(delimiter = '|', value = {
            "slovníky:pojem                             | Slovník,Tezaurus",
            "slovníky:pojem, owl:Class, slovníky:třída  | Slovník,Tezaurus,Konceptuální model",
            "slovníky:pojem, slovníky:vztah             | Slovník,Tezaurus,Konceptuální model",
            "slovníky:typ-objektu-práva                 | Slovník,Tezaurus,Konceptuální model",
            "slovníky:pojem, slovníky:vlastnost         | Slovník,Tezaurus,Konceptuální model",
            "skos:Concept                               | Slovník",
    })
    @DisplayName("Vocabulary types follow the concept roles and are context terms")
    void vocabularyTypesFollowConceptRoles(String conceptTypes, String expectedTypes) {
        String json = exporter.exportToJson(Ttl.parseOnt(VOCABULARY_HEADER
                + "pojem:x a " + conceptTypes + " ; skos:prefLabel \"X\"@cs ."));

        JsonNode types = read(json).get("typ");
        assertThat(types).map(JsonNode::asString)
                .containsExactly(expectedTypes.split(","))
                .allSatisfy(t -> assertThat(JsonLd.rootTerms()).contains(t));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "slovníky:pojem                                 | slovníky:pojem, skos:Concept",
            "skos:Concept                                   | slovníky:pojem, skos:Concept",
            "owl:Class                                      | slovníky:pojem, skos:Concept, owl:Class",
            "slovníky:třída                                 | slovníky:pojem, skos:Concept, owl:Class",
            "owl:ObjectProperty                             | slovníky:pojem, skos:Concept, owl:ObjectProperty",
            "slovníky:vztah                                 | slovníky:pojem, skos:Concept, owl:ObjectProperty",
            "owl:DatatypeProperty                           | slovníky:pojem, skos:Concept, owl:DatatypeProperty",
            "slovníky:vlastnost                             | slovníky:pojem, skos:Concept, owl:DatatypeProperty",
            "slovníky:třída, slovníky:typ-subjektu-práva    | slovníky:pojem, skos:Concept, owl:Class, vsgov:typ-subjektu-práva",
            "slovníky:třída, slovníky:typ-objektu-práva     | slovníky:pojem, skos:Concept, owl:Class, vsgov:typ-objektu-práva",
            "slovníky:pojem, l111:veřejný-údaj              | slovníky:pojem, skos:Concept, l111:veřejný-údaj",
            "slovníky:pojem, l111:neveřejný-údaj            | slovníky:pojem, skos:Concept, l111:neveřejný-údaj",
    })
    @DisplayName("Concept rdf:type expands through the context's type terms")
    void conceptTypeExpandsThroughContext(String sourceTypes, String expandedTypes) {
        String json = exporter.exportToJson(Ttl.parseOnt(VOCABULARY_HEADER
                + "pojem:x a " + sourceTypes + " ; skos:prefLabel \"X\"@cs ."));

        Model expanded = JsonLd.expand(json);
        Resource concept = expanded.getResource(Ttl.POJEM + "x");
        Set<RDFNode> actual = expanded.listObjectsOfProperty(concept, RDF.type).toSet();
        Model expected = Ttl.parse("pojem:x a " + expandedTypes + " .");

        assertThat(actual).containsExactlyInAnyOrderElementsOf(
                expected.listObjectsOfProperty(expected.getResource(Ttl.POJEM + "x"), RDF.type).toSet());
    }

    @Test
    @DisplayName("A concept typed as both vztah and vlastnost fails the export")
    void propertyAndRelationshipConflictFailsExport() {
        assertThatThrownBy(() -> exporter.exportToJson(Ttl.parseOnt(VOCABULARY_HEADER
                + "pojem:x a slovníky:vztah, slovníky:vlastnost ; skos:prefLabel \"X\"@cs .")))
                .isInstanceOf(JsonExportException.class)
                .hasMessageContaining(Ttl.POJEM + "x");
    }
}
