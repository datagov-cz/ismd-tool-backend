package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.ParameterizedSparqlString;

public class RppSPARQLQuery {

    // Predicate IRIs verified 2026-04-21 against https://rpp-opendata.egon.gov.cz/odrpp/sparql:
    //   Agenda  type: legislativní/sbírka/111/2009/pojem/agenda
    //   Agenda  code: legislativní/sbírka/111/2009/pojem/má-kód-agendy        (returns A-prefixed codes)
    //   Agenda  name: legislativní/sbírka/111/2009/pojem/má-název-agendy
    //   ISVS    type: legislativní/sbírka/365/2000/pojem/informační-systém-veřejné-správy
    //                legislativní/sbírka/365/2000/pojem/určený-informační-systém-veřejné-správy
    //                (upstream uses two disjoint classes — no subClassOf between them — so both
    //                 must be enumerated)
    //   ISVS    code: agendový/104/pojem/má-identifikátor-isvs                (numeric string id)
    //   ISVS    name: legislativní/sbírka/329/2020/pojem/název-isvs           (note: no "má-" prefix)
    //   ISVS→Agenda:  legislativní/sbírka/329/2020/pojem/poskytuje-služby-pro-výkon-agendy

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
                  VALUES ?isvsType {
                    <https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/informační-systém-veřejné-správy>
                    <https://slovník.gov.cz/legislativní/sbírka/365/2000/pojem/určený-informační-systém-veřejné-správy>
                  }
                  ?isvs a ?isvsType ;
                        <https://slovník.gov.cz/agendový/104/pojem/má-identifikátor-isvs> ?code ;
                        <https://slovník.gov.cz/legislativní/sbírka/329/2020/pojem/název-isvs> ?nazev .
                  OPTIONAL {
                    ?isvs <https://slovník.gov.cz/legislativní/sbírka/329/2020/pojem/poskytuje-služby-pro-výkon-agendy> ?agenda .
                  }
                }
                """);
        return pss.toString();
    }

    private RppSPARQLQuery() {}
}
