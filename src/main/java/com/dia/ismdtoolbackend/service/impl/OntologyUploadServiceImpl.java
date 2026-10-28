package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.OntologySummaryDto;
import com.dia.ismdtoolbackend.controller.dto.OntologyUploadResultDto;
import com.dia.ismdtoolbackend.controller.dto.RejectedConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.NormalizeMode;
import com.dia.ismdtoolbackend.exception.OntologyUploadConflictingTypesException;
import com.dia.ismdtoolbackend.exception.OntologyUploadException;
import com.dia.ismdtoolbackend.exception.OntologyUploadMetadataException;
import com.dia.ismdtoolbackend.exception.OntologyUploadRdfStoreException;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.OntologyUploadService;
import com.dia.ismdtoolbackend.utility.published.PublishedResourceUtil;
import com.dia.ismdtoolbackend.utility.validation.ConceptRoleConflict;
import com.dia.ismdtoolbackend.utility.upload.ExcludedConceptPruning;
import com.dia.ismdtoolbackend.utility.upload.InSchemeStamping;
import com.dia.ismdtoolbackend.utility.upload.RoleMarkerDerivation;
import com.dia.ismdtoolbackend.utility.upload.UploadGraphNameResolver;
import com.dia.ismdtoolbackend.utility.upload.UploadIriNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.riot.Lang;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entry point for vocabulary upload. Owns the cache-eviction boundary and runs the upload as a
 * sequence of steps, each in its focused class. It holds no transaction itself: the Postgres
 * writes run in {@link OntologyUploadTransactions}, so no connection is held while the file is
 * parsed, NKD is queried or the graph is written.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OntologyUploadServiceImpl implements OntologyUploadService {

    private final UploadFileReader fileReader;
    private final UploadVocabularyLock vocabularyLock;
    private final UploadConceptRoleResolver roleResolver;
    private final UploadConceptGate uploadConceptGate;
    private final PublishedResourceUtil deviationChecker;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final UploadMetadataWriter metadataWriter;
    private final OntologyUploadTransactions transactions;
    private final UploadValidationRunner validationRunner;

    @Override
    public Lang determineRDFFormat(MultipartFile file) {
        return fileReader.determineRDFFormat(file);
    }

    @Override
    @CacheEvict(cacheNames = {ReferencedConceptResolutionEngine.CACHE_NAME,
            WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE}, allEntries = true)
    public OntologyUploadResultDto uploadFromFile(MultipartFile file, String userId,
                                                  NormalizeMode normalizeMode,
                                                  List<String> conceptsToNormalize) throws IOException, OntologyUploadException {
        OntModel finalModel = fileReader.read(file);
        UploadVocabularyLock.Claim claim = null;
        try {
            // The upload is stored as uploaded. Each step below is one class; nothing else repairs data.
            // 1. Trailing slashes off IRIs, so every later step sees the corrected ones.
            List<String> correctedIris = UploadIriNormalizer.normalize(finalModel);
            if (!correctedIris.isEmpty()) {
                log.info("Stripped the trailing slash from {} IRI(s): {}", correctedIris.size(), correctedIris);
            }

            // 2. The vocabulary IRI (graph name), read from the data.
            String graphName = UploadGraphNameResolver.resolve(finalModel);
            log.info("Uploading final model with {} statements to graph: {}", finalModel.size(), graphName);

            // One upload per vocabulary at a time, so the existence check below is reliable.
            claim = vocabularyLock.claim(graphName);
            metadataWriter.checkSlugUniqueness(graphName);

            // 3. skos:inScheme for owned concepts lacking it, only as the user decided (asks when undecided).
            Set<String> chosen = InSchemeStamping.chosen(finalModel, graphName, normalizeMode, conceptsToNormalize);
            InSchemeStamping.stamp(finalModel, graphName, chosen);

            // 4. Role markers derived from each concept's OWL kind (D4).
            RoleMarkerDerivation.apply(finalModel, graphName);

            // A concept typed as both a property and a relationship fails the whole upload.
            List<String> conflictingTypes = ConceptRoleConflict.findPropertyAndRelationship(finalModel);
            if (!conflictingTypes.isEmpty()) {
                throw new OntologyUploadConflictingTypesException(conflictingTypes);
            }

            // 5. Concept gate. A concept with no single role, one breaking a create/edit rule, one whose
            //    domain/range points at a concept published in NKD, or one failing the OFN shape or
            //    concept-kind tier is not imported: it loses skos:inScheme. A vocabulary that does not
            //    conform itself fails the whole upload. Incomplete concepts are kept as drafts.
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
            rejectedConcepts.addAll(uploadConceptGate.findNonConformant(finalModel, graphName));
            rejectedConcepts = UploadConceptGate.mergeByConcept(rejectedConcepts);
            roleResolver.excludeRejectedConcepts(finalModel, graphName, rejectedConcepts);

            // 6. Excluded concepts nothing references are removed, so "no PG row ⇒ no owned RDF" holds.
            ExcludedConceptPruning.apply(finalModel, graphName);

            // Serialized before anything is stored, so a failure here leaves no data behind.
            String ontologyContent = fileReader.convertOntModelToTtl(finalModel);

            // 5. Save RDF data to TDB2 first — if this fails, no metadata exists, clean exit
            try {
                jenaTDB2Repository.putOntologyModel(graphName, finalModel);
            } catch (Exception e) {
                throw new OntologyUploadRdfStoreException(
                        "Uložení slovníku do RDF úložiště (TDB2) selhalo: " + e.getMessage(), e);
            }

            // 6. Save metadata to PostgreSQL in one transaction. If it fails or does not commit,
            //    PostgreSQL holds nothing of this upload and the catch cleans up TDB2.
            OntologySummaryDto ontology;
            try {
                ontology = transactions.saveMetadata(finalModel, graphName, userId, publishedConceptIris);
            } catch (Exception e) {
                // A committed row for this graph belongs to a writer that got there first, and so
                // does the graph: it is left in place.
                if (!metadataWriter.graphHasMetadata(graphName)) {
                    try {
                        jenaTDB2Repository.deleteGraph(graphName);
                        log.info("Successfully rolled back TDB2 data for graph: {}", graphName);
                    } catch (Exception tdbException) {
                        log.error("Failed to rollback TDB2 data for graph: {}", graphName, tdbException);
                    }
                }
                // Reports a vocabulary created meanwhile as the duplicate it is.
                metadataWriter.checkSlugUniqueness(graphName);
                throw new OntologyUploadMetadataException(
                        "Uložení metadat slovníku selhalo: " + e.getMessage(), e);
            }

            // 7. Advisory validation, started once the metadata is committed.
            validationRunner.start(ontologyContent, graphName);

            return new OntologyUploadResultDto(ontology, rejectedConcepts, correctedIris);
        } finally {
            if (claim != null) {
                claim.close();
            }
            finalModel.close();
        }
    }
}
