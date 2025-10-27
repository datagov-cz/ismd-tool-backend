package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyEditModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyService;
import com.dia.ismdtoolbackend.utility.editor.OntologyEditor;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptData;
import com.dia.ismdtoolbackend.utility.exporter.json.ConceptProcessor;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelAnalyzer;
import com.dia.ismdtoolbackend.utility.exporter.json.ModelStructure;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFilterUtil;
import com.dia.ismdtoolbackend.utility.exporter.turtle.TurtleFormatterUtil;
import com.dia.models.OFNBaseModel;
import com.dia.utility.DataTypeConverter;
import com.dia.utility.URIGenerator;
import com.dia.utility.UtilityMethods;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.dia.constants.ArchiConstants.*;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.AGENDA;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.AIS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.ALTERNATIVNI_NAZEV;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.CAS_NS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.DATUM_A_CAS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.DEFINICE;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.DEFINICNI_OBOR;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.DEFINUJICI_NELEGISLATIVNI_ZDROJ;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.EKVIVALENTNI_POJEM;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.IDENTIFIKATOR;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.JE_PPDF;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.NADRAZENA_TRIDA;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.NAZEV;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.OBOR_HODNOT;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.OKAMZIK_VYTVORENI;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.POPIS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.SOUVISEJICI_NELEGISLATIVNI_ZDROJ;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.TYP_OBSAHU;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.USTANOVENI_NEVEREJNOST;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.ZPUSOB_SDILENI;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.ZPUSOB_ZISKANI;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyServiceImpl implements OntologyService {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ValidationReportRepository validationReportRepository;
    private final JenaTDB2Repository jenaTDB2Repository;

    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyEditor ontologyEditor;

    @Override
    @Transactional
    public void deleteOntology(Long ontologyId) throws OntologyException {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new EntityNotFoundException("Slovník s id " + ontologyId + " nebyl nalezen.");
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();

        Optional<ValidationReportEntity> validationReport =
                validationReportRepository.findByOntologyMetadataId(ontologyId);
        validationReport.ifPresent(validationReportRepository::delete);

        Model model = jenaTDB2Repository.fetchGraph(graphName);

        if (model.isEmpty()) {
            log.error("Ontology model is empty.");
            throw new EntityNotFoundException("Slovník je prázdný, nebo nebyl nalezen.");
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
            OntologyMetadataEntity metadataEntity = createOntologyMetadata(ontologyIRI, userId);
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
    public OntologyDetailModel getOntologyDetailModel(Long ontologyId) throws OntologyException {
        Optional<OntologyMetadataEntity> ontologyMetadataOpt = ontologyMetadataRepository.findById(ontologyId);
        if (ontologyMetadataOpt.isEmpty()) {
            log.error("ontologyId {} not found", ontologyId);
            throw new OntologyException("Metadata slovníku s id " + ontologyId + " nebyla nalezena.");
        }

        String graphName = ontologyMetadataOpt.get().getGraphName();

        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);

        if (rawModel.isEmpty()) {
            log.error("Ontology model is empty for graph: {}", graphName);
            throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
        }

        Model processedModel = applyOFNTransformations(rawModel);

        OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM, processedModel);

        ModelAnalyzer modelAnalyzer = new ModelAnalyzer();
        ConceptProcessor conceptProcessor = new ConceptProcessor();

        ModelStructure structure = modelAnalyzer.analyzeModel(processedModel);
        ConceptData conceptData = conceptProcessor.processAllConcepts(ontModel, structure);

        return mapToOntologyDetailModel(structure, conceptData);
    }

    private Model applyOFNTransformations(Model rawModel) {
        log.debug("Applying OFN transformations");
        Model filteredModel = TurtleFilterUtil.createFilteredModel(rawModel);
        Model ofnFormattedModel = TurtleFormatterUtil.transformToOFNFormat(filteredModel);
        log.debug("OFN transformation complete: {} -> {} -> {} statements",
                rawModel.size(), filteredModel.size(), ofnFormattedModel.size());
        return ofnFormattedModel;
    }

    private OntologyDetailModel mapToOntologyDetailModel(ModelStructure structure, ConceptData conceptData) {
        List<OntologyDetailModel.ConceptDetailModel> concepts = conceptData.getConcepts().stream()
                .map(this::mapToConceptDetailModel)
                .toList();

        return OntologyDetailModel.builder()
                .context(CONTEXT_JSONLD)
                .iri(structure.getOntologyIRI())
                .types(structure.getVocabularyTypes())
                .name(createMultilingualMap(structure.getModelName()))
                .description(createMultilingualMap(structure.getModelDescription()))
                .creationDate(structure.getCreationDate())
                .modificationDate(structure.getModificationDate())
                .concepts(concepts)
                .build();
    }

    @SuppressWarnings("unchecked")
    private OntologyDetailModel.ConceptDetailModel mapToConceptDetailModel(Map<String, Object> conceptMap) {
        return OntologyDetailModel.ConceptDetailModel.builder()
                .iri((String) conceptMap.get("iri"))
                .types((List<String>) conceptMap.get("typ"))
                .name((Map<String, Object>) conceptMap.get(NAZEV))
                .alternativeName((Map<String, Object>) conceptMap.get(ALTERNATIVNI_NAZEV))
                .definition((Map<String, Object>) conceptMap.get(DEFINICE))
                .description((Map<String, Object>) conceptMap.get(POPIS))
                .identifiers((List<String>) conceptMap.get(IDENTIFIKATOR))
                .exactMatches((List<Map<String, Object>>) conceptMap.get(EKVIVALENTNI_POJEM))
                .domain((String) conceptMap.get(DEFINICNI_OBOR))
                .range((String) conceptMap.get(OBOR_HODNOT))
                .broaderClasses((List<String>) conceptMap.get(NADRAZENA_TRIDA))
                .broaderRelations((List<String>) conceptMap.get(NADRAZENY_VZTAH))
                .broaderProperties((List<String>) conceptMap.get(NADRAZENA_VLASTNOST))
                .definingLegalSources((List<String>) conceptMap.get(DEFINUJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .relatedLegalSources((List<String>) conceptMap.get(SOUVISEJICI_USTANOVENI_PRAVNIHO_PREDPISU))
                .definingNonLegalSources((List<String>) conceptMap.get(DEFINUJICI_NELEGISLATIVNI_ZDROJ))
                .relatedNonLegalSources((List<String>) conceptMap.get(SOUVISEJICI_NELEGISLATIVNI_ZDROJ))
                .sharingMethods((List<String>) conceptMap.get(ZPUSOB_SDILENI))
                .acquisitionMethod((String) conceptMap.get(ZPUSOB_ZISKANI))
                .contentType((String) conceptMap.get(TYP_OBSAHU))
                .isPpdf((Boolean) conceptMap.get(JE_PPDF))
                .ais((String) conceptMap.get(AIS))
                .agenda((String) conceptMap.get(AGENDA))
                .privacyProvisions((List<String>) conceptMap.get(USTANOVENI_NEVEREJNOST))
                .build();
    }

    private Map<String, String> createMultilingualMap(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        map.put("cs", value);
        return map;
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

    private OntologyMetadataEntity createOntologyMetadata(String ontologyIRI, String userId) throws OntologyException {
        OntologyMetadataEntity metadataEntity = new OntologyMetadataEntity();
        metadataEntity.setGraphName(ontologyIRI);
        metadataEntity.setUserId(userId);
        metadataEntity.setIsPublished(false);

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
        OntologyMetadataEntity updatedEntity = ontologyMetadataRepository.save(metadataEntity);
        log.info("Updated metadata with new graph name: {}", newGraphName);
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
