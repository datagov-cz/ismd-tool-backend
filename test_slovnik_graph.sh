#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
SLOVNIK_GRAPH="https://data.gov.cz/slovník/"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Exploring the slovník named graph"
echo "=========================================="
echo ""

# Query 1: What's in the slovník graph?
echo "1. All triples in the slovník graph..."
echo ""

QUERY_1=$(cat <<EOF
CONSTRUCT {
  ?s ?p ?o .
}
WHERE {
  GRAPH <${SLOVNIK_GRAPH}> {
    ?s ?p ?o .
  }
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: text/turtle" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1"

echo ""
echo "=========================================="
echo ""

# Query 2: List all subjects in the slovník graph
echo "2. All subjects in the slovník graph..."
echo ""

QUERY_2=$(cat <<EOF
SELECT DISTINCT ?s ?type
WHERE {
  GRAPH <${SLOVNIK_GRAPH}> {
    ?s ?p ?o .
    OPTIONAL { ?s a ?type }
  }
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_2" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 3: Check if our ontology is referenced in the slovník graph
echo "3. Is our ontology referenced in the slovník graph?"
echo ""

QUERY_3=$(cat <<EOF
SELECT ?s ?p ?o
WHERE {
  GRAPH <${SLOVNIK_GRAPH}> {
    {
      ?s ?p <${ONTOLOGY_IRI}> .
    }
    UNION
    {
      <${ONTOLOGY_IRI}> ?p ?o .
    }
  }
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

# Query 4: Look for any dia.gov.cz IRIs in the slovník graph
echo "4. Any dia.gov.cz resources in the slovník graph?"
echo ""

QUERY_4=$(cat <<EOF
SELECT ?s ?p ?o
WHERE {
  GRAPH <${SLOVNIK_GRAPH}> {
    ?s ?p ?o .
    FILTER(
      REGEX(STR(?s), "dia.gov.cz", "i") ||
      REGEX(STR(?o), "dia.gov.cz", "i")
    )
  }
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_4" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Test complete"
echo "=========================================="
