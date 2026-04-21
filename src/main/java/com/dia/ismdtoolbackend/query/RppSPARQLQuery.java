package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.ParameterizedSparqlString;

public class RppSPARQLQuery {

    // Locked by spec — do NOT adjust without updating .planning/rpp-integration-analysis.md:
    //   Agenda type: https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/agenda
    //   ISVS type:   https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/informační-systém-veřejné-správy
    // TODO (Phase 2 smoke test): verify the four predicate IRIs (má-kód-agendy, má-název-agendy,
    // má-kód-isvs, má-název-isvs) and the OPTIONAL predicate (poskytuje-služby-pro-agendu) against
    // live RPP. They follow the RPP naming pattern but are not referenced elsewhere in this repo.

    public static String buildAgendaListQuery() {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT ?agenda ?code ?nazev
                WHERE {
                  ?agenda a <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/agenda> ;
                          <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/má-kód-agendy> ?code ;
                          <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/má-název-agendy> ?nazev .
                }
                """);
        return pss.toString();
    }

    public static String buildIsvsListQuery() {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

                SELECT ?isvs ?code ?nazev ?agenda
                WHERE {
                  ?isvs a <https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/informační-systém-veřejné-správy> ;
                        <https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/má-kód-isvs> ?code ;
                        <https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/má-název-isvs> ?nazev .
                  OPTIONAL {
                    ?isvs <https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/poskytuje-služby-pro-agendu> ?agenda .
                  }
                }
                """);
        return pss.toString();
    }

    private RppSPARQLQuery() {}
}
