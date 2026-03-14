package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.utility.URIGenerator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static com.dia.constants.VocabularyConstants.*;

/**
 * D — Cross-cutting / edge cases
 *   D1  – Name unchanged = no IRI change
 *   D1b – Identifier-based IRI generation
 *   D2  – Null fields do not cause removals
 */
class ConceptEditorCrossCuttingTest extends ConceptEditorTestBase {

    // D1 – Name unchanged = no IRI change
    @Test
    void editConcept_ShouldNotChangeIRI_WhenNameStaysTheSame() {
        String conceptIri = DEFAULT_NS + "class-d1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Same name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));

        stubAllClassFieldsNull(classConceptEditModel);
        NameModel sameName = createNameModel("cs", "Same name");
        when(classConceptEditModel.getNameModel()).thenReturn(sameName);
        when(classConceptEditModel.getType()).thenReturn("subjekt");

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertFalse(result.iriChanged);
        assertEquals(conceptIri, result.newConceptIRI);

        Resource updated = model.getResource(conceptIri);
        Resource tspType = model.getResource(OFN_NAMESPACE + TSP);
        Resource topType = model.getResource(OFN_NAMESPACE + TOP);
        assertTrue(updated.hasProperty(RDF.type, tspType));
        assertFalse(updated.hasProperty(RDF.type, topType));
    }

    // D1b – Identifier-based IRI generation
    @Test
    void editConcept_ShouldUseIdentifier_WhenIdentifierIsNonNull() {
        String oldIri = DEFAULT_NS + "class-d1b";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        NameModel newName = createNameModel("cs", "New name d1b");
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("CUSTOM-ID-123");

        URIGenerator uriGen = new URIGenerator();
        String expectedIri = uriGen.generateConceptURI("New name d1b", "CUSTOM-ID-123");

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(oldIri, classConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        assertEquals(expectedIri, result.newConceptIRI);
    }

    // D2 – Null fields do not cause removals
    @Test
    void editConcept_ShouldNotRemoveExistingData_WhenEditFieldsAreNull() {
        String conceptIri = DEFAULT_NS + "class-d2";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class d2", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP));

        Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
        existing.addProperty(descProperty, model.createLiteral("Existing description", "cs"));
        existing.addProperty(SKOS.definition, model.createLiteral("Existing definition", "cs"));
        existing.addProperty(SKOS.altLabel, model.createLiteral("Existing alt", "cs"));

        Property exactMatchProp = model.createProperty("http://www.w3.org/2004/02/skos/core#exactMatch");
        existing.addProperty(exactMatchProp, model.createResource("https://example.com/existing-match"));

        Property ppdfProperty = model.createProperty(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
        existing.addProperty(ppdfProperty, model.createLiteral("true"));

        long originalStatementCount = model.size();

        stubAllClassFieldsNull(classConceptEditModel);

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertFalse(result.iriChanged);
        assertEquals(0, result.changesCount);

        Resource updated = model.getResource(conceptIri);
        assertTrue(updated.hasProperty(SKOS.prefLabel));
        assertTrue(updated.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA)));
        assertTrue(updated.hasProperty(RDF.type, model.getResource(OFN_NAMESPACE + TOP)));
        assertTrue(updated.hasProperty(descProperty));
        assertTrue(updated.hasProperty(SKOS.definition));
        assertTrue(updated.hasProperty(SKOS.altLabel));
        assertTrue(updated.hasProperty(exactMatchProp));
        assertTrue(updated.hasProperty(ppdfProperty));
        assertEquals(originalStatementCount, model.size());
    }
}
