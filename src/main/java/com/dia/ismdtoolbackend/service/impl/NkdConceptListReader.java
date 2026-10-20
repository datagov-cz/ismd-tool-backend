package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dia.ismdtoolbackend.service.impl.NkdListItemMeta.DEFAULT_LANG;

/** Lists the concepts of an NKD ontology. */
@Component
@RequiredArgsConstructor
@Slf4j
class NkdConceptListReader {

    private final NkdSparqlClient nkdSparqlClient;
    private final NkdRequestGuard guard;

    /**
     * Shares {@link NkdSparqlClient#PUBLISHED_RESOURCE_CACHE} with the other NKD-published
     * projections — same source, same 24h-TTL freshness model — under its own key prefix, alongside
     * the client's {@code concept:} / {@code ontology:} keys.
     *
     * <p>Caching is not optional here: the previous implementation reached NKD through the
     * {@code @Cacheable} {@code fetchPublishedOntology}, so dropping to a raw SELECT made the query
     * cheaper but sent every warm request to NKD live (measured: 2ms → ~1s).
     */
    @Cacheable(cacheNames = NkdSparqlClient.PUBLISHED_RESOURCE_CACHE,
            key = "'conceptList:' + #ontologyIri")
    List<MinimalConceptDto> listOntologyConcepts(String ontologyIri) {
        guard.validateIri(ontologyIri);
        guard.ensureEndpointConfigured();

        List<Map<String, String>> rows;
        try {
            rows = nkdSparqlClient.executeSelect(
                    NKDSPARQLBrowseQuery.buildOntologyConceptsQuery(ontologyIri, DEFAULT_LANG));
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while listing concepts of {}: {}", ontologyIri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        List<MinimalConceptDto> concepts = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            String iri = row.get("concept");
            if (iri == null) {
                continue;
            }
            String label = row.get("label");
            concepts.add(MinimalConceptDto.builder()
                    .iri(iri)
                    // NKD concepts have no local slug — the FE deep-links via IRI only.
                    .name(label == null || label.isBlank() ? Map.of() : Map.of(DEFAULT_LANG, label))
                    .conceptType(conceptTypeFromRoleMarkers(row))
                    .build());
        }
        log.debug("Listed {} NKD concepts for ontology {}", concepts.size(), ontologyIri);
        return concepts;
    }

    /**
     * Role markers are three independent OPTIONAL binds, so a concept tagged as more than one role
     * resolves in this fixed order — matching {@code NkdSearchProvider}. Null when NKD publishes no
     * recognizable role.
     */
    private ConceptType conceptTypeFromRoleMarkers(Map<String, String> row) {
        if (row.get("roleTrida") != null) return ConceptType.TRIDA;
        if (row.get("roleVlastnost") != null) return ConceptType.VLASTNOST;
        if (row.get("roleVztah") != null) return ConceptType.VZTAH;
        return null;
    }
}
