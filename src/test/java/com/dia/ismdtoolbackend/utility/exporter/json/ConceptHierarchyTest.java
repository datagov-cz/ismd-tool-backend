package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConceptJsonMapper - Hierarchical Relationships")
class ConceptHierarchyTest {

    private ConceptJsonMapper processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptJsonMapper();
    }

    @Test
    @DisplayName("rdfs:subClassOf produces nadřazená-třída")
    void subClassOf_producesNadrazenaTrida() {
        OntModel model = createDefaultModel();
        Resource parent = addOwlClass(model, "subjekt", "Subjekt");
        Resource child = addOwlClass(model, "osoba", "Osoba");
        addSubClassRelationship(child, parent);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, child.getURI());

        assertThat(result).containsKey(NADRAZENA_TRIDA);
        @SuppressWarnings("unchecked")
        List<String> parents = (List<String>) result.get(NADRAZENA_TRIDA);
        assertThat(parents).contains(parent.getURI());
    }

    @Test
    @DisplayName("Multiple subClassOf relationships produce multiple parents")
    void multipleSubClassOf() {
        OntModel model = createDefaultModel();
        Resource parent1 = addOwlClass(model, "subjekt", "Subjekt");
        Resource parent2 = addOwlClass(model, "entita", "Entita");
        Resource child = addOwlClass(model, "osoba", "Osoba");
        addSubClassRelationship(child, parent1);
        addSubClassRelationship(child, parent2);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, child.getURI());

        @SuppressWarnings("unchecked")
        List<String> parents = (List<String>) result.get(NADRAZENA_TRIDA);
        assertThat(parents).hasSize(2);
    }

    @Test
    @DisplayName("rdfs:subPropertyOf on vztah produces nadřazený-vztah")
    void subPropertyOf_vztah_producesNadrazenyVztah() {
        OntModel model = createDefaultModel();
        Resource parentRelation = addObjectProperty(model, "ma-entitu", "má entitu", null, null);
        Resource childRelation = addObjectProperty(model, "ma-osobu", "má osobu", null, null);
        addSubPropertyRelationship(childRelation, parentRelation);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, childRelation.getURI());

        assertThat(result).containsKey(NADRAZENY_VZTAH);
        @SuppressWarnings("unchecked")
        List<String> superProps = (List<String>) result.get(NADRAZENY_VZTAH);
        assertThat(superProps).contains(parentRelation.getURI());
    }

    @Test
    @DisplayName("rdfs:subPropertyOf on vlastnost produces nadřazená-vlastnost")
    void subPropertyOf_vlastnost_producesNadrazenaVlastnost() {
        OntModel model = createDefaultModel();
        Resource parentProp = addDatatypeProperty(model, "identifikator", "identifikátor", null, "string");
        Resource childProp = addDatatypeProperty(model, "rodne-cislo", "rodné číslo", null, "string");
        addSubPropertyRelationship(childProp, parentProp);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, childProp.getURI());

        assertThat(result).containsKey(NADRAZENA_VLASTNOST);
        @SuppressWarnings("unchecked")
        List<String> superProps = (List<String>) result.get(NADRAZENA_VLASTNOST);
        assertThat(superProps).contains(parentProp.getURI());
    }

    @Test
    @DisplayName("skos:broader produces nadřazený-pojem")
    void skosBroader_producesNadrazenyPojem() {
        OntModel model = createDefaultModel();
        Resource broader = addOwlClass(model, "subjekt", "Subjekt");
        Resource narrower = addOwlClass(model, "osoba", "Osoba");
        addSkosBroader(narrower, broader);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, narrower.getURI());

        assertThat(result).containsKey("nadřazený-pojem");
        @SuppressWarnings("unchecked")
        List<String> broaderList = (List<String>) result.get("nadřazený-pojem");
        assertThat(broaderList).contains(broader.getURI());
    }

    @Test
    @DisplayName("No hierarchy → fields absent")
    void noHierarchy_fieldsAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "osoba", "Osoba");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertThat(result).doesNotContainKey(NADRAZENA_TRIDA);
        assertThat(result).doesNotContainKey(NADRAZENY_VZTAH);
        assertThat(result).doesNotContainKey(NADRAZENA_VLASTNOST);
        assertThat(result).doesNotContainKey("nadřazený-pojem");
    }

    @Test
    @DisplayName("subPropertyOf on concept without vztah or vlastnost type produces no output")
    void subPropertyOf_noPropertyType_noOutput() {
        OntModel model = createDefaultModel();
        Resource parent = addOwlClass(model, "parent", "Parent");
        Resource child = addOwlClass(model, "child", "Child");
        // subPropertyOf on an owl:Class (not vztah/vlastnost) - determinePropertyKey returns null
        addSubPropertyRelationship(child, parent);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, child.getURI());

        assertThat(result).doesNotContainKey(NADRAZENY_VZTAH);
        assertThat(result).doesNotContainKey(NADRAZENA_VLASTNOST);
    }
}
