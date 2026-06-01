package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.DescriptionModel;
import com.dia.ismdtoolbackend.models.NameModel;
import com.dia.ismdtoolbackend.models.concept.AltNameModel;
import com.dia.ismdtoolbackend.models.concept.ClassConceptEditModel;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.concept.DefinitionModel;
import com.dia.ismdtoolbackend.models.concept.PropertyConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.RelationshipConceptEditModel;
import lombok.Getter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
abstract class ConceptEditorTestBase {

    @InjectMocks
    protected ConceptEditor conceptEditor;

    @Mock
    protected ClassConceptEditModel classConceptEditModel;

    @Mock
    protected PropertyConceptEditModel propertyConceptEditModel;

    @Mock
    protected RelationshipConceptEditModel relationshipConceptEditModel;

    @Getter
    protected NameModel nameModel;
    @Getter
    protected DescriptionModel descriptionModel;
    @Getter
    protected DefinitionModel definitionModel;
    @Getter
    protected AltNameModel altNameModel;

    protected Model model;

    @BeforeEach
    void setUp() {
        model = ModelFactory.createDefaultModel();
        nameModel = new NameModel();
        descriptionModel = new DescriptionModel();
        definitionModel = new DefinitionModel();
        altNameModel = new AltNameModel();
    }

    // ========== Helper Methods for Model Creation ==========

    protected NameModel createNameModel(String languageCode, String value) {
        NameModel nameModel1 = new NameModel();
        nameModel1.setName(Map.of(languageCode, value));
        return nameModel1;
    }

    protected DescriptionModel createDescriptionModel(String languageCode, String value) {
        DescriptionModel descriptionModel1 = new DescriptionModel();
        descriptionModel1.setDescription(Map.of(languageCode, value));
        return descriptionModel1;
    }

    protected DefinitionModel createDefinitionModel(String languageCode, String value) {
        DefinitionModel definitionModel1 = new DefinitionModel();
        definitionModel1.setDefinition(Map.of(languageCode, value));
        return definitionModel1;
    }

    protected AltNameModel createAltNameModel(String languageCode, String value) {
        AltNameModel altNameModel1 = new AltNameModel();
        altNameModel1.setAltName(Map.of(languageCode, value));
        return altNameModel1;
    }

    // ========== Helper: stub all fields null for each concept type ==========

    protected void stubAllClassFieldsNull(ClassConceptEditModel mock) {
        when(mock.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        when(mock.getNameModel()).thenReturn(null);
        when(mock.getDescriptionModel()).thenReturn(null);
        when(mock.getDefinitionModel()).thenReturn(null);
        when(mock.getAltNameModel()).thenReturn(null);
        when(mock.getDefiningLegalSource()).thenReturn(null);
        when(mock.getRelatedLegalSource()).thenReturn(null);
        when(mock.getDefiningNonLegalSource()).thenReturn(null);
        when(mock.getRelatedNonLegalSource()).thenReturn(null);
        when(mock.getExactMatch()).thenReturn(null);
        when(mock.getInTezaurus()).thenReturn(null);
        when(mock.getNamespace()).thenReturn(null);
        when(mock.getType()).thenReturn(null);
        when(mock.getPrivacyProvisions()).thenReturn(null);
        when(mock.getBroaderConcept()).thenReturn(null);
        when(mock.getIsInPPDF()).thenReturn(null);
        when(mock.getAgendaCode()).thenReturn(null);
        when(mock.getAgendaSystemCode()).thenReturn(null);
        when(mock.getSharingMethod()).thenReturn(null);
        when(mock.getAcquisitionMethod()).thenReturn(null);
        when(mock.getContentType()).thenReturn(null);
        when(mock.getIsPublic()).thenReturn(null);
    }

    protected void stubAllPropertyFieldsNull(PropertyConceptEditModel mock) {
        when(mock.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
        when(mock.getNameModel()).thenReturn(null);
        when(mock.getDescriptionModel()).thenReturn(null);
        when(mock.getDefinitionModel()).thenReturn(null);
        when(mock.getAltNameModel()).thenReturn(null);
        when(mock.getDefiningLegalSource()).thenReturn(null);
        when(mock.getRelatedLegalSource()).thenReturn(null);
        when(mock.getDefiningNonLegalSource()).thenReturn(null);
        when(mock.getRelatedNonLegalSource()).thenReturn(null);
        when(mock.getExactMatch()).thenReturn(null);
        when(mock.getInTezaurus()).thenReturn(null);
        when(mock.getNamespace()).thenReturn(null);
        when(mock.getDomain()).thenReturn(null);
        when(mock.getDataType()).thenReturn(null);
        when(mock.getSuperProperty()).thenReturn(null);
        when(mock.getIsInPPDF()).thenReturn(null);
        when(mock.getAgendaCode()).thenReturn(null);
        when(mock.getAgendaSystemCode()).thenReturn(null);
        when(mock.getSharingMethod()).thenReturn(null);
        when(mock.getAcquisitionMethod()).thenReturn(null);
        when(mock.getContentType()).thenReturn(null);
        when(mock.getIsPublic()).thenReturn(null);
    }

    protected void stubAllRelationshipFieldsNull(RelationshipConceptEditModel mock) {
        when(mock.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
        when(mock.getNameModel()).thenReturn(null);
        when(mock.getDescriptionModel()).thenReturn(null);
        when(mock.getDefinitionModel()).thenReturn(null);
        when(mock.getAltNameModel()).thenReturn(null);
        when(mock.getDefiningLegalSource()).thenReturn(null);
        when(mock.getRelatedLegalSource()).thenReturn(null);
        when(mock.getDefiningNonLegalSource()).thenReturn(null);
        when(mock.getRelatedNonLegalSource()).thenReturn(null);
        when(mock.getExactMatch()).thenReturn(null);
        when(mock.getInTezaurus()).thenReturn(null);
        when(mock.getNamespace()).thenReturn(null);
        when(mock.getDomain()).thenReturn(null);
        when(mock.getRange()).thenReturn(null);
        when(mock.getSuperRelation()).thenReturn(null);
        when(mock.getIsInPPDF()).thenReturn(null);
        when(mock.getAgendaCode()).thenReturn(null);
        when(mock.getAgendaSystemCode()).thenReturn(null);
        when(mock.getSharingMethod()).thenReturn(null);
        when(mock.getAcquisitionMethod()).thenReturn(null);
        when(mock.getContentType()).thenReturn(null);
        when(mock.getIsPublic()).thenReturn(null);
    }
}
