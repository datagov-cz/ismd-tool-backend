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

import static org.mockito.Mockito.lenient;

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
        lenient().when(mock.getConceptTypeEnum()).thenReturn(ConceptType.TRIDA);
        lenient().when(mock.getNameModel()).thenReturn(null);
        lenient().when(mock.getDescriptionModel()).thenReturn(null);
        lenient().when(mock.getDefinitionModel()).thenReturn(null);
        lenient().when(mock.getAltNameModel()).thenReturn(null);
        lenient().when(mock.getDefiningLegalSource()).thenReturn(null);
        lenient().when(mock.getRelatedLegalSource()).thenReturn(null);
        lenient().when(mock.getDefiningNonLegalSource()).thenReturn(null);
        lenient().when(mock.getRelatedNonLegalSource()).thenReturn(null);
        lenient().when(mock.getExactMatch()).thenReturn(null);
        lenient().when(mock.getInTezaurus()).thenReturn(null);
        lenient().when(mock.getNamespace()).thenReturn(null);
        lenient().when(mock.getType()).thenReturn(null);
        lenient().when(mock.getPrivacyProvisions()).thenReturn(null);
        lenient().when(mock.getBroaderConcept()).thenReturn(null);
        lenient().when(mock.getIsInPPDF()).thenReturn(null);
        lenient().when(mock.getAgendaCode()).thenReturn(null);
        lenient().when(mock.getAgendaSystemCode()).thenReturn(null);
        lenient().when(mock.getSharingMethod()).thenReturn(null);
        lenient().when(mock.getAcquisitionMethod()).thenReturn(null);
        lenient().when(mock.getContentType()).thenReturn(null);
        lenient().when(mock.getIsPublic()).thenReturn(null);
    }

    protected void stubAllPropertyFieldsNull(PropertyConceptEditModel mock) {
        lenient().when(mock.getConceptTypeEnum()).thenReturn(ConceptType.VLASTNOST);
        lenient().when(mock.getNameModel()).thenReturn(null);
        lenient().when(mock.getDescriptionModel()).thenReturn(null);
        lenient().when(mock.getDefinitionModel()).thenReturn(null);
        lenient().when(mock.getAltNameModel()).thenReturn(null);
        lenient().when(mock.getDefiningLegalSource()).thenReturn(null);
        lenient().when(mock.getRelatedLegalSource()).thenReturn(null);
        lenient().when(mock.getDefiningNonLegalSource()).thenReturn(null);
        lenient().when(mock.getRelatedNonLegalSource()).thenReturn(null);
        lenient().when(mock.getExactMatch()).thenReturn(null);
        lenient().when(mock.getInTezaurus()).thenReturn(null);
        lenient().when(mock.getNamespace()).thenReturn(null);
        lenient().when(mock.getDomain()).thenReturn(null);
        lenient().when(mock.getDataType()).thenReturn(null);
        lenient().when(mock.getSuperProperty()).thenReturn(null);
        lenient().when(mock.getIsInPPDF()).thenReturn(null);
        lenient().when(mock.getAgendaCode()).thenReturn(null);
        lenient().when(mock.getAgendaSystemCode()).thenReturn(null);
        lenient().when(mock.getSharingMethod()).thenReturn(null);
        lenient().when(mock.getAcquisitionMethod()).thenReturn(null);
        lenient().when(mock.getContentType()).thenReturn(null);
        lenient().when(mock.getIsPublic()).thenReturn(null);
    }

    protected void stubAllRelationshipFieldsNull(RelationshipConceptEditModel mock) {
        lenient().when(mock.getConceptTypeEnum()).thenReturn(ConceptType.VZTAH);
        lenient().when(mock.getNameModel()).thenReturn(null);
        lenient().when(mock.getDescriptionModel()).thenReturn(null);
        lenient().when(mock.getDefinitionModel()).thenReturn(null);
        lenient().when(mock.getAltNameModel()).thenReturn(null);
        lenient().when(mock.getDefiningLegalSource()).thenReturn(null);
        lenient().when(mock.getRelatedLegalSource()).thenReturn(null);
        lenient().when(mock.getDefiningNonLegalSource()).thenReturn(null);
        lenient().when(mock.getRelatedNonLegalSource()).thenReturn(null);
        lenient().when(mock.getExactMatch()).thenReturn(null);
        lenient().when(mock.getInTezaurus()).thenReturn(null);
        lenient().when(mock.getNamespace()).thenReturn(null);
        lenient().when(mock.getDomain()).thenReturn(null);
        lenient().when(mock.getRange()).thenReturn(null);
        lenient().when(mock.getSuperRelation()).thenReturn(null);
        lenient().when(mock.getIsInPPDF()).thenReturn(null);
        lenient().when(mock.getAgendaCode()).thenReturn(null);
        lenient().when(mock.getAgendaSystemCode()).thenReturn(null);
        lenient().when(mock.getSharingMethod()).thenReturn(null);
        lenient().when(mock.getAcquisitionMethod()).thenReturn(null);
        lenient().when(mock.getContentType()).thenReturn(null);
        lenient().when(mock.getIsPublic()).thenReturn(null);
    }
}
