package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.entity.OntologyMetadataEntity;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.MatchedBy;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.repository.JenaTDB2Repository;
import com.dia.ismdtoolbackend.repository.OntologyMetadataRepository;
import com.dia.ismdtoolbackend.service.OntologyLabelLookup;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class IsmdSearchProvider implements SearchProvider {

    private static final String SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String SKOS_ALT_LABEL = "http://www.w3.org/2004/02/skos/core#altLabel";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String SKOS_DEFINITION = "http://www.w3.org/2004/02/skos/core#definition";
    private static final String SKOS_CONCEPT_SCHEME = "http://www.w3.org/2004/02/skos/core#ConceptScheme";
    private static final String OWL_ONTOLOGY = "http://www.w3.org/2002/07/owl#Ontology";

    /**
     * Total order applied to the merged result set before the page slice.
     * <p>
     * Ordering, outermost key first:
     * <ol>
     *   <li>drafts before published — an unpublished row is the one the author is
     *       actively working on, and is the row most likely to be looked for;</li>
     *   <li>ontologies before concepts — an ontology hit is the broader container
     *       and orients the user before its individual concepts;</li>
     *   <li>most recently modified first, then IRI — recency is the useful tiebreak,
     *       and IRI makes the order total so pagination never repeats or skips a row
     *       when two rows share a timestamp.</li>
     * </ol>
     * Null publish state sorts with drafts and null timestamps sort last, so a row
     * with incomplete metadata is never silently pushed off the page.
     */
    static final Comparator<SearchResultDto> RESULT_ORDER =
            Comparator.<SearchResultDto, Boolean>comparing(
                            r -> Boolean.TRUE.equals(r.getIsPublished()))
                    .thenComparing(r -> r.getType() != SearchType.ONTOLOGY)
                    .thenComparing(SearchResultDto::getLastModified,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(SearchResultDto::getIri,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private final OntologyMetadataRepository ontologyMetadataRepository;
    private final ConceptMetadataRepository conceptMetadataRepository;
    private final JenaTDB2Repository jenaTDB2Repository;
    private final DiagramSearchLookup diagramSearchLookup;
    private final OntologyLabelLookup ontologyLabelLookup;
    private final Executor searchExecutor;
    private final long fusekiTimeoutMs;
    private final long pgTimeoutMs;

    public IsmdSearchProvider(OntologyMetadataRepository ontologyMetadataRepository,
                              ConceptMetadataRepository conceptMetadataRepository,
                              JenaTDB2Repository jenaTDB2Repository,
                              DiagramSearchLookup diagramSearchLookup,
                              OntologyLabelLookup ontologyLabelLookup,
                              @Qualifier("searchExecutor") Executor searchExecutor,
                              @Value("${search.fuseki-timeout-ms:10000}") long fusekiTimeoutMs,
                              @Value("${search.pg-timeout-ms:10000}") long pgTimeoutMs) {
        this.ontologyMetadataRepository = ontologyMetadataRepository;
        this.conceptMetadataRepository = conceptMetadataRepository;
        this.jenaTDB2Repository = jenaTDB2Repository;
        this.diagramSearchLookup = diagramSearchLookup;
        this.ontologyLabelLookup = ontologyLabelLookup;
        this.searchExecutor = searchExecutor;
        this.fusekiTimeoutMs = fusekiTimeoutMs;
        this.pgTimeoutMs = pgTimeoutMs;
    }

    @Override
    public SearchProviderResult search(String query, SearchType type, int limit, int offset,
                                       String lang, List<String> ontologyIris,
                                       List<RelationType> relationTypes, String userId,
                                       boolean isAdmin, Boolean publishedFilter) {
        // Defense in depth: SearchServiceImpl.resolveSource is the primary chokepoint
        // that rejects anonymous UNPUBLISHED requests, but a future refactor of that
        // method must not be allowed to leak unpublished content from other users.
        // An anonymous, non-admin caller has no rows they're allowed to see under
        // the unpublished filter, so reject before any repo/Fuseki work runs.
        if (Boolean.FALSE.equals(publishedFilter) && userId == null && !isAdmin) {
            log.warn("Refusing UNPUBLISHED search for unauthenticated caller — " +
                    "should have been blocked at SearchServiceImpl.resolveSource");
            return new SearchProviderResult(List.of(), 0, 0, 0);
        }

        List<SearchResultDto> allResults = new ArrayList<>();
        AtomicBoolean fusekiDegraded = new AtomicBoolean(false);

        // DIAGRAM is its own kind: one row per ontology-with-a-diagram, matched on the
        // ontology slug, keyed by a synthetic IRI so it never dedup-collides with the
        // ontology's ONTOLOGY row on a type=null pass. A DIAGRAM-only request skips the
        // ontology and concept branches entirely (they contribute nothing of that kind).
        if (type == SearchType.DIAGRAM) {
            // Nothing to merge or dedup against on this branch, so the page is cut in SQL rather than by
            // fetching every match and slicing it. Total comes from the count query, not the page size.
            List<SearchResultDto> paged = searchDiagrams(query, publishedFilter, limit, offset);
            Integer totalDiagrams = countDiagramMatches(query, publishedFilter);
            return new SearchProviderResult(paged, totalDiagrams != null ? totalDiagrams : paged.size(),
                    0, 0, totalDiagrams);
        }

        // PG ontology list — matches on slug even for empty ontologies where Fuseki
        // has no indexable labels. Role filters (CLASS/PROPERTY/RELATIONSHIP) are
        // concept-only by definition, so they skip the ontology branch entirely.
        if (type == null || type == SearchType.ONTOLOGY) {
            allResults.addAll(searchOntologies(query, publishedFilter));
        }

        // On a type=null pass diagrams ride along with ontologies and concepts. The page is cut after the
        // merge below, so this cannot page in SQL — but it can still be bounded: at most offset+limit
        // diagram rows can survive into the requested page, however the merge orders them.
        //
        // Saturating, not wrapping: `offset` is validated non-negative but has no upper bound, so a plain
        // sum overflows to a NEGATIVE row limit, which Postgres rejects — and the failure is swallowed as
        // "no diagram results", silently dropping diagrams from the page rather than erroring.
        if (type == null) {
            allResults.addAll(searchDiagrams(query, publishedFilter, boundedFetch(offset, limit), 0));
        }

        // Concept-side search hits PG (concepts only) and Fuseki text index (concepts
        // AND ontology labels — Fuseki rows self-classify by rdf:type). For an
        // ONTOLOGY-only request we skip the PG half (it returns nothing of interest),
        // skip the relation-type filter (concept-only), and let Fuseki contribute
        // ontology label matches that PG slug-search would miss. For CONCEPT,
        // role-narrowed (CLASS/PROPERTY/RELATIONSHIP), or unfiltered we run the full
        // path — the role narrowing pushes into PG (concept_type column) and Fuseki
        // (FILTER EXISTS on the OFN role IRI) so unrelated rows never reach merge.
        if (type != SearchType.ONTOLOGY) {
            allResults.addAll(searchConcepts(query, userId, isAdmin, publishedFilter, lang,
                    ontologyIris, relationTypes, fusekiDegraded, limit, type));
        } else {
            allResults.addAll(searchOntologyLabelsViaFuseki(
                    query, userId, isAdmin, publishedFilter, ontologyIris, fusekiDegraded, limit));
        }

        // Dedup by IRI
        LinkedHashMap<String, SearchResultDto> deduped = new LinkedHashMap<>();
        for (SearchResultDto result : allResults) {
            if (result.getIri() != null) {
                deduped.merge(result.getIri(), result, (existing, incoming) -> {
                    mergeNonNullFields(existing, incoming);
                    return existing;
                });
            }
        }

        // Backfill ontology rows AFTER dedup, so it covers every branch that can
        // produce one. A Fuseki row carries no slug, and PG's ontology search
        // matches on slug alone — so an ontology whose label matches but whose slug
        // does not ("qa test" against slug "test-slovnik") reaches here PG-less on
        // BOTH paths. The concept-side backfill cannot rescue it: it resolves IRIs
        // against concept_metadata, where an ontology has no row. Running after the
        // merge also means a row already carrying a PG slug is left alone.
        backfillPgFieldsForOntologyRows(new ArrayList<>(deduped.values()));

        // Type filter: apply AFTER dedup so Fuseki-classified ontologies merge with
        // PG ontology hits (and PG concepts merge with Fuseki concepts) before we
        // decide what to drop. Role narrowing (CLASS/PROPERTY/RELATIONSHIP) requires
        // the merged row to be a concept whose conceptType matches the requested role
        // — sourced from PG's concept_type column or enriched from Fuseki rdf:types.
        List<SearchResultDto> results = deduped.values().stream()
                .filter(r -> matchesType(r, type))
                .sorted(RESULT_ORDER)
                .toList();

        // Apply offset and limit in-memory
        int fromIndex = Math.min(offset, results.size());
        int toIndex = Math.min(fromIndex + limit, results.size());
        List<SearchResultDto> paged = results.subList(fromIndex, toIndex);

        // Per-ontology concept counts for the page — single batched PG query.
        populateOntologyConceptCounts(paged);

        // Per-source totals from dedicated count queries (cheap — no LIMIT/OFFSET).
        Integer totalOntologies = SearchProvider.countIfMatches(
                type == null || type == SearchType.ONTOLOGY,
                () -> countOntologyMatches(query, publishedFilter));
        Integer totalConcepts = SearchProvider.countIfMatches(
                type == null || type.isAnyConcept(),
                () -> countConceptMatches(query, publishedFilter, ontologyIris, type));
        // type is null here (DIAGRAM-only returned early; ONTOLOGY/CONCEPT/role never match).
        Integer totalDiagrams = SearchProvider.countIfMatches(
                type == null,
                () -> countDiagramMatches(query, publishedFilter));

        if (fusekiDegraded.get()) {
            return new SearchProviderResult(paged, results.size(),
                    totalOntologies, totalConcepts, totalDiagrams,
                    SearchSourceStatus.DEGRADED,
                    "Fuseki unavailable, returning PostgreSQL results only");
        }

        return new SearchProviderResult(paged, results.size(), totalOntologies, totalConcepts, totalDiagrams);
    }

    private void populateOntologyConceptCounts(List<SearchResultDto> paged) {
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
    private void backfillPgFieldsForOntologyRows(List<SearchResultDto> rows) {
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

    private Integer countOntologyMatches(String query, Boolean publishedFilter) {
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

    private Integer countConceptMatches(String query, Boolean publishedFilter,
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

    private List<SearchResultDto> searchOntologies(String query, Boolean publishedFilter) {
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

    /**
     * One DIAGRAM result per ontology that has a diagram and whose slug matches the query. Keyed by a
     * synthetic {@code graphName + "#diagram"} IRI so a type=null pass keeps it distinct from the
     * ontology's ONTOLOGY row through dedup.
     */
    /** One page of diagram rows; a PG failure degrades to no diagram results rather than failing search. */
    /**
     * How many rows a branch must fetch to fill a page it cannot cut in SQL: everything up to the end of the
     * requested window. Saturates instead of overflowing — {@code offset} is validated non-negative but
     * unbounded above, and a wrapped sum becomes a negative row limit that the database rejects.
     */
    private static int boundedFetch(int offset, int limit) {
        long fetch = (long) offset + limit;
        return (int) Math.min(fetch, Integer.MAX_VALUE);
    }

    private List<SearchResultDto> searchDiagrams(String query, Boolean publishedFilter,
                                                 int limit, int offset) {
        List<SearchResultDto> rows;
        try {
            rows = diagramSearchLookup.search(query, publishedFilter, limit, offset);
        } catch (RuntimeException e) {
            log.warn("PG diagram search failed, continuing without diagram results: {}", e.getMessage());
            return List.of();
        }
        // Deliberately OUTSIDE diagramSearchLookup's transaction: the ontology label comes from
        // Fuseki, and holding a pooled PG connection across that HTTP call is what the lookup's
        // own doc warns against. One batched fetch for the page, not one per row.
        attachOntologyLabels(rows);
        return rows;
    }

    /**
     * Fills in each diagram row's owning-ontology prefLabel. Names live only in RDF, so this is one
     * batched CONSTRUCT for the whole page; rows whose ontology has no label simply keep a null.
     */
    private void attachOntologyLabels(List<SearchResultDto> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Map<String, String>> labels = ontologyLabelLookup.labelsByGraph(
                rows.stream().map(SearchResultDto::getOntologyIri).toList());
        if (labels == null || labels.isEmpty()) {
            return;
        }
        for (SearchResultDto row : rows) {
            row.setOntologyLabel(labels.get(row.getOntologyIri()));
        }
    }

    private Integer countDiagramMatches(String query, Boolean publishedFilter) {
        try {
            return (int) diagramSearchLookup.count(query, publishedFilter);
        } catch (RuntimeException e) {
            log.warn("PG diagram total-count failed: {}", e.getMessage());
            return null;
        }
    }

    private List<SearchResultDto> searchConcepts(String query, String userId,
                                                  boolean isAdmin, Boolean publishedFilter,
                                                  String lang,
                                                  List<String> ontologyIris,
                                                  List<RelationType> relationTypes,
                                                  AtomicBoolean fusekiDegraded, int limit,
                                                  SearchType type) {
        GraphFilter filter = resolveGraphFilter(ontologyIris,
                visibleGraphsFor(userId, isAdmin, publishedFilter));

        ParallelSearchResults raw = runParallelConceptSearch(
                query, publishedFilter, filter, limit, fusekiDegraded, type);

        List<SearchResultDto> merged = mergeByIri(raw.pg(), raw.fuseki());
        backfillPgIdsForFusekiOnlyRows(merged);
        enrichPgOnlyLabels(merged, raw.fusekiIris(), lang, fusekiDegraded);
        return applyRelationTypeFilter(merged, relationTypes);
    }

    /**
     * Visible-graph set used to scope Fuseki text queries so every hit lives
     * inside an ontology the caller is permitted to see. Unpublished-only
     * searches narrow further: admin sees all unpublished, regular user sees
     * only their own.
     */
    private List<String> visibleGraphsFor(String userId, boolean isAdmin, Boolean publishedFilter) {
        boolean unpublishedOnly = Boolean.FALSE.equals(publishedFilter);
        return unpublishedOnly
                ? getUnpublishedVisibleGraphNames(userId, isAdmin)
                : getVisibleGraphNames();
    }

    /**
     * Derives the three graph-name views needed downstream:
     * — {@code hasGraphFilter}: whether the caller restricted to specific ontologies
     * — {@code graphNames}: PG-side {@code IN (...)} list (empty when no filter)
     * — {@code searchGraphs}: Fuseki-side scoped graph list, intersected with what
     *   the caller is allowed to see (so an arbitrary IRI in the request can't
     *   widen visibility)
     */
    private static GraphFilter resolveGraphFilter(List<String> ontologyIris, List<String> visibleGraphNames) {
        boolean hasGraphFilter = ontologyIris != null && !ontologyIris.isEmpty();
        List<String> graphNames = hasGraphFilter ? ontologyIris : List.of();
        List<String> searchGraphs = hasGraphFilter
                ? ontologyIris.stream().filter(visibleGraphNames::contains).toList()
                : visibleGraphNames;
        return new GraphFilter(hasGraphFilter, graphNames, searchGraphs);
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
    private ParallelSearchResults runParallelConceptSearch(String query, Boolean publishedFilter,
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
                        () -> searchFuseki(query, filter.searchGraphs(), limit, roleFilter), searchExecutor)
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
    private List<SearchResultDto> mergeByIri(List<SearchResultDto> pg, List<SearchResultDto> fuseki) {
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
                    mergeNonNullFields(existing, incoming);
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
    private void backfillPgIdsForFusekiOnlyRows(List<SearchResultDto> merged) {
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
    private void enrichPgOnlyLabels(List<SearchResultDto> merged, Set<String> fusekiIris,
                                     String lang, AtomicBoolean fusekiDegraded) {
        if (fusekiDegraded.get() || merged.isEmpty()) return;

        List<SearchResultDto> needsEnrichment = merged.stream()
                .filter(dto -> !fusekiIris.contains(dto.getIri()))
                .toList();
        if (needsEnrichment.isEmpty()) return;

        try {
            log.debug("Enriching {} PG-only results with Fuseki labels (lang={})",
                    needsEnrichment.size(), lang);
            enrichWithLabels(needsEnrichment, lang);
        } catch (Exception e) {
            log.warn("Fuseki label enrichment failed, using existing labels: {}", e.getMessage());
        }
    }

    /**
     * Optional Fuseki-side post-filter by relation type (subClassOf, exactMatch,
     * etc.). Failure is non-fatal: returns the unfiltered list rather than
     * blanking the page.
     */
    private List<SearchResultDto> applyRelationTypeFilter(List<SearchResultDto> mergedResults,
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

    /** Pre-computed graph-name views derived from the request's ontology filter. */
    private record GraphFilter(boolean hasGraphFilter,
                                List<String> graphNames,
                                List<String> searchGraphs) {}

    /** Joined output of the parallel PG + Fuseki concept search. */
    private record ParallelSearchResults(List<SearchResultDto> pg,
                                          List<SearchResultDto> fuseki,
                                          Set<String> fusekiIris) {}

    /**
     * Fuseki-only path used when {@code type=ONTOLOGY}. Skips the PG concept query
     * (concepts can never satisfy an ontology-only request), skips relation-type
     * filtering (concept-only concept), and skips label enrichment (Fuseki already
     * returned labels). Caller's outer type filter then drops any concept-typed
     * Fuseki rows that snuck in alongside the ontology hits.
     */
    private List<SearchResultDto> searchOntologyLabelsViaFuseki(String query, String userId,
                                                                  boolean isAdmin,
                                                                  Boolean publishedFilter,
                                                                  List<String> ontologyIris,
                                                                  AtomicBoolean fusekiDegraded,
                                                                  int limit) {
        GraphFilter filter = resolveGraphFilter(ontologyIris,
                visibleGraphsFor(userId, isAdmin, publishedFilter));

        try {
            return CompletableFuture.supplyAsync(
                            () -> searchFuseki(query, filter.searchGraphs(), limit, null), searchExecutor)
                    .orTimeout(fusekiTimeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Fuseki ontology label search failed: {}", e.getMessage());
            fusekiDegraded.set(true);
            return List.of();
        }
    }

    private List<SearchResultDto> searchFuseki(String query, List<String> visibleGraphNames, int limit,
                                                ConceptType conceptTypeFilter) {
        if (visibleGraphNames.isEmpty()) {
            log.debug("Fuseki search skipped: no visible graph names for user");
            return List.of();
        }

        log.debug("Fuseki text search: query='{}', searching {} graphs, conceptType={}",
                query, visibleGraphNames.size(), conceptTypeFilter);

        List<Map<String, String>> rows = jenaTDB2Repository.searchByText(
                query, visibleGraphNames, limit, conceptTypeFilter);

        log.debug("Fuseki text search returned {} raw rows", rows.size());

        List<SearchResultDto> results = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String typesField = row.get("types");
            SearchType resolvedType = classifyByRdfTypes(typesField);
            // For ontology resources, the resource IRI IS the graph name — don't
            // populate ontologyIri with a self-reference (which would also overwrite
            // the PG ontology hit's null ontologyIri during merge). Concepts get the
            // graph they live in, as before.
            String ontologyIri = resolvedType == SearchType.ONTOLOGY
                    ? null
                    : row.get("graphName");
            // Enrich conceptType from rdf:types so Fuseki-only concept rows survive
            // the role-narrowing post-merge filter — PG side carries the column
            // verbatim, but Fuseki-only rows would otherwise have conceptType=null.
            ConceptType conceptType = resolvedType == SearchType.CONCEPT
                    ? conceptTypeByRdfTypes(typesField)
                    : null;
            results.add(SearchResultDto.builder()
                    .iri(row.get("resourceIri"))
                    .label(row.get("prefLabel"))
                    .labelLang(row.get("prefLabelLang"))
                    .altName(row.get("altLabel"))
                    .description(row.get("description"))
                    .definition(row.get("definition"))
                    .ontologyIri(ontologyIri)
                    .type(resolvedType)
                    .conceptType(conceptType)
                    .source(SearchSource.ISMD)
                    .matchedBy(MatchedBy.SPARQL)
                    .build());
        }
        return results;
    }

    /**
     * Classifies a Fuseki search hit by its rdf:type set (pipe-separated).
     * An ontology / concept scheme always carries skos:ConceptScheme or owl:Ontology;
     * a concept carries skos:Concept. The ontology node in our data is both
     * ConceptScheme and Ontology but never skos:Concept, so an ontology marker is
     * decisive. Everything else — including skos:Concept, ambiguous, or missing
     * types — falls back to CONCEPT, matching how the text index was historically
     * consumed.
     */
    private static SearchType classifyByRdfTypes(String types) {
        if (types == null || types.isEmpty()) {
            return SearchType.CONCEPT;
        }
        for (String t : types.split("\\|")) {
            if (SKOS_CONCEPT_SCHEME.equals(t) || OWL_ONTOLOGY.equals(t)) {
                return SearchType.ONTOLOGY;
            }
        }
        return SearchType.CONCEPT;
    }

    /**
     * Extracts the concept role from a pipe-separated rdf:type set produced by
     * Fuseki text search. Returns null when no specific role marker resolves, so
     * the PG-authoritative type can still backfill (KONCEPT is a terminal
     * fallback for the projection endpoint, not a search signal).
     */
    private static ConceptType conceptTypeByRdfTypes(String types) {
        if (types == null || types.isEmpty()) return null;
        ConceptType resolved = ConceptType.fromRdfTypes(java.util.Arrays.asList(types.split("\\|")));
        return resolved == ConceptType.KONCEPT ? null : resolved;
    }

    /**
     * Post-merge predicate that applies the request's {@code type} parameter to
     * a deduped row. ONTOLOGY/CONCEPT match by {@code dto.type}; role-narrowing
     * types (CLASS/PROPERTY/RELATIONSHIP) additionally require the concept's
     * {@code conceptType} to match.
     */
    private static boolean matchesType(SearchResultDto r, SearchType type) {
        if (type == null) return true;
        if (type == SearchType.ONTOLOGY) return r.getType() == SearchType.ONTOLOGY;
        if (type == SearchType.CONCEPT) return r.getType() == SearchType.CONCEPT;
        if (type == SearchType.DIAGRAM) return r.getType() == SearchType.DIAGRAM;
        return r.getType() == SearchType.CONCEPT && r.getConceptType() == type.toConceptType();
    }

    private void enrichWithLabels(List<SearchResultDto> results, String lang) {
        List<String> conceptIris = results.stream()
                .map(SearchResultDto::getIri)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (conceptIris.isEmpty()) return;

        Model labelsModel = jenaTDB2Repository.fetchConceptLabels(conceptIris);

        for (SearchResultDto dto : results) {
            if (dto.getIri() == null) continue;

            Resource concept = labelsModel.getResource(dto.getIri());

            // Resolve prefLabel with language preference
            String bestLabel = getLiteralWithLangPreference(concept, SKOS_PREF_LABEL, lang);
            if (bestLabel != null) {
                dto.setLabel(bestLabel);
                dto.setLabelLang(lang);
            }

            // altLabel
            String altLabel = getFirstLiteral(concept);
            if (altLabel != null) dto.setAltName(altLabel);

            // description
            String description = getLiteralWithLangPreference(concept, DCTERMS_DESCRIPTION, lang);
            if (description != null) dto.setDescription(description);

            // definition
            String definition = getLiteralWithLangPreference(concept, SKOS_DEFINITION, lang);
            if (definition != null) dto.setDefinition(definition);
        }
    }

    private String getLiteralWithLangPreference(Resource resource, String propertyUri, String lang) {
        Property property = resource.getModel().getProperty(propertyUri);
        StmtIterator stmts = resource.listProperties(property);

        String bestMatch = null;
        String fallback = null;

        while (stmts.hasNext()) {
            Statement stmt = stmts.next();
            if (stmt.getObject().isLiteral()) {
                Literal literal = stmt.getLiteral();
                String literalLang = literal.getLanguage();
                if (lang != null && lang.equals(literalLang)) {
                    bestMatch = literal.getString();
                } else if (fallback == null) {
                    fallback = literal.getString();
                }
            }
        }

        return bestMatch != null ? bestMatch : fallback;
    }

    private String getFirstLiteral(Resource resource) {
        Property property = resource.getModel().getProperty(IsmdSearchProvider.SKOS_ALT_LABEL);
        Statement stmt = resource.getProperty(property);
        if (stmt != null && stmt.getObject().isLiteral()) {
            return stmt.getLiteral().getString();
        }
        return null;
    }

    /**
     * Graph names visible on the default (no-source) search pass: every ontology,
     * published AND unpublished drafts of all users. Scopes Fuseki text queries so a
     * concept hit is transitively inside an ontology the caller may see — which, on
     * this pass, is all of them (anonymous callers never reach ISMD search). The
     * {@code userId} parameter is retained for signature symmetry with the
     * unpublished-only path but is no longer needed to narrow visibility.
     */
    private List<String> getVisibleGraphNames() {
        return ontologyMetadataRepository.findAll().stream()
                .map(OntologyMetadataEntity::getGraphName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Graph names visible to the caller on the {@code UNPUBLISHED} ("rozpracovaný")
     * pass — every local ontology, matching the PG row filter so the Fuseki label
     * search and the PG slug search agree on scope. "Rozpracovaný" means local rather
     * than draft-flagged: an uploaded working copy is {@code is_published = true}
     * throughout until a concept is edited, so a publish-state test here would hide
     * whole vocabularies from the filter.
     * <p>
     * Every authenticated caller sees every such graph regardless of ownership; the
     * {@code userId}/{@code isAdmin} short-circuit only mirrors the defense-in-depth
     * guard that keeps an anonymous, non-admin caller from seeing any unpublished
     * rows (they are already rejected upstream at
     * {@code SearchServiceImpl.resolveSource}).
     */
    private List<String> getUnpublishedVisibleGraphNames(String userId, boolean isAdmin) {
        if (!isAdmin && userId == null) {
            return List.of();
        }
        return ontologyMetadataRepository.findAllGraphNames().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private SearchResultDto mapConceptEntity(ConceptMetadataEntity e) {
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

    private void mergeNonNullFields(SearchResultDto target, SearchResultDto source) {
        if (source.getLabel() != null) target.setLabel(source.getLabel());
        if (source.getLabelLang() != null) target.setLabelLang(source.getLabelLang());
        if (source.getAltName() != null) target.setAltName(source.getAltName());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getDefinition() != null) target.setDefinition(source.getDefinition());
        if (source.getOntologyIri() != null) target.setOntologyIri(source.getOntologyIri());
        if (source.getConceptType() != null) target.setConceptType(source.getConceptType());
        if (source.getSlug() != null) target.setSlug(source.getSlug());
        if (source.getIsPublished() != null) target.setIsPublished(source.getIsPublished());
        if (source.getLastModified() != null) target.setLastModified(source.getLastModified());
        if (source.getConceptCount() != null) target.setConceptCount(source.getConceptCount());
    }
}