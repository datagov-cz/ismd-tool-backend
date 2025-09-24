package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.entity.models.OntologyCreateModel;
import com.dia.ismdtoolbackend.entity.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyService;
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
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static com.dia.constants.ArchiConstants.CASOVY_OKAMZIK;
import static com.dia.constants.ArchiConstants.SLOVNIKY_NS;
import static com.dia.ismdtoolbackend.constants.OFNJsonConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyServiceImpl implements OntologyService {

    private final String fusekiEndpoint;

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ValidationReportRepository validationReportRepository;

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

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            Model model = conn.fetch(graphName);

            if (model.isEmpty()) {
                log.error("Ontology model is empty.");
                throw new OntologyException("Slovník je prázdný, nebo nebyl nalezen.");
            }

            conn.delete(graphName);
        }
        ontologyMetadataRepository.deleteById(ontologyId);
    }

    @Override
    @Transactional
    public OntologyMetadataModel createOntology(OntologyCreateModel ontologyCreateModel, String userId) throws OntologyException {
        validateOntologyCreateModel(ontologyCreateModel);

        URIGenerator uriGenerator = new URIGenerator();
        String ontologyIRI = uriGenerator.generateVocabularyURIFromGivenNamespace(ontologyCreateModel.getName(), ontologyCreateModel.getNamespace());

        if (!UtilityMethods.isValidIRI(ontologyIRI)) {
            log.error("ontologyIRI {} not valid", ontologyCreateModel.getName());
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

        if (model.getName() == null || model.getName().trim().isEmpty()) {
            throw new OntologyException("Název slovníku je povinný");
        }

        if (model.getDescription() == null || model.getDescription().trim().isEmpty()) {
            throw new OntologyException("Popis slovníku je povinný");
        }

        if (!model.getName().matches("^[a-zA-Z0-9\\-_]+$")) {
            throw new OntologyException("Název může obsahovat pouze písmena, čísla, pomlčky a podtržítka");
        }
    }

    private void createOFNBaseModel(String ontologyIRI, OntologyCreateModel ontologyCreateModel) throws OntologyException {
        Set<String> baseClasses = Set.of(POJEM);
        Set<String> baseProperties = Set.of(NAZEV, POPIS, OKAMZIK_VYTVORENI, DATUM_A_CAS);
        OFNBaseModel ofnModel = new OFNBaseModel(baseClasses, baseProperties);

        OntModel model = ofnModel.getOntModel();
        Resource ontologyResource = model.createResource(ontologyIRI);

        Property prefLabel = model.createProperty(SKOS_NS + "prefLabel");
        ontologyResource.addProperty(prefLabel, ontologyCreateModel.getName(), "cs");

        if (ontologyCreateModel.getDescription() != null && !ontologyCreateModel.getDescription().trim().isEmpty()) {
            ontologyResource.addProperty(DCTerms.description, ontologyCreateModel.getDescription(), "cs");
        }

        String temporalMomentIRI = ontologyIRI + "/casovy-okamzik-vytvoreni";
        Resource temporalMoment = model.createResource(temporalMomentIRI);
        temporalMoment.addProperty(RDF.type, model.createResource(CAS_NS + CASOVY_OKAMZIK));

        Property datumACasProperty = model.createProperty(CAS_NS + DATUM_A_CAS);
        String currentDateTime = LocalDateTime.now().toString();
        temporalMoment.addProperty(datumACasProperty, currentDateTime);

        Property okamzikVytvoreniProperty = model.createProperty(SLOVNIKY_NS + OKAMZIK_VYTVORENI);
        ontologyResource.addProperty(okamzikVytvoreniProperty, temporalMoment);

        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            conn.load(ontologyIRI, model);
            log.info("Successfully saved ontology model to TDB2 with graph name: {}", ontologyIRI);
        } catch (Exception e) {
            log.error("Failed to save ontology model to TDB2: {}", e.getMessage());
            throw new OntologyException("Nepodařilo se uložit slovník do databáze: " + e.getMessage());
        }
    }

    private OntologyMetadataEntity createOntologyMetadata(String ontologyIRI, String userId) throws OntologyException {
        OntologyMetadataEntity metadataEntity = new OntologyMetadataEntity();
        metadataEntity.setGraphName(ontologyIRI);
        metadataEntity.setUserId(userId);
        metadataEntity.setIsPublished(false);

        return ontologyMetadataRepository.save(metadataEntity);
    }

    private void cleanupTDB2Graph(String graphName) {
        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            conn.delete(graphName);
            log.info("Successfully cleaned up TDB2 graph: {}", graphName);
        } catch (Exception e) {
            log.error("Failed to cleanup TDB2 graph: {}", graphName, e);
            throw new OntologyException("Failed to cleanup TDB2 graph: " + e.getMessage());
        }
    }
}
