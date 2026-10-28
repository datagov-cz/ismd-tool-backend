# Foreign concepts (IRI outside <vocabulary>/) put into the vocabulary's scheme by upload (Q7: a124 5,
# a3791 4 locally). They are references only: the skos:inScheme link is removed, everything else stays.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

DELETE { GRAPH ?graph { ?concept skos:inScheme ?graph } }
WHERE {
  GRAPH ?graph {
    ?concept skos:inScheme ?graph .
    FILTER(!STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
  }
}
