package com.dia.ismdtoolbackend.utility.editor;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

import java.util.List;
import java.util.Set;

/**
 * Keeps a concept's unchanged blank-node children (non-legal sources) in the change set. When a delta
 * touches a blank node, {@code FusekiGraphWriter.applyConceptDelta} deletes every blank child of the
 * concept by pattern, because blank nodes cannot be matched by identity; on a rename the outbox
 * aggregate is the old IRI, so the children under the new IRI arrive as bare links. Each surviving
 * blank child is therefore re-staged, link and contents, so TDB2 ends up equal to the edited model.
 */
final class BlankChildRestaging {

    private BlankChildRestaging() {
    }

    static void apply(Resource oldConcept, Resource newConcept, Set<Statement> toRemove, Set<Statement> toAdd) {
        boolean renamed = !oldConcept.equals(newConcept);
        if (!renamed && !touchesBlank(toRemove) && !touchesBlank(toAdd)) {
            return;
        }
        Model model = oldConcept.getModel();
        for (Statement link : model.listStatements(oldConcept, null, (RDFNode) null).toList()) {
            if (!link.getObject().isAnon()) {
                continue;
            }
            Statement survivor = renamed
                    ? model.createStatement(newConcept, link.getPredicate(), link.getObject())
                    : link;
            boolean survives = renamed ? toAdd.contains(survivor) : !toRemove.contains(link);
            List<Statement> contents = link.getObject().asResource().listProperties().toList();
            if (!survives || contents.stream().anyMatch(toRemove::contains)) {
                continue;
            }
            toAdd.add(survivor);
            toAdd.addAll(contents);
        }
    }

    private static boolean touchesBlank(Set<Statement> statements) {
        return statements.stream().anyMatch(s -> s.getSubject().isAnon() || s.getObject().isAnon());
    }
}
