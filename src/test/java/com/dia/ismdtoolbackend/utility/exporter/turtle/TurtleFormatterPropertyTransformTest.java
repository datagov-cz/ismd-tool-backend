package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - Property Type Passthrough")
class TurtleFormatterPropertyTransformTest {

    @Test
    @DisplayName("Pre-normalized ObjectProperty with vztah type is preserved")
    void objectPropertyWithVztah_preserved() {
        OntModel model = createDefaultModel();
        Resource prop = model.createResource(TEST_POJEM_NS + "má-adresu");
        prop.addProperty(RDF.type, OWL2.ObjectProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + "vztah"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "má-adresu");
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(OFN_NAMESPACE + "vztah")));
        assertTrue(transformed.hasProperty(RDF.type, OWL2.ObjectProperty));
    }

    @Test
    @DisplayName("Pre-normalized DatatypeProperty with vlastnost type is preserved")
    void datatypePropertyWithVlastnost_preserved() {
        OntModel model = createDefaultModel();
        Resource prop = model.createResource(TEST_POJEM_NS + "jméno");
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + "vlastnost"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "jméno");
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(OFN_NAMESPACE + "vlastnost")));
    }

    @Test
    @DisplayName("ObjectProperty without /pojem/ passes through unchanged")
    void propertyWithoutPojem_unchanged() {
        OntModel model = createDefaultModel();
        Resource prop = model.createResource("https://example.org/ontology/myProp");
        prop.addProperty(RDF.type, OWL2.ObjectProperty);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource("https://example.org/ontology/myProp");
        Resource vztah = result.getResource(OFN_NAMESPACE + "vztah");
        assertFalse(transformed.hasProperty(RDF.type, vztah));
    }

    @Test
    @DisplayName("All OWL property types are preserved through export")
    void owlPropertyTypes_preserved() {
        OntModel model = createDefaultModel();
        Resource prop = model.createResource(TEST_POJEM_NS + "dual");
        prop.addProperty(RDF.type, OWL2.ObjectProperty);
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "dual");
        assertTrue(transformed.hasProperty(RDF.type, OWL2.ObjectProperty));
        assertTrue(transformed.hasProperty(RDF.type, OWL2.DatatypeProperty));
    }
}
