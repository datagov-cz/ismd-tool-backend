package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.exception.OntologyAlreadyExistsException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.OntologyUploadMetadataException;
import com.dia.ismdtoolbackend.exception.OntologyUploadRdfStoreException;
import com.dia.ismdtoolbackend.models.OntologyMetadataModel;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.ismdtoolbackend.utility.exporter.turtle.OFNTypeNormalizer;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import com.dia.ismdtoolbackend.utility.validation.UploadIriNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.riot.Lang;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Entry point for vocabulary upload. Owns the transaction and cache-eviction boundaries and runs
 * the upload as a sequence of steps, each in its focused class.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyUploadServiceImpl implements OntologyUploadService {

    private final UploadFileReader fileReader;
    private final UploadModelNormalizer modelNormalizer;
    private final UploadConceptRoleResolver roleResolver;
    private final UploadConceptGate uploadConceptGate;
    private final PublishedResourceUtil deviationChecker;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final UploadMetadataWriter metadataWriter;
    private final UploadValidationRunner validationRunner;

    @Override
    public Lang determineRDFFormat(MultipartFile file) {
        return fileReader.determineRDFFormat(file);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public OntologyMetadataModel uploadFromFile(MultipartFile file, String userId,
                                                NormalizeMode normalizeMode,
                                                List<String> conceptsToNormalize) throws IOException, OntologyUploadException {
        OntModel finalModel = fileReader.read(file);
        try {
            // 0. Strip trailing slashes from IRIs first, so every later step sees the corrected ones.
            List<String> correctedIris = UploadIriNormalizer.normalize(finalModel);
            if (!correctedIris.isEmpty()) {
                log.info("Stripped the trailing slash from {} IRI(s): {}", correctedIris.size(), correctedIris);
            }

            String graphName = modelNormalizer.determineGraphName(finalModel);
            log.info("Uploading final model with {} statements to graph: {}", finalModel.size(), graphName);

            modelNormalizer.normalizeOntologyType(finalModel, graphName);

            // 1. Fail-fast validation — check slug uniqueness before any persistence
            metadataWriter.checkSlugUniqueness(graphName);

            // 2. Missing-inScheme decision gate. Detect BEFORE normalize (normalize would
            //    stamp inScheme and hide the gap). If any owned concept lacks inScheme and
            //    the user hasn't decided, reject with the list — nothing persisted.
            // 3. Resolve which missing concepts to normalize from the user's decision.
            Set<String> conceptsToStamp =
                    modelNormalizer.conceptsToStamp(finalModel, graphName, normalizeMode, conceptsToNormalize);

            // 4a. Normalize OFN types/labels + add inScheme only for the chosen concepts.
            int normalizedCount = OFNTypeNormalizer.normalize(finalModel, graphName, conceptsToStamp);
            if (normalizedCount > 0) {
                log.info("Normalized OFN types on {} resources", normalizedCount);
            }

            // 4b. Concept gate. A concept that declares no single role, breaks a create/edit
            //     rule or points its domain/range at a concept published in NKD is not
            //     imported: it loses skos:inScheme here, so the prune below and the metadata
            //     step treat it like an excluded concept. The domain/range targets ride the
            //     one NKD query that also decides isPublished.
            List<RejectedConceptDto> rejectedConcepts = new ArrayList<>();
            Map<String, ConceptType> ownedConcepts = roleResolver.resolveOwnedConcepts(finalModel, graphName, rejectedConcepts);
            Map<String, List<String>> domainRangeTargets =
                    uploadConceptGate.foreignDomainRangeTargets(finalModel, graphName, ownedConcepts);
            List<String> publishedConceptIris = deviationChecker.checkPublishedResourcesInNKD(finalModel,
                    domainRangeTargets.values().stream().flatMap(List::stream).collect(Collectors.toSet()));
            if (!publishedConceptIris.isEmpty()) {
                log.info("Model contains published resources: {}", publishedConceptIris.size());
            }
            rejectedConcepts.addAll(uploadConceptGate.findInvalid(
                    finalModel, ownedConcepts, domainRangeTargets, publishedConceptIris));
            roleResolver.excludeRejectedConcepts(finalModel, graphName, rejectedConcepts);

            // 4c. Prune owned-namespace concepts the user EXCLUDED (no inScheme → no PG
            //     row) that nothing else references, so they don't leak into TDB2 as
            //     subjects with no metadata row. Still-referenced excluded concepts are
            //     kept as inert context (no dangling edges). This makes the upload path
            //     honour "no PG row ⇒ no owned RDF", which the PG↔TDB2 reconciler relies on.
            OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(finalModel, graphName);

            // 5. Save RDF data to TDB2 first — if this fails, no metadata exists, clean exit
            try {
                jenaTDB2Repository.putOntologyModel(graphName, finalModel);
            } catch (Exception e) {
                throw new OntologyUploadRdfStoreException(
                        "Uložení slovníku do RDF úložiště (TDB2) selhalo: " + e.getMessage(), e);
            }

            // 6. Save metadata to PostgreSQL — protected by @Transactional
            //    If anything below fails, Spring rolls back PostgreSQL; catch cleans up TDB2
            OntologyMetadataModel metadata;
            try {
                metadata = metadataWriter.createOntologyMetadataEntity(graphName, userId);
                boolean ontologyPublished = metadataWriter.extractAndSaveConceptMetadata(finalModel, graphName, userId, metadata.getId(), publishedConceptIris);
                // extractAndSaveConceptMetadata flips is_published on a re-fetched entity; mirror it
                // onto the returned model so the upload response matches the persisted row.
                metadata.setIsPublished(ontologyPublished);
                metadata.setRejectedConcepts(rejectedConcepts);
                metadata.setCorrectedIris(correctedIris);
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
                throw new OntologyUploadMetadataException(
                        "Uložení metadat slovníku selhalo: " + e.getMessage(), e);
            }

            String ontologyContent = fileReader.convertOntModelToTtl(finalModel);
            CompletableFuture.runAsync(() ->
                    validationRunner.requestAndSaveValidationReport(ontologyContent, graphName)
            ).exceptionally(ex -> {
                log.error("Async validation failed for ontology: {}", graphName, ex);
                return null;
            });

            return metadata;
        } finally {
            finalModel.close();
        }
    }
}
