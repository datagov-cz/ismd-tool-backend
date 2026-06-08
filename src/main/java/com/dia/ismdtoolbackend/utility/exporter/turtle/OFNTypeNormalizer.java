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

    /**
     * Read/NKD-path normalization with no authoritative graph IRI. {@code inScheme}
     * is derived per-concept by stripping {@code /pojem/} from the concept IRI
     * ({@link #extractOntologyIRIFromConcept}). Used where there is no single
     * owning vocabulary (e.g. {@code OntologyDetailExtractor}, which mixes graphs).
     */
    public static int normalize(Model model) {
        int count = 0;
        count += ensureConceptsHaveSkosType(model);
        count += normalizeOwlClassConcepts(model);
        count += normalizePropertyConcepts(model);
        count += convertLabelsToSkosPrefLabel(model);
        count += ensureOwnedConceptsHaveInScheme(model, null);
        return count;
    }

    /**
     * Upload-path normalization. {@code graphName} is the authoritative vocabulary
     * IRI derived from the RDF; every {@code skos:Concept} <b>under that namespace</b>
     * that lacks an {@code skos:inScheme} gets {@code inScheme → graphName} (not the
     * {@code /pojem/}-stripped value, which can diverge). Concepts whose IRI is NOT
     * under {@code graphName} (alien / referenced concepts) are left untouched — they
     * are not claimed as owned by this vocabulary.
     *
     * @param model     the parsed upload model
     * @param graphName the authoritative vocabulary IRI (must be non-null)
     */
    public static int normalize(Model model, String graphName) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must be non-null for the upload-path normalizer");
        }
        int count = 0;
        count += ensureConceptsHaveSkosType(model);
        count += normalizeOwlClassConcepts(model);
        count += normalizePropertyConcepts(model);
        count += convertLabelsToSkosPrefLabel(model);
        count += ensureOwnedConceptsHaveInScheme(model, graphName);
        return count;
    }

    /**
     * Guarantees the resolution invariant for owned concepts: every
     * {@code skos:Concept} carries a {@code skos:inScheme}. When {@code graphName}
     * is provided, only concepts under that namespace are touched and they receive
     * {@code inScheme → graphName} authoritatively; alien concepts are skipped.
     * When {@code graphName} is null (read path), {@code inScheme} is derived by
     * stripping {@code /pojem/} from the concept IRI, and concepts that can't yield
     * a derived scheme are left as-is.
     */
    private static int ensureOwnedConceptsHaveInScheme(Model model, String graphName) {
        Property skosInScheme = model.createProperty(SKOS_NS + "inScheme");
        int count = 0;

        List<Resource> concepts = new ArrayList<>();
        ResIterator iter = model.listResourcesWithProperty(RDF.type, SKOS.Concept);
        while (iter.hasNext()) {
            Resource r = iter.next();
            if (r.isURIResource()) {
                concepts.add(r);
            }
        }

        for (Resource concept : concepts) {
            if (concept.hasProperty(skosInScheme)) {
                continue;
            }
            String scheme;
            if (graphName != null) {
                if (!isOwnedConcept(concept.getURI(), graphName)) {
                    continue;
                }
                scheme = graphName;
            } else {
                scheme = extractOntologyIRIFromConcept(concept.getURI());
                if (scheme == null) {
                    continue;
                }
            }
            concept.addProperty(skosInScheme, model.getResource(scheme));
            count++;
        }
        return count;
    }

    /**
     * Ownership predicate mirroring the resolution invariant
     * ({@code STRSTARTS(conceptIri, scheme)} in
     * {@code JenaTDB2Repository#fetchConceptResolutions}). A concept is owned by
     * {@code graphName} iff its IRI starts with the vocabulary IRI; owned concepts
     * are {@code {graphName}/pojem/{name}}.
     */
    public static boolean isOwnedConcept(String conceptIri, String graphName) {
        return conceptIri != null && graphName != null && conceptIri.startsWith(graphName);
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
