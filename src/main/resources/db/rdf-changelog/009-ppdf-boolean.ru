# The PPDF flag stored as text "true"/"false" (edit path, finding C12). OFN wants xsd:boolean. Any other
# text is left for the OFN gate to report.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>
PREFIX a104:     <https://slovník.gov.cz/agendový/104/pojem/>

DELETE { GRAPH ?graph { ?concept a104:je-sdílen-v-propojeném-datovém-fondu ?text } }
INSERT { GRAPH ?graph { ?concept a104:je-sdílen-v-propojeném-datovém-fondu ?flag } }
WHERE {
  GRAPH ?graph {
    ?concept a104:je-sdílen-v-propojeném-datovém-fondu ?text .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER(isLiteral(?text) && DATATYPE(?text) != xsd:boolean)
    BIND(LCASE(STR(?text)) AS ?lexical)
    FILTER(?lexical IN ("true", "false"))
    BIND(STRDT(?lexical, xsd:boolean) AS ?flag)
  }
}
