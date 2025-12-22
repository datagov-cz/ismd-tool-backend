#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Searching for specific vocabulary pattern"
echo "=========================================="
echo ""

# Search for vocabularies matching "slovník" pattern
echo "1. Looking for vocabularies with 'slovník' in the IRI..."
echo ""

QUERY_1=$(cat <<EOF
SELECT DISTINCT ?vocab
WHERE {
  ?vocab ?p ?o .
  FILTER(REGEX(STR(?vocab), "slovn.*k", "i"))
}
LIMIT 50
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Search for "metodiky-dat" pattern
echo "2. Looking for resources with 'metodiky-dat' in the IRI..."
echo ""

QUERY_2=$(cat <<EOF
SELECT DISTINCT ?resource
WHERE {
  ?resource ?p ?o .
  FILTER(REGEX(STR(?resource), "metodiky-dat", "i"))
}
LIMIT 50
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_2" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# List all ConceptSchemes from dia.gov.cz
echo "3. All ConceptSchemes from dia.gov.cz..."
echo ""

QUERY_3=$(cat <<EOF
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

SELECT DISTINCT ?scheme ?title
WHERE {
  ?scheme a skos:ConceptScheme .
  FILTER(REGEX(STR(?scheme), "dia.gov.cz", "i"))
  OPTIONAL { ?scheme dcterms:title ?title }
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_3" | python3 -m json.tool

echo ""
echo "=========================================="