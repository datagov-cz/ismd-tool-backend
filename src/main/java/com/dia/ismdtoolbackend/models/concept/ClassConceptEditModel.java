package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ClassConceptEditModel extends ConceptEditModel {
    private String type;
    private String agendaCode;
    private String agendaSystemCode;
    private String contentType;
    private String acquisitionMethod;
    private List<String> sharingMethod;
    private String isPublic;
    private String privacyProvision;
    private String broaderConcept;

    @Override
    public ConceptType getConceptTypeEnum() {
        return ConceptType.TRIDA;
    }

    @Override
    protected void validateSpecificFields() {
        if (!"TRIDA".equalsIgnoreCase(conceptType)) {
            throw new OntologyException("ConceptType musí být 'TRIDA'");
        }

        if (privacyProvision != null && !privacyProvision.trim().isEmpty() && isPublic != null && isPublicTrue(isPublic)) {
            throw new OntologyException(
                    "Třída nemůže být současně veřejná a mít ustanovení o neveřejnosti"
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

    private boolean isPublicTrue(String value) {
        return value.toLowerCase().contains("ano") ||
                value.toLowerCase().contains("true") ||
                value.equalsIgnoreCase("yes");
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
