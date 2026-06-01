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

@DisplayName("TurtleFormatterUtil - conformsTo → definující-ustanovení")
class TurtleFormatterConformsToTest {

    @Test
    @DisplayName("dct:conformsTo is replaced by definující-ustanovení")
    void conformsTo_replacedByDefinujiciUstanoveni() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        Property conformsTo = model.createProperty(DCT_NS + "conformsTo");
        Resource law = model.createResource("https://zakon.example.org/sb/89-2012");
        cls.addProperty(conformsTo, law);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        // conformsTo should be gone
        Property resultConformsTo = result.getProperty(DCT_NS + "conformsTo");
        assertFalse(result.listStatements(null, resultConformsTo, (RDFNode) null).hasNext(),
                "dct:conformsTo should be removed");

        // definující-ustanovení should be present
        Property definujici = result.getProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        assertTrue(result.listStatements(null, definujici, (RDFNode) null).hasNext(),
                "definující-ustanovení should be added");
    }

    @Test
    @DisplayName("Object of conformsTo is preserved in replacement")
    void conformsTo_objectPreserved() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        Property conformsTo = model.createProperty(DCT_NS + "conformsTo");
        Resource law = model.createResource("https://zakon.example.org/sb/89-2012");
        cls.addProperty(conformsTo, law);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Property definujici = result.getProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        Statement stmt = result.listStatements(null, definujici, (RDFNode) null).next();
        assertEquals("https://zakon.example.org/sb/89-2012",
                stmt.getObject().asResource().getURI());
    }

    @Test
    @DisplayName("Multiple conformsTo statements are all replaced")
    void multipleConformsTo_allReplaced() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        Property conformsTo = model.createProperty(DCT_NS + "conformsTo");
        cls.addProperty(conformsTo, model.createResource("https://zakon.example.org/sb/89-2012/par/23"));
        cls.addProperty(conformsTo, model.createResource("https://zakon.example.org/sb/89-2012/par/24"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Property definujici = result.getProperty(OFN_NAMESPACE + DEFINUJICI_USTANOVENI);
        long count = result.listStatements(null, definujici, (RDFNode) null).toList().size();
        assertEquals(2, count, "Both conformsTo should be replaced");
    }
}
