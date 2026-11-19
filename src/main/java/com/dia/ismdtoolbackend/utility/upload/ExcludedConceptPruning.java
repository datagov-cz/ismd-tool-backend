package com.dia.ismdtoolbackend.utility.upload;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Upload step: removes owned concepts left without {@code skos:inScheme} (excluded by the user or
 * rejected, including owned resources with only an OWL kind) that nothing else references, so "no metadata row ⇒ no owned RDF" holds. A still-referenced
 * one stays as inert context, so no reference is left pointing at a bodiless IRI.
 */
@Slf4j
public final class ExcludedConceptPruning {

    private ExcludedConceptPruning() {
    }

    /** Returns the number of concepts removed. */
    public static int apply(Model model, String graphName) {
        OwnedConcepts.requireGraphName(graphName);
        Set<Resource> candidates = new LinkedHashSet<>(OwnedConcepts.of(model, graphName));
        candidates.addAll(OwnedConcepts.withOnlyAnOwlKind(model, graphName));
        List<Resource> unreferenced = candidates.stream()
                .filter(concept -> !concept.hasProperty(SKOS.inScheme))
                .filter(concept -> !isReferencedByOther(model, concept))
                .toList();
        unreferenced.forEach(concept -> remove(model, concept));
        if (!unreferenced.isEmpty()) {
            log.info("Pruned {} unreferenced excluded concept(s) from {}", unreferenced.size(), graphName);
        }
        return unreferenced.size();
    }

    /**
     * Removes the concept with its blank children (provisions, time instants, digital objects), nested ones
     * included. A blank node that anything outside the concept also references is kept.
     */
    private static void remove(Model model, Resource concept) {
        Set<Resource> body = new LinkedHashSet<>();
        body.add(concept);
        Deque<Resource> toVisit = new ArrayDeque<>(List.of(concept));
        while (!toVisit.isEmpty()) {
            for (Statement statement : model.listStatements(toVisit.pop(), null, (RDFNode) null).toList()) {
                RDFNode object = statement.getObject();
                if (object.isAnon() && body.add(object.asResource())) {
                    toVisit.push(object.asResource());
                }
            }
        }
        body.removeIf(node -> node.isAnon() && model.listStatements(null, null, node).toList().stream()
                .anyMatch(statement -> !body.contains(statement.getSubject())));
        body.forEach(node -> model.removeAll(node, null, null));
    }

    private static boolean isReferencedByOther(Model model, Resource concept) {
        for (Statement statement : model.listStatements(null, null, concept).toList()) {
            if (!statement.getSubject().equals(concept)) {
                return true;
            }
        }
        return false;
    }
}
