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

        if (privacyProvisions != null && !privacyProvisions.isEmpty() && isPublic != null && isPublic) {
            throw new OntologyException(
                    "Vlastnost nemůže být současně veřejná a mít ustanovení o neveřejnosti"
            );
        }

        if (sharingMethod != null && !sharingMethod.isEmpty()) {
            for (String s : sharingMethod) {
                validateGovernanceValue(s, "způsob sdílení");
            }
        }
        if (acquisitionMethod != null && !acquisitionMethod.trim().isEmpty()) {
            validateGovernanceValue(acquisitionMethod, "způsob získání");
        }
        if (contentType != null && !contentType.trim().isEmpty()) {
            validateGovernanceValue(contentType, "typ obsahu");
        }
    }

    private void validateGovernanceValue(String value, String fieldName) {
        String[] allowedValues = {
                "veřejně přístupné", "poskytované na žádost", "nesdílené",
                "základních registrů", "jiných agend", "vlastní",
                "provozní", "identifikační", "evidenční", "statistické"
        };

        boolean valid = false;
        for (String allowed : allowedValues) {
            if (allowed.equalsIgnoreCase(value)) {
                valid = true;
                break;
            }
        }

        if (!valid) {
            throw new OntologyException(
                    "Neplatná hodnota pro " + fieldName + ": " + value
            );
        }
    }
}
