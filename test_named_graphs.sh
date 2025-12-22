#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Testing Named Graphs in SPARQL Endpoint"
echo "=========================================="
echo ""

# Query 1: List all named graphs in the endpoint
echo "1. Listing all named graphs in the endpoint..."
echo ""

QUERY_1=$(cat <<EOF
SELECT DISTINCT ?g
WHERE {
  GRAPH ?g { ?s ?p ?o }
}
ORDER BY ?g
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 2: Count triples in each named graph
echo "2. Counting triples in each named graph..."
echo ""

QUERY_2=$(cat <<EOF
SELECT ?g (COUNT(*) as ?count)
WHERE {
  GRAPH ?g { ?s ?p ?o }
}
GROUP BY ?g
ORDER BY DESC(?count)
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

# Query 3: Search for the ontology IRI in any named graph
echo "3. Searching for ontology IRI in all named graphs..."
echo ""

QUERY_3=$(cat <<EOF
SELECT DISTINCT ?g ?p ?o
WHERE {
  GRAPH ?g {
    <${ONTOLOGY_IRI}> ?p ?o .
  }
}
LIMIT 100
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_3" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 4: Search for any dia.gov.cz resources in named graphs
echo "4. Searching for dia.gov.cz resources in named graphs..."
echo ""

QUERY_4=$(cat <<EOF
SELECT DISTINCT ?g ?s
WHERE {
  GRAPH ?g {
    ?s ?p ?o .
    FILTER(REGEX(STR(?s), "data.dia.gov.cz", "i"))
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

# Query 5: Search for "slovník" pattern in any named graph
echo "5. Searching for 'slovník' pattern in named graphs..."
echo ""

QUERY_5=$(cat <<EOF
SELECT DISTINCT ?g ?s
WHERE {
  GRAPH ?g {
    ?s ?p ?o .
    FILTER(REGEX(STR(?s), "slovn.*k.*metodiky", "i"))
  }
}
LIMIT 50
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_5" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 6: Try to construct data from a specific named graph (if the ontology is the graph name)
echo "6. Trying to query the ontology IRI as a named graph..."
echo ""

QUERY_6=$(cat <<EOF
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

SELECT ?s ?p ?o
WHERE {
  GRAPH <${ONTOLOGY_IRI}> {
    ?s ?p ?o .
  }
}
LIMIT 20
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_6" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 7: Look for concepts with skos:inScheme to our ontology in any graph
echo "7. Looking for concepts that reference this ontology in any graph..."
echo ""

QUERY_7=$(cat <<EOF
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT DISTINCT ?g ?concept ?label
WHERE {
  GRAPH ?g {
    ?concept skos:inScheme <${ONTOLOGY_IRI}> .
    OPTIONAL { ?concept skos:prefLabel ?label }
  }
}
LIMIT 50
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_7" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Named graph investigation complete"
echo "=========================================="