package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;

public interface ConceptService {
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId);
    void deleteConcept(Long conceptId);
    ConceptMetadataModel editConcept(ConceptEditModel conceptEditModel);
}
