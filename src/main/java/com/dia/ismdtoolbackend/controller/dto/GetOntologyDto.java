package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Data
@Getter
@Setter
public class GetOntologyDto {
    private OntologyMetadataModel ontologyMetadata;
    private OntologyDetailModel ontologyDetail;
    private List<ConceptMetadataModel> conceptMetadataModelList;
}
