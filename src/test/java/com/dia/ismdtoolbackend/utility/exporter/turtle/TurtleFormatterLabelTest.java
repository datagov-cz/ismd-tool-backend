package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.utility.exporter.RdfTestModelFactory.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - Label Passthrough")
class TurtleFormatterLabelTest {

    @Test
    @DisplayName("Pre-normalized skos:prefLabel is preserved through export")
    void skosPrefLabel_preserved() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, SKOS.Concept);
        cls.addProperty(SKOS.prefLabel, model.createLiteral("Osoba", "cs"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        Property skosPrefLabel = result.getProperty(SKOS_NS + "prefLabel");
        assertTrue(transformed.hasProperty(skosPrefLabel),
                "skos:prefLabel should be preserved");
        Statement stmt = transformed.getProperty(skosPrefLabel);
        assertEquals("cs", stmt.getLanguage());
        assertEquals("Osoba", stmt.getString());
    }

    @Test
    @DisplayName("rdfs:label on non-SKOS resource is preserved")
    void rdfsLabelOnNonSkos_preserved() {
        OntModel model = createDefaultModel();
        Resource other = model.createResource("https://example.org/ontology/SomeClass");
        other.addProperty(RDF.type, OWL2.Class);
        other.addProperty(RDFS.label, model.createLiteral("Some Class", "en"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource("https://example.org/ontology/SomeClass");
        assertTrue(transformed.hasProperty(RDFS.label),
                "rdfs:label should remain on non-SKOS resources");
    }

    @Test
    @DisplayName("Multiple language prefLabels are all preserved")
    void multipleLanguagePrefLabels_preserved() {
        OntModel model = createDefaultModel();
        Resource cls = model.createResource(TEST_POJEM_NS + "osoba");
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, SKOS.Concept);
        cls.addProperty(SKOS.prefLabel, model.createLiteral("Osoba", "cs"));
        cls.addProperty(SKOS.prefLabel, model.createLiteral("Person", "en"));

        Model result = TurtleFormatterUtil.transformToOFNFormat(model);

        Resource transformed = result.getResource(TEST_POJEM_NS + "osoba");
        Property skosPrefLabel = result.getProperty(SKOS_NS + "prefLabel");
        long count = result.listStatements(transformed, skosPrefLabel, (RDFNode) null).toList().size();
        assertEquals(2, count, "Both language variants should be preserved");
    }
}
