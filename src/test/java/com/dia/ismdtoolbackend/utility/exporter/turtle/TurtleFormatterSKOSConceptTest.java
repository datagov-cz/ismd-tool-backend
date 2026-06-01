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

@DisplayName("TurtleFormatterUtil - SKOS Concept Passthrough")
class TurtleFormatterSKOSConceptTest {

    @Test
    @DisplayName("Pre-normalized skos:Concept type is preserved through export")
    void preNormalizedSkosConcept_preserved() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, SKOS.Concept);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(SKOS_NS + "Concept")),
                "skos:Concept should be preserved");
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(OFN_NAMESPACE + POJEM)),
                "slovníky:pojem should be preserved");
        assertTrue(transformed.hasProperty(RDF.type, result.getResource(OFN_NAMESPACE + TRIDA)),
                "slovníky:třída should be preserved");
    }

    @Test
    @DisplayName("owl:Class without SKOS types passes through unchanged (normalization is at import)")
    void owlClassOnly_passesThrough() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        // After moving normalization to import, formatter no longer adds SKOS types
        assertTrue(transformed.hasProperty(RDF.type, OWL2.Class),
                "owl:Class should be preserved");
    }

    @Test
    @DisplayName("Base vocabulary class is not affected")
    void baseVocabularyClass_notAffected() {
        OntModel model = createDefaultModel();
        Resource w3cClass = model.createResource("http://www.w3.org/2002/07/owl#Thing");
        w3cClass.addProperty(RDF.type, OWL2.Class);

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource("http://www.w3.org/2002/07/owl#Thing");
        Resource skosConcept = result.getResource(SKOS_NS + "Concept");
        assertFalse(transformed.hasProperty(RDF.type, skosConcept));
    }

    @Test
    @DisplayName("skos:inScheme is preserved through export")
    void skosInScheme_preserved() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, SKOS.Concept);
        cls.addProperty(model.createProperty(SKOS_NS + "inScheme"), model.getResource(TEST_NS));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        assertTrue(transformed.hasProperty(result.getProperty(SKOS_NS + "inScheme")),
                "skos:inScheme should be preserved");
    }
}
