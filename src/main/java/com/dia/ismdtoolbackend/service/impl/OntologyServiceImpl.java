package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyService;
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
import java.util.Optional;

import static com.dia.constants.ArchiConstants.*;
import static com.dia.constants.ExportConstants.Common.DEFAULT_LANG;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.CAS_NS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.DATUM_A_CAS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.OKAMZIK_VYTVORENI;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyServiceImpl implements OntologyService {

    private final String fusekiEndpoint;

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ValidationReportRepository validationReportRepository;
    private final JenaTDB2Repository jenaTDB2Repository;

    private final OntologyMetadataMapper ontologyMetadataMapper;

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

    private void validateOntologyCreateModel(OntologyCreateModel model) throws OntologyException {
        if (model == null) {
            throw new OntologyException("Data pro vytvoření slovníku jsou prázdná");
        }

        if (model.getNameModel() == null || model.getNameModel().getName() == null || model.getNameModel().getName().trim().isEmpty()) {
            throw new OntologyException("Název slovníku je povinný");
        }

        if (model.getDescriptionModel() == null || model.getDescriptionModel().getDescription() == null || model.getDescriptionModel().getDescription().trim().isEmpty()) {
            throw new OntologyException("Popis slovníku je povinný");
        }

        if (!model.getNameModel().getName().matches("^[a-zA-Z0-9\\-_]+$")) {
            throw new OntologyException("Název může obsahovat pouze písmena, čísla, pomlčky a podtržítka");
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

    private void cleanupTDB2Graph(String graphName) {
        jenaTDB2Repository.deleteGraph(graphName);
    }
}
