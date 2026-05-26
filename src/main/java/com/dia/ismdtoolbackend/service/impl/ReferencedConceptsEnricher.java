package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.controller.dto.ResolvedConceptDto;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inline-enriches a {@link OntologyDetailModel.ConceptDetailModel} with a single
 * map of every referenced-concept IRI → {@link ResolvedConceptDto}.
 *
 * <p>Shared by the ISMD concept-detail and NKD concept-detail paths so both
 * stay in lockstep on which fields are considered "referenced IRIs" and on the
 * dedupe/batching semantics. Delegates the heavy lifting to
 * {@link ConceptMetadataResolver}, which already handles input sanitisation,
 * cache-first lookup (24h Caffeine, per-IRI key), batched ISMD CONSTRUCT,
 * batched NKD CONSTRUCT, and Postgres slug enrichment.
 *
 * <p>Cache-warm requests pay zero new I/O. Cold requests pay at most one
 * ISMD CONSTRUCT + one NKD CONSTRUCT + one Postgres {@code findByConceptIriIn},
 * regardless of how many referenced fields the concept has.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReferencedConceptsEnricher {

    private final ConceptMetadataResolver conceptMetadataResolver;

    public void enrich(OntologyDetailModel.ConceptDetailModel detail) {
        if (detail == null) {
            return;
        }

        Set<String> iris = collectReferencedIris(detail);
        if (iris.isEmpty()) {
            return;
        }

        Map<String, ResolvedConceptDto> resolved =
                conceptMetadataResolver.resolveAll(new ArrayList<>(iris));
        detail.setReferencedConceptsResolved(resolved);

        if (log.isInfoEnabled()) {
            long ismd = resolved.values().stream()
                    .filter(d -> d.source() == SearchSource.ISMD)
                    .count();
            long nkd = resolved.values().stream()
                    .filter(d -> d.source() == SearchSource.NKD)
                    .count();
            log.info("Enriched concept detail references: requested={}, ismd={}, nkd={}, unresolved={}",
                    iris.size(), ismd, nkd, iris.size() - resolved.size());
        }
    }

    private static Set<String> collectReferencedIris(OntologyDetailModel.ConceptDetailModel detail) {
        Set<String> iris = new LinkedHashSet<>();
        addAll(iris, detail.getExactMatches());
        addAll(iris, detail.getBroaderClasses());
        addAll(iris, detail.getBroaderRelations());
        addAll(iris, detail.getBroaderProperties());
        add(iris, detail.getDomain());
        add(iris, detail.getRange());
        if (detail.getConceptProperties() != null) {
            for (ConceptPropertiesModel p : detail.getConceptProperties()) {
                add(iris, p.getIri());
            }
        }
        if (detail.getConceptRelationships() != null) {
            for (ConceptRelationshipsModel r : detail.getConceptRelationships()) {
                add(iris, r.getIri());
            }
        }
        return iris;
    }

    private static void addAll(Set<String> sink, Collection<String> source) {
        if (source == null) return;
        for (String s : source) add(sink, s);
    }

    private static void add(Set<String> sink, String iri) {
        if (iri != null && !iri.isBlank()) sink.add(iri);
    }
}
