package com.dia.ismdtoolbackend.utility.upload;

import com.dia.ismdtoolbackend.exception.OntologyUploadMissingIriException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;

/**
 * Upload step: finds the vocabulary IRI, which is also the graph name. Read-only. The data is
 * authoritative: without an {@code owl:Ontology} or {@code skos:ConceptScheme} the upload fails rather
 * than inventing a name.
 */
public final class UploadGraphNameResolver {

    private UploadGraphNameResolver() {
    }

    public static String resolve(Model model) {
        String iri = firstUriSubjectOfType(model, OWL2.Ontology);
        if (iri == null) {
            iri = firstUriSubjectOfType(model, SKOS.ConceptScheme);
        }
        if (iri == null) {
            throw new OntologyUploadMissingIriException(
                    "Z RDF dat nelze odvodit IRI slovníku (chybí owl:Ontology nebo skos:ConceptScheme). "
                            + "Slovník nelze nahrát bez identity odvozené z dat.");
        }
        return iri;
    }

    private static String firstUriSubjectOfType(Model model, Resource type) {
        return model.listResourcesWithProperty(RDF.type, type).toList().stream()
                .filter(Resource::isURIResource)
                .map(Resource::getURI)
                .findFirst()
                .orElse(null);
    }
}
