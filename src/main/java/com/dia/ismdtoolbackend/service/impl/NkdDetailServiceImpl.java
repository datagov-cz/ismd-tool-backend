package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.NkdOntologyListItemDto;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class NkdDetailServiceImpl implements NkdDetailService {

    /**
     * Hard cap on a single batch — the FE limits to 10, this is 5x slack for power
     * users while still bounding fan-out (each IRI is one SPARQL round-trip).
     */
    private static final int MAX_LIST_IRIS = 50;

    /** Pagination caps for the catalog "list all" endpoint — same shape as SearchController. */
    private static final int MAX_LIST_LIMIT = 100;
    private static final String DEFAULT_LANG = "cs";

    /**
     * TTL for the cached global counts (total ontologies, total concepts).
     * Catalog-level totals change rarely; 1h is a fine accuracy/latency trade.
     * In-heap, single-instance — to be revisited once a project-wide cache
     * abstraction lands (see feat/issue-101).
     */
    private static final Duration COUNT_TTL = Duration.ofHours(1);

    private final NkdSparqlClient nkdSparqlClient;

    private volatile CachedValue<Integer> cachedTotalOntologies;
    private volatile CachedValue<Integer> cachedTotalConcepts;

    @Override
    public GetNkdOntologyDto getOntologyDetail(String iri) {
        ensureEndpointConfigured();
        validateIri(iri);

        Optional<OntologyDetailModel> ontologyDetail;
        try {
            ontologyDetail = nkdSparqlClient.fetchPublishedOntology(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching ontology {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        OntologyDetailModel detail = ontologyDetail.orElseThrow(() -> {
            log.info("Ontology not found in NKD: {}", iri);
            return new NkdResourceNotFoundException("Slovník s IRI " + iri + " nebyl v NKD nalezen.");
        });

        return new GetNkdOntologyDto(detail);
    }

    @Override
    public GetNkdConceptDto getConceptDetail(String iri, String ontologyIri) {
        ensureEndpointConfigured();
        validateIri(iri);
        if (ontologyIri != null && !ontologyIri.isBlank()) {
            validateIri(ontologyIri);
        }

        Optional<OntologyDetailModel.ConceptDetailModel> conceptDetail;
        try {
            conceptDetail = nkdSparqlClient.fetchPublishedConcept(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching concept {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        OntologyDetailModel.ConceptDetailModel detail = conceptDetail.orElseThrow(() -> {
            log.info("Concept not found in NKD: {}", iri);
            return new NkdResourceNotFoundException("Pojem s IRI " + iri + " nebyl v NKD nalezen.");
        });

        String normalizedOntologyIri = (ontologyIri == null || ontologyIri.isBlank()) ? null : ontologyIri;
        return new GetNkdConceptDto(detail, normalizedOntologyIri);
    }

    @Override
    public GetNkdOntologyListDto getOntologyList(List<String> iris) {
        if (iris == null || iris.isEmpty()) {
            GetNkdOntologyListDto empty = new GetNkdOntologyListDto();
            empty.setOntologies(List.of());
            return empty;
        }
        if (iris.size() > MAX_LIST_IRIS) {
            throw new IllegalArgumentException(
                    "Příliš mnoho IRI v jedné žádosti (max " + MAX_LIST_IRIS + ", obdrženo " + iris.size() + ").");
        }
        ensureEndpointConfigured();
        // Validate up front so a single bad IRI doesn't waste N-1 SPARQL round-trips
        // before failing. Per-IRI fetch errors are tolerated below; per-IRI shape
        // errors are not (they indicate a client bug, not a remote outage).
        for (String iri : iris) {
            validateIri(iri);
        }

        List<NkdOntologyListItemDto> items = new ArrayList<>(iris.size());
        for (String iri : iris) {
            try {
                Optional<OntologyDetailModel> detail = nkdSparqlClient.fetchPublishedOntology(iri);
                if (detail.isEmpty()) {
                    log.info("NKD ontology not found, skipping in list response: {}", iri);
                    continue;
                }
                items.add(toListItem(detail.get()));
            } catch (RuntimeException e) {
                // Skip-and-continue: a single stale bookmark in the FE's localStorage
                // shouldn't blank the whole "last accessed" tile row.
                log.warn("NKD SPARQL error while fetching ontology {} for list, skipping: {}",
                        iri, e.getMessage());
            }
        }
        GetNkdOntologyListDto response = new GetNkdOntologyListDto();
        response.setOntologies(items);
        return response;
    }

    private static NkdOntologyListItemDto toListItem(OntologyDetailModel detail) {
        return NkdOntologyListItemDto.builder()
                .iri(detail.getIri())
                .name(detail.getName())
                .description(detail.getDescription())
                .creationDate(detail.getCreationDate())
                .modificationDate(detail.getModificationDate())
                .build();
    }

    private void ensureEndpointConfigured() {
        if (!nkdSparqlClient.isEndpointConfigured()) {
            throw new NkdEndpointException("NKD SPARQL endpoint není nakonfigurován.");
        }
    }

    private void validateIri(String iri) {
        if (!SparqlIriValidator.isSafeHttpIri(iri)) {
            throw new IllegalArgumentException("IRI není platné http(s) URI: " + iri);
        }
    }

    @Override
    public GetNkdOntologyListDto listAllOntologies(int limit, int offset, String lang) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("Limit musí být v rozsahu 1–" + MAX_LIST_LIMIT + ".");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset nesmí být záporný.");
        }
        ensureEndpointConfigured();

        String sortLang = (lang == null || lang.isBlank()) ? DEFAULT_LANG : lang;

        List<String> pageIris;
        try {
            pageIris = fetchOntologyIrisPage(sortLang, limit, offset);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while listing ontology IRIs: {}", e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        Map<String, Integer> conceptCounts = pageIris.isEmpty()
                ? Map.of()
                : fetchConceptCountsForOntologies(pageIris);

        // Per-IRI CONSTRUCT round-trips reuse the existing extractor so labels
        // (multi-language) and dates match the detail endpoint exactly.
        // Items unfetchable from NKD are skipped, matching getOntologyList behaviour.
        List<NkdOntologyListItemDto> items = new ArrayList<>(pageIris.size());
        for (String iri : pageIris) {
            try {
                Optional<OntologyDetailModel> detail = nkdSparqlClient.fetchPublishedOntology(iri);
                if (detail.isEmpty()) {
                    log.info("NKD ontology vanished between list and fetch, skipping: {}", iri);
                    continue;
                }
                items.add(toListItem(detail.get(), conceptCounts.getOrDefault(iri, 0)));
            } catch (RuntimeException e) {
                log.warn("NKD SPARQL error while fetching ontology {} for browse, skipping: {}",
                        iri, e.getMessage());
            }
        }

        int totalOntologies = getCachedTotalOntologies();
        int totalConcepts = getCachedTotalConcepts();

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

    private static NkdOntologyListItemDto toListItem(OntologyDetailModel detail, int conceptCount) {
        return NkdOntologyListItemDto.builder()
                .iri(detail.getIri())
                .name(detail.getName())
                .description(detail.getDescription())
                .creationDate(detail.getCreationDate())
                .modificationDate(detail.getModificationDate())
                .conceptCount(conceptCount)
                .build();
    }

    private int getCachedTotalOntologies() {
        return getCached(cachedTotalOntologies,
                () -> fetchSingleCount(NKDSPARQLBrowseQuery.buildCountOntologiesQuery()),
                v -> cachedTotalOntologies = v);
    }

    private int getCachedTotalConcepts() {
        return getCached(cachedTotalConcepts,
                () -> fetchSingleCount(NKDSPARQLBrowseQuery.buildCountConceptsQuery()),
                v -> cachedTotalConcepts = v);
    }

    private int getCached(CachedValue<Integer> current,
                          Supplier<Integer> loader,
                          java.util.function.Consumer<CachedValue<Integer>> store) {
        Instant now = Instant.now();
        if (current != null && current.expiresAt.isAfter(now)) {
            return current.value;
        }
        try {
            int fresh = loader.get();
            store.accept(new CachedValue<>(fresh, now.plus(COUNT_TTL)));
            return fresh;
        } catch (RuntimeException e) {
            // Serve stale on remote failure — better a slightly old number than
            // a 500 on the catalog page.
            if (current != null) {
                log.warn("NKD count refresh failed, serving stale value: {}", e.getMessage());
                return current.value;
            }
            log.warn("NKD count fetch failed and no stale value available: {}", e.getMessage());
            return 0;
        }
    }

    private int fetchSingleCount(String query) {
        List<Map<String, String>> rows = nkdSparqlClient.executeSelect(query);
        if (rows.isEmpty()) return 0;
        String value = rows.get(0).get("total");
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("Unparseable COUNT result from NKD: {}", value);
            return 0;
        }
    }

    private record CachedValue<T>(T value, Instant expiresAt) {}
}
