package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.ConversionException;
import com.dia.ismdtoolbackend.utility.analyzer.AnalysisResult;
import com.dia.ismdtoolbackend.utility.analyzer.OntologyAnalyzer;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.exception.OntologyAlreadyExistsException;
import com.dia.ismdtoolbackend.exception.OntologyAnalysisException;
import com.dia.ismdtoolbackend.exception.OntoloyUploadException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.models.OFNBaseModel;
import com.dia.utility.UtilityMethods;
import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.dia.constants.VocabularyConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyUploadServiceImpl implements OntologyUploadService {

    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final ValidationClient validationClient;
    private final ValidationReportRepository validationReportRepository;
    private final OntologyAnalyzer ontologyAnalyzer;
    private final JenaTDB2Repository jenaTDB2Repository;

    @Override
    public Lang determineRDFFormat(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (fileName != null) {
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

            switch (extension) {
                case "ttl", "turtle":
                    return Lang.TURTLE;
                case "jsonld", "json-ld":
                    return Lang.JSONLD;
                default:
                    break;
            }
        }

        String contentType = file.getContentType();
        if (contentType != null) {
            if (contentType.contains("turtle")) {
                return Lang.TURTLE;
            } else if (contentType.contains("json")) {
                return Lang.JSONLD;
            }
        }

        return null;
    }

    @Override
    @Transactional
    public OntologyMetadataModel uploadFromFile(MultipartFile file, String providedName, Lang rdfLang, String userId) throws IOException, OntoloyUploadException {
        OntModel finalModel = createMergedOntologyModel(file, rdfLang);
        String graphName = determineGraphName(file, providedName, finalModel);
        log.info("Uploading final model with {} statements to graph: {}", finalModel.size(), graphName);

        OntologyMetadataModel metadata = createOntologyMetadataEntity(graphName, userId);

        try {
            jenaTDB2Repository.putOntologyModel(graphName, finalModel);
        } catch (Exception e) {
            ontologyMetadataRepository.deleteById(metadata.getId());
            throw new OntoloyUploadException("Failed to upload to TDB2", e);
        }

        try {
            extractAndSaveConceptMetadata(finalModel, graphName, userId);
        } catch (Exception e) {
            log.warn("Failed to extract concept metadata from uploaded ontology: {}", e.getMessage(), e);
        }

        String ontologyContent = convertOntModelToTtl(finalModel);
        CompletableFuture.runAsync(() ->
                requestAndSaveValidationReport(ontologyContent, graphName)
        );

        return metadata;
    }

    private OntModel createMergedOntologyModel(MultipartFile file, Lang rdfLang) throws IOException {
        OntModel uploadedModel = getOntologyModel(file, rdfLang);

        try {
            AnalysisResult analysisResult = ontologyAnalyzer.analyzeUploadedOntology(uploadedModel);
            Set<String> requiredBaseClasses = analysisResult.requiredBaseClasses();
            Set<String> requiredProperties = analysisResult.requiredProperties();

            log.info("Analysis complete - Required base classes: {}, Required properties: {}",
                    requiredBaseClasses.size(), requiredProperties.size());
            log.debug("Base classes: {}", requiredBaseClasses);
            log.debug("Properties: {}", requiredProperties);

            if (requiredBaseClasses.isEmpty() && requiredProperties.isEmpty()) {
                log.info("Ontology is complete, using as-is with {} statements", uploadedModel.size());
                return uploadedModel;
            }

            OFNBaseModel baseModel = new OFNBaseModel(requiredBaseClasses, requiredProperties);

            OntModel mergedModel = ModelFactory.createOntologyModel();
            mergedModel.add(uploadedModel);
            mergedModel.add(baseModel.getOntModel());

            log.info("Created merged model with {} statements (uploaded: {}, base: {})",
                    mergedModel.size(), uploadedModel.size(), baseModel.getOntModel().size());

            return mergedModel;
        } catch (Exception e) {
            throw new OntologyAnalysisException(e, e.getMessage());
        }
    }

    public void requestAndSaveValidationReport(String ontologyContent, String iri) {
        try {
            Optional<OntologyMetadataEntity> ontologyOpt = ontologyMetadataRepository.findByGraphName(iri);
            if (ontologyOpt.isEmpty()) {
                log.warn("Ontology metadata not found for graph name: {}", iri);
                return;
            }

            Optional<ValidationReportEntity> validationReportOpt = validationReportRepository.findByOntologyMetadataId(ontologyOpt.get().getId());
            validationReportOpt.ifPresent(validationReportRepository::delete);

            Optional<ValidationReport> report = validationClient.requestValidation(ontologyContent, iri);
            if (report.isPresent()) {
                OntologyMetadataEntity ontologyEntity = ontologyOpt.get();
                ValidationReportEntity validationEntity = new ValidationReportEntity(report.get(), ontologyEntity.getId());
                validationReportRepository.save(validationEntity);
                ontologyEntity.setValidationReportId(validationEntity.getId());
                ontologyMetadataRepository.save(ontologyEntity);
            }
        } catch (Exception e) {
            log.warn("Validation failed for ontology {}: {}", iri, e.getMessage());
        }
    }

    private String determineGraphName(MultipartFile file, String providedName, OntModel model) {
        if (providedName != null && !providedName.trim().isEmpty()) {
            log.debug("Using provided graph name: {}", providedName);
            return providedName;
        }

        String ontologyIRI = extractOntologyIRI(model);
        if (ontologyIRI != null) {
            log.debug("Using extracted ontology IRI as graph name: {}", ontologyIRI);
            return ontologyIRI;
        }

        String fileName = file.getOriginalFilename();
        String baseName = fileName != null ?
                fileName.replaceAll("\\.[^.]+$", "") : "ontology";

        String generatedName = String.format(DEFAULT_NS + "%s-%s", baseName, UUID.randomUUID());
        log.debug("Generated graph name: {}", generatedName);
        return generatedName;
    }

    private String extractOntologyIRI(OntModel model) {
        ResIterator ontologies = model.listResourcesWithProperty(RDF.type, OWL2.Ontology);
        if (ontologies.hasNext()) {
            Resource ont = ontologies.next();
            if (ont.isURIResource()) {
                return ont.getURI();
            }
        }
        return null;
    }

    private OntologyMetadataModel createOntologyMetadataEntity(String graphName, String userId) {
        String slug = UtilityMethods.extractNameFromIRI(graphName);

        Optional<OntologyMetadataEntity> existingBySlug = ontologyMetadataRepository.findBySlug(slug);
        if (existingBySlug.isPresent()) {
            OntologyMetadataModel existingMetadata = ontologyMetadataMapper.toDto(existingBySlug.get());
            log.info("Ontology already exists with slug: {} (graph name: {})", slug, graphName);
            throw new OntologyAlreadyExistsException(
                    "Slovník se stejným IRI již v Nástroji existuje: " + graphName,
                    existingMetadata
            );
        }

        OntologyMetadataModel ontologyMetadataModel = new OntologyMetadataModel();
        ontologyMetadataModel.setSlug(slug);
        ontologyMetadataModel.setGraphName(graphName);
        ontologyMetadataModel.setUser(new UserModel(userId));
        ontologyMetadataModel.setIsPublished(false);

        log.debug("Ontology metadata entity graphName: {}, userId: {}", ontologyMetadataModel.getGraphName(), userId);
        OntologyMetadataEntity ontologyMetadataEntity = ontologyMetadataMapper.toEntity(ontologyMetadataModel);
        OntologyMetadataEntity savedOntologyMetadataEntity = ontologyMetadataRepository.save(ontologyMetadataEntity);
        log.debug("Ontology metadata saved: {}", savedOntologyMetadataEntity);
        return ontologyMetadataMapper.toDto(savedOntologyMetadataEntity);
    }

    private OntModel getOntologyModel(MultipartFile file, Lang rdfLang) throws IOException {
        OntModel uploadedModel = ModelFactory.createOntologyModel();

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(file.getBytes())) {
            RDFDataMgr.read(uploadedModel, inputStream, rdfLang);
        }
        return uploadedModel;
    }

    private String convertOntModelToTtl(OntModel model) throws RuntimeException {
        try {
            StringWriter writer = new StringWriter();
            model.write(writer, "TTL");
            return writer.toString();
        } catch (Exception e) {
            log.error("Failed to convert OntModel to TTL", e);
            throw new ConversionException("Failed to convert OntModel to TTL: " + e.getMessage(), e);
        }
    }

    private void extractAndSaveConceptMetadata(OntModel model, String graphName, String userId) {
        log.info("Extracting concept metadata from ontology: {}", graphName);

        ResIterator conceptIterator = model.listResourcesWithProperty(RDF.type, SKOS.Concept);
        List<ConceptMetadataEntity> conceptEntities = new ArrayList<>();

        while (conceptIterator.hasNext()) {
            Resource conceptResource = conceptIterator.next();

            if (!conceptResource.isURIResource()) {
                continue;
            }

            String conceptIri = conceptResource.getURI();
            String conceptName = UtilityMethods.extractNameFromIRI(conceptIri);
            String slug = generateConceptSlug(graphName, conceptName);

            Optional<ConceptMetadataEntity> existing = conceptMetadataRepository.findByConceptIri(conceptIri);
            if (existing.isPresent()) {
                log.debug("Concept already exists: {}", conceptIri);
                continue;
            }

            ConceptType conceptType = determineConceptType(conceptResource, model);

            ConceptMetadataEntity conceptEntity = new ConceptMetadataEntity();
            conceptEntity.setSlug(slug);
            conceptEntity.setConceptName(conceptName);
            conceptEntity.setConceptType(conceptType);
            conceptEntity.setGraphName(graphName);
            conceptEntity.setConceptIri(conceptIri);
            conceptEntity.setUserId(userId);
            conceptEntity.setIsPublished(false);

            conceptEntities.add(conceptEntity);
        }

        if (!conceptEntities.isEmpty()) {
            conceptMetadataRepository.saveAll(conceptEntities);
            log.info("Saved {} concept metadata entries for ontology: {}", conceptEntities.size(), graphName);
        } else {
            log.info("No concepts found in uploaded ontology: {}", graphName);
        }
    }

    private ConceptType determineConceptType(Resource conceptResource, OntModel model) {
        if (hasOFNType(conceptResource, VLASTNOST, model)) {
            return ConceptType.VLASTNOST;
        }
        if (hasOFNType(conceptResource, VZTAH, model)) {
            return ConceptType.VZTAH;
        }
        return ConceptType.TRIDA;
    }

    private boolean hasOFNType(Resource conceptResource, String typeName, OntModel model) {
        String typeUri = OFN_NAMESPACE + typeName;
        Resource typeResource = model.getResource(typeUri);
        return conceptResource.hasProperty(RDF.type, typeResource);
    }

    private String generateConceptSlug(String graphName, String conceptName) {
        String baseSlug = UtilityMethods.extractNameFromIRI(graphName) + "-" + conceptName;
        String slug = baseSlug;
        int counter = 1;

        while (conceptMetadataRepository.findBySlug(slug).isPresent()) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }
}
