#!/bin/bash

# Test SPARQL endpoint for the uploaded ontology
ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Testing NKD SPARQL Endpoint"
echo "Endpoint: $ENDPOINT"
echo "Ontology: $ONTOLOGY_IRI"
echo "=========================================="
echo ""

# Query 1: Check if ontology exists
echo "1. Checking if ontology exists in the endpoint..."
echo ""

QUERY_1=$(cat <<EOF
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
  -H "Accept: text/turtle" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" > /tmp/ontology_result.ttl

if [ -s /tmp/ontology_result.ttl ]; then
  echo "✓ Ontology data found:"
  cat /tmp/ontology_result.ttl
else
  echo "✗ No data found for ontology"
fi

echo ""
echo "=========================================="
echo ""

# Query 2: List all concepts from this ontology
echo "2. Listing all concepts from this ontology..."
echo ""

QUERY_2=$(cat <<EOF
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT DISTINCT ?concept ?label
WHERE {
  ?concept skos:inScheme <${ONTOLOGY_IRI}> .
  OPTIONAL { ?concept skos:prefLabel ?label }
}
LIMIT 20
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_2" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 3: Count concepts in this ontology
echo "3. Counting concepts in this ontology..."
echo ""

QUERY_3=$(cat <<EOF
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT (COUNT(DISTINCT ?concept) as ?count)
WHERE {
  ?concept skos:inScheme <${ONTOLOGY_IRI}> .
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_3" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 4: Test a specific concept (example)
echo "4. Testing if a specific concept exists..."
echo "   (Using first concept from the ontology if any)"
echo ""

QUERY_4=$(cat <<EOF
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?concept
WHERE {
  ?concept skos:inScheme <${ONTOLOGY_IRI}> .
}
LIMIT 1
EOF
)

CONCEPT_IRI=$(curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_4" | python3 -c "import sys, json; results = json.load(sys.stdin)['results']['bindings']; print(results[0]['concept']['value'] if results else '')")

if [ -n "$CONCEPT_IRI" ]; then
  echo "Found concept: $CONCEPT_IRI"
  echo ""
  echo "Fetching concept data..."

  QUERY_5=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

CONSTRUCT {
  ?concept ?p ?o .
  ?o ?nestedP ?nestedO .
}
WHERE {
  BIND(<${CONCEPT_IRI}> as ?concept)

  {
    ?concept ?p ?o .
  }
  UNION
  {
    ?concept ?p ?o .
    FILTER(isBlank(?o))
    ?o ?nestedP ?nestedO .
  }
}
EOF
)

  curl -s -X POST "$ENDPOINT" \
    -H "Accept: text/turtle" \
    -H "Content-Type: application/sparql-query" \
    --data-binary "$QUERY_5"
else
  echo "✗ No concepts found in this ontology"
fi

echo ""
echo "=========================================="
echo "Test complete"
echo "=========================================="