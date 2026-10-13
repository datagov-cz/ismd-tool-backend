package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ontology rows of an ISMD search: the PG slug search, the Fuseki label search, counts and PG backfill. */
@Slf4j
@Component
class IsmdOntologySearch {

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final IsmdFusekiSearch fusekiSearch;
    private final VisibleGraphResolver visibleGraphs;
    private final Executor searchExecutor;
    private final long fusekiTimeoutMs;

    IsmdOntologySearch(OntologyMetadataRepository ontologyMetadataRepository,
                       ConceptMetadataRepository conceptMetadataRepository,
                       IsmdFusekiSearch fusekiSearch,
                       VisibleGraphResolver visibleGraphs,
                       @Qualifier("searchExecutor") Executor searchExecutor,
                       @Value("${search.fuseki-timeout-ms:10000}") long fusekiTimeoutMs) {
        this.ontologyMetadataRepository = ontologyMetadataRepository;
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.fusekiSearch = fusekiSearch;
        this.visibleGraphs = visibleGraphs;
        this.searchExecutor = searchExecutor;
        this.fusekiTimeoutMs = fusekiTimeoutMs;
    }

    List<SearchResultDto> searchOntologies(String query, Boolean publishedFilter) {
        List<OntologyMetadataEntity> entities;
        if (Boolean.FALSE.equals(publishedFilter)) {
            entities = ontologyMetadataRepository.searchByTextUnpublished(query);
        } else {
            entities = ontologyMetadataRepository.searchByText(query);
        }

        return entities.stream()
                .map(e -> SearchResultDto.builder()
                        .id(e.getId())
                        .iri(e.getGraphName())
                        .slug(e.getSlug())
                        .label(e.getSlug())
                        .type(SearchType.ONTOLOGY)
                        .source(SearchSource.ISMD)
                        .isPublished(e.getIsPublished())
                        .lastModified(e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null)
                        .build())
                .toList();
    }

    Integer countOntologyMatches(String query, Boolean publishedFilter) {
        try {
            long count = Boolean.FALSE.equals(publishedFilter)
                    ? ontologyMetadataRepository.countSearchByTextUnpublished(query)
                    : ontologyMetadataRepository.countSearchByText(query);
            return (int) count;
        } catch (RuntimeException e) {
            log.warn("PG ontology total-count failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fuseki-only path used when {@code type=ONTOLOGY}. Skips the PG concept query
     * (concepts can never satisfy an ontology-only request), skips relation-type
     * filtering (concept-only concept), and skips label enrichment (Fuseki already
     * returned labels). Caller's outer type filter then drops any concept-typed
     * Fuseki rows that snuck in alongside the ontology hits.
     */
    List<SearchResultDto> searchOntologyLabelsViaFuseki(String query, String userId,
                                                                  boolean isAdmin,
                                                                  Boolean publishedFilter,
                                                                  List<String> ontologyIris,
                                                                  AtomicBoolean fusekiDegraded,
                                                                  int limit) {
        GraphFilter filter = visibleGraphs.graphFilterFor(ontologyIris, userId, isAdmin, publishedFilter);

        try {
            return CompletableFuture.supplyAsync(
                            () -> fusekiSearch.searchFuseki(query, filter.searchGraphs(), limit, null), searchExecutor)
                    .orTimeout(fusekiTimeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Fuseki ontology label search failed: {}", e.getMessage());
            fusekiDegraded.set(true);
            return List.of();
        }
    }

    /**
     * Backfills PG-sourced fields on ontology rows that came from the Fuseki label
     * search. Those rows are built purely from RDF, so they carry no {@code id},
     * {@code slug} or {@code isPublished}. Without this an ontology found by label
     * (rather than by slug) reaches the FE with a null slug — which the FE turns
     * into a {@code /dictionary/null} link — plus a null id and an unknown publish
     * state, so it sorts with the drafts regardless of what it actually is.
     * <p>
     * Runs on the deduped set, so it covers every branch that can emit an ontology
     * row: the ONTOLOGY-only Fuseki path and the unfiltered path, where the
     * concept-side backfill cannot help (it resolves IRIs against
     * {@code concept_metadata}, which holds no row for an ontology).
     * <p>
     * Rows that already carry a PG id are skipped — they merged with a PG hit and
     * have their fields. Visibility is safe: every IRI here already came from a
     * graph the caller may see. Failure is non-fatal — rows keep their null fields
     * and search succeeds.
     */
    void backfillPgFieldsForOntologyRows(List<SearchResultDto> rows) {
        List<String> graphNames = rows.stream()
                .filter(r -> r.getType() == SearchType.ONTOLOGY && r.getId() == null)
                .map(SearchResultDto::getIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (graphNames.isEmpty()) return;

        Map<String, OntologyMetadataEntity> byGraph;
        try {
            byGraph = new HashMap<>();
            for (OntologyMetadataEntity e : ontologyMetadataRepository.findAllByGraphNameIn(graphNames)) {
                if (e.getGraphName() != null) byGraph.put(e.getGraphName(), e);
            }
        } catch (RuntimeException e) {
            log.warn("PG ontology backfill for {} Fuseki rows failed, leaving fields null: {}",
                    graphNames.size(), e.getMessage());
            return;
        }
        if (byGraph.isEmpty()) return;

        for (SearchResultDto dto : rows) {
            if (dto.getType() != SearchType.ONTOLOGY || dto.getIri() == null) continue;
            OntologyMetadataEntity e = byGraph.get(dto.getIri());
            if (e == null) continue;

            if (dto.getId() == null) dto.setId(e.getId());
            if (dto.getSlug() == null) dto.setSlug(e.getSlug());
            if (dto.getIsPublished() == null) dto.setIsPublished(e.getIsPublished());
            if (dto.getLastModified() == null && e.getUpdatedAt() != null) {
                dto.setLastModified(e.getUpdatedAt().toString());
            }
        }
    }

    void populateOntologyConceptCounts(List<SearchResultDto> paged) {
        List<String> graphNames = paged.stream()
                .filter(r -> r.getType() == SearchType.ONTOLOGY)
                .map(SearchResultDto::getIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (graphNames.isEmpty()) return;

        Map<String, Integer> counts;
        try {
            List<Object[]> rows = conceptMetadataRepository.countByGraphNameIn(graphNames);
            counts = new HashMap<>(rows.size() * 2);
            for (Object[] row : rows) {
                if (row.length >= 2 && row[0] != null && row[1] != null) {
                    counts.put(row[0].toString(), ((Number) row[1]).intValue());
                }
            }
        } catch (RuntimeException e) {
            log.warn("PG concept-count batch failed for ISMD search page, leaving counts null: {}",
                    e.getMessage());
            return;
        }

        for (SearchResultDto dto : paged) {
            if (dto.getType() == SearchType.ONTOLOGY && dto.getIri() != null) {
                dto.setConceptCount(counts.getOrDefault(dto.getIri(), 0));
            }
        }
    }
}
