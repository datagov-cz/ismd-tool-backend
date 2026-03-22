package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConceptProcessor - Code List Dataset (Datová sada v NKOD)")
class ConceptCodeListDatasetTest {

    private static final String NKOD_DATASET_URL = "https://data.gov.cz/zdroj/datové-sady/test-dataset-123";

    private ConceptProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ConceptProcessor();
    }

    private Resource addCodeListDataset(Resource concept, OntModel model, String datasetUrl) {
        Property instanceDefinedByCodeList = model.createProperty(
                OFN_NAMESPACE + "má-instance-definované-číselníkem");
        Resource codeListType = model.createResource(
                OFN_NAMESPACE_LEGAL + CISELNIK);
        Property datasetProperty = model.createProperty(
                OFN_NAMESPACE_LEGAL + "má-v-nkod-zastřešující-datovou-sadu");

        Resource codeListNode = model.createResource();
        codeListNode.addProperty(RDF.type, codeListType);
        codeListNode.addProperty(datasetProperty, model.createResource(datasetUrl));

        concept.addProperty(instanceDefinedByCodeList, codeListNode);
        return codeListNode;
    }

    @Test
    @DisplayName("Exports code list dataset for class concept")
    void exportsCodeListDataset_forClassConcept() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "test-class", "Test třída");
        addCodeListDataset(concept, model, NKOD_DATASET_URL);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey("instance-definovány-číselníkem"));
        @SuppressWarnings("unchecked")
        Map<String, Object> codeListObj = (Map<String, Object>) result.get("instance-definovány-číselníkem");
        assertEquals("Číselník", codeListObj.get("typ"));
        assertEquals(NKOD_DATASET_URL, codeListObj.get("datová-sada-v-nkod"));
    }

    @Test
    @DisplayName("Exports code list dataset for property concept")
    void exportsCodeListDataset_forPropertyConcept() {
        OntModel model = createDefaultModel();
        Resource concept = addDatatypeProperty(model, "test-prop", "Test vlastnost", null, null);
        addCodeListDataset(concept, model, NKOD_DATASET_URL);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey("instance-definovány-číselníkem"));
        @SuppressWarnings("unchecked")
        Map<String, Object> codeListObj = (Map<String, Object>) result.get("instance-definovány-číselníkem");
        assertEquals(NKOD_DATASET_URL, codeListObj.get("datová-sada-v-nkod"));
    }

    @Test
    @DisplayName("Exports code list dataset for relationship concept")
    void exportsCodeListDataset_forRelationshipConcept() {
        OntModel model = createDefaultModel();
        Resource domain = addOwlClass(model, "domain-class", "Doména");
        Resource range = addOwlClass(model, "range-class", "Rozsah");
        Resource concept = addObjectProperty(model, "test-rel", "Test vztah", domain, range);
        addCodeListDataset(concept, model, NKOD_DATASET_URL);
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertTrue(result.containsKey("instance-definovány-číselníkem"));
    }

    @Test
    @DisplayName("No code list dataset when property is absent")
    void noCodeListDataset_whenAbsent() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "plain-class", "Plain třída");
        ModelStructure structure = createModelStructure(model);

        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey("instance-definovány-číselníkem"));
    }

    @Test
    @DisplayName("Ignores code list node without correct type")
    void ignoresCodeListNode_withoutCorrectType() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "typed-class", "Typed třída");

        Property instanceDefinedByCodeList = model.createProperty(
                OFN_NAMESPACE + "má-instance-definované-číselníkem");
        Property datasetProperty = model.createProperty(
                OFN_NAMESPACE_LEGAL + "má-v-nkod-zastřešující-datovou-sadu");

        // Create blank node WITHOUT the correct type
        Resource codeListNode = model.createResource();
        codeListNode.addProperty(datasetProperty, model.createResource(NKOD_DATASET_URL));
        concept.addProperty(instanceDefinedByCodeList, codeListNode);

        ModelStructure structure = createModelStructure(model);
        Map<String, Object> result = processor.processConceptByIri(model, structure, concept.getURI());

        assertFalse(result.containsKey("instance-definovány-číselníkem"));
    }
}
