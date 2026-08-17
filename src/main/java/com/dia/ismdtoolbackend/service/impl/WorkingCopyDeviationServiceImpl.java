package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.SnapshotOrigin;
import com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException;
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
        return deviationForWithLocal(conceptIri, null);
    }

    @Override
    public PublishedConceptDeviationModel deviationForWithLocal(String conceptIri, ConceptDetailModel local) {
        return compareAgainstNkd(conceptIri,
                local != null ? local : self.canonicalLocalConcept(conceptIri), null);
    }

    @Override
    public Map<String, PublishedConceptDeviationModel> deviationForAll(Model processedModel, List<String> conceptIris) {
        return deviationForAllInOntology(processedModel, conceptIris, null);
    }

    @Override
    public Map<String, PublishedConceptDeviationModel> deviationForAllInOntology(Model processedModel,
                                                                                  List<String> conceptIris,
                                                                                  String ontologyIri) {
        Map<String, ConceptDetailModel> locals = self.canonicalLocalConcepts(processedModel, conceptIris);
        Map<String, Optional<ConceptDetailModel>> prefetched = prefetchNkdSide(conceptIris, ontologyIri);

        Map<String, PublishedConceptDeviationModel> out = new LinkedHashMap<>();
        for (String conceptIri : conceptIris) {
            // enrich=false: reference resolution is deferred so all N deviations resolve in ONE
            // round-trip below, instead of one per concept.
            out.put(conceptIri, compareAgainstNkd(conceptIri, locals.get(conceptIri),
                    prefetched.get(conceptIri), false));
        }

        deviationEnricher.enrichAll(new ArrayList<>(out.values()));
        return out;
    }

    /**
     * Collapses the NKD side of a bulk check into one round-trip and seeds the per-IRI
     * {@code nkdPublishedResource} entries (so later single fetches hit cache).
     *
     * <p>When {@code ontologyIri} is known, that round-trip is the ontology CONSTRUCT the deviation
     * check already needs: it returns every in-scheme concept, so the concepts are sliced out of it
     * and the batched concept query is skipped entirely. Otherwise (or if the ontology model is
     * unavailable) the batched concept query runs as before.
     */
    private Map<String, Optional<ConceptDetailModel>> prefetchNkdSide(List<String> conceptIris, String ontologyIri) {
        Cache cache = cacheManager.getCache(NkdSparqlClient.PUBLISHED_RESOURCE_CACHE);
        List<String> misses = conceptIris.stream()
                .filter(iri -> cache == null || cache.get("concept:" + iri) == null)
                .distinct()
                .toList();
        if (misses.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, Optional<NkdSparqlClient.PublishedConcept>> fetched = fetchNkdSide(misses, ontologyIri);
            Map<String, Optional<ConceptDetailModel>> out = new LinkedHashMap<>();
            fetched.forEach((iri, published) -> {
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
     * The NKD side for {@code misses}, preferring the shared ontology model over a second round-trip.
     * A concept the ontology model does not carry (e.g. published under a different scheme) still
     * falls back to the batched query, so coverage never shrinks.
     */
    private Map<String, Optional<NkdSparqlClient.PublishedConcept>> fetchNkdSide(List<String> misses,
                                                                                 String ontologyIri) {
        if (ontologyIri == null || ontologyIri.isBlank()) {
            return nkdSparqlClient.fetchPublishedConceptsBatched(misses);
        }
        Optional<Model> ontologyModel = nkdSparqlClient.fetchPublishedOntologyRaw(ontologyIri);
        if (ontologyModel.isEmpty()) {
            log.debug("NKD ontology model unavailable for {}; using batched concept fetch", ontologyIri);
            return nkdSparqlClient.fetchPublishedConceptsBatched(misses);
        }

        Map<String, Optional<NkdSparqlClient.PublishedConcept>> derived =
                nkdSparqlClient.derivePublishedConceptsFromOntology(ontologyModel.get(), misses);

        // A concept absent from the scheme model is not proof it is absent from NKD — it may be
        // published under a different scheme. Ask the batched query about just those.
        List<String> unresolved = derived.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (!unresolved.isEmpty()) {
            log.debug("{} of {} concepts absent from NKD ontology model {}; querying those separately",
                    unresolved.size(), misses.size(), ontologyIri);
            Map<String, Optional<NkdSparqlClient.PublishedConcept>> out = new LinkedHashMap<>(derived);
            out.putAll(nkdSparqlClient.fetchPublishedConceptsBatched(unresolved));
            return out;
        }
        return derived;
    }

    /**
     * The one comparison both entry points run, so a bulk result and a per-IRI result for the same
     * concept are identical by construction. {@code local} is the canonical projection (may be null);
     * {@code prefetched} is the batched NKD result when the caller has one, else {@code null} to fetch
     * this concept on its own.
     */
    private PublishedConceptDeviationModel compareAgainstNkd(String conceptIri, ConceptDetailModel local,
                                                             Optional<ConceptDetailModel> prefetched) {
        return compareAgainstNkd(conceptIri, local, prefetched, true);
    }

    /**
     * {@code enrich=false} leaves reference resolution to the caller, which is what lets the bulk path
     * resolve every deviation's references in ONE {@code resolveAll} instead of one per concept.
     */
    private PublishedConceptDeviationModel compareAgainstNkd(String conceptIri, ConceptDetailModel local,
                                                             Optional<ConceptDetailModel> prefetched,
                                                             boolean enrich) {
        if (local == null) {
            return error(DeviationStatus.QUERY_ERROR, "Local concept detail not available", conceptIri);
        }
        try {
            // A non-null prefetched is authoritative, including when it is empty (absent from NKD).
            Optional<ConceptDetailModel> publishedOpt =
                    prefetched != null ? prefetched : nkdSparqlClient.fetchPublishedConcept(conceptIri);
            if (publishedOpt.isEmpty()) {
                log.warn("Published concept not found in NKD: {}", conceptIri);
                return error(DeviationStatus.CONCEPT_NOT_FOUND_IN_NKD,
                        "Concept not found in NKD SPARQL endpoint", conceptIri);
            }
            // WORKING_COPY: this concept's own IRI is the NKD twin it is compared against.
            PublishedConceptDeviationModel deviation = conceptDeviationComparator.compareConceptDetails(
                    local, publishedOpt.get(), SnapshotOrigin.WORKING_COPY, conceptIri);
            if (enrich) {
                deviationEnricher.enrich(deviation);
            }
            return deviation;
        } catch (SparqlEndpointUnavailableException e) {
            log.error("NKD unavailable while checking working-copy deviation for {}: {}", conceptIri, e.getMessage());
            return error(DeviationStatus.ENDPOINT_UNAVAILABLE,
                    "NKD SPARQL endpoint unavailable: " + e.getMessage(), conceptIri);
        } catch (Exception e) {
            // Not an upstream outage: a bug or bad local data. QUERY_ERROR keeps the comparison untrusted
            // (no snapshot actions offered) without blaming NKD for a fault on our side.
            log.error("Failed to compute working-copy deviation for {}: {}", conceptIri, e.getMessage(), e);
            return error(DeviationStatus.QUERY_ERROR,
                    "Deviation check failed: " + e.getMessage(), conceptIri);
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
        return projectLocalConcept(conceptIri, rawModel);
    }

    /**
     * Same projection, same cache entry, but off a graph the caller already fetched — so a request that
     * has just read this graph does not read it from Fuseki a second time.
     */
    @Override
    @Cacheable(cacheNames = LOCAL_CONCEPT_PROJECTION_CACHE, key = "#conceptIri")
    public ConceptDetailModel canonicalLocalConcept(String conceptIri, Model rawModel) {
        if (rawModel == null || rawModel.isEmpty()) {
            return null;
        }
        return projectLocalConcept(conceptIri, rawModel);
    }

    /**
     * The transform+extract half of the canonical read, shared by both overloads so the projection is
     * identical however the graph was obtained.
     */
    private ConceptDetailModel projectLocalConcept(String conceptIri, Model rawModel) {
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