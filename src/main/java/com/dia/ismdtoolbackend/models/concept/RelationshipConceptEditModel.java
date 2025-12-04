package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
public class RelationshipConceptEditModel extends ConceptEditModel {
    private String domain;
    private String range;
    private List<String> superRelation;
    private Boolean isInPPDF;
    private String agendaCode;
    private String agendaSystemCode;
    private String isPublic;
    private String privacyProvision;
    private List<String> sharingMethod;
    private String acquisitionMethod;
    private String contentType;

    @Override
    public ConceptType getConceptTypeEnum() {
        return ConceptType.VZTAH;
    }

    @Override
    protected void validateSpecificFields() {
        if (!"VZTAH".equalsIgnoreCase(conceptType)) {
            throw new OntologyException("ConceptType musí být 'VZTAH'");
        }

        if ((domain == null || domain.trim().isEmpty()) ||
                (range == null || range.trim().isEmpty())) {
            log.warn("Relationship '{}' without domain/range", nameModel);
        }

        if (privacyProvision != null && !privacyProvision.trim().isEmpty() && isPublic != null && isPublicTrue(isPublic)) {
            throw new OntologyException(
                    "Vztah nemůže být současně veřejný a mít ustanovení o neveřejnosti"
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
