package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyRef;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;

import java.util.List;

public interface OntologyService {
    OntologyIriCheckResponseDto checkIri(OntologyIriCheckRequestDto request);
    OntologyCreateWithConceptsResponseDto createWithConcepts(OntologyCreateWithConceptsRequestDto request, String userId);
    void deleteOntology(Long ontologyId);
    OntologySummaryDto createOntology(OntologyCreateModel ontologyCreateModel, String userId);
    GetOntologyDto getOntologyDetailModel(String ontologySlug);
    OntologyDetailModel getOntologyDetail(String ontologySlug);
    List<MinimalConceptDto> getConceptsByIri(String ontologyIri, SearchSource source);
    OntologySummaryDto editOntology(Long id, OntologyEditModel ontologyEditModel);
    List<OntologySummaryDto> getAll(String userId, Boolean isPublished);
    List<OntologySummaryDto> getBySlugs(List<String> slugs);
    String getTtlContentFromOntology(OntologyRef ontology);
    OntologyRef getOntologyMetadata(Long ontologyId);
    OntologyRef getOntologyMetadataBySlug(String ontologySlug);
}
