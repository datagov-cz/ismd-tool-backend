package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.ConceptSummaryDto;
import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.service.snapshot.NkdLookup;
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
    private final ConceptEditTransaction editTransaction;

    /** NKD first, outside any transaction, then the create in a bounded one, as for {@link #editConcept}. */
    @Override
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        NkdLookup nkd = createOperation.prefetchNkd(createModel);
        return editTransaction.run(() -> createOperation.createConcept(createModel, userId, nkd));
    }

    /** All or nothing: one transaction for the batch, NKD asked for each concept before it. */
    @Override
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public List<ConceptMetadataModel> createConcepts(List<? extends ConceptCreateModel> createModels, String userId) {
        List<NkdLookup> lookups = createModels.stream().map(createOperation::prefetchNkd).toList();
        return editTransaction.run(() -> java.util.stream.IntStream.range(0, createModels.size())
                .mapToObj(i -> createOperation.createConcept(createModels.get(i), userId, lookups.get(i)))
                .toList());
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public void deleteConcept(Long conceptId) {
        deleteOperation.deleteConcept(conceptId);
    }

    /**
     * NKD first, outside any transaction, then the edit in a bounded one ({@link ConceptEditTransaction}), so
     * NKD latency never holds the row lock or a connection.
     */
    @Override
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public ConceptMetadataModel editConcept(Long conceptId, ConceptEditModel conceptEditModel) {
        NkdLookup nkd = editOperation.prefetchNkd(conceptId, conceptEditModel);
        return editTransaction.run(() -> editOperation.editConcept(conceptId, conceptEditModel, nkd));
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, allEntries = true)
    public String syncWorkingCopy(Long conceptId, List<String> fieldsToAccept) {
        return syncOperation.syncWorkingCopy(conceptId, fieldsToAccept);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConceptSummaryDto> getAll(String userId, Boolean isPublished) {
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
