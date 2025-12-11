package com.dia.ismdtoolbackend.query;

public class NKDSPARQLConstructQuery {
    public static String buildConstructQuery(String conceptIri) {
        return String.format("""
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
                  BIND(<%s> as ?concept)

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
                """, conceptIri);
    }

    public static String buildOntologyConstructQuery(String ontologyIri) {
        return String.format("""
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
                  BIND(<%s> as ?ontology)

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
                """, ontologyIri);
    }

    private NKDSPARQLConstructQuery(){}
}
