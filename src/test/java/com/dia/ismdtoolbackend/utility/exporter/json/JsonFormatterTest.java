package com.dia.ismdtoolbackend.utility.exporter.json;

import com.dia.ismdtoolbackend.exception.JsonExportException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonFormatter.formatAsJson")
class JsonFormatterTest {

    private static final String IRI = "https://slovník.gov.cz/datový/test-slovník/";

    private final JsonFormatter formatter = new JsonFormatter();

    private static JsonNode read(String json) {
        return JsonMapper.builder().build().readTree(json);
    }

    private static ModelStructure.ModelStructureBuilder structure() {
        return ModelStructure.builder()
                .ontologyIRI(IRI)
                .vocabularyTypes(List.of("Slovník"))
                .modelName("Slovník")
                .modelDescription("Popis");
    }

    private static List<String> keys(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    @Test
    @DisplayName("writes the vocabulary metadata in a fixed key order, then pojmy")
    void fullMetadataInOrder() {
        JsonNode root = read(formatter.formatAsJson(structure()
                .creationDate("2025-01-01T10:00:00Z")
                .modificationDate("2025-02-01")
                .build(), ConceptData.empty()));

        assertThat(keys(root)).containsExactly("@context", "iri", "typ", "název", "popis",
                "vytvořeno", "aktualizováno", "pojmy");
        assertThat(root.get("název").get("cs").asString()).isEqualTo("Slovník");
        assertThat(root.get("popis").get("cs").asString()).isEqualTo("Popis");
        assertThat(root.get("vytvořeno")).isEqualTo(read("""
                {"typ": "Časový okamžik", "datum_a_čas": "2025-01-01T10:00:00Z"}"""));
        assertThat(root.get("aktualizováno")).isEqualTo(read("""
                {"typ": "Časový okamžik", "datum": "2025-02-01"}"""));
    }

    @Test
    @DisplayName("omits a missing IRI, empty name and description, absent dates and empty type strings")
    void emptyMetadataOmitted() {
        JsonNode root = read(formatter.formatAsJson(structure()
                .ontologyIRI(null)
                .modelName("")
                .modelDescription(null)
                .vocabularyTypes(Arrays.asList("Slovník", "", null))
                .build(), ConceptData.empty()));

        assertThat(keys(root)).containsExactly("@context", "typ", "pojmy");
        assertThat(root.get("typ")).hasSize(1);
        assertThat(root.get("typ").get(0).asString()).isEqualTo("Slovník");
    }

    @Test
    @DisplayName("omits an empty or missing type list")
    void emptyTypeListOmitted() {
        assertThat(keys(read(formatter.formatAsJson(structure().vocabularyTypes(List.of()).build(),
                ConceptData.empty())))).doesNotContain("typ");
        assertThat(keys(read(formatter.formatAsJson(structure().vocabularyTypes(null).build(),
                ConceptData.empty())))).doesNotContain("typ");
    }

    @Test
    @DisplayName("orders concept fields by the OFN field order, unknown fields last, values untouched")
    void conceptFieldsOrdered() {
        Map<String, Object> concept = new LinkedHashMap<>();
        concept.put("vlastní-pole", "x");
        concept.put("definice", Map.of("cs", ""));
        concept.put("název", Map.of("cs", "Osoba"));
        concept.put("typ", List.of("Pojem"));
        concept.put("iri", IRI + "pojem/osoba");

        JsonNode root = read(formatter.formatAsJson(structure().build(),
                ConceptData.builder().concepts(List.of(concept)).totalConceptCount(1).build()));

        JsonNode exported = root.get("pojmy").get(0);
        assertThat(keys(exported)).containsExactly("iri", "typ", "název", "definice", "vlastní-pole");
        assertThat(exported.get("definice").get("cs").asString()).isEmpty();
    }

    @Test
    @DisplayName("wraps a formatting failure in JsonExportException")
    void failureWrapped() {
        assertThatThrownBy(() -> formatter.formatAsJson(structure().build(), null))
                .isInstanceOf(JsonExportException.class)
                .hasMessageStartingWith("Failed to format JSON");
    }
}
