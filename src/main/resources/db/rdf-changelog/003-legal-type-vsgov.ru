# Typ subjektu/objektu práva written in the slovníky: namespace (finding C3). OFN uses vsgov:.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>
PREFIX vsgov:    <https://slovník.gov.cz/veřejný-sektor/pojem/>

DELETE { GRAPH ?graph { ?concept a ?legacy } }
INSERT { GRAPH ?graph { ?concept a ?ofn } }
WHERE {
  GRAPH ?graph {
    VALUES (?legacy ?ofn) {
      (slovníky:typ-subjektu-práva vsgov:typ-subjektu-práva)
      (slovníky:typ-objektu-práva  vsgov:typ-objektu-práva)
    }
    ?concept a ?legacy .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
  }
}
