#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Searching for DIA Ontology"
echo "=========================================="
echo ""

# Query 1: Search for the exact ontology IRI as subject in ALL graphs (including default)
echo "1. Search for ontology IRI as subject in ALL DATA..."
echo ""

QUERY_1=$(cat <<EOF
SELECT ?g ?p ?o
WHERE {
  {
    <${ONTOLOGY_IRI}> ?p ?o .
    BIND("DEFAULT" as ?g)
  }
  UNION
  {
    GRAPH ?g {
      <${ONTOLOGY_IRI}> ?p ?o .
    }
  }
}
LIMIT 20
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 2: Search for the exact ontology IRI as object
echo "2. Search for ontology IRI as object (e.g., skos:inScheme)..."
echo ""

QUERY_2=$(cat <<EOF
SELECT ?g ?s ?p
WHERE {
  {
    ?s ?p <${ONTOLOGY_IRI}> .
    BIND("DEFAULT" as ?g)
  }
  UNION
  {
    GRAPH ?g {
      ?s ?p <${ONTOLOGY_IRI}> .
    }
  }
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

# Query 3: Search for any string containing "slovník-dle-metodiky"
echo "3. Search for any resources containing 'slovník-dle-metodiky'..."
echo ""

QUERY_3=$(cat <<EOF
SELECT DISTINCT ?g ?s ?p ?o
WHERE {
  {
    ?s ?p ?o .
    FILTER(
      REGEX(STR(?s), "slovník-dle-metodiky", "i") ||
      REGEX(STR(?o), "slovník-dle-metodiky", "i")
    )
    BIND("DEFAULT" as ?g)
  }
  UNION
  {
    GRAPH ?g {
      ?s ?p ?o .
      FILTER(
        REGEX(STR(?s), "slovník-dle-metodiky", "i") ||
        REGEX(STR(?o), "slovník-dle-metodiky", "i")
      )
    }
  }
}
LIMIT 20
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_3" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Search complete"
echo "=========================================="