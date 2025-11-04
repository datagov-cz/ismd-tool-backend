package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import org.apache.jena.ontology.OntologyException;

import java.util.List;

public interface ConceptService {
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId);
    void deleteConcept(Long conceptId);
    ConceptMetadataModel editConcept(ConceptEditModel conceptEditModel);
    List<ConceptMetadataModel> getAll(String userId, Boolean isPublished);
    GetConceptDto getConceptDetail(String conceptSlug) throws OntologyException;
}
