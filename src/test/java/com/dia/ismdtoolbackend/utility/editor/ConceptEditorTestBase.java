package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.EditModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared set-up for the {@link ConceptEditor} tests: a real editor, an empty model and one empty
 * edit model per concept type. Every edit-model field starts {@code null}, which the editor reads
 * as "leave unchanged", so a test sets only the fields it edits.
 */
abstract class ConceptEditorTestBase {

    protected ConceptEditor conceptEditor;
    protected ClassConceptEditModel classConceptEditModel;
    protected PropertyConceptEditModel propertyConceptEditModel;
    protected RelationshipConceptEditModel relationshipConceptEditModel;
    protected Model model;

    @BeforeEach
    void setUpEditor() {
        conceptEditor = new ConceptEditor();
        model = ModelFactory.createDefaultModel();
        classConceptEditModel = EditModels.trida();
        propertyConceptEditModel = EditModels.vlastnost();
        relationshipConceptEditModel = EditModels.vztah();
    }

    protected NameModel createNameModel(String languageCode, String value) {
        return Texts.name(languageCode, value);
    }

    protected DescriptionModel createDescriptionModel(String languageCode, String value) {
        return Texts.description(languageCode, value);
    }

    protected DefinitionModel createDefinitionModel(String languageCode, String value) {
        return Texts.definition(languageCode, value);
    }

    protected AltNameModel createAltNameModel(String languageCode, String value) {
        return Texts.altName(languageCode, value);
    }
}
