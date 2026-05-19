package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.ConversionException;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.models.UserModel;
import com.dia.ismdtoolbackend.exception.OntologyAlreadyExistsException;
import com.dia.ismdtoolbackend.mapper.OntologyMetadataMapper;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.repository.ValidationReportRepository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.utility.UtilityMethods;
import com.dia.validation.ValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.rdf.model.*;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final JenaTDB2Repository jenaTDB2Repository;
    private final PublishedResourceUtil deviationChecker;

    @Value("${spring.servlet.multipart.max-file-size:10MB}")
    private String maxFileSizeConfig;

    @Value("${rdf.parsing.timeout:60}")
    private int rdfParsingTimeoutSeconds;

    private static final String POJEM_GENERIC = "https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/pojem";

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
    public OntologyMetadataModel uploadFromFile(MultipartFile file, String providedName, String userId) throws IOException, OntologyUploadException {
        if (file.isEmpty()) {
            throw new EmptyFileException("Uploaded file is empty");
        }

        long maxBytes = DataSize.parse(maxFileSizeConfig).toBytes();
        if (file.getSize() > maxBytes) {
            throw new OntologyUploadException(
                    String.format("Soubor překračuje maximální povolenou velikost (%d MB).", maxBytes / (1024 * 1024))
            );
        }

        Lang rdfLang = determineRDFFormat(file);
        if (rdfLang == null) {
            throw new UnsupportedRdfFormatException("Nepodporovaný RDF jazyk");
        }

        OntModel finalModel = getOntologyModel(file, rdfLang);
        try {
            String graphName = determineGraphName(file, providedName, finalModel);
            log.info("Uploading final model with {} statements to graph: {}", finalModel.size(), graphName);

            List<String> publishedConceptIris = deviationChecker.checkPublishedResourcesInNKD(finalModel);
            if (!publishedConceptIris.isEmpty()) {
                log.info("Model contains published resources: {}", publishedConceptIris.size());
            }

            // 1. Fail-fast validation — check slug uniqueness before any persistence
            checkSlugUniqueness(graphName);

            // 2. Normalize OFN types and labels at import time
            int normalizedCount = OFNTypeNormalizer.normalize(finalModel);
            if (normalizedCount > 0) {
                log.info("Normalized OFN types on {} resources", normalizedCount);
            }

            // 3. Save RDF data to TDB2 first — if this fails, no metadata exists, clean exit
            try {
                jenaTDB2Repository.putOntologyModel(graphName, finalModel);
            } catch (Exception e) {
                throw new OntologyUploadException("Failed to save ontology to TDB2: " + e.getMessage(), e);
            }

            // 4. Save metadata to PostgreSQL — protected by @Transactional
            //    If anything below fails, Spring rolls back PostgreSQL; catch cleans up TDB2
            OntologyMetadataModel metadata;
            try {
                metadata = createOntologyMetadataEntity(graphName, userId);
                extractAndSaveConceptMetadata(finalModel, graphName, userId, metadata.getId(), publishedConceptIris);
            } catch (OntologyAlreadyExistsException e) {
                // Re-throw without TDB2 cleanup — slug check above should prevent this,
                // but if it happens (race condition), let @Transactional handle PostgreSQL
                throw e;
            } catch (Exception e) {
                try {
                    jenaTDB2Repository.deleteGraph(graphName);
                    log.info("Successfully rolled back TDB2 data for graph: {}", graphName);
                } catch (Exception tdbException) {
                    log.error("Failed to rollback TDB2 data for graph: {}", graphName, tdbException);
                }
                throw new OntologyUploadException("Failed to upload ontology: " + e.getMessage(), e);
            }

            String ontologyContent = convertOntModelToTtl(finalModel);
            CompletableFuture.runAsync(() ->
                    requestAndSaveValidationReport(ontologyContent, graphName)
            ).exceptionally(ex -> {
                log.error("Async validation failed for ontology: {}", graphName, ex);
                return null;
            });

            return metadata;
        } finally {
            finalModel.close();
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
                ValidationReportEntity validationReportEntity = new ValidationReportEntity();
                validationReportEntity.setId(report.get().getId());
                validationReportEntity.setTimestamp(report.get().getTimestamp());
                validationReportEntity.setOntologyMetadataId(ontologyOpt.get().getId());
                validationReportEntity.setGetOntologyIri(ontologyOpt.get().getGraphName());
                String validationResults = validationReportEntity.convertResultsToJson(report.get().getResults());
                validationReportEntity.setResultsJson(validationResults);
                validationReportRepository.save(validationReportEntity);
            }
        } catch (Exception e) {
            log.warn("Validation failed for ontology {}: {}", iri, e.getMessage(), e);
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

    private void checkSlugUniqueness(String graphName) {
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
    }

    private OntologyMetadataModel createOntologyMetadataEntity(String graphName, String userId) {
        String slug = UtilityMethods.extractNameFromIRI(graphName);

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
        OntModel uploadedModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);

        byte[] fileBytes = file.getBytes();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes)) {
                    RDFDataMgr.read(uploadedModel, inputStream, rdfLang);
                } catch (IOException e) {
                    throw new OntologyUploadException(e.getMessage());
                }
            });
            future.get(rdfParsingTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("RDF parsing timed out after {} seconds", rdfParsingTimeoutSeconds);
            throw new OntologyUploadException("Zpracování RDF souboru překročilo časový limit (" + rdfParsingTimeoutSeconds + " s).");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new OntologyUploadException("Chyba při zpracování RDF souboru: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OntologyUploadException("Zpracování RDF souboru bylo přerušeno.");
        } finally {
            executor.shutdownNow();
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

    private void extractAndSaveConceptMetadata(OntModel model, String graphName, String userId, Long ontologyMetadataId, List<String> publishedConceptIris) {
        log.info("Extracting concept metadata from ontology: {}", graphName);

        OntologyMetadataEntity ontologyMetadata = ontologyMetadataRepository.findById(ontologyMetadataId)
                .orElseThrow(() -> new IllegalStateException("Ontology metadata not found with id: " + ontologyMetadataId));

        if (publishedConceptIris.contains(graphName)) {
            log.info("Ontology {} is published in NKD, setting isPublished = true", graphName);
            ontologyMetadata.setIsPublished(true);
            ontologyMetadataRepository.save(ontologyMetadata);
        }

        Resource pojemResource = model.createResource(POJEM_GENERIC);
        ResIterator conceptIterator = model.listResourcesWithProperty(RDF.type, pojemResource);
        List<ConceptMetadataEntity> conceptEntities = new ArrayList<>();

        while (conceptIterator.hasNext()) {
            Resource conceptResource = conceptIterator.next();

            if (shouldSkipConcept(conceptResource)) {
                continue;
            }

            String conceptIri = conceptResource.getURI();
            String conceptName = UtilityMethods.extractNameFromIRI(conceptIri);
            String slug = generateConceptSlug(graphName, conceptName);

            ConceptType conceptType = determineConceptType(conceptResource);

            ConceptMetadataEntity conceptEntity = new ConceptMetadataEntity();
            conceptEntity.setSlug(slug);
            conceptEntity.setConceptName(conceptName);
            conceptEntity.setConceptType(conceptType);
            conceptEntity.setGraphName(graphName);
            conceptEntity.setConceptIri(conceptIri);
            conceptEntity.setUserId(userId);
            conceptEntity.setIsPublished(false);
            conceptEntity.setOntologyMetadata(ontologyMetadata);

            conceptEntities.add(conceptEntity);
        }

        List<ConceptMetadataEntity> publishedConcepts = conceptEntities.stream()
                .filter(concept -> publishedConceptIris.contains((concept.getConceptIri())))
                .toList();

        if (!publishedConcepts.isEmpty()) {
            log.info("Setting isPublished = true for {} concepts", publishedConcepts.size());
            for (ConceptMetadataEntity conceptMetadataEntity : publishedConcepts) {
                conceptMetadataEntity.setIsPublished(true);
                log.info("IsPublished set for concept {}", conceptMetadataEntity.getConceptIri());
            }
        }

        if (!conceptEntities.isEmpty()) {
            conceptMetadataRepository.saveAll(conceptEntities);
            log.info("Saved {} concept metadata entries for ontology: {}", conceptEntities.size(), graphName);
        } else {
            log.info("No concepts found in uploaded ontology: {}", graphName);
        }
    }

    private boolean shouldSkipConcept(Resource conceptResource) {
        if (!conceptResource.isURIResource()) {
            return true;
        }

        String conceptIri = conceptResource.getURI();
        Optional<ConceptMetadataEntity> existing = conceptMetadataRepository.findByConceptIri(conceptIri);
        if (existing.isPresent()) {
            log.debug("Concept already exists: {}", conceptIri);
            return true;
        }

        return false;
    }

    private ConceptType determineConceptType(Resource conceptResource) {
        if (conceptResource.hasProperty(RDF.type, OWL2.DatatypeProperty)) {
            return ConceptType.VLASTNOST;
        }
        if (conceptResource.hasProperty(RDF.type, OWL2.ObjectProperty)) {
            return ConceptType.VZTAH;
        }
        if (conceptResource.hasProperty(RDF.type, SKOS.Concept) || conceptResource.hasProperty(RDF.type, OWL2.Class)) {
            return ConceptType.TRIDA;
        }
        return null;
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
