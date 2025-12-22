#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/vsparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Testing CONSTRUCT Query Response Format"
echo "=========================================="
echo ""

# The exact query from the Java code
QUERY=$(cat <<EOF
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

echo "1. Testing with application/rdf+xml (Jena's default)..."
echo ""
curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/rdf+xml" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY" | head -50

echo ""
echo "=========================================="
echo ""

echo "2. Testing with text/turtle..."
echo ""
curl -s -X POST "$ENDPOINT" \
  -H "Accept: text/turtle" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY"

echo ""
echo "=========================================="
echo ""

echo "3. Testing with application/n-triples..."
echo ""
curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/n-triples" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY"

echo ""
echo "=========================================="
echo "Test complete"
echo "=========================================="