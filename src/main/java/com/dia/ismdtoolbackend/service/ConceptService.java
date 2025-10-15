package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;

public interface ConceptService {
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId);
}
