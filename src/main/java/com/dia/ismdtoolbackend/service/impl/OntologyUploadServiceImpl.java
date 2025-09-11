package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.ConversionException;
import com.dia.ismdtoolbackend.analyzer.AnalysisResult;
import com.dia.ismdtoolbackend.analyzer.OntologyAnalyzer;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.entity.dto.OntologyMetadataDto;
import com.dia.ismdtoolbackend.entity.dto.UserDto;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.models.OFNBaseModel;
import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
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

    private final String fusekiEndpoint;
    private final OntologyMetadataMapper ontologyMetadataMapper;
    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ValidationClient validationClient;
    private final ValidationReportRepository validationReportRepository;
    private final OntologyAnalyzer ontologyAnalyzer;

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
    public OntologyMetadataDto uploadFromFile(MultipartFile file, String providedName, Lang rdfLang, String userId) throws IOException {
        OntologyMetadataDto ontologyMetadataDto = uploadOntologyCore(file, providedName, rdfLang, userId);
        OntModel mergedModel = createMergedOntologyModel(file, rdfLang);
        String ontologyContent = convertOntModelToTtl(mergedModel);

        CompletableFuture.runAsync(() -> requestAndSaveValidationReport(ontologyContent, extractOntologyIRI(mergedModel)));

        return ontologyMetadataDto;
    }

    public OntologyMetadataDto uploadOntologyCore(MultipartFile file, String providedName, Lang rdfLang, String userId) throws IOException {
        OntModel uploadedModel = getOntologyModel(file, rdfLang);

        String graphName = determineGraphName(file, providedName, uploadedModel);

        log.info("Loaded model has {} statements", uploadedModel.size());
        log.info("Writing to graph: {}", graphName);


        try (RDFConnection conn = RDFConnection.connect(fusekiEndpoint)) {
            conn.put(graphName, uploadedModel);
            log.info("Successfully uploaded {} statements to graph {}", uploadedModel.size(), graphName);
        }

        return createOntologyMetadataEntity(graphName, userId);
    }

    private OntModel createMergedOntologyModel(MultipartFile file, Lang rdfLang) throws IOException {
        OntModel uploadedModel = getOntologyModel(file, rdfLang);

        AnalysisResult analysisResult = ontologyAnalyzer.analyzeUploadedOntology(uploadedModel);
        Set<String> requiredBaseClasses = analysisResult.requiredBaseClasses();
        Set<String> requiredProperties = analysisResult.requiredProperties();

        log.debug("Required base classes: {}", requiredBaseClasses);
        log.debug("Required properties: {}", requiredProperties);

        OFNBaseModel baseModel = new OFNBaseModel(requiredBaseClasses, requiredProperties);

        OntModel mergedModel = baseModel.getOntModel();
        mergedModel.add(uploadedModel);

        log.info("Created merged model with {} statements (base: {}, uploaded: {})",
                mergedModel.size(), baseModel.getOntModel().size(), uploadedModel.size());

        return mergedModel;
    }

    public void requestAndSaveValidationReport(String ontologyContent, String iri) {
        try {
            Optional<OntologyMetadataEntity> ontologyOpt = ontologyMetadataRepository.findByGraphName(iri);
            if (ontologyOpt.isEmpty()) {
                log.warn("Ontology metadata not found for graph name: {}", iri);
                return;
            }

            Optional<ValidationReportEntity> validationReportOpt = validationReportRepository.findByOntologyId(ontologyOpt.get().getId());
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

    private OntologyMetadataDto createOntologyMetadataEntity(String graphName, String userId) {
        Optional<OntologyMetadataEntity> ontologyOpt = ontologyMetadataRepository.findByGraphNameAndUserId(graphName, userId);
        if (ontologyOpt.isPresent()) {
            return ontologyMetadataMapper.toDto(ontologyOpt.get());
        }

        OntologyMetadataDto ontologyMetadataDto = new OntologyMetadataDto();
        ontologyMetadataDto.setGraphName(graphName);
        ontologyMetadataDto.setUser(new UserDto(userId));

        log.debug("Ontology metadata entity name: {}, userId: {}", ontologyMetadataDto.getGraphName(), userId);
        OntologyMetadataEntity ontologyMetadataEntity = ontologyMetadataMapper.toEntity(ontologyMetadataDto);
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
