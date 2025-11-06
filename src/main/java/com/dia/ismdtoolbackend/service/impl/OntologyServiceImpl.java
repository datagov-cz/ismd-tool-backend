package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.GetOntologyDto;
import com.dia.ismdtoolbackend.entity.CommentEntity;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.CommentRepository;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import com.dia.models.OFNBaseModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
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
    private final OntologyEditor ontologyEditor;
    private final OntologyDetailExtractor detailExtractor;

    @Override
    @Transactional
    public void deleteOntology(Long ontologyId) throws OntologyException {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyException("Slovník s id " + ontologyId + "nebyl nalezen.");
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();

        Optional<ValidationReportEntity> validationReport =
                validationReportRepository.findByOntologyMetadataId(ontologyId);
        validationReport.ifPresent(validationReportRepository::delete);

        Model model = jenaTDB2Repository.fetchGraph(graphName);

        if (model.isEmpty()) {
            log.error("Ontology model is empty.");
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        jenaTDB2Repository.deleteGraph(graphName);
        ontologyMetadataRepository.deleteById(ontologyId);
    }

    @Override
    @Transactional
    public OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) throws OntologyException {
        validateOntologyCreateModel(ontologyCreateModel);

        URIGenerator uriGenerator = new URIGenerator();
        String ontologyIRI = uriGenerator.generateVocabularyURIFromGivenNamespace(ontologyCreateModel.getNameModel().getName(), ontologyCreateModel.getNamespace());

        if (!UtilityMethods.isValidIRI(ontologyIRI)) {
            log.error("ontologyIRI {} not valid", ontologyCreateModel.getNameModel().getName());
            throw new OntologyException("IRI slovníku " + ontologyIRI + " není platné.");
        }

        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findByGraphName(ontologyIRI);
        if (ontologyMetadataOpt.isPresent()) {
            log.error("ontologyId {} already present", ontologyIRI);
            return ontologyMetadataMapper.toDto(ontologyMetadataOpt.get());
        }

        try {
            createOFNBaseModel(ontologyIRI, ontologyCreateModel);
            log.info("Successfully saved RDF model to TDB2 with graph name: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save RDF model to TDB2", e);
            throw new OntologyException("Nepodařilo se uložit RDF model: " + e.getMessage());
        }

        try {
            String popis = null;
            if (ontologyCreateModel.getDescriptionModel() != null
                && ontologyCreateModel.getDescriptionModel().getDescription() != null
                && !ontologyCreateModel.getDescriptionModel().getDescription().trim().isEmpty()) {
                popis = ontologyCreateModel.getDescriptionModel().getDescription();
            }
            OntologyMetadataEntity metadataEntity = createOntologyMetadata(ontologyIRI, userId, popis);
            log.info("Successfully created ontology with ID: {}", metadataEntity.getId());
            return ontologyMetadataMapper.toDto(metadataEntity);
        } catch (Exception e) {
            log.warn("PostgreSQL save failed, cleaning up TDB2 data for graph: {}", ontologyIRI);
            try {
                cleanupTDB2Graph(ontologyIRI);
            } catch (Exception cleanupException) {
                log.error("Failed to cleanup TDB2 graph {}: {}", ontologyIRI, cleanupException.getMessage());
            }
            throw new OntologyException("Nepodařilo se uložit metadata slovníku: " + e.getMessage());
        }
    }

    @Override
    public GetOntologyDto getOntologyDetailModel(String ontologySlug) throws OntologyException {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findBySlug(ontologySlug);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologySlug {} not found", ontologySlug);
            throw new OntologyException("Metadata slovníku s názvem " + ontologySlug + " nebyla nalezena.");
        }

        OntologyMetadataEntity metadataEntity = ontologyMetadataOpt.get();
        String graphName = metadataEntity.getGraphName();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        OntologyDetailModel detailModel = detailExtractor.extractOntologyDetail(processedModel);
        OntologyMetadataModel metadataModel = ontologyMetadataMapper.toDto(metadataEntity);

        List<CommentEntity> commentEntities = commentRepository.findByOntologyIRI(graphName);
        metadataModel.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));

        GetOntologyDto result = new GetOntologyDto();
        result.setOntologyMetadata(metadataModel);
        result.setOntologyDetail(detailModel);

        return result;
    }

    private void validateOntologyCreateModel(OntologyCreateModel model) throws OntologyException {
        if (model == null) {
            throw new OntologyException("Data pro vytvoření slovníku jsou prázdná");
        }
    }

    private void createOFNBaseModel(String ontologyIRI, OntologyCreateModel ontologyCreateModel) throws OntologyException {
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

    private OntologyMetadataEntity createOntologyMetadata(String ontologyIRI, String userId, String popis) throws OntologyException {
        OntologyMetadataEntity metadataEntity = new OntologyMetadataEntity();
        String slug = UtilityMethods.extractNameFromIRI(ontologyIRI);
        metadataEntity.setSlug(slug);
        metadataEntity.setGraphName(ontologyIRI);
        metadataEntity.setUserId(userId);
        metadataEntity.setIsPublished(false);
        metadataEntity.setPopis(popis);

        String name = extractNameFromGraphName(slug);
        metadataEntity.setName(name);

        return ontologyMetadataRepository.save(metadataEntity);
    }

    @Override
    @Transactional
    public OntologyMetadataModel editOntology(OntologyEditModel ontologyEditModel) throws OntologyException {
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

        return ontologyMetadataMapper.toDto(metadataEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologyMetadataModel> getAll(String userId, Boolean isPublished) throws OntologyException {
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
                    if (model.getName() == null || model.getName().isEmpty()) {
                        model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(entity.getGraphName())));
                    }
                    List<CommentEntity> commentEntities = commentRepository.findByOntologyIRI(entity.getGraphName());
                    model.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OntologyMetadataModel> getBySlugs(List<String> slugs) throws OntologyException {
        if (slugs == null || slugs.isEmpty()) {
            throw new OntologyException("Seznam slugů je prázdný");
        }

        if (slugs.size() > 6) {
            throw new OntologyException("Maximální počet slugů je 6");
        }

        List<OntologyMetadataEntity> ontologyMetadataEntities = ontologyMetadataRepository.findBySlugIn(slugs);

        return ontologyMetadataEntities.stream()
                .map(entity -> {
                    OntologyMetadataModel model = ontologyMetadataMapper.toDto(entity);
                    if (model.getName() == null || model.getName().isEmpty()) {
                        model.setName(extractNameFromGraphName(UtilityMethods.extractNameFromIRI(entity.getGraphName())));
                    }
                    List<CommentEntity> commentEntities = commentRepository.findByOntologyIRI(entity.getGraphName());
                    model.setComments(ontologyMetadataMapper.commentEntitiesToModels(commentEntities));
                    return model;
                })
                .toList();
    }

    private String extractNameFromGraphName(String graphName) {
        if (graphName == null || graphName.isEmpty()) {
            return graphName;
        }

        String result = graphName.replace("-", " ");

        result = result.substring(0, 1).toUpperCase() + result.substring(1);

        return result;
    }

    private OntologyMetadataEntity fetchOntologyMetadata(String ontologyIRI) throws OntologyException {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findByGraphName(ontologyIRI);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("Ontology with IRI {} not found", ontologyIRI);
            throw new OntologyException("Slovník s IRI " + ontologyIRI + " nebyl nalezen.");
        }
        return ontologyMetadataOpt.get();
    }

    private Model fetchOntologyModel(String ontologyIRI) throws OntologyException {
        Model model = jenaTDB2Repository.fetchGraph(ontologyIRI);
        if (model.isEmpty()) {
            log.error("Ontology model is empty for IRI: {}", ontologyIRI);
            throw new OntologyException("Model slovníku je prázdný nebo nebyl nalezen.");
        }
        return model;
    }

    private OntologyEditor.EditResult performOntologyEdit(OntologyEditModel editModel, Model model, String oldNamespace) {
        return ontologyEditor.editOntology(editModel, model, oldNamespace);
    }

    private OntologyMetadataEntity handleOntologyIRIChange(String oldOntologyIRI, String newOntologyIRI,
                                                           Model model, OntologyMetadataEntity metadataEntity)
            throws OntologyException {
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
            throw new OntologyException("Nepodařilo se uložit změny slovníku: " + e.getMessage());
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

    private void saveOntologyModel(String ontologyIRI, Model model) throws OntologyException {
        try {
            jenaTDB2Repository.saveOntologyModel(ontologyIRI, model);
            log.info("Saved/updated ontology model for graph: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save ontology model: {}", e.getMessage());
            throw new OntologyException("Nepodařilo se uložit změny slovníku: " + e.getMessage());
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

    private void validateOntologyEditModel(OntologyEditModel model) throws OntologyException {
        if (model == null) {
            throw new OntologyException("Data pro úpravu slovníku jsou prázdná");
        }
        if (model.getOntologyIRI() == null || model.getOntologyIRI().trim().isEmpty()) {
            throw new OntologyException("IRI slovníku je povinné");
        }
    }

    private void cleanupTDB2Graph(String graphName) {
        jenaTDB2Repository.deleteGraph(graphName);
    }
}
