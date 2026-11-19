package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The concepts an upload owns: URI resources typed {@code skos:Concept} or {@code slovníky:pojem} whose
 * IRI lies under the vocabulary IRI ({@link OfnIris#isOwnedBy}).
 */
public final class OwnedConcepts {

    private OwnedConcepts() {
    }

    static List<Resource> of(Model model, String graphName) {
        Set<Resource> concepts = new LinkedHashSet<>();
        for (Resource type : List.of(SKOS.Concept, model.createResource(OfnIris.POJEM))) {
            for (Resource resource : model.listResourcesWithProperty(RDF.type, type).toList()) {
                if (resource.isURIResource() && OfnIris.isOwnedBy(resource.getURI(), graphName)) {
                    concepts.add(resource);
                }
            }
        }
        return new ArrayList<>(concepts);
    }

    /**
     * Owned URI resources that carry an OWL kind but neither {@code skos:Concept} nor {@code slovníky:pojem}
     * and no {@code skos:inScheme} (e.g. a plain OWL export). They are not concepts for the inScheme decision
     * and not members for the OFN check, so the upload reports them as rejected and prunes them.
     */
    public static List<Resource> withOnlyAnOwlKind(Model model, String graphName) {
        Set<Resource> concepts = new LinkedHashSet<>();
        for (String kind : OfnIris.OWL_KINDS) {
            for (Resource resource : model.listResourcesWithProperty(RDF.type, model.createResource(kind)).toList()) {
                if (resource.isURIResource() && OfnIris.isOwnedBy(resource.getURI(), graphName)
                        && !resource.hasProperty(RDF.type, SKOS.Concept)
                        && !resource.hasProperty(RDF.type, model.createResource(OfnIris.POJEM))
                        && !resource.hasProperty(SKOS.inScheme)) {
                    concepts.add(resource);
                }
            }
        }
        List<Resource> sorted = new ArrayList<>(concepts);
        sorted.sort(Comparator.comparing(Resource::getURI));
        return sorted;
    }

    static void requireGraphName(String graphName) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must be non-null");
        }
    }
}
