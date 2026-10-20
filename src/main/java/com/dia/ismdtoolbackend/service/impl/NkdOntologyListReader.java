package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.NkdOntologyListItemDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.dia.ismdtoolbackend.service.impl.NkdListItemMeta.DEFAULT_LANG;

/** Reads NKD ontology list rows: a lookup by IRI and the paged catalogue browse. */
@Component
@RequiredArgsConstructor
@Slf4j
class NkdOntologyListReader {

    /**
     * Hard cap on a single batch — the FE limits to 10, this is 5x slack for power
     * users while still bounding fan-out (each IRI is one SPARQL round-trip).
     */
    private static final int MAX_LIST_IRIS = 50;

    /** Pagination caps for the catalog "list all" endpoint — same shape as SearchController. */
    private static final int MAX_LIST_LIMIT = 100;

    private final NkdSparqlClient nkdSparqlClient;
    private final NkdRequestGuard guard;
    private final NkdCatalogTotalsReader totals;

    GetNkdOntologyListDto getOntologyList(List<String> iris) {
        if (iris == null || iris.isEmpty()) {
            GetNkdOntologyListDto empty = new GetNkdOntologyListDto();
            empty.setOntologies(List.of());
            return empty;
        }
        if (iris.size() > MAX_LIST_IRIS) {
            throw new IllegalArgumentException(
                    "Příliš mnoho IRI v jedné žádosti (max " + MAX_LIST_IRIS + ", obdrženo " + iris.size() + ").");
        }
        guard.ensureEndpointConfigured();
        // Validate up front so a single bad IRI doesn't waste N-1 SPARQL round-trips
        // before failing. Per-IRI fetch errors are tolerated below; per-IRI shape
        // errors are not (they indicate a client bug, not a remote outage).
        for (String iri : iris) {
            guard.validateIri(iri);
        }

        // Same batched metadata SELECT as the browse path — one round-trip for all IRIs.
        // No concept-count batch on this lookup-by-IRI flow, so conceptCount stays null
        // (omitted from JSON via NON_NULL on the item DTO). A stale bookmarked IRI absent
        // from NKD simply yields no rows and is skipped — it never blanks the row.
        List<NkdOntologyListItemDto> items = assembleListItems(iris, Map.of(), false);

        GetNkdOntologyListDto response = new GetNkdOntologyListDto();
        response.setOntologies(items);
        return response;
    }

    GetNkdOntologyListDto listAllOntologies(int limit, int offset, String lang) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("Limit musí být v rozsahu 1–" + MAX_LIST_LIMIT + ".");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset nesmí být záporný.");
        }
        guard.ensureEndpointConfigured();

        String sortLang = (lang == null || lang.isBlank()) ? DEFAULT_LANG : lang;

        long tStage1 = System.currentTimeMillis();
        List<String> pageIris;
        try {
            pageIris = fetchOntologyIrisPage(sortLang, limit, offset);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while listing ontology IRIs: {}", e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }
        log.info("[timing] listAllOntologies stage1 (fetchOntologyIrisPage, {} iris) took {} ms",
                pageIris.size(), System.currentTimeMillis() - tStage1);

        long tStage2 = System.currentTimeMillis();
        Map<String, Integer> conceptCounts = pageIris.isEmpty()
                ? Map.of()
                : fetchConceptCountsForOntologies(pageIris);
        log.info("[timing] listAllOntologies stage2 (fetchConceptCountsForOntologies) took {} ms",
                System.currentTimeMillis() - tStage2);

        // Single batched metadata SELECT for the whole page — labels, multilingual
        // descriptions and dates come back field-for-field identical to the full
        // extractor, without the per-IRI full-graph CONSTRUCT over-fetch.
        // Items with no metadata rows are skipped, matching getOntologyList behaviour.
        long tStage3 = System.currentTimeMillis();
        List<NkdOntologyListItemDto> items = assembleListItems(pageIris, conceptCounts, true);
        log.info("[timing] listAllOntologies stage3 (batched metadata SELECT, {} iris) took {} ms",
                pageIris.size(), System.currentTimeMillis() - tStage3);

        long tStage4 = System.currentTimeMillis();
        int totalOntologies = totals.getCachedTotalOntologies();
        int totalConcepts = totals.getCachedTotalConcepts();
        log.info("[timing] listAllOntologies stage4 (cached totals) took {} ms",
                System.currentTimeMillis() - tStage4);

        GetNkdOntologyListDto response = new GetNkdOntologyListDto();
        response.setOntologies(items);
        response.setOntologyCount(totalOntologies);
        response.setConceptCount(totalConcepts);
        // totalCount mirrors ontologyCount for now (the FE pagination needs
        // ontology-count-across-all-pages). Kept as separate field so the FE
        // contract is explicit and survives a future refactor.
        response.setTotalCount(totalOntologies);
        return response;
    }

    private List<String> fetchOntologyIrisPage(String sortLang, int limit, int offset) {
        String query = NKDSPARQLBrowseQuery.buildListOntologyIrisQuery(sortLang, limit, offset);
        List<Map<String, String>> rows = nkdSparqlClient.executeSelect(query);
        List<String> iris = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            String iri = row.get("ontology");
            if (iri != null && !iri.isBlank()) {
                iris.add(iri);
            }
        }
        return iris;
    }

    private Map<String, Integer> fetchConceptCountsForOntologies(List<String> iris) {
        try {
            String query = NKDSPARQLBrowseQuery.buildConceptCountsForOntologiesQuery(iris);
            List<Map<String, String>> rows = nkdSparqlClient.executeSelect(query);
            Map<String, Integer> counts = new HashMap<>(rows.size() * 2);
            for (Map<String, String> row : rows) {
                String iri = row.get("ontology");
                String cnt = row.get("cnt");
                if (iri == null || cnt == null) continue;
                try {
                    counts.put(iri, Integer.parseInt(cnt));
                } catch (NumberFormatException ignored) {
                    // Tolerate odd literals — concept count is informational, not critical.
                }
            }
            return counts;
        } catch (RuntimeException e) {
            // Concept counts are nice-to-have; don't fail the page if this single
            // batched query errors. Items will get null conceptCount via getOrDefault(0).
            log.warn("NKD SPARQL error while fetching concept counts for page: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Builds list items for a page of ontology IRIs from a SINGLE batched metadata
     * SELECT, instead of one full-graph CONSTRUCT per IRI. Replaces the N-round-trip,
     * full-graph-over-fetch loop: the list row only needs name/description/dates, and
     * those fields come back field-for-field identical to the full-extractor path
     * (see {@link NKDSPARQLBrowseQuery#buildListItemMetadataQuery}).
     *
     * <p>Order follows {@code pageIris}. An IRI that the metadata query returns no rows
     * for is skipped (matches the old loop's "vanished between list and fetch" skip).
     * {@code conceptCountByIri} may be empty/missing-keyed — items then carry the count
     * supplied (0 for the browse path, null for lookup-by-IRI).
     */
    private List<NkdOntologyListItemDto> assembleListItems(List<String> pageIris,
                                                           Map<String, Integer> conceptCountByIri,
                                                           boolean countAvailable) {
        Map<String, NkdListItemMeta> metaByIri = fetchNkdListItemMetadata(pageIris);
        List<NkdOntologyListItemDto> items = new ArrayList<>(pageIris.size());
        for (String iri : pageIris) {
            NkdListItemMeta meta = metaByIri.get(iri);
            if (meta == null) {
                log.info("NKD ontology has no metadata rows, skipping in list response: {}", iri);
                continue;
            }
            Integer conceptCount = countAvailable ? conceptCountByIri.getOrDefault(iri, 0) : null;
            items.add(NkdOntologyListItemDto.builder()
                    .iri(iri)
                    .name(meta.nameMap())
                    .description(meta.descriptionMap())
                    .creationDate(meta.creationDate())
                    .modificationDate(meta.modificationDate())
                    .conceptCount(conceptCount)
                    .build());
        }
        return items;
    }

    /**
     * Runs the batched list-item metadata SELECT and folds the (possibly multi-row,
     * one per description language) result into one {@link NkdListItemMeta} per ontology.
     * On SPARQL failure returns an empty map — callers then skip every IRI, which the
     * browse/list flows already treat as "page returns what it can".
     */
    private Map<String, NkdListItemMeta> fetchNkdListItemMetadata(List<String> iris) {
        if (iris.isEmpty()) {
            return Map.of();
        }
        List<Map<String, String>> rows;
        try {
            String query = NKDSPARQLBrowseQuery.buildListItemMetadataQuery(iris);
            rows = nkdSparqlClient.executeSelect(query);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching list-item metadata for page: {}", e.getMessage());
            return Map.of();
        }

        Map<String, NkdListItemMeta.Builder> builders = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String iri = row.get("ontology");
            if (iri == null) continue;
            NkdListItemMeta.Builder b = builders.computeIfAbsent(iri, k -> new NkdListItemMeta.Builder());

            // Name: prefer rdfs:label, else skos:prefLabel — first non-blank wins, keyed "cs",
            // mirroring ModelAnalyzer.extractModelName + createMultilingualMap.
            b.offerLabel(row.get("label"), row.get("prefLabel"));

            // Description: one row per literal; key by lang tag, "cs" when untagged
            // (matches extractMultilingualDescription).
            String desc = row.get("desc");
            if (desc != null && !desc.trim().isEmpty()) {
                String lang = row.get("descLang");
                String langTag = (lang != null && !lang.isEmpty()) ? lang : DEFAULT_LANG;
                b.putDescription(langTag, desc);
            }

            // Dates: two-hop instant already joined in SPARQL; prefer datum-a-čas (dateTime)
            // over datum (date), matching ModelAnalyzer.extractTemporalValue.
            b.offerCreation(row.get("cDateTime"), row.get("cDate"));
            b.offerModification(row.get("mDateTime"), row.get("mDate"));
        }

        Map<String, NkdListItemMeta> out = new LinkedHashMap<>(builders.size() * 2);
        builders.forEach((iri, b) -> out.put(iri, b.build()));
        return out;
    }
}
