package com.dia.ismdtoolbackend.service.impl;

import com.dia.exceptions.ConversionException;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import com.dia.ismdtoolbackend.exception.EmptyFileException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.UnsupportedRdfFormatException;
import com.dia.ismdtoolbackend.client.ValidationClient;
import com.dia.ismdtoolbackend.controller.dto.MissingConceptDto;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.exception.InSchemeDecisionRequiredException;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.entity.ValidationReportEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.OntologyValidationStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
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
import java.time.Instant;
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

    /**
     * Self-reference to the Spring proxy. The async validation runs from a {@code runAsync} lambda,
     * which captures {@code this} directly — a plain call to {@code saveValidationOutcome} would
     * bypass the proxy and its {@code @Transactional} would be inert. Calling through the proxy
     * makes the report-save + status-mark a single atomic unit. {@code @Lazy} field injection (not
     * a constructor arg) resolves to a lazy proxy AFTER construction, breaking the self-cycle that
     * a {@code final} constructor parameter would otherwise form.
     */
    @Lazy
    @Autowired
    private OntologyUploadServiceImpl self;

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
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public OntologyMetadataModel uploadFromFile(MultipartFile file, String userId,
                                                NormalizeMode normalizeMode,
                                                List<String> conceptsToNormalize) throws IOException, OntologyUploadException {
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
            String graphName = determineGraphName(finalModel);
            log.info("Uploading final model with {} statements to graph: {}", finalModel.size(), graphName);

            normalizeOntologyType(finalModel, graphName);

            List<String> publishedConceptIris = deviationChecker.checkPublishedResourcesInNKD(finalModel);
            if (!publishedConceptIris.isEmpty()) {
                log.info("Model contains published resources: {}", publishedConceptIris.size());
            }

            // 1. Fail-fast validation — check slug uniqueness before any persistence
            checkSlugUniqueness(graphName);

            // 2. Missing-inScheme decision gate. Detect BEFORE normalize (normalize would
            //    stamp inScheme and hide the gap). If any owned concept lacks inScheme and
            //    the user hasn't decided, reject with the list — nothing persisted.
            List<String> missingInScheme = OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(finalModel, graphName);
            if (!missingInScheme.isEmpty() && normalizeMode == null) {
                List<MissingConceptDto> missingDtos = missingInScheme.stream()
                        .map(iri -> new MissingConceptDto(
                                iri, UtilityMethods.extractNameFromIRI(iri), graphName))
                        .toList();
                log.info("Upload requires inScheme decision: {} concept(s) missing skos:inScheme in {}",
                        missingDtos.size(), graphName);
                throw new InSchemeDecisionRequiredException(graphName, missingDtos);
            }

            // 3. Resolve which missing concepts to normalize from the user's decision.
            Set<String> conceptsToStamp = resolveConceptsToNormalize(missingInScheme, normalizeMode, conceptsToNormalize);

            // 4a. Normalize OFN types/labels + add inScheme only for the chosen concepts.
            int normalizedCount = OFNTypeNormalizer.normalize(finalModel, graphName, conceptsToStamp);
            if (normalizedCount > 0) {
                log.info("Normalized OFN types on {} resources", normalizedCount);
            }

            // 4b. Prune owned-namespace concepts the user EXCLUDED (no inScheme → no PG
            //     row) that nothing else references, so they don't leak into TDB2 as
            //     subjects with no metadata row. Still-referenced excluded concepts are
            //     kept as inert context (no dangling edges). This makes the upload path
            //     honour "no PG row ⇒ no owned RDF", which the PG↔TDB2 reconciler relies on.
            OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(finalModel, graphName);

            // 5. Save RDF data to TDB2 first — if this fails, no metadata exists, clean exit
            try {
                jenaTDB2Repository.putOntologyModel(graphName, finalModel);
            } catch (Exception e) {
                throw new OntologyUploadException("Failed to save ontology to TDB2: " + e.getMessage(), e);
            }

            // 6. Save metadata to PostgreSQL — protected by @Transactional
            //    If anything below fails, Spring rolls back PostgreSQL; catch cleans up TDB2
            OntologyMetadataModel metadata;
            try {
                metadata = createOntologyMetadataEntity(graphName, userId);
                boolean ontologyPublished = extractAndSaveConceptMetadata(finalModel, graphName, userId, metadata.getId(), publishedConceptIris);
                // extractAndSaveConceptMetadata flips is_published on a re-fetched entity; mirror it
                // onto the returned model so the upload response matches the persisted row.
                metadata.setIsPublished(ontologyPublished);
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
                    self.requestAndSaveValidationReport(ontologyContent, graphName)
            ).exceptionally(ex -> {
                log.error("Async validation failed for ontology: {}", graphName, ex);
                return null;
            });

            return metadata;
        } finally {
            finalModel.close();
        }
    }

    /**
     * Advisory post-upload validation: calls the validator (lenient — a validator outage must never
     * block ingest) and then persists the outcome. The HTTP call is deliberately OUTSIDE any DB
     * transaction so a slow/hung validator never holds a pooled connection; only the persistence is
     * transactional, via {@link #saveValidationOutcome} through the proxy ({@link #self}).
     */
    public void requestAndSaveValidationReport(String ontologyContent, String iri) {
        // Lenient client swallows outages/rejections and returns empty — never throws here.
        Optional<ValidationReport> report = validationClient.requestValidationLenient(ontologyContent, iri);
        try {
            self.saveValidationOutcome(iri, report.orElse(null));
        } catch (Exception e) {
            log.warn("Persisting validation outcome failed for ontology {}: {}", iri, e.getMessage(), e);
        }
    }

    /**
     * Atomically persists the validation outcome: deletes any prior report, saves the new one (if
     * the validator answered), and marks {@code last_validation_status}. {@code @Transactional} so
     * the whole sequence commits or rolls back together — never a saved report without a status, or
     * a status without its report. {@code report == null} means the validator was unavailable →
     * {@code SKIPPED_UNAVAILABLE} (the ontology is ingested regardless). Public + invoked via the
     * proxy ({@link #self}) so the annotation is honoured from the async lambda.
     */
    @Transactional
    public void saveValidationOutcome(String iri, ValidationReport report) {
        Optional<OntologyMetadataEntity> ontologyOpt = ontologyMetadataRepository.findByGraphName(iri);
        if (ontologyOpt.isEmpty()) {
            log.warn("Ontology metadata not found for graph name: {}", iri);
            return;
        }
        OntologyMetadataEntity ontology = ontologyOpt.get();

        Optional<ValidationReportEntity> validationReportOpt = validationReportRepository.findByOntologyMetadataId(ontology.getId());
        validationReportOpt.ifPresent(validationReportRepository::delete);

        if (report != null) {
            ValidationReportEntity validationReportEntity = new ValidationReportEntity();
            validationReportEntity.setId(report.getId());
            validationReportEntity.setTimestamp(report.getTimestamp());
            validationReportEntity.setOntologyMetadataId(ontology.getId());
            validationReportEntity.setGetOntologyIri(ontology.getGraphName());
            String validationResults = validationReportEntity.convertResultsToJson(report.getResults());
            validationReportEntity.setResultsJson(validationResults);
            validationReportRepository.save(validationReportEntity);
            markValidationStatus(ontology, OntologyValidationStatus.VALIDATED);
        } else {
            markValidationStatus(ontology, OntologyValidationStatus.SKIPPED_UNAVAILABLE);
        }
    }

    private void markValidationStatus(OntologyMetadataEntity ontology, OntologyValidationStatus status) {
        ontology.setLastValidationStatus(status);
        ontology.setLastValidationAt(Instant.now());
        ontologyMetadataRepository.save(ontology);
    }

    private String determineGraphName(OntModel model) {
        // The RDF data is authoritative for the vocabulary IRI / graph name. If no
        // ontology IRI can be derived, FAIL — never fabricate a filename-based name,
        // which produces orphan concepts whose namespace diverges from the graph.
        String ontologyIRI = extractOntologyIRI(model);
        if (ontologyIRI == null) {
            throw new OntologyUploadException(
                    "Z RDF dat nelze odvodit IRI slovníku (chybí owl:Ontology nebo skos:ConceptScheme). "
                            + "Slovník nelze nahrát bez identity odvozené z dat.");
        }
        log.debug("Using extracted ontology IRI as graph name: {}", ontologyIRI);
        return ontologyIRI;
    }

    /**
     * Maps the user's {@link NormalizeMode} decision to the set of missing-inScheme
     * concept IRIs that should be normalized (stamped with {@code inScheme → graphName}).
     * The complement — missing concepts NOT returned here — are excluded: no inScheme,
     * no ownership row, triples kept.
     */
    private Set<String> resolveConceptsToNormalize(List<String> missingInScheme,
                                                   NormalizeMode normalizeMode,
                                                   List<String> conceptsToNormalize) {
        if (missingInScheme.isEmpty()) {
            return Set.of();
        }
        Set<String> missingSet = new HashSet<>(missingInScheme);
        // normalizeMode is non-null here (the decision gate above returned otherwise).
        return switch (normalizeMode) {
            case NORMALIZE_ALL -> missingSet;
            case EXCLUDE_ALL -> Set.of();
            case PER_CONCEPT -> {
                if (conceptsToNormalize == null) {
                    yield Set.of();
                }
                // Only honor IRIs that are actually in the missing set; ignore unknowns.
                Set<String> chosen = new HashSet<>(conceptsToNormalize);
                chosen.retainAll(missingSet);
                yield chosen;
            }
        };
    }

    /**
     * Stamps {@code owl:Ontology} on the vocabulary resource when the uploaded data omits it.
     *
     * <p>OFN vocabularies commonly declare only {@code skos:ConceptScheme} and the OFN
     * {@code …/pojem/slovník} type. {@code owl:Ontology} is the foundational type every
     * ontology-keyed lookup asks for — {@link PublishedResourceUtil#checkPublishedResourcesInNKD}
     * among them, which decides {@code isPublished}. The create path stamps all three types; upload
     * must land in the same shape.
     */
    private void normalizeOntologyType(OntModel model, String graphName) {
        Resource ontologyResource = model.getResource(graphName);
        if (!ontologyResource.hasProperty(RDF.type, OWL2.Ontology)) {
            ontologyResource.addProperty(RDF.type, OWL2.Ontology);
            log.info("Normalized ontology {} to owl:Ontology (absent from uploaded data)", graphName);
        }
    }

    private String extractOntologyIRI(OntModel model) {
        // owl:Ontology is the primary signal; SKOS-only vocabularies declare the
        // vocabulary as a skos:ConceptScheme instead, so accept that too.
        String iri = firstUriSubjectOfType(model, OWL2.Ontology);
        if (iri == null) {
            iri = firstUriSubjectOfType(model, SKOS.ConceptScheme);
        }
        return iri;
    }

    private String firstUriSubjectOfType(OntModel model, Resource type) {
        ResIterator subjects = model.listResourcesWithProperty(RDF.type, type);
        while (subjects.hasNext()) {
            Resource subject = subjects.next();
            if (subject.isURIResource()) {
                return subject.getURI();
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

    private boolean extractAndSaveConceptMetadata(OntModel model, String graphName, String userId, Long ontologyMetadataId, List<String> publishedConceptIris) {
        log.info("Extracting concept metadata from ontology: {}", graphName);

        OntologyMetadataEntity ontologyMetadata = ontologyMetadataRepository.findById(ontologyMetadataId)
                .orElseThrow(() -> new IllegalStateException("Ontology metadata not found with id: " + ontologyMetadataId));

        boolean ontologyPublished = publishedConceptIris.contains(graphName);
        if (ontologyPublished) {
            log.info("Ontology {} is published in NKD, setting isPublished = true", graphName);
            ontologyMetadata.setIsPublished(true);
            ontologyMetadataRepository.save(ontologyMetadata);
        }

        Resource pojemResource = model.createResource(POJEM_GENERIC);
        ResIterator conceptIterator = model.listResourcesWithProperty(RDF.type, pojemResource);
        List<ConceptMetadataEntity> conceptEntities = new ArrayList<>();

        while (conceptIterator.hasNext()) {
            Resource conceptResource = conceptIterator.next();

            if (shouldSkipConcept(conceptResource, graphName)) {
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

        return ontologyPublished;
    }

    private boolean shouldSkipConcept(Resource conceptResource, String graphName) {
        if (!conceptResource.isURIResource()) {
            return true;
        }

        String conceptIri = conceptResource.getURI();

        // Alien-concept guard: a concept whose IRI is not under this vocabulary's
        // namespace is not owned by this upload (e.g. an embedded legislative
        // reference). Don't claim it with a Postgres ownership row — it stays a
        // referenced concept, resolved later via its own vocabulary / NKD. Mirrors
        // the resolution invariant STRSTARTS(conceptIri, graphName).
        if (!OFNTypeNormalizer.isOwnedConcept(conceptIri, graphName)) {
            log.debug("Skipping alien concept not owned by {}: {}", graphName, conceptIri);
            return true;
        }

        // Excluded-concept guard: an owned concept that still lacks skos:inScheme after
        // normalization is one the user chose to EXCLUDE. It must not get an ownership
        // row — it would be unresolvable (no inScheme) and falsely claimed. Its triples
        // remain in the graph as inert context.
        Property skosInScheme = conceptResource.getModel().createProperty(SKOS_NS + "inScheme");
        if (!conceptResource.hasProperty(skosInScheme)) {
            log.debug("Skipping excluded concept (no skos:inScheme) in {}: {}", graphName, conceptIri);
            return true;
        }

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
