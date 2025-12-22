#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/vsparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Testing Ontology Properties"
echo "=========================================="
echo ""

# Query 1: Get ALL properties of the ontology (no filter)
echo "1. Get ALL properties of the ontology..."
echo ""

QUERY_1=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

SELECT ?p ?o
WHERE {
  <${ONTOLOGY_IRI}> ?p ?o .
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 2: Test the EXACT query from Java code (with restrictive filter)
echo "2. Test Java code query (with restrictive filter)..."
echo ""

QUERY_2=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

CONSTRUCT {
  ?ontology ?p ?o .
  ?o ?nestedP ?nestedO .
}
WHERE {
  BIND(<${ONTOLOGY_IRI}> as ?ontology)

  {
    ?ontology ?p ?o .
    FILTER(?p IN (rdf:type, skos:prefLabel, dcterms:title, dcterms:description, rdfs:label))
  }
  UNION
  {
    ?ontology ?p ?o .
    FILTER(?p IN (rdf:type, skos:prefLabel, dcterms:title, dcterms:description, rdfs:label))
    FILTER(isBlank(?o))
    ?o ?nestedP ?nestedO .
  }
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_2" | python3 -c "
import sys, json
data = json.load(sys.stdin)
if 'results' in data and 'bindings' in data['results']:
    print(json.dumps(data, indent=2))
    print(f\"\\nTriple count: {len(data['results']['bindings'])}\")
elif 'boolean' in data:
    print(json.dumps(data, indent=2))
else:
    # CONSTRUCT returns RDF, not JSON bindings
    print('CONSTRUCT query returned RDF data')
    print(json.dumps(data, indent=2) if data else 'Empty result')
"

echo ""
echo "=========================================="
echo ""

# Query 3: Check which of the expected properties exist
echo "3. Check which expected properties exist..."
echo ""

QUERY_3=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

SELECT ?p (COUNT(*) as ?count)
WHERE {
  <${ONTOLOGY_IRI}> ?p ?o .
  FILTER(?p IN (rdf:type, skos:prefLabel, dcterms:title, dcterms:description, rdfs:label))
}
GROUP BY ?p
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_3" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Test complete"
echo "=========================================="