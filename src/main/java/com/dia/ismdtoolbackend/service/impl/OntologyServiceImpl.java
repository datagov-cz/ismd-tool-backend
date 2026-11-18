package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.service.search.OntologyGraphNames;
import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyCreateWithConceptsResponseDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckRequestDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyIriCheckResponseDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyRef;
import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.service.snapshot.NkdLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Entry point for vocabulary operations. Owns the transaction and cache-eviction boundaries and
 * delegates each operation to its focused class.
 */
@Service
@RequiredArgsConstructor
public class OntologyServiceImpl implements OntologyService {

    private final OntologyCreateOperation createOperation;
    private final OntologyEditOperation editOperation;
    private final OntologyDeleteOperation deleteOperation;
    private final OntologyDetailReader detailReader;
    private final OntologyMetadataReader metadataReader;
    private final OntologyTurtleExport turtleExport;
    private final ConceptWriteTransaction writeTransaction;

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, OntologyGraphNames.CACHE_NAME},
            allEntries = true)
    public void deleteOntology(Long ontologyId) {
        deleteOperation.deleteOntology(ontologyId);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, OntologyGraphNames.CACHE_NAME},
            allEntries = true)
    public OntologySummaryDto createOntology(OntologyCreateModel ontologyCreateModel, String userId) {
        return createOperation.createOntology(ontologyCreateModel, userId);
    }

    /** NKD first, outside any transaction, then the create in a bounded one. */
    @Override
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, OntologyGraphNames.CACHE_NAME},
            allEntries = true)
    public OntologyCreateWithConceptsResponseDto createWithConcepts(OntologyCreateWithConceptsRequestDto request, String userId) {
        NkdLookup nkd = createOperation.prefetchNkd(request);
        return writeTransaction.run(() -> createOperation.createWithConcepts(request, userId, nkd));
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyDetailModel getOntologyDetail(String ontologySlug) {
        return detailReader.getOntologyDetail(ontologySlug);
    }

    @Override
    @Transactional(readOnly = true)
    public GetOntologyDto getOntologyDetailModel(String ontologySlug) {
        return detailReader.getOntologyDetailModel(ontologySlug);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MinimalConceptDto> getConceptsByIri(String ontologyIri, SearchSource source) {
        return detailReader.getConceptsByIri(ontologyIri, source);
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyIriCheckResponseDto checkIri(OntologyIriCheckRequestDto request) {
        return createOperation.checkIri(request);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE, OntologyGraphNames.CACHE_NAME},
            allEntries = true)
    public OntologySummaryDto editOntology(Long id, OntologyEditModel ontologyEditModel) {
        return editOperation.editOntology(id, ontologyEditModel);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologySummaryDto> getAll(String userId, Boolean isPublished) {
        return metadataReader.getAll(userId, isPublished);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologySummaryDto> getBySlugs(List<String> slugs) {
        return metadataReader.getBySlugs(slugs);
    }

    @Override
    public String getTtlContentFromOntology(OntologyRef ontology) {
        return turtleExport.getTtlContentFromOntology(ontology);
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyRef getOntologyMetadata(Long ontologyId) {
        return metadataReader.getOntologyMetadata(ontologyId);
    }

    @Override
    @Transactional(readOnly = true)
    public OntologyRef getOntologyMetadataBySlug(String ontologySlug) {
        return metadataReader.getOntologyMetadataBySlug(ontologySlug);
    }
}
