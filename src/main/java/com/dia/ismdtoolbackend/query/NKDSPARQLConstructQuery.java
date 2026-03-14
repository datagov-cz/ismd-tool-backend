package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.ParameterizedSparqlString;

public class NKDSPARQLConstructQuery {
    public static String buildConstructQuery(String conceptIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
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
                  BIND(?inputConcept as ?concept)

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
                """);
        pss.setIri("inputConcept", conceptIri);
        return pss.toString();
    }

    public static String buildOntologyConstructQuery(String ontologyIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.setCommandText("""
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
                  BIND(?inputOntology as ?ontology)

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
                """);
        pss.setIri("inputOntology", ontologyIri);
        return pss.toString();
    }

    private NKDSPARQLConstructQuery(){}
}
