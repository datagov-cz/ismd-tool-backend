package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.utility.detail.OntologyDetailExtractor;
import lombok.RequiredArgsConstructor;
import org.apache.jena.rdf.model.Model;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.ismdtoolbackend.service.impl.WorkingCopyDeviationServiceImpl.LOCAL_CONCEPT_PROJECTION_CACHE;

/** Reads the canonical local side of a working-copy comparison, cached per concept IRI. */
@Component
@RequiredArgsConstructor
class LocalConceptProjectionReader {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final OntologyDetailExtractor detailExtractor;
    private final CacheManager cacheManager;

    /**
     * The one canonical way the local side of a working-copy comparison is read: the concept's graph,
     * OFN-transformed, then extracted. Cached per {@code conceptIri} so repeated deviation checks share it.
     * Returns {@code null} when the concept has no metadata row or its graph is empty.
     */
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
}
