package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.ofn.OfnIris;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The concepts an upload owns: URI resources typed {@code skos:Concept} or {@code slovníky:pojem} whose
 * IRI lies under the vocabulary IRI ({@link OfnIris#isOwnedBy}).
 */
final class OwnedConcepts {

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

    static void requireGraphName(String graphName) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must be non-null");
        }
    }
}
