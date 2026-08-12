package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;

import java.util.List;
import java.util.Optional;

public interface OntologyService {
    void deleteOntology(Long ontologyId);
    OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId);
    GetOntologyDto getOntologyDetailModel(String ontologySlug);
    OntologyDetailModel getOntologyDetail(String ontologySlug);
    List<MinimalConceptDto> getConceptsByIri(String ontologyIri, SearchSource source);
    OntologyMetadataModel editOntology(Long id, OntologyEditModel ontologyEditModel);
    List<OntologyMetadataModel> getAll(String userId, Boolean isPublished);
    List<OntologyMetadataModel> getBySlugs(List<String> slugs);
    String getTtlContentFromOntology(OntologyMetadataModel ontologyMetadataModel);
    OntologyMetadataModel getOntologyMetadata(Long ontologyId);
}
