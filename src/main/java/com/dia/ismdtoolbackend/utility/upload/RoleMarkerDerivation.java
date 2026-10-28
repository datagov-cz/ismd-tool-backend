package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.util.List;

/**
 * Adds the ISMD role marker ({@code slovníky:třída/vlastnost/vztah}) that matches a concept's OWL kind
 * (D4: the marker is derived from OWL, never the reverse). Never adds an OWL type and never removes
 * anything: a concept with no OWL kind, two kinds, or a disagreeing marker is left for the OFN gate to
 * reject.
 */
public final class RoleMarkerDerivation {

    private RoleMarkerDerivation() {
    }

    /** Upload step: derives the marker on every owned subject. Returns how many markers were added. */
    public static int apply(Model model, String graphName) {
        OwnedConcepts.requireGraphName(graphName);
        int added = 0;
        for (Resource subject : model.listSubjects().toList()) {
            if (subject.isURIResource() && OfnIris.isOwnedBy(subject.getURI(), graphName) && derive(subject)) {
                added++;
            }
        }
        return added;
    }

    /** Adds the marker for the concept's single OWL kind. Returns true when one was added. */
    public static boolean derive(Resource concept) {
        List<String> kinds = concept.listProperties(RDF.type).toList().stream()
                .map(statement -> statement.getObject())
                .filter(RDFNode::isURIResource)
                .map(type -> type.asResource().getURI())
                .filter(OfnIris.MARKER_BY_OWL_KIND::containsKey)
                .toList();
        if (kinds.size() != 1) {
            return false;
        }
        Resource marker = concept.getModel().createResource(OfnIris.MARKER_BY_OWL_KIND.get(kinds.get(0)));
        if (concept.hasProperty(RDF.type, marker)) {
            return false;
        }
        concept.addProperty(RDF.type, marker);
        return true;
    }
}
