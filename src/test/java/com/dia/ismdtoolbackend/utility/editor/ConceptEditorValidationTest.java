package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.exception.ConceptValidationException;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dia.constants.VocabularyConstants.DEFAULT_NS;
import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict pre-flight validation: a concept edit that carries any invalid value is
 * rejected as a whole (ConceptValidationException → HTTP 400), the message names
 * every offender, and the model is left untouched (no partial write). Empty/blank
 * values mean "clear" and are NOT rejected.
 *
 * <p>Uses real {@link ClassConceptEditModel} instances (not mocks) so the full
 * field surface is exercised without stubbing ceremony.
 */
class ConceptEditorValidationTest {

    private ConceptEditor conceptEditor;
    private Model model;
    private String conceptIri;

    @BeforeEach
    void setUp() {
        conceptEditor = new ConceptEditor();
        model = ModelFactory.createDefaultModel();
        conceptIri = DEFAULT_NS + "validated-class";
        Resource existing = model.createResource(conceptIri);
        existing.addProperty(SKOS.prefLabel, model.createLiteral("Pojem", "cs"));
        existing.addProperty(RDF.type, model.getResource(OFN_NAMESPACE + TRIDA));
    }

    private ClassConceptEditModel baseModel() {
        ClassConceptEditModel m = new ClassConceptEditModel();
        m.setConceptType("TRIDA");
        return m;
    }

    private ConceptValidationException editExpectingRejection(ClassConceptEditModel m) {
        long sizeBefore = model.size();
        ConceptValidationException ex = assertThrows(ConceptValidationException.class, () ->
                conceptEditor.editConcept(conceptIri, m, model, null));
        assertEquals(sizeBefore, model.size(), "rejected edit must not mutate the model");
        return ex;
    }

    @Test
    void rejectsInvalidExactMatchIri() {
        ClassConceptEditModel m = baseModel();
        m.setExactMatch(List.of("not-an-iri"));
        ConceptValidationException ex = editExpectingRejection(m);
        assertTrue(ex.getMessage().contains("exactMatch"), ex.getMessage());
    }

    @Test
    void rejectsNonCanonicalEliInDefiningLegalSource() {
        ClassConceptEditModel m = baseModel();
        m.setDefiningLegalSource(List.of("https://example.org/not-eli"));
        ConceptValidationException ex = editExpectingRejection(m);
        assertTrue(ex.getMessage().contains("definingLegalSource"), ex.getMessage());
    }

    @Test
    void rejectsNonCanonicalEliInPrivacyProvisions() {
        ClassConceptEditModel m = baseModel();
        m.setPrivacyProvisions(List.of("https://example.org/not-eli"));
        ConceptValidationException ex = editExpectingRejection(m);
        assertTrue(ex.getMessage().contains("privacyProvisions"), ex.getMessage());
    }

    @Test
    void rejectsBlankAndInvalidNonLegalUrls() {
        ClassConceptEditModel m = baseModel();
        m.setDefiningNonLegalSource(List.of(new DigitalObjectModel("Doc", "Popis", "")));
        m.setRelatedNonLegalSource(List.of(new DigitalObjectModel("Doc2", "Popis", "not a url")));
        ConceptValidationException ex = editExpectingRejection(m);
        assertTrue(ex.getMessage().contains("definingNonLegalSource"), ex.getMessage());
        assertTrue(ex.getMessage().contains("relatedNonLegalSource"), ex.getMessage());
    }

    @Test
    void rejectsInvalidAgendaAndAisCodes() {
        ClassConceptEditModel m = baseModel();
        m.setAgendaCode("not-a-valid-agenda");
        m.setAgendaSystemCode("not-a-valid-ais");
        ConceptValidationException ex = editExpectingRejection(m);
        assertTrue(ex.getMessage().contains("agendaCode"), ex.getMessage());
        assertTrue(ex.getMessage().contains("agendaSystemCode"), ex.getMessage());
    }

    @Test
    void reportsAllOffendersInOneMessage() {
        ClassConceptEditModel m = baseModel();
        m.setExactMatch(List.of("bad-iri"));
        m.setDefiningLegalSource(List.of("https://example.org/not-eli"));
        m.setAgendaCode("bad-agenda");
        ConceptValidationException ex = editExpectingRejection(m);
        assertTrue(ex.getMessage().contains("exactMatch"), ex.getMessage());
        assertTrue(ex.getMessage().contains("definingLegalSource"), ex.getMessage());
        assertTrue(ex.getMessage().contains("agendaCode"), ex.getMessage());
    }

    @Test
    void emptyValuesMeanClear_notRejected() {
        // Empty list / blank string = "clear this field". Must NOT be rejected.
        ClassConceptEditModel m = baseModel();
        m.setExactMatch(List.of());
        m.setDefiningLegalSource(List.of());
        m.setAgendaCode("");
        m.setAgendaSystemCode("   ");
        m.setPrivacyProvisions(List.of());

        // Should succeed (no exception), leaving a valid concept.
        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, m, model, null);
        assertNotNull(result);
        assertFalse(result.iriChanged);
    }

    @Test
    void validEdit_isAccepted() {
        // A clean edit with no invalid inputs passes validation and applies.
        ClassConceptEditModel m = baseModel();
        m.setInTezaurus(Boolean.TRUE);
        ConceptEditor.EditResult result = conceptEditor.editConcept(conceptIri, m, model, null);
        assertNotNull(result);
    }
}
