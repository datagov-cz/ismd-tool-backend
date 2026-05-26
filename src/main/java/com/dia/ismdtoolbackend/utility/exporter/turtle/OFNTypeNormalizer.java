package com.dia.ismdtoolbackend.utility.exporter.turtle;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayList;
import java.util.List;

import static com.dia.constants.VocabularyConstants.*;

/**
 * Adds the OFN role tags ({@code slovníky:třída}, {@code slovníky:vztah},
 * {@code slovníky:vlastnost}) and {@code skos:Concept} that real concepts in
 * OFN-conforming vocabularies are expected to carry. Run before
 * {@link TurtleFilterUtil} so that concepts published with a leaner type set
 * (e.g. only {@code slovníky:pojem} + {@code owl:Class}) survive filtering.
 */
@Slf4j
public final class OFNTypeNormalizer {

    private static final String POJEM_GENERIC = OFN_NAMESPACE + POJEM;

    private OFNTypeNormalizer() {}

    public static int normalize(Model model) {
        int count = 0;
        count += ensureConceptsHaveSkosType(model);
        count += normalizeOwlClassConcepts(model);
        count += normalizePropertyConcepts(model);
        count += convertLabelsToSkosPrefLabel(model);
        return count;
    }

    private static int ensureConceptsHaveSkosType(Model model) {
        Resource pojemResource = model.createResource(POJEM_GENERIC);
        ResIterator conceptIterator = model.listResourcesWithProperty(RDF.type, pojemResource);
        int added = 0;

        while (conceptIterator.hasNext()) {
            Resource conceptResource = conceptIterator.next();
            if (!conceptResource.isURIResource()) {
                continue;
            }
            if (!conceptResource.hasProperty(RDF.type, SKOS.Concept)) {
                conceptResource.addProperty(RDF.type, SKOS.Concept);
                added++;
            }
        }
        return added;
    }

    private static int normalizeOwlClassConcepts(Model model) {
        Resource slovnikyPojem = model.createResource(OFN_NAMESPACE + POJEM);
        Resource slovnikyTrida = model.createResource(OFN_NAMESPACE + TRIDA);
        Property skosInScheme = model.createProperty(SKOS_NS + "inScheme");
        int count = 0;

        List<Resource> classesToNormalize = new ArrayList<>();
        ResIterator iter = model.listResourcesWithProperty(RDF.type, OWL2.Class);
        while (iter.hasNext()) {
            Resource r = iter.next();
            if (r.isURIResource() && isConceptResource(r.getURI())) {
                classesToNormalize.add(r);
            }
        }

        for (Resource cls : classesToNormalize) {
            boolean modified = false;
            if (!cls.hasProperty(RDF.type, SKOS.Concept)) {
                cls.addProperty(RDF.type, SKOS.Concept);
                modified = true;
            }
            if (!cls.hasProperty(RDF.type, slovnikyPojem)) {
                cls.addProperty(RDF.type, slovnikyPojem);
                modified = true;
            }
            if (!cls.hasProperty(RDF.type, slovnikyTrida)) {
                cls.addProperty(RDF.type, slovnikyTrida);
                modified = true;
            }
            String ontologyIRI = extractOntologyIRIFromConcept(cls.getURI());
            if (ontologyIRI != null && !cls.hasProperty(skosInScheme)) {
                cls.addProperty(skosInScheme, model.getResource(ontologyIRI));
                modified = true;
            }
            if (modified) count++;
        }

        return count;
    }

    private static int normalizePropertyConcepts(Model model) {
        Resource slovnikyVztah = model.createResource(OFN_NAMESPACE + VZTAH);
        Resource slovnikyVlastnost = model.createResource(OFN_NAMESPACE + VLASTNOST);
        Property skosInScheme = model.createProperty(SKOS_NS + "inScheme");
        int count = 0;

        List<Resource> objectProperties = new ArrayList<>();
        ResIterator iter = model.listResourcesWithProperty(RDF.type, OWL2.ObjectProperty);
        while (iter.hasNext()) {
            objectProperties.add(iter.next());
        }
        for (Resource prop : objectProperties) {
            if (prop.isURIResource() && prop.getURI().contains("/pojem/")) {
                boolean modified = false;
                if (!prop.hasProperty(RDF.type, slovnikyVztah)) {
                    prop.addProperty(RDF.type, slovnikyVztah);
                    modified = true;
                }
                String ontologyIRI = extractOntologyIRIFromConcept(prop.getURI());
                if (ontologyIRI != null && !prop.hasProperty(skosInScheme)) {
                    prop.addProperty(skosInScheme, model.getResource(ontologyIRI));
                    modified = true;
                }
                if (modified) count++;
            }
        }

        List<Resource> datatypeProperties = new ArrayList<>();
        iter = model.listResourcesWithProperty(RDF.type, OWL2.DatatypeProperty);
        while (iter.hasNext()) {
            datatypeProperties.add(iter.next());
        }
        for (Resource prop : datatypeProperties) {
            if (prop.isURIResource() && prop.getURI().contains("/pojem/")
                    && !prop.hasProperty(RDF.type, OWL2.ObjectProperty)) {
                boolean modified = false;
                if (!prop.hasProperty(RDF.type, slovnikyVlastnost)) {
                    prop.addProperty(RDF.type, slovnikyVlastnost);
                    modified = true;
                }
                String ontologyIRI = extractOntologyIRIFromConcept(prop.getURI());
                if (ontologyIRI != null && !prop.hasProperty(skosInScheme)) {
                    prop.addProperty(skosInScheme, model.getResource(ontologyIRI));
                    modified = true;
                }
                if (modified) count++;
            }
        }

        return count;
    }

    private static int convertLabelsToSkosPrefLabel(Model model) {
        Property skosPrefLabel = model.createProperty(SKOS_NS + "prefLabel");
        int count = 0;

        List<Statement> toConvert = new ArrayList<>();
        StmtIterator iter = model.listStatements(null, RDFS.label, (RDFNode) null);
        while (iter.hasNext()) {
            Statement stmt = iter.next();
            if (stmt.getSubject().hasProperty(RDF.type, SKOS.Concept)) {
                toConvert.add(stmt);
            }
        }

        for (Statement stmt : toConvert) {
            model.remove(stmt);
            model.add(stmt.getSubject(), skosPrefLabel, stmt.getObject());
            count++;
        }

        return count;
    }

    private static boolean isConceptResource(String uri) {
        return uri.contains("/pojem/") && !isBaseVocabularyClass(uri);
    }

    private static boolean isBaseVocabularyClass(String uri) {
        return uri.startsWith("http://www.w3.org/")
                || uri.startsWith("https://slovník.gov.cz/veřejný-sektor/pojem/typ-")
                || uri.contains("/generický/")
                || uri.contains("cz:třída")
                || uri.contains("cz:pojem");
    }

    private static String extractOntologyIRIFromConcept(String resourceURI) {
        if (resourceURI.contains("/pojem/")) {
            return resourceURI.substring(0, resourceURI.lastIndexOf("/pojem/"));
        }
        return null;
    }
}
