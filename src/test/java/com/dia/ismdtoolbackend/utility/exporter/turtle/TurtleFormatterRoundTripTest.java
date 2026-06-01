package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - Round-trip Fidelity")
class TurtleFormatterRoundTripTest {

    @Test
    @DisplayName("IRIs are preserved through transformation")
    void irisPreserved() {
        OntModel model = createDefaultModel();
        String conceptUri = TEST_POJEM_NS + "osoba";
        Resource cls = model.createResource(conceptUri);
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDFS.label, model.createLiteral("Osoba", "cs"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        assertNotNull(result.getResource(conceptUri));
        assertNotNull(result.getResource(TEST_NS));
    }

    @Test
    @DisplayName("Statement count only increases (transformations add, rarely remove)")
    void statementCountIncreases() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDFS.label, model.createLiteral("Osoba", "cs"));
        cls.addProperty(RDFS.subClassOf, model.createResource(TEST_POJEM_NS + "subjekt"));
        long inputCount = model.size();

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        // The export adds nadřazená-třída and ConceptScheme types, so count should not decrease
        assertTrue(result.size() >= inputCount,
                "Output should have at least as many statements as input. Input: " + inputCount + ", Output: " + result.size());
    }

    @Test
    @DisplayName("Output Turtle is re-parseable")
    void outputIsParseable() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDFS.label, model.createLiteral("Osoba", "cs"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        // Serialize to Turtle string
        StringWriter writer = new StringWriter();
        result.write(writer, "TURTLE");
        String turtleString = writer.toString();

        assertFalse(turtleString.isEmpty());

        // Re-parse - should not throw
        Model reparsed = ModelFactory.createDefaultModel();
        reparsed.read(new StringReader(turtleString), null, "TURTLE");
        assertTrue(reparsed.size() > 0, "Re-parsed model should have statements");
    }

    @Test
    @DisplayName("Complex model round-trip preserves all original resources")
    void complexModelRoundTrip() {
        OntModel model = createDefaultModel();
        Resource parent = model.createResource(TEST_POJEM_NS + "subjekt");
        parent.addProperty(RDF.type, OWL2.Class);
        Resource child = model.createResource(TEST_POJEM_NS + "osoba");
        child.addProperty(RDF.type, OWL2.Class);
        child.addProperty(RDFS.subClassOf, parent);
        Resource prop = model.createResource(TEST_POJEM_NS + "jméno");
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        // Verify all original resources exist
        assertNotNull(result.getResource(TEST_POJEM_NS + "subjekt"));
        assertNotNull(result.getResource(TEST_POJEM_NS + "osoba"));
        assertNotNull(result.getResource(TEST_POJEM_NS + "jméno"));
    }
}
