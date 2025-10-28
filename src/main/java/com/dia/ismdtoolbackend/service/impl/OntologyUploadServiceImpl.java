package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.ConversionException;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.exception.*;
import com.dia.ismdtoolbackend.utility.analyzer.AnalysisResult;
import com.dia.ismdtoolbackend.utility.analyzer.OntologyAnalyzer;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.models.OFNBaseModel;
import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntologyException;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.dia.constants.ArchiConstants.DEFAULT_NS;

@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyUploadServiceImpl implements OntologyUploadService {

    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataRepository ontologyMetadataRepository;
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
    public OntologyMetadataModel uploadFromFile(MultipartFile file, String providedName, String userId) {
        if (file.isEmpty()) {
            log.error("Ontology upload file is empty");
            throw new EmptyFileException("Ontology upload file is empty");
        }

        Lang rdfLang = determineRDFFormat(file);
        if (rdfLang == null) {
            log.error("Ontology RDF language is not supported");
            throw new UnsupportedRdfFormatException("Ontology RDF language is not supported");
        }

        OntModel finalModel;
        try {
            finalModel = createMergedOntologyModel(file, rdfLang);
        } catch (IOException e) {
            log.error("Failed to read uploaded file: {}", e.getMessage(), e);
            throw new OntologyUploadException("Nepodařilo se načíst nahraný soubor: " + e.getMessage(), e);
        }


        String graphName = determineGraphName(file, providedName, finalModel);
        log.info("Uploading final model with {} statements to graph: {}", finalModel.size(), graphName);

        OntologyMetadataModel metadata = createOntologyMetadataEntity(graphName, userId);

        try {
            jenaTDB2Repository.putOntologyModel(graphName, finalModel);
        } catch (Exception e) {
            ontologyMetadataRepository.deleteById(metadata.getId());
            throw new OntologyStorageException("Failed to upload to TDB2", e);
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
        Optional<OntologyMetadataEntity> ontologyOpt = ontologyMetadataRepository.findByGraphNameAndUserId(graphName, userId);
        if (ontologyOpt.isPresent()) {
            throw new OntologyValidationException("Slovník se stejným IRI již v Nástroji existuje: {}" + graphName);
        }

        OntologyMetadataModel ontologyMetadataModel = new OntologyMetadataModel();
        ontologyMetadataModel.setGraphName(graphName);
        ontologyMetadataModel.setUser(new UserModel(userId));
        ontologyMetadataModel.setIsPublished(false);

        log.debug("Ontology metadata entity name: {}, userId: {}", ontologyMetadataModel.getGraphName(), userId);
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
}
