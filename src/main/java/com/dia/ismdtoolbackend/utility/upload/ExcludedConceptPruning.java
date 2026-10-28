package com.dia.ismdtoolbackend.utility.upload;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.SKOS;

import java.util.List;

/**
 * Upload step: removes owned concepts left without {@code skos:inScheme} (excluded by the user or
 * rejected) that nothing else references, so "no metadata row ⇒ no owned RDF" holds. A still-referenced
 * one stays as inert context, so no reference is left pointing at a bodiless IRI.
 */
@Slf4j
public final class ExcludedConceptPruning {

    private ExcludedConceptPruning() {
    }

    /** Returns the number of concepts removed. */
    public static int apply(Model model, String graphName) {
        OwnedConcepts.requireGraphName(graphName);
        List<Resource> unreferenced = OwnedConcepts.of(model, graphName).stream()
                .filter(concept -> !concept.hasProperty(SKOS.inScheme))
                .filter(concept -> !isReferencedByOther(model, concept))
                .toList();
        unreferenced.forEach(concept -> model.removeAll(concept, null, null));
        if (!unreferenced.isEmpty()) {
            log.info("Pruned {} unreferenced excluded concept(s) from {}", unreferenced.size(), graphName);
        }
        return unreferenced.size();
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
