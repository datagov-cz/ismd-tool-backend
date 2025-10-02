package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConceptServiceImpl implements ConceptService {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final ConceptCreator conceptCreator;
    private final JenaTDB2Repository jenaTDB2Repository;

    @Override
    @Transactional
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        log.info("Creating concept: type={}, name={}, namespace={}, userId={}",
                createModel.getConceptType(), createModel.getConceptName(),
                createModel.getNamespace(), userId);

        validateInput(createModel, userId);

        Resource conceptResource;
        try {
            conceptResource = conceptCreator.createSingleConcept(createModel);
            log.debug("Successfully transformed concept to Jena Resource: {}", conceptResource.getURI());
        } catch (Exception e) {
            log.error("Failed to transform concept to Jena Resource", e);
            throw new OntologyException("Nepodařilo se transformovat pojem: " + e.getMessage());
        }

        String conceptUri = conceptResource.getURI();

        if (jenaTDB2Repository.conceptExists(conceptUri)) {
            log.error("Concept already exists in TDB2: {}", conceptUri);
            throw new OntologyException("Pojem s daným IRI již existuje: " + conceptUri);
        }

        String existingGraph = jenaTDB2Repository.findGraphContainingConcept(conceptUri);
        if (existingGraph != null) {
            log.error("Concept already exists in ontology graph: {} in graph: {}", conceptUri, existingGraph);
            throw new OntologyException(
                    "Pojem s daným IRI již existuje ve slovníku.\n" +
                            "IRI pojmu: " + conceptUri + "\n" +
                            "Slovník: " + existingGraph
            );
        }

        ConceptMetadataEntity savedEntity;
        try {
            savedEntity = saveMetadata(createModel, userId, conceptUri);
            log.info("Metadata saved successfully with ID: {}", savedEntity.getId());
        } catch (Exception e) {
            log.error("Failed to save concept metadata", e);
            throw new OntologyException("Nepodařilo se uložit metadata pojmu: " + e.getMessage());
        }

        try {
            String conceptIRI = jenaTDB2Repository.saveConcept(conceptResource);
            log.info("Concept saved to TDB2 successfully: {}", conceptIRI);
        } catch (Exception e) {
            log.error("Failed to save concept to TDB2, rolling back metadata", e);

            try {
                rollbackMetadata(savedEntity.getId());
                log.info("Successfully rolled back metadata for failed TDB2 save");
            } catch (Exception rollbackException) {
                log.error("CRITICAL: Failed to rollback metadata after TDB2 failure. " +
                        "Manual cleanup required for metadata ID: {}", savedEntity.getId(), rollbackException);
            }

            throw new OntologyException("Nepodařilo se uložit pojem do TDB2: " + e.getMessage());
        }

        ConceptMetadataModel result = conceptMetadataMapper.toDto(savedEntity);
        log.info("Concept creation completed successfully: {}", conceptUri);

        return result;
    }

    protected ConceptMetadataEntity saveMetadata(ConceptCreateModel createModel,
                                                 String userId,
                                                 String conceptUri) {
        ConceptMetadataEntity entity = createMetadataEntity(createModel, userId);
        ConceptMetadataEntity savedEntity = conceptMetadataRepository.save(entity);

        log.debug("Saved concept metadata: id={}, name={}, type={}, uri={}",
                savedEntity.getId(), savedEntity.getConceptName(),
                savedEntity.getConceptType(), conceptUri);

        return savedEntity;
    }

    protected void rollbackMetadata(Long metadataId) {
        conceptMetadataRepository.deleteById(metadataId);
        log.info("Rolled back metadata with ID: {}", metadataId);
    }

    private void validateInput(ConceptCreateModel createModel, String userId) {
        if (createModel == null) {
            throw new OntologyException("Data pro vytvoření pojmu jsou prázdná");
        }

        if (userId == null || userId.trim().isEmpty()) {
            throw new OntologyException("ID uživatele je povinné");
        }

        try {
            createModel.validate();
        } catch (OntologyException e) {
            log.error("Validation failed for concept: {}", e.getMessage());
            throw e;
        }
    }

    private ConceptMetadataEntity createMetadataEntity(ConceptCreateModel createModel,
                                                       String userId) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptName(createModel.getConceptName());
        entity.setConceptType(createModel.getConceptTypeEnum());
        entity.setUserId(userId);
        entity.setIsPublished(false);
        entity.setInTezaurus(createModel.getInTezaurus());

        return entity;
    }
}
