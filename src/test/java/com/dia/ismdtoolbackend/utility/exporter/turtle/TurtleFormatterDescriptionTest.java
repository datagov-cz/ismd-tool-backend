package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - DCT Description Handling")
class TurtleFormatterDescriptionTest {

    @Test
    @DisplayName("dct:description is preserved in output model")
    void descriptionPreserved() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        Property dctDesc = model.createProperty(DCT_NS + "description");
        cls.addProperty(dctDesc, model.createLiteral("Popis osoby", "cs"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Property resultDesc = result.getProperty(DCT_NS + "description");
        assertTrue(result.listStatements(null, resultDesc, (RDFNode) null).hasNext(),
                "Description should be preserved in output");
    }

    @Test
    @DisplayName("dct prefix is set in output model")
    void dctPrefixSet() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        Property dctDesc = model.createProperty(DCT_NS + "description");
        cls.addProperty(dctDesc, model.createLiteral("Popis", "cs"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        assertEquals(DCT_NS, result.getNsPrefixMap().get("dct"));
    }
}
