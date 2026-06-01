package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class GetConceptDto {
    private ConceptMetadataModel conceptMetadata;
    private OntologyDetailModel.ConceptDetailModel conceptDetail;
    private PublishedConceptDeviationModel publishedConceptDeviationModel;
}