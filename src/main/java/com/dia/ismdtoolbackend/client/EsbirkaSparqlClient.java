package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.FragmentResolutionModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.query.EsbirkaSPARQLQuery;
import com.dia.ismdtoolbackend.utility.sparql.HttpSparqlExecutor;
import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Component
@Slf4j
public class EsbirkaSparqlClient {

    /**
     * Endpoint label used for {@link com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException} so the
     * global handler can render a per-endpoint Czech message.
     */
    public static final String ESBIRKA_LABEL = "e-Sbírka";

    @Value("${esbirka.sparql.endpoint:}")
    private String endpoint;

    @Value("${esbirka.sparql.timeout:10000}")
    private int sparqlTimeout;

    @PostConstruct
    void warnIfEndpointMissing() {
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("esbirka.sparql.endpoint is not configured — /api/eli/* endpoints will return 503 until set.");
        }
    }

    public List<LawModel> searchLaws(String q, int limit) {
        return executeSelect("law search",
                EsbirkaSPARQLQuery.buildLawSearchQuery(q, limit),
                this::mapLawRows);
    }

    public List<LawVersionModel> fetchVersions(String lawIri) {
        return executeSelect("version list",
                EsbirkaSPARQLQuery.buildVersionListQuery(lawIri),
                this::mapVersionRows);
    }

    public List<FragmentModel> fetchFragments(String versionIri) {
        return executeSelect("fragment tree",
                EsbirkaSPARQLQuery.buildFragmentTreeQuery(versionIri),
                this::mapFragmentRows);
    }

    /**
     * Resolve a single fragment IRI to its display citation + parent version
     * end-of-validity date + is-latest flag. Returns empty Optional when SPARQL
     * returns zero rows (fragment not in dataset). Propagates
     * {@link com.dia.ismdtoolbackend.exception.SparqlEndpointUnavailableException}
     * on connection failures via the executor.
     */
    public Optional<FragmentResolutionModel> resolveFragment(String fragmentIri, String versionIri, String lawIri) {
        List<FragmentResolutionModel> rows = executeSelect("fragment resolution",
                EsbirkaSPARQLQuery.buildResolveFragmentQuery(fragmentIri, versionIri, lawIri),
                this::mapResolutionRows);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private <T> List<T> executeSelect(String label, String query, Function<ResultSet, List<T>> mapper) {
        return executor().select("e-Sbírka " + label, query, mapper);
    }

    private HttpSparqlExecutor executor() {
        // Built per call so test reflection (`setField(client, "endpoint", ...)`)
        // continues to flow through. The executor is a ~24-byte wrapper around two
        // strings and an int — allocation cost is negligible compared to the SPARQL roundtrip.
        return new HttpSparqlExecutor(ESBIRKA_LABEL, endpoint, sparqlTimeout);
    }

    private List<LawModel> mapLawRows(ResultSet rs) {
        List<LawModel> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "akt");
            String citace = SparqlSolutions.literalString(sol, "citace");
            String cislo = SparqlSolutions.literalString(sol, "cislo");
            Integer rok = SparqlSolutions.literalInt(sol, "rok");
            String sbirka = SparqlSolutions.literalString(sol, "sbirka");
            if (iri == null || citace == null) {
                log.warn("Law row missing iri/citace; iri={}", iri);
                continue;
            }
            out.add(new LawModel(iri, citace, cislo, rok, sbirka));
        }
        return out;
    }

    private List<LawVersionModel> mapVersionRows(ResultSet rs) {
        List<LawVersionModel> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "zneni");
            LocalDate from = SparqlSolutions.literalDate(sol, "ucinnostOd");
            LocalDate to = SparqlSolutions.literalDate(sol, "ucinnostDo");
            String typ = SparqlSolutions.resourceUri(sol, "typ");
            boolean isLatest = SparqlSolutions.literalBool(sol, "isLatest");
            if (iri == null) {
                log.warn("Version row missing iri");
                continue;
            }
            out.add(new LawVersionModel(iri, from, to, typ, isLatest));
        }
        return out;
    }

    private List<FragmentModel> mapFragmentRows(ResultSet rs) {
        List<FragmentModel> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "fragment");
            String parent = SparqlSolutions.resourceUri(sol, "parent");
            String citation = SparqlSolutions.literalString(sol, "citace");
            String order = SparqlSolutions.literalString(sol, "order");
            if (iri == null || parent == null) {
                log.warn("Fragment row missing iri/parent; iri={}", iri);
                continue;
            }
            String kind = parseKindFromIri(iri);
            out.add(new FragmentModel(iri, parent, citation, kind, order));
        }
        return out;
    }

    private List<FragmentResolutionModel> mapResolutionRows(ResultSet rs) {
        List<FragmentResolutionModel> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String citation = SparqlSolutions.literalString(sol, "citace");
            LocalDate validUntil = SparqlSolutions.literalDate(sol, "ucinnostDo");
            boolean isLatest = SparqlSolutions.literalBool(sol, "isLatest");
            String bodyHtml = SparqlSolutions.literalString(sol, "obsah");
            out.add(new FragmentResolutionModel(citation, validUntil, isLatest, bodyHtml));
        }
        return out;
    }

    /**
     * Parse the fragment kind from the last IRI segment, e.g.
     * ".../par_122/odst_4/pism_g" → "pism". Returns "unknown" if no underscore segment found.
     */
    private static String parseKindFromIri(String iri) {
        int slash = iri.lastIndexOf('/');
        if (slash < 0 || slash >= iri.length() - 1) return "unknown";
        String last = iri.substring(slash + 1);
        int underscore = last.indexOf('_');
        return underscore > 0 ? last.substring(0, underscore) : last;
    }
}
