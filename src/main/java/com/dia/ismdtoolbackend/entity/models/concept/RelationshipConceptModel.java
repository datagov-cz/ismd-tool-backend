package com.dia.ismdtoolbackend.entity.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
public class RelationshipConceptModel extends ConceptCreateModel {
    private String conceptType;
    private String domain;
    private String range;
    private String superRelation;

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
            log.warn("Relationship '{}' without domain/range", conceptName);
        }
    }
}
