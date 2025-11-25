package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetConceptDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.ConceptEditModel;
import com.dia.ismdtoolbackend.models.concept.ConceptMetadataModel;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.ConceptService;
import com.dia.ismdtoolbackend.utility.creator.ConceptCreator;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.ConceptEditor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConceptServiceImpl implements ConceptService {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final ConceptCreator conceptCreator;
    private final ConceptEditor conceptEditor;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyDetailExtractor detailExtractor;
    private final CommentRepository commentRepository;

    @Override
    @Transactional
    public ConceptMetadataModel createConcept(ConceptCreateModel createModel, String userId) {
        log.info("Creating concept: type={}, name={}, namespace={}, userId={}",
                createModel.getConceptType(), createModel.getNameModel(),
                createModel.getNamespace(), userId);

        validateInput(createModel, userId);

        Resource conceptResource = createConceptResource(createModel);
        String conceptUri = conceptResource.getURI();

        ConceptMetadataModel existingConcept = checkForExistingConcept(conceptUri);
        if (existingConcept != null) {
            return existingConcept;
        }

        String ontologyGraphName = createModel.getOntologyGraphName();
        saveConceptToTDB2(conceptResource, ontologyGraphName);

        return saveMetadataWithRollback(createModel, userId, conceptUri, ontologyGraphName);
    }

    @Override
    @Transactional
    public void deleteConcept(Long conceptId) {
        Optional<ConceptMetadataEntity> conceptMetadataOpt = conceptMetadataRepository.findById(conceptId);
        if (conceptMetadataOpt.isEmpty()) {
            log.error("conceptId {} not found", conceptId);
            throw new OntologyException("Metadata pojmu s id " + conceptId + "nebyla nalezena.");
        }

        String graphName = conceptMetadataOpt.get().getGraphName();
        String conceptUri = conceptMetadataOpt.get().getConceptIri();

        Model model = jenaTDB2Repository.fetchGraph(graphName);

        if (model.isEmpty()) {
            log.error("Ontology model is empty.");
            throw new OntologyException("Slovník, ve kterém se pojem nachází, je prázdný, nebo nebyl nalezen.");
        }

        Resource conceptResource = model.getResource(conceptUri);
        if (conceptResource == null || !model.containsResource(conceptResource)) {
            log.error("Concept resource {} not found in graph {}", conceptUri, graphName);
            throw new OntologyException("Pojem s IRI " + conceptUri + " nebyl nalezen.");
        }

        List<String> relatedConceptUris = findRelatedConcepts(model, conceptUri);
        relatedConceptUris.add(conceptUri);
        List<ConceptMetadataEntity> relatedConceptEntities = findRelatedConceptEntities(relatedConceptUris);

        jenaTDB2Repository.deleteConceptsFromGraph(relatedConceptUris, graphName);
        conceptMetadataRepository.deleteAll(relatedConceptEntities);
    }

    @Override
    @Transactional
    public ConceptMetadataModel editConcept(ConceptEditModel conceptEditModel) {
        log.info("Editing concept: IRI={}, type={}",
                conceptEditModel.getConceptIRI(), conceptEditModel.getConceptType());
        String conceptIRI = conceptEditModel.getConceptIRI();

        ConceptMetadataEntity metadata = fetchAndValidateMetadata(conceptIRI);
        String graphName = metadata.getGraphName();

        Model model = fetchAndValidateGraph(graphName);
        validateConceptInGraph(conceptIRI, graphName, model);

        ConceptEditor.EditResult editResult = performConceptEdit(conceptEditModel, model, graphName);
        saveUpdatedModelToTDB2(graphName, model);
        updateMetadataFromEditResult(metadata, conceptEditModel, editResult);

        return saveAndReturnMetadata(metadata, editResult.newConceptIRI);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConceptMetadataModel> getAll(String userId, Boolean isPublished) {
        List<ConceptMetadataEntity> conceptMetadataEntities;

        if (userId != null && isPublished != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByUserIdAndIsPublished(userId, isPublished);
        } else if (userId != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByUserId(userId);
        } else if (isPublished != null) {
            conceptMetadataEntities = conceptMetadataRepository.findAllByIsPublished(isPublished);
        } else {
            conceptMetadataEntities = conceptMetadataRepository.findAll();
        }

        return conceptMetadataEntities.stream()
                .map(entity -> {
                    ConceptMetadataModel model = conceptMetadataMapper.toDto(entity);
                    List<CommentEntity> commentEntities = commentRepository.findByConceptIRI(entity.getConceptIri());
                    model.setComments(conceptMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    @Override
    public GetConceptDto getConceptDetail(String conceptSlug) throws OntologyException {
        Optional<ConceptMetadataEntity> conceptMetadataOpt = conceptMetadataRepository.findBySlug(conceptSlug);
        if (conceptMetadataOpt.isEmpty()) {
            log.error("conceptSlug {} not found", conceptSlug);
            throw new OntologyException("Metadata pojmu s názvem " + conceptSlug + " nebyla nalezena.");
        }

        ConceptMetadataEntity metadataEntity = conceptMetadataOpt.get();
        String graphName = metadataEntity.getGraphName();
        String conceptIri = metadataEntity.getConceptIri();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        OntologyDetailModel.ConceptDetailModel conceptDetail = detailExtractor.extractConceptDetail(processedModel, conceptIri);

        if (conceptDetail == null) {
            log.error("Concept detail not found for IRI: {}", conceptIri);
            throw new OntologyException("Detail pojmu s IRI " + conceptIri + " nebyl nalezen.");
        }

        ConceptMetadataModel metadataModel = conceptMetadataMapper.toDto(metadataEntity);

        List<CommentEntity> commentEntities = commentRepository.findByConceptIRI(conceptIri);
        metadataModel.setComments(conceptMetadataMapper.commentEntitiesToModels(commentEntities));

        GetConceptDto result = new GetConceptDto();
        result.setConceptMetadata(metadataModel);
        result.setConceptDetail(conceptDetail);

        return result;
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

    private List<String> findRelatedConcepts(Model model, String conceptUri) {
        List<String> relatedConcepts = new ArrayList<>();
        Resource domainResource = model.getResource(conceptUri);

        ResIterator iterator = model.listSubjectsWithProperty(RDFS.domain, domainResource);
        while (iterator.hasNext()) {
            Resource property = iterator.nextResource();
            relatedConcepts.add(property.getURI());
        }

        iterator = model.listSubjectsWithProperty(RDFS.range, domainResource);
        while (iterator.hasNext()) {
            Resource property = iterator.nextResource();
            relatedConcepts.add(property.getURI());
        }

        return relatedConcepts;
    }

    private List<ConceptMetadataEntity> findRelatedConceptEntities(List<String> conceptUris) {
        List<ConceptMetadataEntity> relatedConcepts = new ArrayList<>();
        for (String conceptUri : conceptUris) {
            conceptMetadataRepository.findByConceptIri(conceptUri).ifPresent(relatedConcepts::add);
        }
        return relatedConcepts;
    }

    private void validateInput(ConceptCreateModel createModel, String userId) {
        if (createModel == null) {
            throw new OntologyException("Data pro vytvoření pojmu jsou prázdná");
        }

        if (userId == null || userId.trim().isEmpty()) {
            throw new OntologyException("ID uživatele je povinné");
        }
    }

    private ConceptMetadataEntity createMetadataEntity(ConceptCreateModel createModel,
                                                       String userId,
                                                       String conceptIri) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setSlug(com.dia.utility.UtilityMethods.extractNameFromIRI(conceptIri));
        entity.setConceptName(createModel.getNameModel().getName());
        entity.setConceptType(createModel.getConceptTypeEnum());
        entity.setConceptIri(conceptIri);
        entity.setGraphName(createModel.getOntologyGraphName());
        entity.setUserId(userId);
        entity.setIsPublished(false);
        entity.setInTezaurus(createModel.getInTezaurus());

        return entity;
    }

    private ConceptMetadataEntity fetchAndValidateMetadata(String conceptIRI) {
        Optional<ConceptMetadataEntity> metadataOpt = conceptMetadataRepository.findByConceptIri(conceptIRI);
        if (metadataOpt.isEmpty()) {
            log.error("Concept metadata not found for IRI: {}", conceptIRI);
            throw new OntologyException("Metadata pojmu s IRI " + conceptIRI + " nebyla nalezena.");
        }
        return metadataOpt.get();
    }

    private Model fetchAndValidateGraph(String graphName) {
        Model model = jenaTDB2Repository.fetchGraph(graphName);
        if (model.isEmpty()) {
            log.error("Graph {} is empty or not found", graphName);
            throw new OntologyException("Slovník " + graphName + " je prázdný nebo nebyl nalezen.");
        }
        return model;
    }

    private void validateConceptInGraph(String conceptIRI, String graphName, Model model) {
        Resource conceptResource = model.getResource(conceptIRI);
        if (conceptResource == null || !model.containsResource(conceptResource)) {
            log.error("Concept {} not found in graph {}", conceptIRI, graphName);
            throw new OntologyException("Pojem s IRI " + conceptIRI + " nebyl nalezen ve slovníku.");
        }
    }

    private ConceptEditor.EditResult performConceptEdit(ConceptEditModel conceptEditModel, Model model, String graphName) {
        try {
            ConceptEditor.EditResult editResult = conceptEditor.editConcept(conceptEditModel, model, graphName);
            log.info("Edit completed: {} changes, IRI changed: {}, new IRI: {}",
                    editResult.changesCount, editResult.iriChanged, editResult.newConceptIRI);
            return editResult;
        } catch (Exception e) {
            log.error("Failed to edit concept", e);
            throw new OntologyException("Nepodařilo se upravit pojem: " + e.getMessage());
        }
    }

    private void saveUpdatedModelToTDB2(String graphName, Model model) {
        try {
            jenaTDB2Repository.putOntologyModel(graphName, model);
            log.info("Updated model saved to TDB2 graph: {}", graphName);
        } catch (Exception e) {
            log.error("Failed to save updated model to TDB2", e);
            throw new OntologyException("Nepodařilo se uložit upravený pojem do TDB2: " + e.getMessage());
        }
    }

    private void updateMetadataFromEditResult(ConceptMetadataEntity metadata, ConceptEditModel conceptEditModel, ConceptEditor.EditResult editResult) {
        if (editResult.iriChanged) {
            metadata.setConceptIri(editResult.newConceptIRI);
            metadata.setSlug(com.dia.utility.UtilityMethods.extractNameFromIRI(editResult.newConceptIRI));
        }

        if (conceptEditModel.getNameModel() != null && conceptEditModel.getNameModel().getName() != null) {
            metadata.setConceptName(conceptEditModel.getNameModel().getName());
        }

        if (conceptEditModel.getInTezaurus() != null) {
            metadata.setInTezaurus(conceptEditModel.getInTezaurus());
        }
    }

    private ConceptMetadataModel saveAndReturnMetadata(ConceptMetadataEntity metadata, String conceptIRI) {
        try {
            ConceptMetadataEntity savedMetadata = conceptMetadataRepository.save(metadata);
            log.info("Metadata updated successfully for concept: {}", conceptIRI);
            return conceptMetadataMapper.toDto(savedMetadata);
        } catch (Exception e) {
            log.error("Failed to update concept metadata", e);
            throw new OntologyException("Nepodařilo se aktualizovat metadata pojmu: " + e.getMessage());
        }
    }

    private Resource createConceptResource(ConceptCreateModel createModel) {
        try {
            Resource conceptResource = conceptCreator.createSingleConcept(createModel);
            log.debug("Successfully transformed concept to Jena Resource: {}", conceptResource.getURI());
            return conceptResource;
        } catch (Exception e) {
            log.error("Failed to transform concept to Jena Resource", e);
            throw new OntologyException("Nepodařilo se transformovat pojem: " + e.getMessage());
        }
    }

    private ConceptMetadataModel checkForExistingConcept(String conceptUri) {
        Optional<ConceptMetadataEntity> existingConcept = conceptMetadataRepository.findByConceptIri(conceptUri);
        if (existingConcept.isPresent()) {
            log.error("Concept already exists with IRI: {}", conceptUri);
            return conceptMetadataMapper.toDto(existingConcept.get());
        }
        return null;
    }

    private void saveConceptToTDB2(Resource conceptResource, String ontologyGraphName) {
        try {
            String conceptIRI = jenaTDB2Repository.saveConcept(conceptResource, ontologyGraphName);
            log.info("Concept saved to TDB2 graph {} successfully: {}", ontologyGraphName, conceptIRI);
        } catch (Exception e) {
            log.error("Failed to save concept to TDB2 graph {}", ontologyGraphName, e);
            throw new OntologyException("Nepodařilo se uložit pojem do TDB2: " + e.getMessage());
        }
    }

    private ConceptMetadataModel saveMetadataWithRollback(ConceptCreateModel createModel, String userId,
                                                          String conceptUri, String ontologyGraphName) {
        try {
            ConceptMetadataEntity savedEntity = saveMetadata(createModel, userId, conceptUri);
            log.info("Metadata saved successfully with ID: {}", savedEntity.getId());

            ConceptMetadataModel result = conceptMetadataMapper.toDto(savedEntity);
            log.info("Concept creation completed successfully: {}", conceptUri);
            return result;
        } catch (Exception e) {
            log.error("Failed to save concept metadata, rolling back TDB2 data", e);
            rollbackTDB2Data(conceptUri, ontologyGraphName);
            throw new OntologyException("Nepodařilo se uložit metadata pojmu: " + e.getMessage());
        }
    }

    private void rollbackTDB2Data(String conceptUri, String ontologyGraphName) {
        try {
            jenaTDB2Repository.deleteConceptFromGraph(conceptUri, ontologyGraphName);
            log.info("Successfully rolled back TDB2 data from graph {} for failed metadata save", ontologyGraphName);
        } catch (Exception rollbackException) {
            log.error("CRITICAL: Failed to rollback TDB2 data from graph {} after metadata failure. " +
                    "Manual cleanup required for concept IRI: {}", ontologyGraphName, conceptUri, rollbackException);
        }
    }
}
