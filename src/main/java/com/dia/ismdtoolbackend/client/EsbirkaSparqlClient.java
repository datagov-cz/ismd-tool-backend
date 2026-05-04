package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.exception.EsbirkaUnavailableException;
import com.dia.ismdtoolbackend.models.eli.FragmentModel;
import com.dia.ismdtoolbackend.models.eli.LawModel;
import com.dia.ismdtoolbackend.models.eli.LawVersionModel;
import com.dia.ismdtoolbackend.query.EsbirkaSPARQLQuery;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class EsbirkaSparqlClient {

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

    private <T> List<T> executeSelect(String label, String query, Function<ResultSet, List<T>> mapper) {
        requireEndpoint();
        try (QueryExecution qe = QueryExecutionHTTPBuilder.service(endpoint)
                .query(query)
                .timeout(sparqlTimeout, TimeUnit.MILLISECONDS)
                .build()) {
            return mapper.apply(qe.execSelect());
        } catch (QueryExceptionHTTP | HttpException e) {
            throw new EsbirkaUnavailableException("e-Sbírka " + label + " fetch failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new EsbirkaUnavailableException("e-Sbírka " + label + " mapping failed: " + e.getMessage(), e);
        }
    }

    private List<LawModel> mapLawRows(ResultSet rs) {
        List<LawModel> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = resourceUri(sol, "akt");
            String citace = literalString(sol, "citace");
            String cislo = literalString(sol, "cislo");
            Integer rok = literalInt(sol, "rok");
            String sbirka = literalString(sol, "sbirka");
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
            String iri = resourceUri(sol, "zneni");
            LocalDate from = literalDate(sol, "ucinnostOd");
            LocalDate to = literalDate(sol, "ucinnostDo");
            String typ = resourceUri(sol, "typ");
            boolean isLatest = literalBool(sol, "isLatest");
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
            String iri = resourceUri(sol, "fragment");
            String parent = resourceUri(sol, "parent");
            String citation = literalString(sol, "citace");
            String order = literalString(sol, "order");
            if (iri == null || parent == null) {
                log.warn("Fragment row missing iri/parent; iri={}", iri);
                continue;
            }
            String kind = parseKindFromIri(iri);
            out.add(new FragmentModel(iri, parent, citation, kind, order));
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

    private void requireEndpoint() {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new EsbirkaUnavailableException("e-Sbírka endpoint not configured");
        }
    }

    private static String resourceUri(QuerySolution sol, String var) {
        return (sol.contains(var) && sol.get(var).isResource()) ? sol.getResource(var).getURI() : null;
    }

    private static String literalString(QuerySolution sol, String var) {
        return (sol.contains(var) && sol.get(var).isLiteral()) ? sol.getLiteral(var).getString() : null;
    }

    private static Integer literalInt(QuerySolution sol, String var) {
        if (!sol.contains(var) || !sol.get(var).isLiteral()) return null;
        try {
            return Integer.parseInt(sol.getLiteral(var).getString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate literalDate(QuerySolution sol, String var) {
        String s = literalString(sol, var);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean literalBool(QuerySolution sol, String var) {
        if (!sol.contains(var) || !sol.get(var).isLiteral()) return false;
        try {
            return sol.getLiteral(var).getBoolean();
        } catch (Exception e) {
            return false;
        }
    }
}
