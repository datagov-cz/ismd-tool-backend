package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;

import java.util.List;

public interface ConceptService {
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId);
    void deleteConcept(Long conceptId);
    ConceptMetadataModel editConcept(ConceptEditModel conceptEditModel);
    List<ConceptMetadataModel> getAll(String userId, Boolean isPublished);
}
