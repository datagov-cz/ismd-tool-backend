package com.dia.ismdtoolbackend.entity.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.utility.DataTypeConverter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;

@Data
@EqualsAndHashCode(callSuper = true)
@Slf4j
public class PropertyConceptModel extends ConceptCreateModel {
    private String dataType;
    private String domain;
    private String superProperty;
    private Boolean isInPPDF;

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
            log.warn("Property '{}' without domain or dataType", conceptName);
        }

        if (dataType != null && !dataType.trim().isEmpty()) {
            DataTypeConverter.isValidXSDType(dataType.trim());
        }
    }
}
