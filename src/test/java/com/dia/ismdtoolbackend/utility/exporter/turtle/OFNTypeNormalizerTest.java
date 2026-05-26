package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.POJEM;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the missing {@code skos:inScheme} write-path bug. The
 * resolver in {@link com.dia.ismdtoolbackend.repository.JenaTDB2Repository#fetchConceptResolutions}
 * only finds concepts that carry {@code skos:inScheme}, so the upload-side
 * normalizer must add it for every concept that's missing one — classes,
 * datatype properties, and object properties alike.
 */
class OFNTypeNormalizerTest {

    private static final String SKOS_INSCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String ONTOLOGY = "https://example.org/slovnik/test-slovnik";
    private static final String CLASS_IRI = ONTOLOGY + "/pojem/test-trida";
    private static final String DATATYPE_PROP_IRI = ONTOLOGY + "/pojem/test-vlastnost";
    private static final String OBJECT_PROP_IRI = ONTOLOGY + "/pojem/test-vztah";

    @Test
    void normalize_addsInScheme_forOwlClassConcept() {
        Model model = ModelFactory.createDefaultModel();
        Resource cls = model.createResource(CLASS_IRI);
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalize(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertTrue(cls.hasProperty(inScheme, model.createResource(ONTOLOGY)),
                "OWL Class concept should get skos:inScheme → enclosing ontology IRI");
    }

    @Test
    void normalize_addsInScheme_forDatatypeProperty() {
        Model model = ModelFactory.createDefaultModel();
        Resource prop = model.createResource(DATATYPE_PROP_IRI);
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalize(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertTrue(prop.hasProperty(inScheme, model.createResource(ONTOLOGY)),
                "Datatype property concept should get skos:inScheme → enclosing ontology IRI");
    }

    @Test
    void normalize_addsInScheme_forObjectProperty() {
        Model model = ModelFactory.createDefaultModel();
        Resource prop = model.createResource(OBJECT_PROP_IRI);
        prop.addProperty(RDF.type, OWL2.ObjectProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalize(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertTrue(prop.hasProperty(inScheme, model.createResource(ONTOLOGY)),
                "Object property concept should get skos:inScheme → enclosing ontology IRI");
    }

    @Test
    void normalize_doesNotOverwriteExistingInScheme_onProperty() {
        Model model = ModelFactory.createDefaultModel();
        String otherScheme = "https://example.org/slovnik/other-slovnik";
        Resource prop = model.createResource(DATATYPE_PROP_IRI);
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        Property inScheme = model.createProperty(SKOS_INSCHEME);
        prop.addProperty(inScheme, model.createResource(otherScheme));

        OFNTypeNormalizer.normalize(model);

        assertTrue(prop.hasProperty(inScheme, model.createResource(otherScheme)),
                "Existing skos:inScheme must be preserved");
        assertFalse(prop.hasProperty(inScheme, model.createResource(ONTOLOGY)),
                "Normalizer must not add a competing skos:inScheme when one already exists");
    }

    @Test
    void normalize_ignoresPropertiesWithoutPojemSegment() {
        Model model = ModelFactory.createDefaultModel();
        Resource prop = model.createResource("https://example.org/something/not-a-concept");
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);

        OFNTypeNormalizer.normalize(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertFalse(prop.hasProperty(inScheme),
                "Resources outside the /pojem/ shape are not concepts and must be left alone");
    }
}
