# Třída written without owl:Class (create path before 3de0418c, finding C2). Adds owl:Class to every
# concept owned by the vocabulary that carries the slovníky:třída marker and no OWL kind. The marker stays:
# it is derived from the OWL kind (D4), which this makes true again.
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

INSERT { GRAPH ?graph { ?concept a owl:Class } }
WHERE {
  GRAPH ?graph {
    ?concept a slovníky:třída .
    FILTER(STRSTARTS(STR(?concept), CONCAT(STR(?graph), "/")))
    FILTER NOT EXISTS {
      VALUES ?kind { owl:Class owl:DatatypeProperty owl:ObjectProperty }
      ?concept a ?kind .
    }
  }
}
