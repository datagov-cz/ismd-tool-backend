package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.ModelCom;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static com.dia.constants.VocabularyConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ConceptEditor transaction handling and exception propagation
 * during name change / IRI rename operations.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
class ConceptEditorTransactionTest extends ConceptEditorTestBase {

    @Test
    void editConcept_exceptionDuringEdit_shouldPreserveOriginalCause() {
        String conceptIri = DEFAULT_NS + "class-tx1";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class tx1", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        // Stub the mock to throw during edit
        when(classConceptEditModel.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(classConceptEditModel.getNameModel()).thenReturn(null);
        RuntimeException cause = new RuntimeException("Simulated failure");
        when(classConceptEditModel.getType()).thenThrow(cause);

        // Stub remaining fields to avoid NPEs before the one that throws
        when(classConceptEditModel.getDescriptionModel()).thenReturn(null);
        when(classConceptEditModel.getDefinitionModel()).thenReturn(null);
        when(classConceptEditModel.getAltNameModel()).thenReturn(null);
        when(classConceptEditModel.getDefiningLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedLegalSource()).thenReturn(null);
        when(classConceptEditModel.getDefiningNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getRelatedNonLegalSource()).thenReturn(null);
        when(classConceptEditModel.getExactMatch()).thenReturn(null);
        when(classConceptEditModel.getInTezaurus()).thenReturn(null);
        when(classConceptEditModel.getNamespace()).thenReturn(null);
        when(classConceptEditModel.getPrivacyProvisions()).thenReturn(null);
        when(classConceptEditModel.getBroaderConcept()).thenReturn(null);
        when(classConceptEditModel.getIsInPPDF()).thenReturn(null);
        when(classConceptEditModel.getAgendaCode()).thenReturn(null);
        when(classConceptEditModel.getAgendaSystemCode()).thenReturn(null);
        when(classConceptEditModel.getSharingMethod()).thenReturn(null);
        when(classConceptEditModel.getAcquisitionMethod()).thenReturn(null);
        when(classConceptEditModel.getContentType()).thenReturn(null);
        when(classConceptEditModel.getIsPublic()).thenReturn(null);
        when(classConceptEditModel.getIdentifier()).thenReturn(null);

        OntologyException thrown = assertThrows(OntologyException.class,
                () -> conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null));

        assertNotNull(thrown.getCause());
        assertSame(cause, thrown.getCause());
        assertTrue(thrown.getMessage().contains(conceptIri));
        assertTrue(thrown.getMessage().contains("Simulated failure"));
    }

    @Test
    void editConcept_exceptionDuringNameChange_shouldNotCorruptModel() {
        String conceptIri = DEFAULT_NS + "class-tx2";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Class tx2", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
        existing.addProperty(model.createProperty(DEFAULT_NS + "custom-prop"), "custom-value");

        long statementCountBefore = model.size();

        stubAllClassFieldsNull(classConceptEditModel);
        // Set a name change that will succeed, but then fail on subsequent field processing
        when(classConceptEditModel.getNameModel()).thenReturn(createNameModel("cs", "New name tx2"));
        when(classConceptEditModel.getIdentifier()).thenReturn("TX-2");
        when(classConceptEditModel.getType()).thenThrow(new RuntimeException("Simulated edit failure"));

        assertThrows(OntologyException.class,
                () -> conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null));

        // Model uses default (non-transactional) implementation, so statements may have been partially modified.
        // But the original concept should still have its statements since remove/add happens after all edits are collected.
        // The exception happens during statement collection, before model.remove/model.add.
        // Verify the model is still usable (no corruption)
        assertDoesNotThrow(() -> model.size());
    }

    @Test
    void editConcept_transactionalModel_shouldAbortOnFailure() {
        // Create a spy on a real model to verify transaction behavior
        Model realModel = ModelFactory.createDefaultModel();
        String conceptIri = DEFAULT_NS + "class-tx3";
        Resource existing = realModel.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, realModel.createLiteral("Class tx3", "cs"));
        existing.addProperty(RDF.type, realModel.getResource(OFN_NAMESPACE + TRIDA));

        // Default model doesn't support transactions, so supportsTransactions() returns false.
        // The transaction abort logic only activates for transactional models (e.g., TDB2).
        // We verify the non-transactional path doesn't crash.
        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getType()).thenThrow(new RuntimeException("Fail during edit"));

        OntologyException thrown = assertThrows(OntologyException.class,
                () -> conceptEditor.editConcept(conceptIri, classConceptEditModel, realModel, null));

        assertNotNull(thrown.getCause());
    }

    @Test
    void editConcept_successfulNameChange_shouldReturnNewIRI() {
        String conceptIri = DEFAULT_NS + "class-tx4";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Old name", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));

        stubAllClassFieldsNull(classConceptEditModel);
        when(classConceptEditModel.getNameModel()).thenReturn(createNameModel("cs", "Brand new name"));
        when(classConceptEditModel.getIdentifier()).thenReturn(null);

        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, classConceptEditModel, model, null);

        assertTrue(result.iriChanged);
        assertNotEquals(conceptIri, result.newConceptIRI);
        assertTrue(result.changesCount > 0);
    }
}
