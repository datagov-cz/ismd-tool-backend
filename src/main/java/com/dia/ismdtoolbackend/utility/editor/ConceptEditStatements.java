package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

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

    /**
     * Writes the property as one {@code xsd:boolean} literal; {@code null} leaves it untouched. A stored
     * value that is already exactly that literal stages nothing; any other shape (an {@code xsd:string}
     * "true", several values) is rewritten.
     */
    static void updateBooleanLiteral(Resource newConcept, Property property, Boolean newValue,
                                     Resource oldConcept, Model model, Set<Statement> toRemove,
                                     Set<Statement> toAdd) {
        if (newValue == null) return;

        Literal wanted = model.createTypedLiteral(newValue);
        List<RDFNode> current = oldConcept.listProperties(property).mapWith(Statement::getObject).toList();
        if (current.size() == 1 && current.get(0).equals(wanted)) {
            return;
        }
        removeAllByPredicate(newConcept, property, toRemove, toAdd);
        toAdd.add(model.createStatement(newConcept, property, wanted));
    }

    /**
     * Removes every statement of the concept whose predicate {@code isLegacy} matches, plus the
     * statements of any blank node such a statement points to. Used to drop pre-OFN copies of a field
     * when that field is edited. Returns true when anything was staged for removal.
     */
    static boolean removeLegacyPredicates(Resource newConcept, Resource oldConcept, Predicate<Property> isLegacy,
                                          Set<Statement> toRemove, Set<Statement> toAdd) {
        Set<Property> legacy = new HashSet<>();
        for (Statement statement : oldConcept.listProperties().toList()) {
            if (isLegacy.test(statement.getPredicate())) {
                legacy.add(statement.getPredicate());
                if (statement.getObject().isAnon()) {
                    toRemove.addAll(statement.getObject().asResource().listProperties().toList());
                }
            }
        }
        for (Property property : legacy) {
            removeAllByPredicate(newConcept, property, toRemove, toAdd);
            if (!newConcept.equals(oldConcept)) {
                removeAllByPredicate(oldConcept, property, toRemove, toAdd);
            }
        }
        return !legacy.isEmpty();
    }

    /** A predicate named {@code localName} in any namespace other than {@code ofnIri}'s own. */
    static Predicate<Property> legacyCopyOf(String ofnIri, String... localNames) {
        Set<String> names = Set.of(localNames);
        return property -> !property.getURI().equals(ofnIri)
                && names.contains(OfnIris.localName(property.getURI()));
    }
}
