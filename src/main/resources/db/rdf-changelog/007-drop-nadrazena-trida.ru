# The internal nadřazená-třída duplicate of rdfs:subClassOf (finding C11), in any namespace. rdfs:subClassOf
# is the OFN term: it is added where only the duplicate exists, then the duplicate is removed.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

INSERT { GRAPH ?graph { ?concept rdfs:subClassOf ?broader } }
WHERE {
  GRAPH ?graph {
    ?concept ?legacy ?broader .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER(REPLACE(STR(?legacy), "^.*[/#]", "") = "nadřazená-třída")
    FILTER(isIRI(?broader))
  }
} ;

DELETE { GRAPH ?graph { ?concept ?legacy ?broader } }
WHERE {
  GRAPH ?graph {
    ?concept ?legacy ?broader .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER(REPLACE(STR(?legacy), "^.*[/#]", "") = "nadřazená-třída")
  }
}
