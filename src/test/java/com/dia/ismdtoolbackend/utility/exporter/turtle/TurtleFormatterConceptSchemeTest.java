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

@DisplayName("TurtleFormatterUtil - Ontology → ConceptScheme")
class TurtleFormatterConceptSchemeTest {

    @Test
    @DisplayName("owl:Ontology gets skos:ConceptScheme type")
    void ontology_getsConceptScheme() {
        OntModel model = createDefaultModel();

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource ontology = result.getResource(TEST_NS);
        Resource conceptScheme = result.getResource(SKOS_NS + "ConceptScheme");
        assertTrue(ontology.hasProperty(RDF.type, conceptScheme),
                "Ontology should have skos:ConceptScheme type");
    }

    @Test
    @DisplayName("owl:Ontology gets slovníky:slovník type")
    void ontology_getsSlovnikType() {
        OntModel model = createDefaultModel();

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource ontology = result.getResource(TEST_NS);
        Resource slovnikType = result.getResource(OFN_NAMESPACE + "slovník");
        assertTrue(ontology.hasProperty(RDF.type, slovnikType),
                "Ontology should have slovníky:slovník type");
    }

    @Test
    @DisplayName("Already present ConceptScheme type is not duplicated")
    void existingConceptScheme_noDuplicate() {
        OntModel model = createDefaultModel();
        // createDefaultModel already adds ConceptScheme

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource ontology = result.getResource(TEST_NS);
        Resource conceptScheme = result.getResource(SKOS_NS + "ConceptScheme");
        long count = result.listStatements(ontology, RDF.type, conceptScheme).toList().size();
        assertEquals(1, count, "Should have exactly one ConceptScheme type");
    }
}
