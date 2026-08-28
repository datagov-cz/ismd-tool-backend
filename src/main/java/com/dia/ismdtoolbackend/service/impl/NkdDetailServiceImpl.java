package com.dia.ismdtoolbackend.service.impl;

import com.dia.ismdtoolbackend.client.NkdSparqlClient;
import com.dia.ismdtoolbackend.controller.dto.GetNkdConceptDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyDto;
import com.dia.ismdtoolbackend.controller.dto.GetNkdOntologyListDto;
import com.dia.ismdtoolbackend.controller.dto.MinimalConceptDto;
import com.dia.ismdtoolbackend.controller.dto.NkdOntologyListItemDto;
import com.dia.ismdtoolbackend.enums.ConceptType;
import com.dia.ismdtoolbackend.enums.SearchSource;
import com.dia.ismdtoolbackend.exception.NkdEndpointException;
import com.dia.ismdtoolbackend.exception.NkdResourceNotFoundException;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.query.NKDSPARQLBrowseQuery;
import com.dia.ismdtoolbackend.service.NkdDetailService;
import com.dia.ismdtoolbackend.service.rpp.RppSnapshotHolder;
import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import com.dia.ismdtoolbackend.utility.security.SparqlIriValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
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
    private final JsonExporter jsonExporter;
    private final RppSnapshotHolder rppSnapshotHolder;
    private final ReferencedConceptsEnricher referencedConceptsEnricher;

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

        // Concepts are loaded eagerly by the CONSTRUCT path (skos:inScheme), so
        // size is the authoritative count for this ontology. No extra round-trip.
        if (detail.getConcepts() != null) {
            detail.setConceptCount(detail.getConcepts().size());
        }

        return new GetNkdOntologyDto(detail);
    }

    @Override
    public byte[] downloadOntology(String iri, String format) {
        ensureEndpointConfigured();
        validateIri(iri);
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("Formát stahování musí být zadán (ttl nebo json-ld).");
        }
        String normalized = format.trim().toLowerCase();
        if (!normalized.equals("ttl") && !normalized.equals("json-ld")) {
            throw new IllegalArgumentException("Nepodporovaný formát: " + format + " (povolené: ttl, json-ld).");
        }

        Optional<Model> rawModel;
        try {
            rawModel = nkdSparqlClient.fetchPublishedOntologyRaw(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching ontology for download {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        Model model = rawModel.orElseThrow(() -> {
            log.info("Ontology not found in NKD for download: {}", iri);
            return new NkdResourceNotFoundException("Slovník s IRI " + iri + " nebyl v NKD nalezen.");
        });

        // Not closed: fetchPublishedOntologyRaw is @Cacheable, so this is the shared cached
        // instance — closing it would poison the entry for the rest of its TTL.
        String body;
        if (normalized.equals("ttl")) {
            StringWriter writer = new StringWriter();
            model.write(writer, "TTL");
            body = writer.toString();
        } else {
            // NKD already publishes OFN-aligned RDF. We bypass the local-store
            // OFN re-formatting pipeline (TurtleFilterUtil/TurtleFormatterUtil)
            // because applying it to an already-OFN payload is a noop at best
            // and lossy at worst. JsonExporter is enough.
            body = jsonExporter.exportToJson(model);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public GetNkdConceptDto getConceptDetail(String iri, String ontologyIri) {
        ensureEndpointConfigured();
        validateIri(iri);
        if (ontologyIri != null && !ontologyIri.isBlank()) {
            validateIri(ontologyIri);
        }

        Optional<NkdSparqlClient.PublishedConcept> conceptResult;
        try {
            conceptResult = nkdSparqlClient.fetchPublishedConceptWithScheme(iri);
        } catch (RuntimeException e) {
            log.warn("NKD SPARQL error while fetching concept {}: {}", iri, e.getMessage());
            throw new NkdEndpointException("NKD SPARQL endpoint je nedostupný.", e);
        }

        NkdSparqlClient.PublishedConcept published = conceptResult.orElseThrow(() -> {
            log.info("Concept not found in NKD: {}", iri);
            return new NkdResourceNotFoundException("Pojem s IRI " + iri + " nebyl v NKD nalezen.");
        });

        OntologyDetailModel.ConceptDetailModel detail = published.detail();
        // NKD detail context: resolve referenced concepts NKD-only so an IRI that
        // also exists in ISMD stays in the NKD context the user is viewing.
        referencedConceptsEnricher.enrich(detail, SearchSource.NKD);
        resolveRppReferences(detail);

        // Query param wins (FE supplies it as breadcrumb context); fall back to
        // the skos:inScheme target parsed from the NKD response so the FE has
        // an IRI to deep-link the parent slovník with.
        String resolvedOntologyIri =
                (ontologyIri != null && !ontologyIri.isBlank()) ? ontologyIri : published.ontologyIri();

        return new GetNkdConceptDto(detail, resolvedOntologyIri);
    }

    private void resolveRppReferences(OntologyDetailModel.ConceptDetailModel detail) {
        String agendaIri = detail.getAgenda();
        if (agendaIri != null) {
            rppSnapshotHolder.findAgendaByIri(agendaIri).ifPresent(detail::setAgendaResolved);
        }
        String aisIri = detail.getAis();
        if (aisIri != null) {
            rppSnapshotHolder.findIsvsByIri(aisIri).ifPresent(detail::setAisResolved);
        }
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

        // Same batched metadata SELECT as the browse path — one round-trip for all IRIs.
        // No concept-count batch on this lookup-by-IRI flow, so conceptCount stays null
        // (omitted from JSON via NON_NULL on the item DTO). A stale bookmarked IRI absent
        // from NKD simply yields no rows and is skipped — it never blanks the row.
        List<NkdOntologyListItemDto> items = assembleListItems(iris, Map.of(), false);

        GetNkdOntologyListDto response = new GetNkdOntologyListDto();
        response.setOntologies(items);
        return response;
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

    /**
     * Shares {@link NkdSparqlClient#PUBLISHED_RESOURCE_CACHE} with the other NKD-published
     * projections — same source, same 24h-TTL freshness model — under its own key prefix, alongside
     * the client's {@code concept:} / {@code ontology:} keys.
     *
     * <p>Caching is not optional here: the previous implementation reached NKD through the
     * {@code @Cacheable} {@code fetchPublishedOntology}, so dropping to a raw SELECT made the query
     * cheaper but sent every warm request to NKD live (measured: 2ms → ~1s).
     */
    @Override
    @Cacheable(cacheNames = NkdSparqlClient.PUBLISHED_RESOURCE_CACHE,
            key = "'conceptList:' + #ontologyIri")
    public List<MinimalConceptDto> listOntologyConcepts(String ontologyIri) {
        validateIri(ontologyIri);
        ensureEndpointConfigured();

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
        int totalOntologies = getCachedTotalOntologies();
        int totalConcepts = getCachedTotalConcepts();
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
        Map<String, ListItemMeta> metaByIri = fetchListItemMetadata(pageIris);
        List<NkdOntologyListItemDto> items = new ArrayList<>(pageIris.size());
        for (String iri : pageIris) {
            ListItemMeta meta = metaByIri.get(iri);
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
     * one per description language) result into one {@link ListItemMeta} per ontology.
     * On SPARQL failure returns an empty map — callers then skip every IRI, which the
     * browse/list flows already treat as "page returns what it can".
     */
    private Map<String, ListItemMeta> fetchListItemMetadata(List<String> iris) {
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

        Map<String, ListItemMeta.Builder> builders = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String iri = row.get("ontology");
            if (iri == null) continue;
            ListItemMeta.Builder b = builders.computeIfAbsent(iri, k -> new ListItemMeta.Builder());

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

        Map<String, ListItemMeta> out = new LinkedHashMap<>(builders.size() * 2);
        builders.forEach((iri, b) -> out.put(iri, b.build()));
        return out;
    }

    /**
     * Assembled list-item metadata for one ontology. {@code nameMap} is the single-entry
     * {@code {cs: <label>}} map (empty when unlabelled); {@code descriptionMap} is the
     * multilingual description map (empty when none). Dates are the raw literal strings or
     * null. The {@link Builder} folds the multi-row SELECT result.
     */
    private record ListItemMeta(Map<String, String> nameMap,
                                Map<String, String> descriptionMap,
                                String creationDate,
                                String modificationDate) {
        static final class Builder {
            private String label;       // rdfs:label (wins)
            private String prefLabel;   // skos:prefLabel (fallback)
            private final Map<String, String> description = new LinkedHashMap<>();
            private String creationDate;
            private String modificationDate;

            void offerLabel(String labelVal, String prefLabelVal) {
                if (label == null && labelVal != null && !labelVal.trim().isEmpty()) {
                    label = labelVal;
                }
                if (prefLabel == null && prefLabelVal != null && !prefLabelVal.trim().isEmpty()) {
                    prefLabel = prefLabelVal;
                }
            }

            void putDescription(String lang, String value) {
                // First value per lang wins — deterministic, matches single-pass extractor.
                description.putIfAbsent(lang, value);
            }

            void offerCreation(String dateTime, String date) {
                if (creationDate == null) {
                    creationDate = firstNonBlank(dateTime, date);
                }
            }

            void offerModification(String dateTime, String date) {
                if (modificationDate == null) {
                    modificationDate = firstNonBlank(dateTime, date);
                }
            }

            private static String firstNonBlank(String a, String b) {
                if (a != null && !a.trim().isEmpty()) return a;
                if (b != null && !b.trim().isEmpty()) return b;
                return null;
            }

            ListItemMeta build() {
                String name = (label != null) ? label : prefLabel;
                Map<String, String> nameMap = (name == null || name.trim().isEmpty())
                        ? Map.of()
                        : Map.of(DEFAULT_LANG, name);
                return new ListItemMeta(nameMap, Map.copyOf(description), creationDate, modificationDate);
            }
        }
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
