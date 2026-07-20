package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.utility.URIGenerator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static com.dia.constants.VocabularyConstants.*;

/**
 * Z — Generic / error handling tests
 *   Z1 – Error when concept is not found in the model
 *   Z2 – Error when renamed IRI already exists
 *   Z3 – Custom namespace from valid graphName IRI
 */
class ConceptEditorErrorHandlingTest extends ConceptEditorTestBase {

    // Z1 – Error when concept is not found in the model
    @Test
    void editConcept_ShouldThrowWhenConceptNotFound() {
        String missingIri = DEFAULT_NS + "missing-concept";

        assertThrows(IllegalArgumentException.class,
                () -> conceptEditor.editConcept(missingIri, classConceptEditModel, model, null));
    }

    // Z2 – Error when the renamed IRI is already claimed by another owned concept
    @Test
    void editConcept_ShouldThrowWhenRenamedIRIAlreadyExists() {
        String oldIri = "https://slovnik.gov.cz/pojem/old-class";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, SKOS.Concept);

        URIGenerator uriGen = new URIGenerator();
        String conflictingIri = uriGen.generateConceptURI("New name", "ID-1");

        NameModel newName = createNameModel("cs", "New name");

        when(classConceptEditModel.getNameModel()).thenReturn(newName);
        when(classConceptEditModel.getIdentifier()).thenReturn("ID-1");

        assertThrows(ConceptValidationException.class,
                () -> conceptEditor.editConcept(oldIri, classConceptEditModel, model, null,
                        conflictingIri::equals));
    }

    // Z2b – A rename onto an IRI that merely appears in the graph (a referenced concept or an
    // NKD snapshot copy, neither of which is an owned concept) is allowed.
    @Test
    void editConcept_ShouldAllowRenameWhenIriOnlyPresentInModel() {
        String oldIri = "https://slovnik.gov.cz/pojem/old-class";
        Resource existing = model.createResource(oldIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        URIGenerator uriGen = new URIGenerator();
        String referencedIri = uriGen.generateConceptURI("New name", "ID-1");
        model.createResource(referencedIri).addProperty(RDF.type, SKOS.Concept);

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getNameModel()).thenReturn(createNameModel("cs", "New name"));
        when(classConceptEditModel.getIdentifier()).thenReturn("ID-1");

        ConceptEditor.EditResult result = conceptEditor.editConcept(
                oldIri, classConceptEditModel, model, null, iri -> false);

        assertEquals(referencedIri, result.newConceptIRI);
    }

    // Z3 – Custom namespace from valid graphName IRI
    @Test
    void editConcept_ShouldUseCustomNamespace_WhenGraphNameIsValidIRI() {
        String conceptIri = DEFAULT_NS + "class-z3";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class z3", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getInTezaurus()).thenReturn(Boolean.TRUE);

        conceptEditor.editConcept(conceptIri, classConceptEditModel, model, "https://custom.cz/vocab/");

        Resource updated = model.getResource(conceptIri);
        Property customInTezaurus = model.createProperty("https://custom.cz/vocab/inTezaurus");
        assertTrue(updated.hasProperty(customInTezaurus));
        assertEquals("true", updated.getProperty(customInTezaurus).getObject().asLiteral().getString());

        Property defaultInTezaurus = model.createProperty(DEFAULT_NS + "inTezaurus");
        assertFalse(updated.hasProperty(defaultInTezaurus));
    }
}
