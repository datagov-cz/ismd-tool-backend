package com.dia.ismdtoolbackend.query;

import org.apache.jena.query.ParameterizedSparqlString;

import java.util.List;

public class NKDSPARQLConstructQuery {

    /**
     * Narrow batched CONSTRUCT for the concept-reference resolver:
     * pulls {@code skos:inScheme} and the {@code skos:prefLabel} of both the
     * concept and its scheme for a batch of concept IRIs. One HTTP round-trip
     * resolves the whole batch, which is ~50× cheaper than calling
     * {@link #buildConstructQuery(String)} per IRI (that variant pulls the full
     * concept graph + blank-node expansion).
     */
    public static String buildResolutionConstructQuery(List<String> conceptIris) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString();
        pss.append("PREFIX skos: <http://www.w3.org/2004/02/skos/core#> ");
        pss.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> ");
        pss.append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#> ");
        pss.append("CONSTRUCT { ");
        pss.append("  ?concept skos:inScheme ?scheme . ");
        pss.append("  ?concept skos:prefLabel ?conceptLabel . ");
        pss.append("  ?concept rdf:type ?type . ");
        pss.append("  ?concept rdfs:domain ?domain . ");
        pss.append("  ?concept rdfs:range ?range . ");
        pss.append("  ?scheme skos:prefLabel ?schemeLabel . ");
        pss.append("} WHERE { VALUES ?concept { ");
        for (String iri : conceptIris) {
            pss.appendIri(iri);
            pss.append(" ");
        }
        pss.append("} ?concept skos:inScheme ?scheme . ");
        pss.append("FILTER(STRSTARTS(STR(?concept), STR(?scheme))) ");
        pss.append("OPTIONAL { ?concept skos:prefLabel ?conceptLabel . } ");
        pss.append("OPTIONAL { ?concept rdf:type ?type . } ");
        pss.append("OPTIONAL { ?concept rdfs:domain ?domain . } ");
        pss.append("OPTIONAL { ?concept rdfs:range ?range . } ");
        pss.append("OPTIONAL { ?scheme skos:prefLabel ?schemeLabel . } ");
        pss.append("}");
        return pss.toString();
    }

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
                  ?ontology ?op ?oo .
                  ?oo ?oNestedP ?oNestedO .
                  ?concept ?cp ?co .
                  ?co ?cNestedP ?cNestedO .
                }
                WHERE {
                  BIND(?inputOntology as ?ontology)

                  {
                    ?ontology ?op ?oo .
                  }
                  UNION
                  {
                    ?ontology ?op ?oo .
                    FILTER(isBlank(?oo))
                    ?oo ?oNestedP ?oNestedO .
                  }
                  UNION
                  {
                    ?concept skos:inScheme ?ontology .
                    ?concept ?cp ?co .
                  }
                  UNION
                  {
                    ?concept skos:inScheme ?ontology .
                    ?concept ?cp ?co .
                    FILTER(isBlank(?co))
                    ?co ?cNestedP ?cNestedO .
                  }
                }
                """);
        pss.setIri("inputOntology", ontologyIri);
        return pss.toString();
    }

    private NKDSPARQLConstructQuery(){}
}
