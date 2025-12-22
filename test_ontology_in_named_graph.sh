#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Testing if ontology exists in named graphs"
echo "=========================================="
echo ""

# Query 1: Check if the ontology IRI itself is a named graph
echo "1. Is the ontology IRI itself a named graph?"
echo ""

QUERY_1=$(cat <<EOF
SELECT (COUNT(*) as ?count)
WHERE {
  GRAPH <${ONTOLOGY_IRI}> {
    ?s ?p ?o .
  }
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

# Query 2: Search for the ontology in ANY named graph
echo "2. Does the ontology exist as a subject in any named graph?"
echo ""

QUERY_2=$(cat <<EOF
SELECT DISTINCT ?g ?p ?o
WHERE {
  GRAPH ?g {
    <${ONTOLOGY_IRI}> ?p ?o .
  }
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

# Query 3: Look for concepts from this ontology in named graphs
echo "3. Are there concepts from this ontology in named graphs?"
echo ""

QUERY_3=$(cat <<EOF
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT DISTINCT ?g ?concept ?label
WHERE {
  GRAPH ?g {
    ?concept skos:inScheme <${ONTOLOGY_IRI}> .
    OPTIONAL { ?concept skos:prefLabel ?label }
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
echo ""

# Query 4: Test the application's query WITH named graphs
echo "4. Testing application's concept query with GRAPH keyword..."
echo ""
echo "   (Using a sample concept IRI pattern)"
echo ""

SAMPLE_CONCEPT="${ONTOLOGY_IRI}/pojem/test-concept"

QUERY_4=$(cat <<EOF
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
  BIND(<${SAMPLE_CONCEPT}> as ?concept)
  GRAPH ?g {
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
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: text/turtle" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_4"

echo ""
echo "=========================================="
echo ""

# Query 5: Check if there are any "slovník" related named graphs
echo "5. Named graphs with 'slovník' or 'metodiky' in their IRI..."
echo ""

QUERY_5=$(cat <<EOF
SELECT DISTINCT ?g (COUNT(*) as ?tripleCount)
WHERE {
  GRAPH ?g { ?s ?p ?o }
  FILTER(
    REGEX(STR(?g), "slovn.*k", "i") ||
    REGEX(STR(?g), "metodiky", "i") ||
    REGEX(STR(?g), "dia.gov.cz", "i")
  )
}
GROUP BY ?g
ORDER BY DESC(?tripleCount)
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