package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.exception.EmptyDataException;
import com.dia.ismdtoolbackend.exception.OntologyNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyStorageException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.mapper.ConceptMetadataMapper;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.models.OFNBaseModel;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.constants.VocabularyConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyServiceImpl implements OntologyService {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ValidationReportRepository validationReportRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final CommentRepository commentRepository;

    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final ConceptMetadataMapper conceptMetadataMapper;
    private final OntologyEditor ontologyEditor;
    private final OntologyDetailExtractor detailExtractor;

    @Override
    @Transactional
    public void deleteOntology(Long ontologyId) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyNotFoundException("Slovník s id " + ontologyId + "nebyl nalezen.");
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();

        Optional<ValidationReportEntity> validationReport =
                validationReportRepository.findByOntologyMetadataId(ontologyId);
        validationReport.ifPresent(validationReportRepository::delete);

        Model model = jenaTDB2Repository.fetchGraph(graphName);

        if (model.isEmpty()) {
            log.error("Ontology model is empty.");
            throw new OntologyStorageException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        jenaTDB2Repository.deleteGraph(graphName);
        ontologyMetadataRepository.deleteById(ontologyId);
    }

    @Override
    @Transactional
    public OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) {
        validateOntologyCreateModel(ontologyCreateModel);

        URIGenerator uriGenerator = new URIGenerator();
        String ontologyIRI = uriGenerator.generateVocabularyURIFromGivenNamespace(ontologyCreateModel.getNameModel().getName(), ontologyCreateModel.getNamespace());

        if (!UtilityMethods.isValidIRI(ontologyIRI)) {
            log.error("ontologyIRI {} not valid", ontologyCreateModel.getNameModel().getName());
            throw new OntologyValidationException("IRI slovníku " + ontologyIRI + " není platné.");
        }

        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findByGraphName(ontologyIRI);
        if (ontologyMetadataOpt.isPresent()) {
            log.error("ontologyId {} already present", ontologyIRI);
            OntologyMetadataEntity existingEntity = ontologyMetadataOpt.get();
            OntologyMetadataModel model = ontologyMetadataMapper.toDto(existingEntity);
            enrichMetadataFromRDF(model, existingEntity);
            return model;
        }

        try {
            createOFNBaseModel(ontologyIRI, ontologyCreateModel);
            log.info("Successfully saved RDF model to TDB2 with graph name: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save RDF model to TDB2", e);
            throw new OntologyStorageException("Nepodařilo se uložit RDF model: " + e.getMessage());
        }

        try {
            OntologyMetadataEntity metadataEntity = createOntologyMetadata(ontologyIRI, userId);
            log.info("Successfully created ontology with ID: {}", metadataEntity.getId());
            OntologyMetadataModel model = ontologyMetadataMapper.toDto(metadataEntity);
            enrichMetadataFromRDF(model, metadataEntity);
            return model;
        } catch (Exception e) {
            log.warn("PostgreSQL save failed, cleaning up TDB2 data for graph: {}", ontologyIRI);
            try {
                cleanupTDB2Graph(ontologyIRI);
            } catch (Exception cleanupException) {
                log.error("Failed to cleanup TDB2 graph {}: {}", ontologyIRI, cleanupException.getMessage());
            }
            throw new OntologyStorageException("Nepodařilo se uložit metadata slovníku: " + e.getMessage());
        }
    }

    @Override
    public GetOntologyDto getOntologyDetailModel(String ontologySlug) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findBySlug(ontologySlug);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologySlug {} not found", ontologySlug);
            throw new OntologyNotFoundException("Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena.");
        }

        OntologyMetadataEntity metadataEntity = ontologyMetadataOpt.get();
        String graphName = metadataEntity.getGraphName();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyStorageException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        OntologyDetailModel detailModel = detailExtractor.extractOntologyDetail(processedModel);
        OntologyMetadataModel metadataModel = ontologyMetadataMapper.toDto(metadataEntity);

        enrichMetadataFromRDF(metadataModel, metadataEntity);

        List<CommentEntity> commentEntities = commentRepository.findByOntologyIRI(graphName);
        metadataModel.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));

        List<ConceptMetadataEntity> conceptMetadataEntities = conceptMetadataRepository.findByGraphName(graphName);


        GetOntologyDto result = new GetOntologyDto();
        result.setConceptMetadataModelList(conceptMetadataEntities.stream().map(conceptMetadataMapper::toDto).toList());
        result.setOntologyMetadata(metadataModel);
        result.setOntologyDetail(detailModel);

        return result;
    }

    private void validateOntologyCreateModel(OntologyCreateModel model) {
        if (model == null) {
            throw new OntologyValidationException("Data pro vytvoření slovníku jsou prázdná");
        }
    }

    private void createOFNBaseModel(String ontologyIRI, OntologyCreateModel ontologyCreateModel) {
        OFNBaseModel ofnModel = new OFNBaseModel();

        OntModel model = ofnModel.getOntModel();
        model.createOntology(ontologyIRI);
        Resource ontologyResource = model.getResource(ontologyIRI);

        Property prefLabel = model.createProperty(SKOS_NS + "prefLabel");
        String nameLanguageTag = ontologyCreateModel.getNameModel().getLanguageTag() != null
                ? ontologyCreateModel.getNameModel().getLanguageTag()
                : DEFAULT_LANG;
        ontologyResource.addProperty(prefLabel, ontologyCreateModel.getNameModel().getName(), nameLanguageTag);
        ontologyResource.addProperty(RDF.type, model.getResource("http://www.w3.org/2002/07/owl#Ontology"));
        ontologyResource.addProperty(RDF.type, SKOS.ConceptScheme);
        ontologyResource.addProperty(RDF.type, model.getResource(SLOVNIKY_NS + SLOVNIK));

        if (ontologyCreateModel.getDescriptionModel() != null && ontologyCreateModel.getDescriptionModel().getDescription() != null && !ontologyCreateModel.getDescriptionModel().getDescription().trim().isEmpty()) {
            Property descProperty = model.createProperty("http://purl.org/dc/terms/description");
            String descLanguageTag = ontologyCreateModel.getDescriptionModel().getLanguageTag() != null
                    ? ontologyCreateModel.getDescriptionModel().getLanguageTag()
                    : DEFAULT_LANG;
            DataTypeConverter.addTypedProperty(ontologyResource, descProperty, ontologyCreateModel.getDescriptionModel().getDescription(), descLanguageTag, model);
        }

        String temporalMomentIRI = ontologyIRI + "/casovy-okamzik-vytvoreni";
        Resource temporalMoment = model.createResource(temporalMomentIRI);
        temporalMoment.addProperty(RDF.type, model.createResource(CAS_NS + CASOVY_OKAMZIK));

        Property datumACasProperty = model.createProperty(CAS_NS + DATUM_A_CAS);
        String currentDateTime = LocalDateTime.now().toString();
        temporalMoment.addProperty(datumACasProperty, currentDateTime);

        Property okamzikVytvoreniProperty = model.createProperty(SLOVNIKY_NS + OKAMZIK_VYTVORENI);
        ontologyResource.addProperty(okamzikVytvoreniProperty, temporalMoment);

        jenaTDB2Repository.saveOntologyModel(ontologyIRI, model);
    }

    private OntologyMetadataEntity createOntologyMetadata(String ontologyIRI, String userId) {
        OntologyMetadataEntity metadataEntity = new OntologyMetadataEntity();
        String slug = UtilityMethods.extractNameFromIRI(ontologyIRI);
        metadataEntity.setSlug(slug);
        metadataEntity.setGraphName(ontologyIRI);
        metadataEntity.setUserId(userId);
        metadataEntity.setIsPublished(false);

        return ontologyMetadataRepository.save(metadataEntity);
    }

    @Override
    @Transactional
    public OntologyMetadataModel editOntology(OntologyEditModel ontologyEditModel) {
        validateOntologyEditModel(ontologyEditModel);

        String oldOntologyIRI = ontologyEditModel.getOntologyIRI();
        OntologyMetadataEntity metadataEntity = fetchOntologyMetadata(oldOntologyIRI);
        Model model = fetchOntologyModel(oldOntologyIRI);

        String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);
        OntologyEditor.EditResult editResult = performOntologyEdit(ontologyEditModel, model, oldNamespace);

        log.info("Ontology edit completed: IRI changed={}", editResult.iriChanged);

        if (editResult.iriChanged) {
            metadataEntity = handleOntologyIRIChange(oldOntologyIRI, editResult.newOntologyIRI, model, metadataEntity);
        } else {
            saveOntologyModel(oldOntologyIRI, model);
        }

        OntologyMetadataModel resultModel = ontologyMetadataMapper.toDto(metadataEntity);
        enrichMetadataFromRDF(resultModel, metadataEntity);
        return resultModel;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologyMetadataModel> getAll(String userId, Boolean isPublished) {
        List<OntologyMetadataEntity> ontologyMetadataEntities;

        if (userId != null && isPublished != null) {
            ontologyMetadataEntities = ontologyMetadataRepository.findAllByUserIdAndIsPublished(userId, isPublished);
        } else if (userId != null) {
            ontologyMetadataEntities = ontologyMetadataRepository.findAllByUserId(userId);
        } else if (isPublished != null) {
            ontologyMetadataEntities = ontologyMetadataRepository.findAllByIsPublished(isPublished);
        } else {
            ontologyMetadataEntities = ontologyMetadataRepository.findAll();
        }

        return ontologyMetadataEntities.stream()
                .map(entity -> {
                    OntologyMetadataModel model = ontologyMetadataMapper.toDto(entity);
                    enrichMetadataFromRDF(model, entity);
                    List<CommentEntity> commentEntities = commentRepository.findByOntologyIRI(entity.getGraphName());
                    model.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologyMetadataModel> getBySlugs(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            throw new OntologyValidationException("Seznam slugů je prázdný");
        }

        if (slugs.size() > 6) {
            throw new OntologyValidationException("Maximální počet slugů je 6");
        }

        List<OntologyMetadataEntity> ontologyMetadataEntities = ontologyMetadataRepository.findBySlugIn(slugs);

        return ontologyMetadataEntities.stream()
                .map(entity -> {
                    OntologyMetadataModel model = ontologyMetadataMapper.toDto(entity);
                    enrichMetadataFromRDF(model, entity);
                    List<CommentEntity> commentEntities = commentRepository.findByOntologyIRI(entity.getGraphName());
                    model.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    @Override
    public String getTtlContentFromOntology(OntologyMetadataModel ontologyMetadataModel) {
        try {
            String graphName = ontologyMetadataModel.getGraphName();

            if (graphName == null || graphName.isEmpty()) {
                log.error("Graph name is null or empty for ontology: {}", ontologyMetadataModel.getSlug());
                throw new OntologyValidationException("Graph name is missing for the ontology");
            }

            log.info("Fetching TTL content for graph: {}", graphName);

            Model model = jenaTDB2Repository.fetchGraph(graphName);

            if (model == null || model.isEmpty()) {
                log.warn("Model is empty or null for graph: {}", graphName);
                throw new OntologyNotFoundException("Ontology model not found or is empty");
            }

            java.io.StringWriter writer = new java.io.StringWriter();
            model.write(writer, "TTL");
            String ttlContent = writer.toString();

            log.info("Successfully retrieved TTL content for graph: {} ({} statements)",
                    graphName, model.size());

            return ttlContent;

        } catch (OntologyValidationException | OntologyNotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error retrieving TTL content from ontology: {}", e.getMessage(), e);
            throw new OntologyStorageException("Failed to retrieve TTL content: " + e.getMessage());
        }
    }

    private String extractNameFromGraphName(String graphName) {
        if (graphName == null || graphName.isEmpty()) {
            return graphName;
        }

        String result = graphName.replace("-", " ");

        result = result.substring(0, 1).toUpperCase() + result.substring(1);

        return result;
    }

    private OntologyMetadataEntity fetchOntologyMetadata(String ontologyIRI) {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findByGraphName(ontologyIRI);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("Ontology with IRI {} not found", ontologyIRI);
            throw new OntologyNotFoundException("Slovník s IRI " + ontologyIRI + " nebyl nalezen.");
        }
        return ontologyMetadataOpt.get();
    }

    private Model fetchOntologyModel(String ontologyIRI) {
        Model model = jenaTDB2Repository.fetchGraph(ontologyIRI);
        if (model.isEmpty()) {
            log.error("Ontology model is empty for IRI: {}", ontologyIRI);
            throw new OntologyNotFoundException("Model slovníku je prázdný nebo nebyl nalezen.");
        }
        return model;
    }

    private OntologyEditor.EditResult performOntologyEdit(OntologyEditModel editModel, Model model, String oldNamespace) {
        return ontologyEditor.editOntology(editModel, model, oldNamespace);
    }

    private OntologyMetadataEntity handleOntologyIRIChange(String oldOntologyIRI, String newOntologyIRI,
                                                           Model model, OntologyMetadataEntity metadataEntity)
    {
        try {
            String oldNamespace = UtilityMethods.ensureNamespaceEndsWithDelimiter(oldOntologyIRI);

            saveOntologyModel(newOntologyIRI, model);
            log.info("Saved ontology to new graph: {}", newOntologyIRI);

            updateConceptMetadataIRIs(oldOntologyIRI, newOntologyIRI, oldNamespace);

            deleteOntologyGraph(oldOntologyIRI);
            log.info("Deleted old graph: {}", oldOntologyIRI);

            return updateOntologyMetadata(metadataEntity, newOntologyIRI);
        } catch (Exception e) {
            log.error("Failed to update ontology with new IRI: {}", e.getMessage());
            throw new OntologyStorageException("Nepodařilo se uložit změny slovníku: " + e.getMessage());
        }
    }

    private void updateConceptMetadataIRIs(String oldGraphName, String newGraphName,
                                           String oldNamespace) {
        List<ConceptMetadataEntity> concepts = conceptMetadataRepository.findByGraphName(oldGraphName);

        if (concepts.isEmpty()) {
            log.info("No concepts found for ontology {}, skipping concept metadata updates", oldGraphName);
            return;
        }

        log.info("Updating {} concept metadata entries for ontology namespace change", concepts.size());

        URIGenerator uriGenerator = new URIGenerator();
        uriGenerator.setEffectiveNamespace(newGraphName);

        int updatedCount = 0;
        for (ConceptMetadataEntity concept : concepts) {
            String oldConceptIRI = concept.getConceptIri();
            String conceptName = concept.getConceptName();

            if (oldConceptIRI != null && conceptName != null && oldConceptIRI.startsWith(oldNamespace)) {
                String newConceptIRI = uriGenerator.generateConceptURI(conceptName, null);

                concept.setConceptIri(newConceptIRI);
                concept.setGraphName(newGraphName);

                log.debug("Updated concept metadata: {} -> {}", oldConceptIRI, newConceptIRI);
                updatedCount++;
            }
        }

        conceptMetadataRepository.saveAll(concepts);
        log.info("Successfully updated {} concept metadata entries", updatedCount);
    }

    private void saveOntologyModel(String ontologyIRI, Model model) {
        try {
            jenaTDB2Repository.saveOntologyModel(ontologyIRI, model);
            log.info("Saved/updated ontology model for graph: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save ontology model: {}", e.getMessage());
            throw new OntologyStorageException("Nepodařilo se uložit změny slovníku: " + e.getMessage());
        }
    }

    private void deleteOntologyGraph(String ontologyIRI) {
        jenaTDB2Repository.deleteGraph(ontologyIRI);
    }

    private OntologyMetadataEntity updateOntologyMetadata(OntologyMetadataEntity metadataEntity, String newGraphName) {
        metadataEntity.setGraphName(newGraphName);
        metadataEntity.setSlug(UtilityMethods.extractNameFromIRI(newGraphName));
        OntologyMetadataEntity updatedEntity = ontologyMetadataRepository.save(metadataEntity);
        log.info("Updated metadata with new graph name: {} and slug: {}", newGraphName, metadataEntity.getSlug());
        return updatedEntity;
    }

    private void validateOntologyEditModel(OntologyEditModel model) {
        if (model == null) {
            throw new EmptyDataException("Data pro úpravu slovníku jsou prázdná");
        }
        if (model.getOntologyIRI() == null || model.getOntologyIRI().trim().isEmpty()) {
            throw new OntologyValidationException("IRI slovníku je povinné");
        }
    }

    private void cleanupTDB2Graph(String graphName) {
        jenaTDB2Repository.deleteGraph(graphName);
    }

    private void enrichMetadataFromRDF(OntologyMetadataModel model, OntologyMetadataEntity entity) {
        try {
            String graphName = entity.getGraphName();
            if (graphName == null || graphName.isEmpty()) {
                log.warn("Cannot enrich metadata: graphName is null or empty");
                return;
            }

            Model rdfModel = jenaTDB2Repository.fetchGraph(graphName);
            if (rdfModel == null || rdfModel.isEmpty()) {
                log.warn("Cannot enrich metadata: RDF model is empty for graph {}", graphName);
                model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName)));
                return;
            }

            Resource ontologyResource = rdfModel.getResource(graphName);
            if (ontologyResource == null) {
                log.warn("Cannot find ontology resource for IRI: {}", graphName);
                model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName)));
                return;
            }

            Statement prefLabelStmt = ontologyResource.getProperty(SKOS.prefLabel);
            if (prefLabelStmt != null) {
                RDFNode prefLabelNode = prefLabelStmt.getObject();
                if (prefLabelNode.isLiteral()) {
                    Literal prefLabelLiteral = prefLabelNode.asLiteral();
                    String prefLabel = prefLabelLiteral.getString();
                    if (prefLabel != null && !prefLabel.isEmpty()) {
                        model.setName(prefLabel);
                        log.debug("Enriched name from skos:prefLabel: {}", prefLabel);
                    } else {
                        model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName)));
                    }
                } else {
                    model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName)));
                }
            } else {
                model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(graphName)));
            }

            Statement descriptionStmt = ontologyResource.getProperty(DCTerms.description);
            if (descriptionStmt != null) {
                RDFNode descriptionNode = descriptionStmt.getObject();
                if (descriptionNode.isLiteral()) {
                    Literal descriptionLiteral = descriptionNode.asLiteral();
                    String description = descriptionLiteral.getString();
                    if (description != null && !description.isEmpty()) {
                        model.setPopis(description);
                        log.debug("Enriched popis from dcterms:description: {}", description);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to enrich metadata from RDF for graph {}: {}", entity.getGraphName(), e.getMessage());
            if ((model.getName() == null || model.getName().isEmpty()) && entity.getGraphName() != null) {
                model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(entity.getGraphName())));
            }
        }
    }
}
