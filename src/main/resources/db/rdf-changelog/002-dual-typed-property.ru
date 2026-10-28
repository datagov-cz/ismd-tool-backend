# Vlastnost written as owl:DatatypeProperty AND owl:ObjectProperty (create path before c253e6b; dev 1,
# test 4). The user declared these concepts properties: owl:ObjectProperty and any slovníky:vztah marker
# a later upload added are removed. Concepts with both OWL kinds but no vlastnost marker are ambiguous
# and left for the OFN gate.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

DELETE { GRAPH ?graph { ?concept a owl:ObjectProperty, slovníky:vztah } }
WHERE {
  GRAPH ?graph {
    ?concept a slovníky:vlastnost, owl:DatatypeProperty, owl:ObjectProperty .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
  }
}
