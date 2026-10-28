package com.dia.ismdtoolbackend.rdfmigration;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.syntaxtransform.UpdateTransformOps;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;

import java.util.Map;

/**
 * One RDF migration step: a SPARQL Update applied to every ISMD vocabulary graph, with {@code ?graph}
 * bound to the graph IRI. Its id is the file name without {@code .ru}; ids sort in apply order.
 *
 * @param id          e.g. {@code 001-trida-owl-class}
 * @param description the file's leading {@code #} comment lines
 * @param update      the SPARQL Update text, as stored (byte-order mark removed, LF line endings)
 * @param checksum    SHA-256 of {@code update}; changing an applied file fails startup
 */
public record RdfChangeset(String id, String description, String update, String checksum) {

    /** The variable a changeset uses for the graph it is applied to. */
    public static final String GRAPH_VARIABLE = "graph";

    /**
     * The update with {@code ?graph} bound to {@code graphIri}. The variable is substituted in the parsed
     * update, so string literals and comments are never touched.
     */
    public String boundTo(String graphIri) {
        UpdateRequest parsed = UpdateFactory.create(update);
        return UpdateTransformOps.transform(parsed,
                Map.of(Var.alloc(GRAPH_VARIABLE), NodeFactory.createURI(graphIri))).toString();
    }
}
