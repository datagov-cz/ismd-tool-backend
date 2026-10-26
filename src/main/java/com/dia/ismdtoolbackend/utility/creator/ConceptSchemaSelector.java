package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.*;

/** Selects the base classes and properties a concept's working model needs. */
final class ConceptSchemaSelector {

    Set<String> determineRequiredBaseClasses(ConceptCreateModel createModel) {
        Set<String> classes = new HashSet<>();
        classes.add(POJEM);

        if (createModel instanceof ClassConceptModel classModel) {
            addClassBaseClasses(classes, classModel);
        } else if (createModel instanceof PropertyConceptModel propModel) {
            classes.add(VLASTNOST);
            addPropertyBaseClasses(classes, propModel);
        } else if (createModel instanceof RelationshipConceptModel relModel) {
            classes.add(VZTAH);
            addRelationshipBaseClasses(classes, relModel);
        }

        return classes;
    }

    void addClassBaseClasses(Set<String> classes, ClassConceptModel classModel) {
        classes.add(TRIDA);
        addTypeSpecificClass(classes, classModel.getType());
        addDataClassificationClasses(classes, classModel);
    }

    void addPropertyBaseClasses(Set<String> classes, PropertyConceptModel propModel) {
        if (hasPublicDataFromModel(propModel)) {
            classes.add(VEREJNY_UDAJ);
        }
        if (hasPrivateDataFromModel(propModel)) {
            classes.add(NEVEREJNY_UDAJ);
        }
    }

    void addRelationshipBaseClasses(Set<String> classes, RelationshipConceptModel relModel) {
        if (hasPublicDataFromRelationship(relModel)) {
            classes.add(VEREJNY_UDAJ);
        }
        if (hasPrivateDataFromRelationship(relModel)) {
            classes.add(NEVEREJNY_UDAJ);
        }
    }

    boolean hasPublicDataFromModel(PropertyConceptModel model) {
        Boolean isPublic = model.getIsPublic();
        return isPublic != null && isPublic &&
                (model.getPrivacyProvisions() == null || model.getPrivacyProvisions().isEmpty());
    }

    boolean hasPrivateDataFromModel(PropertyConceptModel model) {
        return (model.getPrivacyProvisions() != null && !model.getPrivacyProvisions().isEmpty()) ||
                (model.getIsPublic() != null && !model.getIsPublic());
    }

    boolean hasPublicDataFromRelationship(RelationshipConceptModel model) {
        Boolean isPublic = model.getIsPublic();
        return isPublic != null && isPublic &&
                (model.getPrivacyProvisions() == null || model.getPrivacyProvisions().isEmpty());
    }

    boolean hasPrivateDataFromRelationship(RelationshipConceptModel model) {
        return (model.getPrivacyProvisions() != null && !model.getPrivacyProvisions().isEmpty()) ||
                (model.getIsPublic() != null && !model.getIsPublic());
    }

    void addTypeSpecificClass(Set<String> classes, String type) {
        if (type == null) {
            return;
        }
        if (type.contains("subjekt")) {
            classes.add(TSP);
        } else if (type.contains("objekt")) {
            classes.add(TOP);
        }
    }

    void addDataClassificationClasses(Set<String> classes, ClassConceptModel classModel) {
        if (Boolean.TRUE.equals(classModel.getIsPublic())) {
            classes.add(VEREJNY_UDAJ);
        }
        if (Boolean.FALSE.equals(classModel.getIsPublic())) {
            classes.add(NEVEREJNY_UDAJ);
        }
    }

    Set<String> determineRequiredProperties(ConceptCreateModel createModel) {
        Set<String> properties = new HashSet<>();

        addCommonProperties(properties);
        addConditionalCommonProperties(properties, createModel);
        addConceptTypeSpecificProperties(properties, createModel);

        return properties;
    }

    void addCommonProperties(Set<String> properties) {
        properties.add(NAZEV);
        properties.add(POPIS);
        properties.add(DEFINICE);
    }

    void addConditionalCommonProperties(Set<String> properties, ConceptCreateModel createModel) {
        if (createModel.getAltNameModel() != null) {
            properties.add(ALTERNATIVNI_NAZEV);
        }

        if (hasLegalSources(createModel)) {
            properties.add(DEFINUJICI_USTANOVENI);
            properties.add(SOUVISEJICI_USTANOVENI);
        }

        if (hasNonLegalSources(createModel)) {
            properties.add(DEFINUJICI_NELEGISLATIVNI_ZDROJ);
            properties.add(SOUVISEJICI_NELEGISLATIVNI_ZDROJ);
            properties.add("schema:url");
            properties.add("dcterms:title");
            properties.add("dcterms:description");
        }
    }

    void addConceptTypeSpecificProperties(Set<String> properties, ConceptCreateModel createModel) {
        if (createModel instanceof ClassConceptModel classModel) {
            addClassSpecificProperties(properties, classModel);
        } else if (createModel instanceof PropertyConceptModel propModel) {
            addPropertySpecificProperties(properties, propModel);
        } else if (createModel instanceof RelationshipConceptModel relModel) {
            addRelationshipSpecificProperties(properties, relModel);
        }
    }

    void addClassSpecificProperties(Set<String> properties, ClassConceptModel classModel) {
        if (classModel.getAgendaCode() != null && !classModel.getAgendaCode().trim().isEmpty()) {
            properties.add(AGENDA_LONG);
        }
        if (classModel.getAgendaSystemCode() != null && !classModel.getAgendaSystemCode().trim().isEmpty()) {
            properties.add(UDAJE_AIS);
        }
        if (Boolean.FALSE.equals(classModel.getIsPublic())) {
            properties.add(USTANOVENI_NEVEREJNOST);
        }
        if (hasGovernanceProperties(classModel)) {
            properties.add(ZPUSOB_SDILENI);
            properties.add(ZPUSOB_ZISKANI);
            properties.add(TYP_OBSAHU);
        }
    }

    void addPropertySpecificProperties(Set<String> properties, PropertyConceptModel propModel) {
        addCommonGovernanceProperties(properties, propModel.getIsInPPDF(), propModel.getAgendaCode(),
                propModel.getAgendaSystemCode(), propModel.getPrivacyProvisions(),
                propModel.getSharingMethod(), propModel.getAcquisitionMethod(), propModel.getContentType());
    }

    void addRelationshipSpecificProperties(Set<String> properties, RelationshipConceptModel relModel) {
        addCommonGovernanceProperties(properties, relModel.getIsInPPDF(), relModel.getAgendaCode(),
                relModel.getAgendaSystemCode(), relModel.getPrivacyProvisions(),
                relModel.getSharingMethod(), relModel.getAcquisitionMethod(), relModel.getContentType());
    }

    void addCommonGovernanceProperties(Set<String> properties, Boolean isInPPDF, String agendaCode,
                                                 String agendaSystemCode, List<String> privacyProvisions,
                                                 List<String> sharingMethod, String acquisitionMethod, String contentType) {
        if (Boolean.TRUE.equals(isInPPDF)) {
            properties.add(JE_PPDF_LONG);
        }
        if (agendaCode != null && !agendaCode.trim().isEmpty()) {
            properties.add(AGENDA_LONG);
        }
        if (agendaSystemCode != null && !agendaSystemCode.trim().isEmpty()) {
            properties.add(UDAJE_AIS);
        }
        if (hasPrivacyProvisions(privacyProvisions)) {
            properties.add(USTANOVENI_NEVEREJNOST);
        }
        if (hasGovernancePropertiesValues(sharingMethod, acquisitionMethod, contentType)) {
            properties.add(ZPUSOB_SDILENI);
            properties.add(ZPUSOB_ZISKANI);
            properties.add(TYP_OBSAHU);
        }
    }

    boolean hasLegalSources(ConceptCreateModel model) {
        return (model.getDefiningLegalSource() != null && !model.getDefiningLegalSource().isEmpty()) ||
                (model.getRelatedLegalSource() != null && !model.getRelatedLegalSource().isEmpty());
    }

    boolean hasNonLegalSources(ConceptCreateModel model) {
        return (model.getDefiningNonLegalSource() != null && !model.getDefiningNonLegalSource().isEmpty()) ||
                (model.getRelatedNonLegalSource() != null && !model.getRelatedNonLegalSource().isEmpty());
    }

    boolean hasGovernanceProperties(ClassConceptModel model) {
        return (model.getSharingMethod() != null && !model.getSharingMethod().isEmpty()) ||
                (model.getAcquisitionMethod() != null && !model.getAcquisitionMethod().isEmpty()) ||
                (model.getContentType() != null && !model.getContentType().isEmpty());
    }

    boolean hasPrivacyProvisions(List<String> privacyProvisions) {
        return privacyProvisions != null && !privacyProvisions.isEmpty();
    }

    boolean hasGovernancePropertiesValues(List<String> sharingMethod, String acquisitionMethod, String contentType) {
        return (sharingMethod != null && !sharingMethod.isEmpty()) ||
                (acquisitionMethod != null && !acquisitionMethod.trim().isEmpty()) ||
                (contentType != null && !contentType.trim().isEmpty());
    }
}
