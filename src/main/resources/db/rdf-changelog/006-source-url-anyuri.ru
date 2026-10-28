# schema:url of a non-legal source stored as an IRI or an untyped string (finding C5). OFN wants an
# xsd:anyURI literal. Runs after 005, so only the OFN source predicates need matching.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>
PREFIX schema:   <http://schema.org/>

DELETE { GRAPH ?graph { ?source schema:url ?url } }
INSERT { GRAPH ?graph { ?source schema:url ?typed } }
WHERE {
  GRAPH ?graph {
    VALUES ?link { slovníky:definující-nelegislativní-zdroj slovníky:související-nelegislativní-zdroj }
    ?concept ?link ?source .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    ?source schema:url ?url .
    FILTER(!isLiteral(?url) || DATATYPE(?url) != xsd:anyURI)
    BIND(STRDT(STR(?url), xsd:anyURI) AS ?typed)
  }
}
