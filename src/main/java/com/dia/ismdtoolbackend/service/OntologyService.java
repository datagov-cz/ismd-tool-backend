package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;

import java.util.List;

public interface OntologyService {
    void deleteOntology(Long ontologyId);
    OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId);
    GetOntologyDto getOntologyDetailModel(String ontologySlug);
    OntologyMetadataModel editOntology(OntologyEditModel ontologyEditModel);
    List<OntologyMetadataModel> getAll(String userId, Boolean isPublished);
    List<OntologyMetadataModel> getBySlugs(List<String> slugs);
    String getTtlContentFromOntology(OntologyMetadataModel ontologyMetadataModel);
}
