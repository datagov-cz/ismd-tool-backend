# The junk class <https://slovník.gov.cz/pojem> (a owl:Class, rdfs:label "pojem") that the common
# library's base model added to every vocabulary created in the tool (finding C14).
PREFIX rdf:      <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:     <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl:      <http://www.w3.org/2002/07/owl#>
PREFIX xsd:      <http://www.w3.org/2001/XMLSchema#>
PREFIX skos:     <http://www.w3.org/2004/02/skos/core#>
PREFIX slovníky: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

DELETE WHERE { GRAPH ?graph { <https://slovník.gov.cz/pojem> ?p ?o } }
