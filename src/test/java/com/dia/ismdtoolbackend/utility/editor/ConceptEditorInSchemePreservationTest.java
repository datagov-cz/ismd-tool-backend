package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.NameModel;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Editing a concept must never silently drop {@code skos:inScheme} — without
 * it the resolver in
 * {@link com.dia.ismdtoolbackend.repository.JenaTDB2Repository#fetchConceptResolutions}
 * returns the concept as unresolved. The risky case is rename, where
 * {@code renameConceptIRI} rewrites every statement onto the new IRI; this test
 * pins the invariant that {@code skos:inScheme} is among the statements carried
 * over.
 */
class ConceptEditorInSchemePreservationTest extends ConceptEditorTestBase {

    private static final String ONTOLOGY = "https://example.org/slovnik/test-slovnik";

    @Test
    void editConcept_preservesInScheme_whenConceptIRIIsRenamed() {
        String oldIri = ONTOLOGY + "/pojem/old-name";
        Resource scheme = model.createResource(ONTOLOGY);
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");

        Resource existing = model.createResource(oldIri);
        existing.addProperty(RDF.type, SKOS.Concept);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(inScheme, scheme);

        NameModel newName = createNameModel("cs", "New name");
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);

        ConceptEditor.EditResult result = conceptEditor.editConcept(oldIri, classConceptEditModel, model, ONTOLOGY);

        assertTrue(result.iriChanged, "Rename precondition: IRI should have changed");

        Resource renamed = model.getResource(result.newConceptIRI);
        assertTrue(renamed.hasProperty(inScheme, scheme),
                "skos:inScheme must survive a concept rename — the resolver depends on it");

        Resource oldResource = model.getResource(oldIri);
        assertFalse(oldResource.hasProperty(inScheme),
                "Old IRI must no longer carry skos:inScheme after rename");
    }
}
