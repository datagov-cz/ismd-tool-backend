package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.utility.DataTypeConverter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
public class PropertyConceptModel extends ConceptCreateModel {
    private String dataType;
    private String domain;
    private List<String> superProperty;
    private Boolean isInPPDF;
    private String agendaCode;
    private String agendaSystemCode;
    private Boolean isPublic;
    private List<String> privacyProvisions;
    private List<String> sharingMethod;
    private String acquisitionMethod;
    private String contentType;
    private String codeListDataset;

    @Override
    public ConceptType getConceptTypeEnum() {
        return ConceptType.VLASTNOST;
    }

    @Override
    protected void validateSpecificFields() {
        if (!"VLASTNOST".equalsIgnoreCase(conceptType)) {
            throw new OntologyException("ConceptType musí být 'VLASTNOST'");
        }

        if ((domain == null || domain.trim().isEmpty()) &&
                (dataType == null || dataType.trim().isEmpty())) {
            log.warn("Property '{}' without domain or dataType", nameModel);
        }

        if (dataType != null && !dataType.trim().isEmpty()) {
            DataTypeConverter.isValidXSDType(dataType.trim());
        }

        ConceptValidationUtil.validatePrivacyPublicConflict(privacyProvisions, isPublic, "Vlastnost", "á");
        ConceptValidationUtil.validateCodeListDataset(codeListDataset);
        ConceptValidationUtil.validateGovernanceFields(sharingMethod, acquisitionMethod, contentType);
    }
}
