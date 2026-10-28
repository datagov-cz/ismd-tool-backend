# Vocabulary dates stored as a named node <vocabulary>/casovy-okamzik-vytvoreni with a string value and
# no zone (LocalDateTime.now(), finding C8). OFN wants a blank čas:časový-okamžik whose čas:datum-a-čas is
# an xsd:dateTimeStamp. The deployed servers run in UTC (no TZ set), so a value without a zone is read as
# UTC and gets "Z" (decided 2026-10-28); a value that already has a zone keeps it. Values that are not a
# date-time are left for the OFN gate to report.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>
PREFIX čas:      <https://slovník.gov.cz/generický/čas/pojem/>

INSERT { GRAPH ?graph { ?graph ?when _:instant . _:instant a čas:časový-okamžik ; čas:datum-a-čas ?stamp } }
WHERE {
  GRAPH ?graph {
    VALUES ?when { slovníky:okamžik-vytvoření slovníky:okamžik-poslední-změny }
    ?graph ?when ?named .
    FILTER(isIRI(?named))
    ?named čas:datum-a-čas ?value .
    BIND(STR(?value) AS ?lexical)
    FILTER(REGEX(?lexical, "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$"))
    BIND(STRDT(IF(REGEX(?lexical, "(Z|[+-]\\d{2}:\\d{2})$"), ?lexical, CONCAT(?lexical, "Z")), xsd:dateTimeStamp) AS ?stamp)
  }
} ;

DELETE { GRAPH ?graph { ?graph ?when ?named . ?named ?p ?o } }
WHERE {
  GRAPH ?graph {
    VALUES ?when { slovníky:okamžik-vytvoření slovníky:okamžik-poslední-změny }
    ?graph ?when ?named .
    FILTER(isIRI(?named))
    ?named čas:datum-a-čas ?value ;
           ?p ?o .
    FILTER(REGEX(STR(?value), "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$"))
  }
} ;

DELETE { GRAPH ?graph { ?instant čas:datum-a-čas ?value } }
INSERT { GRAPH ?graph { ?instant čas:datum-a-čas ?stamp } }
WHERE {
  GRAPH ?graph {
    VALUES ?when { slovníky:okamžik-vytvoření slovníky:okamžik-poslední-změny }
    ?graph ?when ?instant .
    FILTER(isBlank(?instant))
    ?instant čas:datum-a-čas ?value .
    FILTER(DATATYPE(?value) != xsd:dateTimeStamp)
    BIND(STR(?value) AS ?lexical)
    FILTER(REGEX(?lexical, "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})?$"))
    BIND(STRDT(IF(REGEX(?lexical, "(Z|[+-]\\d{2}:\\d{2})$"), ?lexical, CONCAT(?lexical, "Z")), xsd:dateTimeStamp) AS ?stamp)
  }
}
