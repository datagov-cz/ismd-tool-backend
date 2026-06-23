package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class ClassConceptModel extends ConceptCreateModel {
    private String type;
    private String agendaCode;
    private String agendaSystemCode;
    private String contentType;
    private String acquisitionMethod;
    private List<String> sharingMethod;
    private Boolean isInPPDF;
    private Boolean isPublic;
    private List<String> privacyProvisions;
    private List<String> broaderConcept;
    private String codeListDataset;

    @Override
    public ConceptType getConceptTypeEnum() {
        return ConceptType.TRIDA;
    }
}
