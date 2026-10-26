package com.dia.ismdtoolbackend.utility.validation;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.dia.ismdtoolbackend.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.VLASTNOST;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.VZTAH;

/** Detects resources typed as both a property (vlastnost) and a relationship (vztah). */
public final class ConceptRoleConflict {

    private ConceptRoleConflict() {
    }

    /** True when the resource carries a property type and a relationship type, OFN or OWL. */
    public static boolean isPropertyAndRelationship(Resource resource) {
        return isProperty(resource) && isRelationship(resource);
    }

    /** The IRIs of every resource in the model typed as both, sorted. */
    public static List<String> findPropertyAndRelationship(Model model) {
        Set<String> conflicting = new TreeSet<>();
        for (Resource type : List.of(OWL2.ObjectProperty, model.createResource(OFN_NAMESPACE + VZTAH))) {
            model.listResourcesWithProperty(RDF.type, type)
                    .filterKeep(resource -> resource.isURIResource() && isProperty(resource))
                    .forEachRemaining(resource -> conflicting.add(resource.getURI()));
        }
        return List.copyOf(conflicting);
    }

    private static boolean isProperty(Resource resource) {
        return resource.hasProperty(RDF.type, OWL2.DatatypeProperty)
                || resource.hasProperty(RDF.type, resource.getModel().createResource(OFN_NAMESPACE + VLASTNOST));
    }

    private static boolean isRelationship(Resource resource) {
        return resource.hasProperty(RDF.type, OWL2.ObjectProperty)
                || resource.hasProperty(RDF.type, resource.getModel().createResource(OFN_NAMESPACE + VZTAH));
    }
}
