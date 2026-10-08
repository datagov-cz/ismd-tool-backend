package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelist;
import com.dia.ismdtoolbackend.models.nkod.NkodCodelistEntry;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetDetail;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
import com.dia.ismdtoolbackend.models.nkod.NkodDistribution;
import com.dia.ismdtoolbackend.query.NKODSPARQLCodelistQuery;
import com.dia.ismdtoolbackend.query.NKODSPARQLDatasetQuery;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Queries the NKOD catalogue for {@code dcat:Dataset} records.
 *
 * <p>Gzip is not configured here: {@link HttpSparqlExecutor} already sends
 * {@code Accept-Encoding: gzip, deflate} on every query.
 */
@Component
@Slf4j
public class NkodSparqlClient {

    /** Endpoint label surfaced in Czech 503 messages by the global handler. */
    public static final String NKOD_LABEL = "NKOD";

    private final HttpSparqlExecutor executor;
    private final NkodConfig config;

    public NkodSparqlClient(NkodConfig config,
                            @Qualifier("externalSparqlHttpClient") HttpClient externalSparqlHttpClient) {
        this.config = config;
        this.executor = new HttpSparqlExecutor(
                NKOD_LABEL,
                config.getSparql().getEndpoint(),
                config.getSparql().getTimeout(),
                externalSparqlHttpClient,
                config.getSparql().getMaxConcurrentRequests());
    }

    @PostConstruct
    void warnIfEndpointMissing() {
        if (!executor.isConfigured()) {
            log.warn("nkod.sparql.endpoint is not configured — /api/nkod/* endpoints will return 503 until set.");
        }
    }

    public boolean isEndpointConfigured() {
        return executor.isConfigured();
    }

    /**
     * Harvests every dataset's IRI, titles and descriptions in one round-trip.
     *
     * <p>One SPARQL row per dataset per language, collapsed here into one
     * {@link NkodDatasetRow} per dataset with language-keyed maps.
     */
    public List<NkodDatasetRow> harvestDatasets() {
        String query = NKODSPARQLDatasetQuery.buildHarvestQuery(config.getSnapshot().getMaxRows());
        return executor.select("NKOD harvest", query, this::mapHarvestRows);
    }

    private List<NkodDatasetRow> mapHarvestRows(ResultSet rs) {
        Map<String, NkodDatasetRow> byIri = new LinkedHashMap<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "ds");
            if (iri == null) {
                continue;
            }
            NkodDatasetRow row = byIri.computeIfAbsent(iri,
                    k -> new NkodDatasetRow(k, new LinkedHashMap<>(), new LinkedHashMap<>()));
            putLangValue(row.name(), sol, "nazev", "nazevLang");
            putLangValue(row.description(), sol, "popis", "popisLang");
        }
        return new ArrayList<>(byIri.values());
    }

    /**
     * The datasets annotated with one concept, newest-first ordering left to the caller.
     *
     * <p>Returns an empty list both when the concept has no datasets and when the IRI is
     * unsafe.
     *
     * <p>Queried live rather than served from the harvested snapshot: the snapshot carries only
     * titles and descriptions, not the {@code týká-se-pojmu} annotations this reads.
     */
    public List<NkodDatasetRow> fetchDatasetsByConcept(String conceptIri) {
        String normalized = SparqlSolutions.toRawUtf8(conceptIri);
        String query = NKODSPARQLDatasetQuery.buildDatasetsByConceptQuery(
                normalized, config.getSnapshot().getMaxRows());
        if (query == null) {
            log.warn("Rejected unsafe concept IRI for NKOD dataset lookup: {}", conceptIri);
            return List.of();
        }
        return executor.select("NKOD datasets by concept", query, this::mapHarvestRows);
    }

    /**
     * Full detail of one dataset, or empty when the dataset is absent from the catalogue.
     *
     * <p>The detail query is all-OPTIONAL, so a dataset with no title is indistinguishable
     * from a missing one by its rows alone — an ASK probe settles it, and only then do we
     * spend a second round-trip.
     */
    public Optional<NkodDatasetDetail> fetchDatasetDetail(String rawDatasetIri) {
        String datasetIri = SparqlSolutions.toRawUtf8(rawDatasetIri);
        String existsQuery = NKODSPARQLDatasetQuery.buildDatasetExistsQuery(datasetIri);
        String detailQuery = NKODSPARQLDatasetQuery.buildDatasetDetailQuery(datasetIri);
        if (existsQuery == null || detailQuery == null) {
            log.warn("Rejected unsafe NKOD dataset IRI: {}", rawDatasetIri);
            return Optional.empty();
        }

        boolean exists = executor.ask("NKOD dataset exists", existsQuery);
        if (!exists) {
            return Optional.empty();
        }

        NkodDatasetDetail detail = executor.select("NKOD dataset detail", detailQuery,
                rs -> mapDetailRows(datasetIri, rs));

        return Optional.of(withDistributions(datasetIri, detail));
    }

    /**
     * Adds the dataset's distributions in a second round-trip.
     *
     * <p>Separate from the detail query because both distributions and {@code týká-se-pojmu}
     * are multi-valued and would otherwise cross-product.
     *
     * <p>Fails soft: distributions are supplementary to the concept list this page exists
     * for, so a failure here returns the detail without them rather than 503-ing the whole
     * page.
     */
    private NkodDatasetDetail withDistributions(String datasetIri, NkodDatasetDetail detail) {
        String query = NKODSPARQLDatasetQuery.buildDatasetDistributionsQuery(datasetIri);
        if (query == null) {
            return detail;
        }
        List<NkodDistribution> distributions;
        try {
            distributions = executor.select("NKOD dataset distributions", query,
                    NkodSparqlClient::mapDistributionRows);
        } catch (RuntimeException e) {
            log.warn("Could not load distributions for NKOD dataset {}: {}",
                    datasetIri, e.toString());
            distributions = List.of();
        }
        return new NkodDatasetDetail(detail.iri(), detail.name(), detail.description(),
                detail.conceptIris(), distributions);
    }

    /**
     * Collapses one row per distribution per title language into one entry per distribution.
     */
    private static List<NkodDistribution> mapDistributionRows(ResultSet rs) {
        Map<String, Map<String, String>> namesByDist = new LinkedHashMap<>();
        Map<String, QuerySolution> firstRowByDist = new LinkedHashMap<>();

        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "dist");
            if (iri == null) {
                continue;
            }
            firstRowByDist.putIfAbsent(iri, sol);
            putLangValue(namesByDist.computeIfAbsent(iri, k -> new LinkedHashMap<>()),
                    sol, "nazev", "nazevLang");
        }

        List<NkodDistribution> result = new ArrayList<>(firstRowByDist.size());
        firstRowByDist.forEach((iri, sol) -> {
            String downloadUrl = SparqlSolutions.resourceUri(sol, "stahovaciUrl");
            String accessUrl = SparqlSolutions.resourceUri(sol, "pristupoveUrl");
            boolean isService = SparqlSolutions.resourceUri(sol, "sluzba") != null
                    || downloadUrl == null;
            result.add(new NkodDistribution(
                    iri,
                    namesByDist.getOrDefault(iri, Map.of()),
                    downloadUrl != null ? downloadUrl : accessUrl,
                    SparqlSolutions.resourceUri(sol, "format"),
                    SparqlSolutions.resourceUri(sol, "mediaTyp"),
                    isService));
        });
        return result;
    }

    private NkodDatasetDetail mapDetailRows(String datasetIri, ResultSet rs) {
        Map<String, String> name = new LinkedHashMap<>();
        Map<String, String> description = new LinkedHashMap<>();
        LinkedHashSet<String> conceptIris = new LinkedHashSet<>();

        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            putLangValue(name, sol, "nazev", "nazevLang");
            putLangValue(description, sol, "popis", "popisLang");
            String pojem = SparqlSolutions.resourceUri(sol, "pojem");
            if (pojem != null) {
                conceptIris.add(pojem);
            }
        }
        return new NkodDatasetDetail(datasetIri, name, description,
                List.copyOf(conceptIris), List.of());
    }

    /**
     * Every codelist dataset in the catalogue, one entry per dataset in title order, with
     * publisher names resolved. Codelist IRIs are left unresolved — they live in the
     * distribution files, not the catalogue.
     *
     * <p>Two round-trips; if either fails the whole call throws, so no entry ever carries a
     * publisher IRI because the name query failed.
     */
    public List<NkodCodelistEntry> fetchCodelists() {
        List<CodelistRow> rows = executor.select("NKOD codelists",
                NKODSPARQLCodelistQuery.buildListQuery(), NkodSparqlClient::mapCodelistRows);
        Map<String, String> names = fetchPublisherNames(publisherIris(rows));
        return groupByDataset(rows, names);
    }

    /** Czech names of the given publishers; publishers without one are absent from the map. */
    Map<String, String> fetchPublisherNames(Set<String> publisherIris) {
        String query = NKODSPARQLCodelistQuery.buildPublisherNamesQuery(publisherIris);
        if (query == null) {
            return Map.of();
        }
        return executor.select("NKOD codelist publishers", query, NkodSparqlClient::mapPublisherNames);
    }

    /** One list-query row: a dataset with at most one of its download URLs. */
    record CodelistRow(String datasetIri, String title, String downloadUrl, String publisherIri,
                       String description, String rppIdentifier, String validFrom) {
    }

    static List<CodelistRow> mapCodelistRows(ResultSet rs) {
        List<CodelistRow> rows = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String dataset = SparqlSolutions.resourceUri(sol, "dataset");
            String title = SparqlSolutions.literalString(sol, "title");
            if (dataset == null || title == null || title.isBlank()) {
                log.warn("Skipping NKOD codelist row without dataset or title: dataset={}", dataset);
                continue;
            }
            rows.add(new CodelistRow(
                    dataset,
                    title,
                    SparqlSolutions.resourceUri(sol, "downloadUrl"),
                    SparqlSolutions.resourceUri(sol, "publisher"),
                    SparqlSolutions.literalString(sol, "description"),
                    uriOrLiteral(sol, "rppIdentifier"),
                    SparqlSolutions.literalString(sol, "validFrom")));
        }
        return rows;
    }

    static Map<String, String> mapPublisherNames(ResultSet rs) {
        Map<String, String> names = new HashMap<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String publisher = SparqlSolutions.resourceUri(sol, "publisher");
            String name = SparqlSolutions.literalString(sol, "name");
            if (publisher != null && name != null && !name.isBlank()) {
                names.putIfAbsent(publisher, name);
            }
        }
        return names;
    }

    private static Set<String> publisherIris(List<CodelistRow> rows) {
        Set<String> iris = new TreeSet<>();
        for (CodelistRow row : rows) {
            if (row.publisherIri() != null) {
                iris.add(row.publisherIri());
            }
        }
        return iris;
    }

    /**
     * Collapses the query's one-row-per-download-URL into one entry per dataset, keeping the
     * query's title order. Metadata depends only on the dataset, so the first row's is taken.
     */
    static List<NkodCodelistEntry> groupByDataset(List<CodelistRow> rows, Map<String, String> names) {
        Map<String, CodelistRow> firstRow = new LinkedHashMap<>();
        Map<String, Set<String>> urls = new LinkedHashMap<>();
        for (CodelistRow row : rows) {
            firstRow.putIfAbsent(row.datasetIri(), row);
            Set<String> datasetUrls = urls.computeIfAbsent(row.datasetIri(), k -> new TreeSet<>());
            if (row.downloadUrl() != null) {
                datasetUrls.add(row.downloadUrl());
            }
        }

        List<NkodCodelistEntry> entries = new ArrayList<>(firstRow.size());
        firstRow.forEach((iri, row) -> {
            String publisher = row.publisherIri() == null ? null
                    : names.getOrDefault(row.publisherIri(), row.publisherIri());
            if (publisher == null) {
                log.warn("NKOD codelist dataset {} has no publisher", iri);
                publisher = "";
            }
            NkodCodelist codelist = NkodCodelist.builder()
                    .datasetIri(iri)
                    .title(row.title())
                    .publisher(publisher)
                    .description(blankToNull(row.description()))
                    .codeListNumber(lastPathSegment(row.rppIdentifier()))
                    .validFrom(blankToNull(row.validFrom()))
                    .build();
            entries.add(new NkodCodelistEntry(codelist, List.copyOf(urls.get(iri))));
        });
        return entries;
    }

    /** {@code …/mdzastresujicids/151} → {@code "151"}; null when absent or segment-less. */
    static String lastPathSegment(String iri) {
        if (iri == null) {
            return null;
        }
        String trimmed = iri.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        String segment = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return segment.isBlank() ? null : segment;
    }

    /** {@code dcterms:identifier} is published both as an IRI and as a literal. */
    private static String uriOrLiteral(QuerySolution sol, String var) {
        String uri = SparqlSolutions.resourceUri(sol, var);
        return uri != null ? uri : SparqlSolutions.literalString(sol, var);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Records a language-tagged literal. Untagged literals are filed under {@code "cs"}:
     * the catalogue is Czech, and dropping them would blank otherwise-valid rows.
     */
    private static void putLangValue(Map<String, String> target, QuerySolution sol,
                                     String valueVar, String langVar) {
        String value = SparqlSolutions.literalString(sol, valueVar);
        if (value == null || value.isBlank()) {
            return;
        }
        String lang = SparqlSolutions.literalString(sol, langVar);
        String key = (lang == null || lang.isBlank()) ? "cs" : lang;
        target.putIfAbsent(key, value);
    }
}