#!/bin/bash

ENDPOINT="https://oha02.dia.gov.cz/sparql"
ONTOLOGY_IRI="https://data.dia.gov.cz/slovník-dle-metodiky-dat-digitální-a-informační-agentury"

echo "=========================================="
echo "Detailed SPARQL Endpoint Investigation"
echo "=========================================="
echo ""

# Query 1: Check ANY triples with the ontology IRI as subject
echo "1. Any triples with ontology IRI as subject?"
echo ""

QUERY_1=$(cat <<EOF
CONSTRUCT { ?s ?p ?o }
WHERE {
  BIND(<${ONTOLOGY_IRI}> as ?s)
  ?s ?p ?o .
}
LIMIT 100
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: text/turtle" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_1" > /tmp/any_triples.ttl

if [ -s /tmp/any_triples.ttl ] && [ "$(wc -l < /tmp/any_triples.ttl)" -gt 1 ]; then
  echo "✓ Found triples:"
  cat /tmp/any_triples.ttl
else
  echo "✗ No triples found where ontology IRI is the subject"
fi

echo ""
echo "=========================================="
echo ""

# Query 2: Check if IRI appears anywhere (subject, predicate, or object)
echo "2. Does the IRI appear anywhere in the triplestore?"
echo ""

QUERY_2=$(cat <<EOF
SELECT ?s ?p ?o
WHERE {
  {
    BIND(<${ONTOLOGY_IRI}> as ?s)
    ?s ?p ?o .
  }
  UNION
  {
    ?s ?p <${ONTOLOGY_IRI}> .
    BIND(<${ONTOLOGY_IRI}> as ?o)
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

# Query 3: Check what ontologies/vocabularies exist in the endpoint
echo "3. What vocabularies are available in the endpoint?"
echo ""

QUERY_3=$(cat <<EOF
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dcterms: <http://purl.org/dc/terms/>

SELECT DISTINCT ?ontology ?title
WHERE {
  {
    ?ontology a owl:Ontology .
  }
  UNION
  {
    ?ontology a skos:ConceptScheme .
  }
  OPTIONAL { ?ontology dcterms:title ?title }
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

# Query 4: Search for similar IRIs (from dia.gov.cz domain)
echo "4. Any vocabularies from dia.gov.cz domain?"
echo ""

QUERY_4=$(cat <<EOF
SELECT DISTINCT ?s ?p ?o
WHERE {
  ?s ?p ?o .
  FILTER(REGEX(STR(?s), "data.dia.gov.cz", "i"))
}
LIMIT 50
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_4" | python3 -m json.tool

echo ""
echo "=========================================="
echo ""

# Query 5: Count total triples
echo "5. Total triples in the endpoint?"
echo ""

QUERY_5=$(cat <<EOF
SELECT (COUNT(*) as ?count)
WHERE {
  ?s ?p ?o .
}
EOF
)

curl -s -X POST "$ENDPOINT" \
  -H "Accept: application/sparql-results+json" \
  -H "Content-Type: application/sparql-query" \
  --data-binary "$QUERY_5" | python3 -m json.tool

echo ""
echo "=========================================="
echo "Investigation complete"
echo "=========================================="