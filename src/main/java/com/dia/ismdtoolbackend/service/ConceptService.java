package com.dia.ismdtoolbackend.service;

import com.dia.ismdtoolbackend.entity.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.entity.models.concept.ConceptMetadataModel;

public interface ConceptService {
    ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId);
}
