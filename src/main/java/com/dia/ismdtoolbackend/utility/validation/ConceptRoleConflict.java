package com.dia.ismdtoolbackend.utility.validation;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;

import java.util.List;

/**
 * Detects resources typed as both a property ({@code owl:DatatypeProperty}) and a relationship
 * ({@code owl:ObjectProperty}). The OWL kind decides the role; the ISMD role marker is derived from it
 * (D4) and never counts here.
 */
public final class ConceptRoleConflict {

    private ConceptRoleConflict() {
    }

    public static boolean isPropertyAndRelationship(Resource resource) {
        return resource.hasProperty(RDF.type, OWL2.DatatypeProperty) && resource.hasProperty(RDF.type, OWL2.ObjectProperty);
    }

    /** The IRIs of every resource in the model typed as both, sorted. */
    public static List<String> findPropertyAndRelationship(Model model) {
        return model.listResourcesWithProperty(RDF.type, OWL2.ObjectProperty).toList().stream()
                .filter(resource -> resource.isURIResource() && isPropertyAndRelationship(resource))
                .map(Resource::getURI)
                .sorted()
                .distinct()
                .toList();
    }
}
