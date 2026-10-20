package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Concept rows of an ISMD search: PG and Fuseki in parallel, merged by IRI, backfilled and filtered. */
@Slf4j
@Component
class IsmdConceptSearch {

    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final IsmdFusekiSearch fusekiSearch;
    private final VisibleGraphResolver visibleGraphs;
    private final Executor searchExecutor;
    private final long fusekiTimeoutMs;
    private final long pgTimeoutMs;

    IsmdConceptSearch(ConceptMetadataRepository conceptMetadataRepository,
                      JenaTDB2Repository jenaTDB2Repository,
                      IsmdFusekiSearch fusekiSearch,
                      VisibleGraphResolver visibleGraphs,
                      @Qualifier("searchExecutor") Executor searchExecutor,
                      @Value("${search.fuseki-timeout-ms:10000}") long fusekiTimeoutMs,
                      @Value("${search.pg-timeout-ms:10000}") long pgTimeoutMs) {
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.fusekiSearch = fusekiSearch;
        this.visibleGraphs = visibleGraphs;
        this.searchExecutor = searchExecutor;
        this.fusekiTimeoutMs = fusekiTimeoutMs;
        this.pgTimeoutMs = pgTimeoutMs;
    }

    List<SearchResultDto> searchConcepts(String query, String userId,
                                                  boolean isAdmin, Boolean publishedFilter,
                                                  String lang,
                                                  List<String> ontologyIris,
                                                  List<RelationType> relationTypes,
                                                  AtomicBoolean fusekiDegraded, int limit,
                                                  SearchType type) {
        GraphFilter filter = visibleGraphs.graphFilterFor(ontologyIris, userId, isAdmin, publishedFilter);

        ParallelSearchResults raw = runParallelConceptSearch(
                query, publishedFilter, filter, limit, fusekiDegraded, type);

        List<SearchResultDto> merged = mergeByIri(raw.pg(), raw.fuseki());
        backfillPgIdsForFusekiOnlyRows(merged);
        enrichPgOnlyLabels(merged, raw.fusekiIris(), lang, fusekiDegraded);
        return applyRelationTypeFilter(merged, relationTypes);
    }

    Integer countConceptMatches(String query, Boolean publishedFilter,
                                         List<String> ontologyIris, SearchType type) {
        try {
            boolean hasGraphFilter = ontologyIris != null && !ontologyIris.isEmpty();
            List<String> graphNames = hasGraphFilter ? ontologyIris : List.of();
            ConceptType roleFilter = type != null ? type.toConceptType() : null;
            boolean hasTypeFilter = roleFilter != null;
            String conceptTypeName = roleFilter != null ? roleFilter.name() : null;
            long count = Boolean.FALSE.equals(publishedFilter)
                    ? conceptMetadataRepository.countSearchByTextUnpublished(
                            query, hasGraphFilter, graphNames,
                            hasTypeFilter, conceptTypeName)
                    : conceptMetadataRepository.countSearchByText(
                            query, hasGraphFilter, graphNames,
                            hasTypeFilter, conceptTypeName);
            return (int) count;
        } catch (RuntimeException e) {
            log.warn("PG concept total-count failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Runs the PG and Fuseki concept-side queries in parallel on the dedicated
     * search executor (both are blocking I/O: JDBC + HTTP). Each future has its
     * own timeout so a slow PG query cannot extend a fast Fuseki timeout.
     * <p>
     * Failure of either side is tolerated: PG failure → Fuseki-only results;
     * Fuseki failure → PG-only results AND {@code fusekiDegraded} flipped to
     * true so the outer response carries {@link SearchSourceStatus#DEGRADED}.
     */
    ParallelSearchResults runParallelConceptSearch(String query, Boolean publishedFilter,
                                                            GraphFilter filter, int limit,
                                                            AtomicBoolean fusekiDegraded,
                                                            SearchType type) {
        boolean unpublishedOnly = Boolean.FALSE.equals(publishedFilter);
        ConceptType roleFilter = type != null ? type.toConceptType() : null;
        boolean hasTypeFilter = roleFilter != null;
        String conceptTypeName = roleFilter != null ? roleFilter.name() : null;

        CompletableFuture<List<SearchResultDto>> pgFuture = CompletableFuture.supplyAsync(() -> {
            List<ConceptMetadataEntity> entities = unpublishedOnly
                    ? conceptMetadataRepository.searchByTextUnpublished(
                            query, filter.hasGraphFilter(), filter.graphNames(),
                            hasTypeFilter, conceptTypeName)
                    : conceptMetadataRepository.searchByText(
                            query, filter.hasGraphFilter(), filter.graphNames(),
                            hasTypeFilter, conceptTypeName);
            return entities.stream()
                    .map(this::mapConceptEntity)
                    .toList();
        }, searchExecutor).orTimeout(pgTimeoutMs, TimeUnit.MILLISECONDS);

        CompletableFuture<List<SearchResultDto>> fusekiFuture = CompletableFuture.supplyAsync(
                        () -> fusekiSearch.searchFuseki(query, filter.searchGraphs(), limit, roleFilter), searchExecutor)
                .orTimeout(fusekiTimeoutMs, TimeUnit.MILLISECONDS);

        List<SearchResultDto> pgResults;
        try {
            pgResults = pgFuture.join();
            log.debug("PG search returned {} results", pgResults.size());
        } catch (Exception e) {
            log.warn("PG text search failed, returning Fuseki-only results: {}", e.getMessage());
            pgResults = List.of();
        }

        List<SearchResultDto> fusekiResults;
        try {
            fusekiResults = fusekiFuture.join();
            log.debug("Fuseki search returned {} results", fusekiResults.size());
        } catch (Exception e) {
            log.warn("Fuseki text search failed, degrading to PG-only results: {}", e.getMessage());
            fusekiResults = List.of();
            fusekiDegraded.set(true);
        }

        Set<String> fusekiIris = new HashSet<>();
        for (SearchResultDto dto : fusekiResults) {
            if (dto.getIri() != null) fusekiIris.add(dto.getIri());
        }

        return new ParallelSearchResults(pgResults, fusekiResults, fusekiIris);
    }

    /**
     * Merges PG and Fuseki results by IRI: PG first, Fuseki enriches with
     * non-null fields. {@code matchedBy} starts as PG, becomes BOTH when a
     * Fuseki row joins. Insertion order preserved via {@link LinkedHashMap}.
     */
    List<SearchResultDto> mergeByIri(List<SearchResultDto> pg, List<SearchResultDto> fuseki) {
        LinkedHashMap<String, SearchResultDto> merged = new LinkedHashMap<>();
        for (SearchResultDto dto : pg) {
            if (dto.getIri() != null) {
                dto.setMatchedBy(MatchedBy.PG);
                merged.put(dto.getIri(), dto);
            }
        }
        for (SearchResultDto dto : fuseki) {
            if (dto.getIri() != null) {
                merged.merge(dto.getIri(), dto, (existing, incoming) -> {
                    SearchResultMerge.mergeNonNullFields(existing, incoming);
                    existing.setMatchedBy(MatchedBy.BOTH);
                    return existing;
                });
            }
        }

        List<SearchResultDto> mergedResults = new ArrayList<>(merged.values());
        log.debug("After merge+dedup: {} results (PG={}, Fuseki={}, unique={})",
                mergedResults.size(), pg.size(), fuseki.size(), merged.size());
        return mergedResults;
    }

    /**
     * Backfills the Postgres {@code id} (and other PG-sourced metadata) on rows that
     * came back Fuseki-only ({@code matchedBy=SPARQL}, so {@code id == null}). A
     * concept can be Fuseki-only here even when a {@code concept_metadata} row
     * exists: PG text search matches on {@code concept_name}/{@code slug}, so a hit
     * that only matched on {@code definition}/{@code description} in the Fuseki text
     * index would never have been returned by the PG side. One batched lookup by
     * IRI closes that gap.
     * <p>
     * Visibility is safe: every IRI reaching the merge already lives in a graph the
     * caller may see (Fuseki was scoped to {@code visibleGraphNames}), so fetching
     * the same IRI's PG row exposes nothing new. Failure is non-fatal — rows keep
     * their null id and the search still succeeds.
     */
    void backfillPgIdsForFusekiOnlyRows(List<SearchResultDto> merged) {
        List<String> orphanIris = merged.stream()
                .filter(dto -> dto.getId() == null && dto.getIri() != null)
                .map(SearchResultDto::getIri)
                .distinct()
                .toList();
        if (orphanIris.isEmpty()) return;

        Map<String, ConceptMetadataEntity> byIri;
        try {
            List<ConceptMetadataEntity> rows = conceptMetadataRepository.findByConceptIriIn(orphanIris);
            byIri = new HashMap<>(rows.size() * 2);
            for (ConceptMetadataEntity e : rows) {
                if (e.getConceptIri() != null) byIri.put(e.getConceptIri(), e);
            }
        } catch (RuntimeException e) {
            log.warn("PG id backfill for {} Fuseki-only rows failed, leaving ids null: {}",
                    orphanIris.size(), e.getMessage());
            return;
        }
        if (byIri.isEmpty()) return;

        for (SearchResultDto dto : merged) {
            if (dto.getId() != null || dto.getIri() == null) continue;
            ConceptMetadataEntity e = byIri.get(dto.getIri());
            if (e == null) continue;

            dto.setId(e.getId());
            if (dto.getSlug() == null) dto.setSlug(e.getSlug());
            if (dto.getConceptType() == null) dto.setConceptType(e.getConceptType());
            if (dto.getOntologyIri() == null) dto.setOntologyIri(e.getGraphName());
            if (dto.getIsPublished() == null) dto.setIsPublished(e.getIsPublished());
            if (dto.getLastModified() == null && e.getUpdatedAt() != null) {
                dto.setLastModified(e.getUpdatedAt().toString());
            }
        }
        log.debug("PG id backfill: matched {}/{} Fuseki-only IRIs to PG rows",
                byIri.size(), orphanIris.size());
    }

    /**
     * Enriches PG-only rows (those NOT in {@code fusekiIris}) with labels from
     * Fuseki. Skipped when Fuseki is degraded — no point making the failing
     * service answer one more query. Failure here is non-fatal: existing labels
     * stay, search succeeds.
     */
    void enrichPgOnlyLabels(List<SearchResultDto> merged, Set<String> fusekiIris,
                                     String lang, AtomicBoolean fusekiDegraded) {
        if (fusekiDegraded.get() || merged.isEmpty()) return;

        List<SearchResultDto> needsEnrichment = merged.stream()
                .filter(dto -> !fusekiIris.contains(dto.getIri()))
                .toList();
        if (needsEnrichment.isEmpty()) return;

        try {
            log.debug("Enriching {} PG-only results with Fuseki labels (lang={})",
                    needsEnrichment.size(), lang);
            fusekiSearch.enrichWithLabels(needsEnrichment, lang);
        } catch (Exception e) {
            log.warn("Fuseki label enrichment failed, using existing labels: {}", e.getMessage());
        }
    }

    /**
     * Optional Fuseki-side post-filter by relation type (subClassOf, exactMatch,
     * etc.). Failure is non-fatal: returns the unfiltered list rather than
     * blanking the page.
     */
    List<SearchResultDto> applyRelationTypeFilter(List<SearchResultDto> mergedResults,
                                                           List<RelationType> relationTypes) {
        if (relationTypes == null || relationTypes.isEmpty() || mergedResults.isEmpty()) {
            return mergedResults;
        }
        try {
            List<String> conceptIris = mergedResults.stream()
                    .map(SearchResultDto::getIri)
                    .toList();
            String safeRelationTypes = relationTypes.stream()
                    .filter(Objects::nonNull)
                    .map(RelationType::name)
                    .collect(java.util.stream.Collectors.joining(","));
            log.debug("Filtering {} concepts by relation types: {}", conceptIris.size(), safeRelationTypes);
            Set<String> matchingIris = jenaTDB2Repository.filterByRelationTypes(conceptIris, relationTypes);
            int beforeFilter = mergedResults.size();
            List<SearchResultDto> filtered = mergedResults.stream()
                    .filter(dto -> matchingIris.contains(dto.getIri()))
                    .toList();
            log.debug("Relation type filter: {} -> {} results", beforeFilter, filtered.size());
            return filtered;
        } catch (Exception e) {
            log.warn("Fuseki relation type filtering failed, returning unfiltered results: {}", e.getMessage());
            return mergedResults;
        }
    }

    SearchResultDto mapConceptEntity(ConceptMetadataEntity e) {
        return SearchResultDto.builder()
                .id(e.getId())
                .iri(e.getConceptIri())
                .slug(e.getSlug())
                .label(e.getConceptName())
                .type(SearchType.CONCEPT)
                .source(SearchSource.ISMD)
                .conceptType(e.getConceptType())
                .ontologyIri(e.getGraphName())
                .isPublished(e.getIsPublished())
                .lastModified(e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null)
                .build();
    }

    /** Joined output of the parallel PG + Fuseki concept search. */
    private record ParallelSearchResults(List<SearchResultDto> pg,
                                          List<SearchResultDto> fuseki,
                                          Set<String> fusekiIris) {}
}
