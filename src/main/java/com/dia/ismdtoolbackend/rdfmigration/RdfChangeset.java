package com.dia.ismdtoolbackend.rdfmigration;

import org.apache.jena.query.ParameterizedSparqlString;

/**
 * One RDF migration step: a SPARQL Update applied to every ISMD vocabulary graph, with {@code ?graph}
 * bound to the graph IRI. Its id is the file name without {@code .ru}; ids sort in apply order.
 *
 * @param id          e.g. {@code 001-trida-owl-class}
 * @param description the file's leading {@code #} comment lines
 * @param update      the SPARQL Update text, as stored
 * @param checksum    SHA-256 of the text with line endings normalised; changing an applied file fails startup
 */
public record RdfChangeset(String id, String description, String update, String checksum) {

    /** The variable a changeset uses for the graph it is applied to. */
    public static final String GRAPH_VARIABLE = "graph";

    /** The update with {@code ?graph} bound to {@code graphIri}. */
    public String boundTo(String graphIri) {
        ParameterizedSparqlString pss = new ParameterizedSparqlString(update);
        pss.setIri(GRAPH_VARIABLE, graphIri);
        return pss.toString();
    }
}
