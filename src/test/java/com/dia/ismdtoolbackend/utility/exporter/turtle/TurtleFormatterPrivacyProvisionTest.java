package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - privacy provision → canonical OFN property")
class TurtleFormatterPrivacyProvisionTest {

    private static final String PROVISION_IRI =
            "https://opendata.eselpoint.cz/esel-esb/eli/cz/sb/2001/56/2024-01-01/dokument/norma/cast_2/par_4/odst_1/pism_a";

    @Test
    @DisplayName("Internal provision property is rewritten to canonical USTANOVENI_LONG")
    void internalPropertyRewrittenToCanonical() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addUstanoveniNeverejnost(concept, model, PROVISION_IRI);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        // Internal (writer) property must be gone.
        Property internal = result.getProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_NEVEREJNOST);
        assertFalse(result.listStatements(null, internal, (RDFNode) null).hasNext(),
                "Internal ustanovení-dokládající-neveřejnost-údaje property should be removed");

        // Canonical OFN property must be present, object preserved.
        Property canonical = result.getProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_LONG);
        Statement stmt = result.listStatements(null, canonical, (RDFNode) null).next();
        assertEquals(PROVISION_IRI, stmt.getObject().asResource().getURI(),
                "Provision target IRI must be preserved");
    }

    @Test
    @DisplayName("Multiple provisions are all rewritten")
    void multipleProvisionsAllRewritten() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addUstanoveniNeverejnost(concept, model, PROVISION_IRI);
        addUstanoveniNeverejnost(concept, model, PROVISION_IRI + "/bod_1");

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Property canonical = result.getProperty(OFN_NAMESPACE_LEGAL + USTANOVENI_LONG);
        long count = result.listStatements(null, canonical, (RDFNode) null).toList().size();
        assertEquals(2, count, "Both provisions should be rewritten to the canonical property");
    }

    @Test
    @DisplayName("Serialized Turtle renders provision as l111-2009: CURIE")
    void serializedAsL111Curie() {
        OntModel model = createDefaultModel();
        Resource concept = addOwlClass(model, "udaj", "Údaj");
        addUstanoveniNeverejnost(concept, model, PROVISION_IRI);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        StringWriter sw = new StringWriter();
        result.write(sw, "TURTLE");
        String ttl = sw.toString();

        assertTrue(ttl.contains("l111-2009:je-vymezen-ustanovením-stanovujícím-jeho-neveřejnost"),
                "Turtle should render the provision as the l111-2009: CURIE. Got:\n" + ttl);
    }
}
