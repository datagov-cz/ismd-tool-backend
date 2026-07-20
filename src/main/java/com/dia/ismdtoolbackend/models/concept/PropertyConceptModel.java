package com.dia.ismdtoolbackend.models.concept;

import com.dia.ismdtoolbackend.enums.ConceptType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
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
}
