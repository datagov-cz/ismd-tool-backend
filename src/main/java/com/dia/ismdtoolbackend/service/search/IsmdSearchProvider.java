package com.dia.ismdtoolbackend.service.search;

import com.dia.ismdtoolbackend.controller.dto.SearchResultDto;
import com.dia.ismdtoolbackend.enums.RelationType;
import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import com.dia.ismdtoolbackend.enums.SearchType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ISMD search: sequences the ontology, diagram and concept searches, then dedups, orders and pages
 * the merged rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsmdSearchProvider implements SearchProvider {

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

    private final IsmdOntologySearch ontologies;
    private final IsmdDiagramSearch diagrams;
    private final IsmdConceptSearch concepts;

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
            List<SearchResultDto> paged = diagrams.searchDiagrams(query, publishedFilter, limit, offset);
            Integer totalDiagrams = diagrams.countDiagramMatches(query, publishedFilter);
            return new SearchProviderResult(paged, totalDiagrams != null ? totalDiagrams : paged.size(),
                    0, 0, totalDiagrams);
        }

        // PG ontology list — matches on slug even for empty ontologies where Fuseki
        // has no indexable labels. Role filters (CLASS/PROPERTY/RELATIONSHIP) are
        // concept-only by definition, so they skip the ontology branch entirely.
        if (type == null || type == SearchType.ONTOLOGY) {
            allResults.addAll(ontologies.searchOntologies(query, publishedFilter));
        }

        // On a type=null pass diagrams ride along with ontologies and concepts. The page is cut after the
        // merge below, so this cannot page in SQL — but it can still be bounded: at most offset+limit
        // diagram rows can survive into the requested page, however the merge orders them.
        //
        // Saturating, not wrapping: `offset` is validated non-negative but has no upper bound, so a plain
        // sum overflows to a NEGATIVE row limit, which Postgres rejects — and the failure is swallowed as
        // "no diagram results", silently dropping diagrams from the page rather than erroring.
        if (type == null) {
            allResults.addAll(diagrams.searchDiagrams(query, publishedFilter, boundedFetch(offset, limit), 0));
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
            allResults.addAll(concepts.searchConcepts(query, userId, isAdmin, publishedFilter, lang,
                    ontologyIris, relationTypes, fusekiDegraded, limit, type));
        } else {
            allResults.addAll(ontologies.searchOntologyLabelsViaFuseki(
                    query, userId, isAdmin, publishedFilter, ontologyIris, fusekiDegraded, limit));
        }

        // Dedup by IRI
        LinkedHashMap<String, SearchResultDto> deduped = new LinkedHashMap<>();
        for (SearchResultDto result : allResults) {
            if (result.getIri() != null) {
                deduped.merge(result.getIri(), result, (existing, incoming) -> {
                    SearchResultMerge.mergeNonNullFields(existing, incoming);
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
        ontologies.backfillPgFieldsForOntologyRows(new ArrayList<>(deduped.values()));

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
        ontologies.populateOntologyConceptCounts(paged);

        // Per-source totals from dedicated count queries (cheap — no LIMIT/OFFSET).
        Integer totalOntologies = SearchProvider.countIfMatches(
                type == null || type == SearchType.ONTOLOGY,
                () -> ontologies.countOntologyMatches(query, publishedFilter));
        Integer totalConcepts = SearchProvider.countIfMatches(
                type == null || type.isAnyConcept(),
                () -> concepts.countConceptMatches(query, publishedFilter, ontologyIris, type));
        // type is null here (DIAGRAM-only returned early; ONTOLOGY/CONCEPT/role never match).
        Integer totalDiagrams = SearchProvider.countIfMatches(
                type == null,
                () -> diagrams.countDiagramMatches(query, publishedFilter));

        if (fusekiDegraded.get()) {
            return new SearchProviderResult(paged, results.size(),
                    totalOntologies, totalConcepts, totalDiagrams,
                    SearchSourceStatus.DEGRADED,
                    "Fuseki unavailable, returning PostgreSQL results only");
        }

        return new SearchProviderResult(paged, results.size(), totalOntologies, totalConcepts, totalDiagrams);
    }

    /**
     * How many rows a branch must fetch to fill a page it cannot cut in SQL: everything up to the end of the
     * requested window. Saturates instead of overflowing — {@code offset} is validated non-negative but
     * unbounded above, and a wrapped sum becomes a negative row limit that the database rejects.
     */
    private static int boundedFetch(int offset, int limit) {
        long fetch = (long) offset + limit;
        return (int) Math.min(fetch, Integer.MAX_VALUE);
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
}
