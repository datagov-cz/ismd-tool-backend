package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - Export Behavior")
class TurtleFormatterHelperTest {

    @Test
    @DisplayName("Pre-normalized concept with all types passes through correctly")
    void fullyNormalizedConcept_passesThrough() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, SKOS.Concept);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        cls.addProperty(SKOS.prefLabel, model.createLiteral("Osoba", "cs"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        assertTrue(transformed.hasProperty(RDF.type, OWL2.Class));
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(SKOS_NS + "Concept")));
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(OFN_NAMESPACE + POJEM)));
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(OFN_NAMESPACE + TRIDA)));
        assertTrue(transformed.hasProperty(result.getProperty(SKOS_NS + "prefLabel")));
    }

    @Test
    @DisplayName("Resource without /pojem/ is not modified by export")
    void noPojemResource_notModified() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource("https://example.org/model/SomeClass");
        cls.addProperty(RDF.type, OWL2.Class);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource("https://example.org/model/SomeClass");
        assertTrue(transformed.hasProperty(RDF.type, OWL2.Class));
        assertFalse(transformed.hasProperty(RDF.type, result.getResource(SKOS_NS + "Concept")));
    }

    @Test
    @DisplayName("Export adds ConceptScheme and subClass augmentation on top of normalized data")
    void exportAddsConceptSchemeAndSubClass() {
        OntModel model = createDefaultModel();
        // Pre-normalized concept with subClassOf
        Resource parent = model.createResource(TEST_POJEM_NS + "subjekt");
        parent.addProperty(RDF.type, OWL2.Class);
        Resource child = model.createResource(TEST_POJEM_NS + "osoba");
        child.addProperty(RDF.type, OWL2.Class);
        child.addProperty(org.apache.jena.vocabulary.RDFS.subClassOf, parent);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        // ConceptScheme should be added to ontology
        Resource ontology = result.getResource(TEST_NS);
        assertTrue(ontology.hasProperty(RDF.type, result.getResource(SKOS_NS + "ConceptScheme")));

        // nadřazená-třída should be added
        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        assertTrue(transformed.hasProperty(
                result.getProperty("https://slovník.gov.cz/nadřazená-třída"),
                result.getResource(TEST_POJEM_NS + "subjekt")));
    }
}
