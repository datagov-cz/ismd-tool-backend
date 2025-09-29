package com.dia.ismdtoolbackend.entity.models;

import lombok.Data;
import lombok.Getter;

@Getter
@Data
public class ConceptCreateModel {
    private String conceptType;
    private String namespace;
    private String conceptName;
    private String altName;
    private String description;
    private String definition;
    private String definingNonLegalSource;
    private String definingLegalSource;
    private String relatedNonLegalSource;
    private String relatedLegalSource;
    private String exactMatch;
    private Boolean isInPPDF;
    private String inTezaurus;
    private String contentType;
    private String acquisitionMethod;
    private String sharingMethod;
    private String broaderConcept;
}
