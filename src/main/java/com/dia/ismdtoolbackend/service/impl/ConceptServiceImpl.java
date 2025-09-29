package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.entity.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.service.ConceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConceptServiceImpl implements ConceptService {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ConceptMetadataMapper conceptMetadataMapper;

    @Override
    @Transactional
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        return null;
    }
}
