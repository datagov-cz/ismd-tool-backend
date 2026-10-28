# Role markers derived from the OWL kind (D4), after 001 and 002 fixed the kinds. For every owned concept
# with exactly one OWL kind: the matching marker is added if missing, and any other marker is removed.
# Concepts with no or several OWL kinds are left for the OFN gate.
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

INSERT { GRAPH ?graph { ?concept a ?marker } }
WHERE {
  GRAPH ?graph {
    VALUES (?kind ?marker) {
      (owl:Class slovníky:třída) (owl:DatatypeProperty slovníky:vlastnost) (owl:ObjectProperty slovníky:vztah)
    }
    ?concept a ?kind .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER NOT EXISTS {
      VALUES ?other { owl:Class owl:DatatypeProperty owl:ObjectProperty }
      ?concept a ?other .
      FILTER(?other != ?kind)
    }
  }
} ;

DELETE { GRAPH ?graph { ?concept a ?wrong } }
WHERE {
  GRAPH ?graph {
    VALUES (?kind ?marker) {
      (owl:Class slovníky:třída) (owl:DatatypeProperty slovníky:vlastnost) (owl:ObjectProperty slovníky:vztah)
    }
    VALUES ?wrong { slovníky:třída slovníky:vlastnost slovníky:vztah }
    ?concept a ?kind, ?wrong .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER(?wrong != ?marker)
    FILTER NOT EXISTS {
      VALUES ?other { owl:Class owl:DatatypeProperty owl:ObjectProperty }
      ?concept a ?other .
      FILTER(?other != ?kind)
    }
  }
}
