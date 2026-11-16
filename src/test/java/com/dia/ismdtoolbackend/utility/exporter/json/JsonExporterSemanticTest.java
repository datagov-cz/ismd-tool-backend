package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.JsonExportException;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shape of the exported JSON document: key names, values and type lists for given RDF. The
 * JSON-LD meaning of the same output is checked in {@link JsonExporterContextConformanceTest}.
 */
@DisplayName("JsonExporter - exported document")
class JsonExporterSemanticTest {

    private static final String VOCABULARY = """
            ex: a owl:Ontology, skos:ConceptScheme, slovníky:slovník ;
                skos:prefLabel "Test Vocabulary"@cs .
            """;

    private final JsonExporter exporter = new JsonExporter();

    private JsonNode export(String turtle) {
        return JsonMapper.builder().build().readTree(exporter.exportToJson(Ttl.parseOnt(VOCABULARY + turtle)));
    }

    private static JsonNode onlyConcept(JsonNode root) {
        assertThat(root.get("pojmy")).hasSize(1);
        return root.get("pojmy").get(0);
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }

    @Test
    @DisplayName("Vocabulary IRI, types, name and context come from the ontology")
    void vocabularyMetadata() {
        JsonNode root = export("");

        assertThat(root.get("@context").asString())
                .isEqualTo("https://ofn.gov.cz/slovníky/2026-02-26/kompletní/kontext.jsonld");
        assertThat(root.get("iri").asString()).isEqualTo(Ttl.VOCABULARY);
        assertThat(strings(root.get("typ"))).containsExactly("Slovník");
        assertThat(root.get("název").get("cs").asString()).isEqualTo("Test Vocabulary");
        assertThat(root.get("pojmy")).isEmpty();
    }

    @Test
    @DisplayName("Labels, definition and description keep each language")
    void multilingualFieldsKeepLanguages() {
        JsonNode concept = onlyConcept(export("""
                pojem:osoba a skos:Concept ;
                    skos:prefLabel "Testovací pojem"@cs, "Test Concept"@en ;
                    skos:definition "Testovací definice"@cs ;
                    dct:description "Popis"@cs .
                """));

        assertThat(concept.get("název").get("cs").asString()).isEqualTo("Testovací pojem");
        assertThat(concept.get("název").get("en").asString()).isEqualTo("Test Concept");
        assertThat(concept.get("definice").get("cs").asString()).isEqualTo("Testovací definice");
        assertThat(concept.get("popis").get("cs").asString()).isEqualTo("Popis");
    }

    @ParameterizedTest(name = "{0} ⇒ {1}")
    @CsvSource(delimiter = '|', value = {
            "skos:Concept                                | Pojem,Koncept",
            "owl:Class                                   | Pojem,Koncept,Třída",
            "skos:Concept, owl:Class, slovníky:třída     | Pojem,Koncept,Třída",
            "owl:DatatypeProperty                        | Pojem,Koncept,Vlastnost",
            "owl:ObjectProperty                          | Pojem,Koncept,Vztah",
            "skos:Concept, slovníky:typ-subjektu-práva   | Pojem,Koncept,Typ subjektu práva",
            "skos:Concept, slovníky:typ-objektu-práva    | Pojem,Koncept,Typ objektu práva",
            "skos:Concept, l111:veřejný-údaj             | Pojem,Koncept,Veřejný údaj",
            "skos:Concept, l111:neveřejný-údaj           | Pojem,Koncept,Neveřejný údaj",
    })
    @DisplayName("RDF types map to exactly these JSON type names")
    void rdfTypesMapToTypeNames(String rdfTypes, String expected) {
        JsonNode concept = onlyConcept(export("pojem:x a " + rdfTypes + " ; skos:prefLabel \"X\"@cs ."));

        assertThat(strings(concept.get("typ"))).containsExactly(expected.split(","));
    }

    @Test
    @DisplayName("skos:broader becomes nadřazený-pojem with the parent IRI")
    void broaderBecomesParentConcept() {
        JsonNode root = export("""
                pojem:rodič a skos:Concept ; skos:prefLabel "Rodič"@cs .
                pojem:dítě a skos:Concept ; skos:prefLabel "Dítě"@cs ; skos:broader pojem:rodič .
                """);

        JsonNode child = StreamSupport.stream(root.get("pojmy").spliterator(), false)
                .filter(c -> c.get("iri").asString().equals(Ttl.POJEM + "dítě"))
                .findFirst().orElseThrow();
        assertThat(strings(child.get("nadřazený-pojem"))).containsExactly(Ttl.POJEM + "rodič");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "https://slovník.gov.cz/datový/test-slovník/pojem/test_concept_123",
            "https://slovník.gov.cz/datový/test-slovník/#fragment-concept",
            "http://other.example.org/vocab/external_concept",
    })
    @DisplayName("Concept IRIs are exported verbatim, from any namespace")
    void conceptIrisVerbatim(String iri) {
        JsonNode concept = onlyConcept(export("<" + iri + "> a skos:Concept ; skos:prefLabel \"X\"@cs ."));

        assertThat(concept.get("iri").asString()).isEqualTo(iri);
    }

    @Test
    @DisplayName("Special characters in labels survive")
    void specialCharactersPreserved() {
        String label = "Test: Speciální znaky - čeština, \\\"uvozovky\\\", & symboly";
        JsonNode concept = onlyConcept(export("pojem:x a skos:Concept ; skos:prefLabel \"" + label + "\"@cs ."));

        assertThat(concept.get("název").get("cs").asString())
                .isEqualTo("Test: Speciální znaky - čeština, \"uvozovky\", & symboly");
    }

    @Test
    @DisplayName("A null model fails as a JSON export error")
    void nullModelFails() {
        assertThatThrownBy(() -> exporter.exportToJson(null)).isInstanceOf(JsonExportException.class);
    }
}
