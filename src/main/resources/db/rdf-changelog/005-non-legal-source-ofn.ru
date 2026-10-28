# Non-legal sources linked under the vocabulary namespace or https://slovník.gov.cz/ (finding C5). The
# link moves to the slovníky: predicate of the same name; the digital-object node itself is kept.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

DELETE { GRAPH ?graph { ?concept ?legacy ?source } }
INSERT { GRAPH ?graph { ?concept ?ofn ?source } }
WHERE {
  GRAPH ?graph {
    ?concept ?legacy ?source .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    BIND(REPLACE(STR(?legacy), "^.*[/#]", "") AS ?name)
    FILTER(?name IN ("definující-nelegislativní-zdroj", "související-nelegislativní-zdroj"))
    BIND(IRI(CONCAT(STR(slovníky:), ?name)) AS ?ofn)
    FILTER(?legacy != ?ofn)
  }
}
