#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Testing: Default Graph vs Named Graphs"
echo "=========================================="
echo ""

# Query 1: Check if ontology exists in DEFAULT graph (what Java code does)
echo "1. Query DEFAULT graph (what Java code does)..."
echo ""

QUERY_1=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?p ?o
WHERE {
  <${ONTOLOGY_IRI}> ?p ?o .
}
LIMIT 10
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 2: Check if ontology exists in ANY NAMED GRAPH
echo "2. Query ALL NAMED GRAPHS..."
echo ""

QUERY_2=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?g ?p ?o
WHERE {
  GRAPH ?g {
    <${ONTOLOGY_IRI}> ?p ?o .
  }
}
LIMIT 10
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_2" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 3: Check if data.gov.cz/slovník/ exists in DEFAULT graph
echo "3. Query DEFAULT graph for data.gov.cz/slovník/..."
echo ""

QUERY_3=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?s ?p ?o
WHERE {
  ?s ?p ?o .
  FILTER(REGEX(STR(?s), "data.gov.cz/slovn", "i"))
}
LIMIT 10
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_3" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 4: Try to query the https://data.gov.cz/slovník/ NAMED GRAPH specifically
echo "4. Query the data.gov.cz/slovník/ NAMED GRAPH..."
echo ""

QUERY_4=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?s ?p ?o
WHERE {
  GRAPH <https://data.gov.cz/slovník/> {
    ?s ?p ?o .
  }
}
LIMIT 100
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_4" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 5: Look for any concepts in the data.gov.cz/slovník/ named graph
echo "5. Look for concepts in data.gov.cz/slovník/ graph..."
echo ""

QUERY_5=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?concept ?label
WHERE {
  GRAPH <https://data.gov.cz/slovník/> {
    ?concept a skos:Concept .
    OPTIONAL { ?concept skos:prefLabel ?label }
  }
}
LIMIT 20
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_5" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Test complete"
echo "=========================================="