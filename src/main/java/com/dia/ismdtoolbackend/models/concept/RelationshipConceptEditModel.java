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
    private Boolean isPublic;
    private List<String> privacyProvisions;
    private List<String> sharingMethod;
    private String acquisitionMethod;
    private String contentType;
    private String codeListDataset;

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

        ConceptValidationUtil.validatePrivacyPublicConflict(privacyProvisions, isPublic, "Vztah", "ý");
        ConceptValidationUtil.validateCodeListDataset(codeListDataset);
        ConceptValidationUtil.validateGovernanceFields(sharingMethod, acquisitionMethod, contentType);
    }
}
