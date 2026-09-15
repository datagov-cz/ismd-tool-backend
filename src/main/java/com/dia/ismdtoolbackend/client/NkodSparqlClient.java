package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.config.NkodConfig;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetDetail;
import com.dia.ismdtoolbackend.models.nkod.NkodDatasetRow;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Full detail of one dataset, or empty when the dataset is absent from the catalogue.
     *
     * <p>The detail query is all-OPTIONAL, so a dataset with no title is indistinguishable
     * from a missing one by its rows alone — an ASK probe settles it, and only then do we
     * spend a second round-trip.
     */
    public Optional<NkodDatasetDetail> fetchDatasetDetail(String datasetIri) {
        String existsQuery = NKODSPARQLDatasetQuery.buildDatasetExistsQuery(datasetIri);
        String detailQuery = NKODSPARQLDatasetQuery.buildDatasetDetailQuery(datasetIri);
        if (existsQuery == null || detailQuery == null) {
            log.warn("Rejected unsafe NKOD dataset IRI: {}", datasetIri);
            return Optional.empty();
        }

        boolean exists = executor.ask("NKOD dataset exists", existsQuery);
        if (!exists) {
            return Optional.empty();
        }

        return Optional.of(executor.select("NKOD dataset detail", detailQuery,
                rs -> mapDetailRows(datasetIri, rs)));
    }

    private NkodDatasetDetail mapDetailRows(String datasetIri, ResultSet rs) {
        Map<String, String> name = new LinkedHashMap<>();
        Map<String, String> description = new LinkedHashMap<>();
        LinkedHashSet<String> conceptIris = new LinkedHashSet<>();
        String landingPage = null;

        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            putLangValue(name, sol, "nazev", "nazevLang");
            putLangValue(description, sol, "popis", "popisLang");
            if (landingPage == null) {
                landingPage = SparqlSolutions.resourceUri(sol, "vstupniStranka");
            }
            String pojem = SparqlSolutions.resourceUri(sol, "pojem");
            if (pojem != null) {
                conceptIris.add(pojem);
            }
        }
        return new NkodDatasetDetail(datasetIri, name, description, landingPage,
                List.copyOf(conceptIris));
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