package com.dia.ismdtoolbackend.client;

import com.dia.ismdtoolbackend.exception.RppUnavailableException;
import com.dia.ismdtoolbackend.models.rpp.RppAgenda;
import com.dia.ismdtoolbackend.models.rpp.RppIsvs;
import com.dia.ismdtoolbackend.query.RppSPARQLQuery;
import com.dia.ismdtoolbackend.utility.sparql.SparqlSolutions;
import lombok.extern.slf4j.Slf4j;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.engine.http.QueryExceptionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class RppSparqlClient {

    @Value("${rpp.sparql.endpoint:}")
    private String rppEndpoint;

    @Value("${rpp.sparql.timeout:10000}")
    private int rppSparqlTimeout;

    public List<RppAgenda> fetchAllAgendas() {
        return executeSelect("agenda", RppSPARQLQuery.buildAgendaListQuery(), this::mapAgendaRows);
    }

    public List<RppIsvs> fetchAllIsvs() {
        return executeSelect("isvs", RppSPARQLQuery.buildIsvsListQuery(), this::mapIsvsRows);
    }

    private <T> List<T> executeSelect(String label, String query, Function<ResultSet, List<T>> mapper) {
        requireEndpoint();
        try (QueryExecution qe = QueryExecutionHTTPBuilder.service(rppEndpoint)
                .query(query)
                .timeout(rppSparqlTimeout, TimeUnit.MILLISECONDS)
                .build()) {
            return mapper.apply(qe.execSelect());
        } catch (QueryExceptionHTTP | HttpException e) {
            throw new RppUnavailableException("RPP " + label + " fetch failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RppUnavailableException("RPP " + label + " mapping failed: " + e.getMessage(), e);
        }
    }

    private List<RppAgenda> mapAgendaRows(ResultSet rs) {
        List<RppAgenda> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "agenda");
            String code = SparqlSolutions.literalString(sol, "code");
            String nazev = SparqlSolutions.literalString(sol, "nazev");
            if (code == null || nazev == null) {
                log.warn("RPP agenda row missing code/nazev; iri={}", iri);
                continue;
            }
            out.add(new RppAgenda(iri, code, nazev));
        }
        return out;
    }

    private List<RppIsvs> mapIsvsRows(ResultSet rs) {
        LinkedHashMap<String, RppIsvs> byIri = new LinkedHashMap<>();
        while (rs.hasNext()) {
            QuerySolution sol = rs.next();
            String iri = SparqlSolutions.resourceUri(sol, "isvs");
            String code = SparqlSolutions.literalString(sol, "code");
            String nazev = SparqlSolutions.literalString(sol, "nazev");
            String agendaIri = SparqlSolutions.resourceUri(sol, "agenda");
            if (iri == null || code == null || nazev == null) {
                log.warn("RPP isvs row missing iri/code/nazev; iri={}", iri);
                continue;
            }
            collapseIsvsRow(byIri, iri, code, nazev, agendaIri);
        }
        return new ArrayList<>(byIri.values());
    }

    private static void collapseIsvsRow(LinkedHashMap<String, RppIsvs> byIri,
                                        String iri, String code, String nazev, String agendaIri) {
        RppIsvs existing = byIri.get(iri);
        if (existing == null) {
            List<String> agendas = new ArrayList<>();
            if (agendaIri != null) {
                agendas.add(agendaIri);
            }
            byIri.put(iri, new RppIsvs(iri, code, nazev, agendas));
        } else if (agendaIri != null && !existing.getAgendaIris().contains(agendaIri)) {
            existing.getAgendaIris().add(agendaIri);
        }
    }

    private void requireEndpoint() {
        if (rppEndpoint == null || rppEndpoint.trim().isEmpty()) {
            throw new RppUnavailableException("RPP endpoint not configured");
        }
    }
}
