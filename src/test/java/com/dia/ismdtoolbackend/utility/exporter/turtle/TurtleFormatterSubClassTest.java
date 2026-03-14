package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - SubClass Augmentation")
class TurtleFormatterSubClassTest {

    private static final String NADRAZENA_TRIDA_URI = "https://slovník.gov.cz/nadřazená-třída";

    @Test
    @DisplayName("rdfs:subClassOf adds nadřazená-třída property")
    void subClassOf_addsNadrazenaTrida() {
        OntModel model = createDefaultModel();
        Resource parent = model.createResource(TEST_POJEM_NS + "subjekt");
        parent.addProperty(RDF.type, OWL2.Class);
        Resource child = model.createResource(TEST_POJEM_NS + "osoba");
        child.addProperty(RDF.type, OWL2.Class);
        child.addProperty(RDFS.subClassOf, parent);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Property nadrazenaTrida = result.getProperty(NADRAZENA_TRIDA_URI);
        assertTrue(result.listStatements(
                result.getResource(TEST_POJEM_NS + "osoba"),
                nadrazenaTrida,
                result.getResource(TEST_POJEM_NS + "subjekt")
        ).hasNext(), "Should have nadřazená-třída");
    }

    @Test
    @DisplayName("rdfs:subClassOf to rdfs:Resource is excluded from nadřazená-třída")
    void subClassOfResource_excluded() {
        OntModel model = createDefaultModel();
        Resource child = model.createResource(TEST_POJEM_NS + "osoba");
        child.addProperty(RDF.type, OWL2.Class);
        child.addProperty(RDFS.subClassOf, RDFS.Resource);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Property nadrazenaTrida = result.getProperty(NADRAZENA_TRIDA_URI);
        assertFalse(result.listStatements(
                result.getResource(TEST_POJEM_NS + "osoba"),
                nadrazenaTrida,
                (RDFNode) null
        ).hasNext(), "rdfs:Resource should not produce nadřazená-třída");
    }

    @Test
    @DisplayName("Original rdfs:subClassOf is preserved alongside nadřazená-třída")
    void originalSubClassOf_preserved() {
        OntModel model = createDefaultModel();
        Resource parent = model.createResource(TEST_POJEM_NS + "subjekt");
        parent.addProperty(RDF.type, OWL2.Class);
        Resource child = model.createResource(TEST_POJEM_NS + "osoba");
        child.addProperty(RDF.type, OWL2.Class);
        child.addProperty(RDFS.subClassOf, parent);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        assertTrue(result.listStatements(
                result.getResource(TEST_POJEM_NS + "osoba"),
                RDFS.subClassOf,
                result.getResource(TEST_POJEM_NS + "subjekt")
        ).hasNext(), "Original rdfs:subClassOf should still be present");
    }
}
