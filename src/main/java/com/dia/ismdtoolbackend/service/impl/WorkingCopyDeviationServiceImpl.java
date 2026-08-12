package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel;
import com.dia.ismdtoolbackend.models.concept.PublishedConceptDeviationModel.DeviationStatus;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.service.WorkingCopyDeviationService;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single source of truth for a working copy's deviation against its NKD twin, so ontology detail and
 * concept detail can never show conflicting results.
 *
 * <p>The deviation is derived from two inputs, and each is cached independently rather than the
 * deviation result itself (a two-tier decomposition): the <b>canonical local projection</b>
 * ({@link #canonicalLocalConcept}, this cache) and the <b>NKD projection</b>
 * ({@link NkdSparqlClient#fetchPublishedConcept}). Both surfaces read the same two cached inputs and run
 * the cheap comparison live, so they produce identical output by construction — there is no cached
 * result to fall out of sync. Resolved navigation metadata is attached by {@link DeviationResolutionEnricher}.
 */
@Service
@Slf4j
public class WorkingCopyDeviationServiceImpl implements WorkingCopyDeviationService {

    public static final String LOCAL_CONCEPT_PROJECTION_CACHE = "workingCopyLocalProjection";

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyDetailExtractor detailExtractor;
    private final ConceptDeviationComparator conceptDeviationComparator;
    private final NkdSparqlClient nkdSparqlClient;
    private final DeviationResolutionEnricher deviationEnricher;
    private final CacheManager cacheManager;

    // Self-reference through the Spring proxy so canonicalLocalConcept's @Cacheable is honoured when called from deviationFor
    private final WorkingCopyDeviationService self;

    public WorkingCopyDeviationServiceImpl(ConceptMetadataRepository conceptMetadataRepository,
                                           JenaTDB2Repository jenaTDB2Repository,
                                           OntologyDetailExtractor detailExtractor,
                                           ConceptDeviationComparator conceptDeviationComparator,
                                           NkdSparqlClient nkdSparqlClient,
                                           DeviationResolutionEnricher deviationEnricher,
                                           CacheManager cacheManager,
                                           @Lazy WorkingCopyDeviationService self) {
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.detailExtractor = detailExtractor;
        this.conceptDeviationComparator = conceptDeviationComparator;
        this.nkdSparqlClient = nkdSparqlClient;
        this.deviationEnricher = deviationEnricher;
        this.cacheManager = cacheManager;
        this.self = self;
    }

    /**
     * The working-copy deviation for {@code conceptIri}: compare the canonical local projection against
     * the live (cached) NKD twin. Both inputs are cached, the comparison is live
     */
    @Override
    public PublishedConceptDeviationModel deviationFor(String conceptIri) {
        return compareAgainstNkd(conceptIri, self.canonicalLocalConcept(conceptIri), null);
    }

    @Override
    public Map<String, PublishedConceptDeviationModel> deviationForAll(Model processedModel, List<String> conceptIris) {
        Map<String, ConceptDetailModel> locals = self.canonicalLocalConcepts(processedModel, conceptIris);
        Map<String, Optional<ConceptDetailModel>> prefetched = prefetchNkdSide(conceptIris);
        Map<String, PublishedConceptDeviationModel> out = new LinkedHashMap<>();
        for (String conceptIri : conceptIris) {
            out.put(conceptIri, compareAgainstNkd(conceptIri, locals.get(conceptIri),
                    prefetched.get(conceptIri)));
        }
        return out;
    }

    /**
     * Collapses the NKD side of a bulk check into one round-trip: fetches every not-yet-cached concept
     * in a single batched CONSTRUCT, seeds the per-IRI {@code nkdPublishedResource} entries (so later
     * single fetches hit cache) and returns the results for immediate use.
     */
    private Map<String, Optional<ConceptDetailModel>> prefetchNkdSide(List<String> conceptIris) {
        Cache cache = cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE);
        List<String> misses = conceptIris.stream()
                .filter(iri -> cache == null || cache.get("concept:" + iri) == null)
                .distinct()
                .toList();
        if (misses.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Optional<ConceptDetailModel>> out = new LinkedHashMap<>();
            nkdSparqlClient.fetchPublishedConceptsBatched(misses)
                    .forEach((iri, published) -> {
                        Optional<ConceptDetailModel> detail =
                                published.map(NkdSparqlClient.PublishedConcept::detail);
                        if (cache != null) {
                            // Mirror both keys the per-IRI methods would have written.
                            cache.put("concept:" + iri, detail);
                            cache.put("conceptWithScheme:" + iri, published);
                        }
                        out.put(iri, detail);
                    });
            return out;
        } catch (Exception e) {
            log.warn("Batched NKD prefetch failed for {} concepts; falling back to per-concept fetch: {}",
                    misses.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * The one comparison both entry points run, so a bulk result and a per-IRI result for the same
     * concept are identical by construction. {@code local} is the canonical projection (may be null);
     * {@code prefetched} is the batched NKD result when the caller has one, else {@code null} to fetch
     * this concept on its own.
     */
    private PublishedConceptDeviationModel compareAgainstNkd(String conceptIri, ConceptDetailModel local,
                                                             Optional<ConceptDetailModel> prefetched) {
        if (local == null) {
            return error(DeviationStatus.QUERY_ERROR, "Local concept detail not available", conceptIri);
        }
        try {
            Optional<ConceptDetailModel> publishedOpt =
                    prefetched.isPresent() ? prefetched : nkdSparqlClient.fetchPublishedConcept(conceptIri);
            if (publishedOpt.isEmpty()) {
                log.warn("Published concept not found in NKD: {}", conceptIri);
                return error(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD,
                        "Concept not found in NKD SPARQL endpoint", conceptIri);
            }
            // WORKING_COPY: this concept's own IRI is the NKD twin it is compared against.
            PublishedConceptDeviationModel deviation = conceptDeviationComparator.compareConceptDetails(
                    local, publishedOpt.get(), SnapshotOrigin.WORKING_COPY, conceptIri);
            deviationEnricher.enrich(deviation);
            return deviation;
        } catch (Exception e) {
            log.error("Error checking working-copy deviation for {}: {}", conceptIri, e.getMessage(), e);
            return error(DeviationStatus.ENDPOINT_UNAVAILABLE,
                    "NKD SPARQL endpoint unavailable: " + e.getMessage(), conceptIri);
        }
    }

    /**
     * The one canonical way the local side of a working-copy comparison is read: the concept's graph,
     * OFN-transformed, then extracted. Cached per {@code conceptIri} so repeated deviation checks share it.
     * Returns {@code null} when the concept has no metadata row or its graph is empty.
     */
    @Override
    @Cacheable(cacheNames = LOCAL_CONCEPT_PROJECTION_CACHE, key = "#conceptIri")
    public ConceptDetailModel canonicalLocalConcept(String conceptIri) {
        Optional<ConceptMetadataEntity> metadataOpt = conceptMetadataRepository.findByConceptIri(conceptIri);
        if (metadataOpt.isEmpty()) {
            return null;
        }
        String graphName = metadataOpt.get().getGraphName();
        Model rawModel = jenaTDB2Repository.fetchGraph(graphName);
        if (rawModel.isEmpty()) {
            return null;
        }
        Model processedModel = detailExtractor.applyOFNTransformations(rawModel);
        return detailExtractor.extractConceptDetail(processedModel, conceptIri);
    }

    /**
     * Bulk canonical local read. Serves whatever {@link #canonicalLocalConcept} already cached, then
     * extracts only the misses from the caller's already-transformed model in one pass and caches them
     * under the same per-IRI keys.
     */
    @Override
    public Map<String, ConceptDetailModel> canonicalLocalConcepts(Model processedModel, List<String> conceptIris) {
        Cache cache = cacheManager.getCache(LOCAL_CONCEPT_PROJECTION_CACHE);
        Map<String, ConceptDetailModel> out = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();

        for (String conceptIri : conceptIris) {
            Cache.ValueWrapper hit = (cache == null) ? null : cache.get(conceptIri);
            if (hit != null) {
                // A cached null is a real answer (no metadata row / empty graph), not a miss.
                out.put(conceptIri, (ConceptDetailModel) hit.get());
            } else {
                misses.add(conceptIri);
            }
        }

        if (!misses.isEmpty()) {
            Map<String, ConceptDetailModel> extracted = detailExtractor.extractConceptDetails(processedModel, misses);
            for (String conceptIri : misses) {
                ConceptDetailModel model = extracted.get(conceptIri);
                if (cache != null) {
                    cache.put(conceptIri, model);
                }
                out.put(conceptIri, model);
            }
        }
        return out;
    }

    private PublishedConceptDeviationModel error(DeviationStatus status, String message, String conceptIri) {
        return PublishedConceptDeviationModel.builder()
                .status(status)
                .origin(SnapshotOrigin.WORKING_COPY)
                .source(com.dia.ismdtoolbackend.controller.dto.NkdConceptRefDto.builder().iri(conceptIri).build())
                .errorMessage(message)
                .build();
    }
}