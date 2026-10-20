package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Entry point for concept operations. Owns the transaction and cache-eviction boundaries and
 * delegates each operation to its focused class.
 */
@Service
@RequiredArgsConstructor
public class ConceptServiceImpl implements ConceptService {

    private final ConceptCreateOperation createOperation;
    private final ConceptEditOperation editOperation;
    private final ConceptDeleteOperation deleteOperation;
    private final WorkingCopySyncOperation syncOperation;
    private final ConceptDetailReader detailReader;

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        return createOperation.createConcept(createModel, userId);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public List<ConceptMetadataModel> createConcepts(List<? extends ConceptCreateModel> createModels, String userId) {
        return createModels.stream()
                .map(createModel -> createOperation.createConcept(createModel, userId))
                .toList();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public void deleteConcept(Long conceptId) {
        deleteOperation.deleteConcept(conceptId);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel) {
        return editOperation.editConcept(conceptId, conceptEditModel);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public String syncWorkingCopy(Long conceptId, List<String> fieldsToAccept) {
        return syncOperation.syncWorkingCopy(conceptId, fieldsToAccept);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConceptMetadataModel> getAll(String userId, Boolean isPublished) {
        return detailReader.getAll(userId, isPublished);
    }

    @Override
    // Deliberately NOT @Transactional: PG reads interleave with Fuseki, NKD and RPP calls (10s
    // timeouts), so a request-wide transaction would pin a pool connection across them. Entities
    // read after their repository call returns are join-fetched instead.
    public GetConceptDto getConceptDetail(String conceptSlug) {
        return detailReader.getConceptDetail(conceptSlug);
    }
}
