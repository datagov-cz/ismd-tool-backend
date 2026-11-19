package com.dia.ismdtoolbackend.utility.exporter.json;

import org.apache.jena.rdf.model.*;

import java.util.*;

/** RDF value reads shared by the concept JSON mappers. */
final class RdfJsonValues {

    private RdfJsonValues() {
    }

    static void addResourceArrayProperty(Resource concept, Property property, String jsonProperty,
                                          Map<String, Object> conceptObj) {
        StmtIterator propIter = concept.listProperties(property);
        if (!propIter.hasNext()) {
            return;
        }

        List<String> propArray = new ArrayList<>();
        while (propIter.hasNext()) {
            Statement propStmt = propIter.next();
            if (propStmt.getObject().isResource()) {
                propArray.add(propStmt.getObject().asResource().getURI());
            } else if (propStmt.getObject().isLiteral()) {
                String literalValue = propStmt.getString();
                if (literalValue != null && !literalValue.trim().isEmpty()) {
                    propArray.add(literalValue);
                }
            }
        }

        if (!propArray.isEmpty()) {
            conceptObj.put(jsonProperty, propArray);
        }
    }

    static List<String> extractResourceURIs(StmtIterator stmtIter) {
        List<String> uriArray = new ArrayList<>();

        while (stmtIter.hasNext()) {
            Statement stmt = stmtIter.next();
            if (stmt.getObject().isResource()) {
                uriArray.add(stmt.getObject().asResource().getURI());
            }
        }

        return uriArray;
    }
}
