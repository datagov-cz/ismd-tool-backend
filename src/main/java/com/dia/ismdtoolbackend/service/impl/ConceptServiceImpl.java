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

import java.util.Optional;

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

        Optional<ConceptMetadataEntity> existingConcept = conceptMetadataRepository.findByConceptIri(conceptUri);
        if (existingConcept.isPresent()) {
            log.error("Concept already exists with IRI: {}", conceptUri);
            return conceptMetadataMapper.toDto(existingConcept.get());
        }

        String ontologyGraphName = createModel.getOntologyGraphName();
        try {
            String conceptIRI = jenaTDB2Repository.saveConcept(conceptResource, ontologyGraphName);
            log.info("Concept saved to TDB2 graph {} successfully: {}", ontologyGraphName, conceptIRI);
        } catch (Exception e) {
            log.error("Failed to save concept to TDB2 graph {}", ontologyGraphName, e);
            throw new OntologyException("Nepodařilo se uložit pojem do TDB2: " + e.getMessage());
        }

        try {
            ConceptMetadataEntity savedEntity = saveMetadata(createModel, userId, conceptUri);
            log.info("Metadata saved successfully with ID: {}", savedEntity.getId());

            ConceptMetadataModel result = conceptMetadataMapper.toDto(savedEntity);
            log.info("Concept creation completed successfully: {}", conceptUri);
            return result;
        } catch (Exception e) {
            log.error("Failed to save concept metadata, rolling back TDB2 data", e);

            try {
                jenaTDB2Repository.deleteConceptFromGraph(conceptUri, ontologyGraphName);
                log.info("Successfully rolled back TDB2 data from graph {} for failed metadata save", ontologyGraphName);
            } catch (Exception rollbackException) {
                log.error("CRITICAL: Failed to rollback TDB2 data from graph {} after metadata failure. " +
                        "Manual cleanup required for concept IRI: {}", ontologyGraphName, conceptUri, rollbackException);
            }

            throw new OntologyException("Nepodařilo se uložit metadata pojmu: " + e.getMessage());
        }
    }

    protected ConceptMetadataEntity saveMetadata(ConceptCreateModel createModel,
                                                 String userId,
                                                 String conceptUri) {
        ConceptMetadataEntity entity = createMetadataEntity(createModel, userId, conceptUri);
        ConceptMetadataEntity savedEntity = conceptMetadataRepository.save(entity);

        log.debug("Saved concept metadata: id={}, name={}, type={}, iri={}",
                savedEntity.getId(), savedEntity.getConceptName(),
                savedEntity.getConceptType(), savedEntity.getConceptIri());

        return savedEntity;
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
                                                       String userId,
                                                       String conceptIri) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptName(createModel.getConceptName());
        entity.setConceptType(createModel.getConceptTypeEnum());
        entity.setConceptIri(conceptIri);
        entity.setGraphName(createModel.getOntologyGraphName());
        entity.setUserId(userId);
        entity.setIsPublished(false);
        entity.setInTezaurus(createModel.getInTezaurus());

        return entity;
    }
}
