package com.dia.ismdtoolbackend.utility.editor;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static com.dia.ismdtoolbackend.utility.editor.RdfLangValues.removeAllByPredicate;

/** Statement reads and staged rewrites shared by the concept field updaters. */
final class ConceptEditStatements {

    private ConceptEditStatements() {
    }

    static String getPropertyValue(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isLiteral() ? stmt.getObject().asLiteral().getString() : null;
    }

    static String getResourceURI(Resource resource, Property property) {
        Statement stmt = resource.getProperty(property);
        return stmt != null && stmt.getObject().isResource() ? stmt.getObject().asResource().getURI() : null;
    }

    static Set<String> getResourceURIs(Resource resource, Property property) {
        Set<String> uris = new HashSet<>();
        StmtIterator iter = resource.listProperties(property);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getObject().isResource()) {
                uris.add(stmt.getObject().asResource().getURI());
            }
        }
        return uris;
    }

    /**
     * Removes a specific {@code rdf:type} value from the concept. Stages removal
     * of the statement under the old IRI (still present in the model) AND strips
     * any copy of it staged under the new IRI by {@code renameConceptIRI} — so a
     * type that's being dropped does not survive a rename via the copy-all pass.
     */
    static void removeTypeStatement(Resource newConcept, Resource oldConcept, Resource typeValue,
                                    Model model, Set<Statement> toRemove, Set<Statement> toAdd) {
        toRemove.add(model.createStatement(oldConcept, RDF.type, typeValue));
        toAdd.removeIf(stmt ->
                stmt.getSubject().equals(newConcept) &&
                        stmt.getPredicate().equals(RDF.type) &&
                        stmt.getObject().equals(typeValue));
    }

    /** Rewrites a boolean literal property when the value differs; {@code null} leaves it untouched. */
    static void updateBooleanLiteral(Resource newConcept, Property property, Boolean newValue,
                                     Resource oldConcept, Model model, Set<Statement> toRemove,
                                     Set<Statement> toAdd) {
        if (newValue == null) return;

        String oldValue = getPropertyValue(oldConcept, property);
        String newValueStr = newValue.toString();

        if (!Objects.equals(oldValue, newValueStr)) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            toAdd.add(model.createStatement(newConcept, property, model.createLiteral(newValueStr)));
        }
    }
}
