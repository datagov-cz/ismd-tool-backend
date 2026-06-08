package com.dia.ismdtoolbackend.utility.exporter.turtle;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        count += ensureConceptsHaveDerivedInScheme(model);
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
        // NORMALIZE_ALL convenience: stamp every owned concept that's missing inScheme.
        return normalize(model, graphName, new HashSet<>(detectOwnedConceptsMissingInScheme(model, graphName)));
    }

    /**
     * Upload-path normalization with an explicit allow-list of concepts to stamp.
     * Runs the type/label normalization steps unconditionally, then adds
     * {@code inScheme → graphName} ONLY for concepts whose IRI is in
     * {@code conceptsToNormalize}. Concepts not in the list (the user's "exclude"
     * choice) are left without inScheme and remain unresolvable by design.
     *
     * @param model                the parsed upload model
     * @param graphName            the authoritative vocabulary IRI (must be non-null)
     * @param conceptsToNormalize  IRIs of owned concepts the user chose to normalize
     */
    public static int normalize(Model model, String graphName, Set<String> conceptsToNormalize) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must be non-null for the upload-path normalizer");
        }
        Set<String> allowList = conceptsToNormalize == null ? Set.of() : conceptsToNormalize;
        int count = 0;
        count += ensureConceptsHaveSkosType(model);
        count += normalizeOwlClassConcepts(model);
        count += normalizePropertyConcepts(model);
        count += convertLabelsToSkosPrefLabel(model);
        count += addInSchemeForAllowedConcepts(model, graphName, allowList);
        return count;
    }

    /**
     * Pure detection (no mutation): returns the IRIs of owned concepts (under
     * {@code graphName}) that lack {@code skos:inScheme}. Drives the
     * {@code MISSING_INSCHEME_DECISION_REQUIRED} prompt. Detect BEFORE
     * {@link #normalize} — once normalize runs, the type-normalization steps may have
     * stamped inScheme on some concepts and they'd no longer appear missing.
     */
    public static List<String> detectOwnedConceptsMissingInScheme(Model model, String graphName) {
        if (graphName == null || graphName.isBlank()) {
            throw new IllegalArgumentException("graphName must be non-null to detect missing inScheme");
        }
        Property skosInScheme = model.createProperty(SKOS_NS + "inScheme");
        Resource pojemResource = model.createResource(POJEM_GENERIC);
        List<String> missing = new ArrayList<>();

        for (Resource concept : ownedConceptCandidates(model, pojemResource, graphName)) {
            if (!concept.hasProperty(skosInScheme)) {
                missing.add(concept.getURI());
            }
        }
        return missing;
    }

    /**
     * Owned concept candidates under {@code graphName}: URI resources typed as
     * {@code skos:Concept} or {@code slovníky:pojem}, de-duplicated.
     */
    private static List<Resource> ownedConceptCandidates(Model model, Resource pojemResource, String graphName) {
        List<Resource> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collectOwnedTyped(model, SKOS.Concept, graphName, candidates, seen);
        collectOwnedTyped(model, pojemResource, graphName, candidates, seen);
        return candidates;
    }

    private static void collectOwnedTyped(Model model, Resource type, String graphName,
                                          List<Resource> out, Set<String> seen) {
        ResIterator iter = model.listResourcesWithProperty(RDF.type, type);
        while (iter.hasNext()) {
            Resource r = iter.next();
            if (r.isURIResource() && isOwnedConcept(r.getURI(), graphName) && seen.add(r.getURI())) {
                out.add(r);
            }
        }
    }

    /**
     * Adds {@code inScheme → graphName} for owned concepts whose IRI is in the
     * allow-list and that don't already have an inScheme. Owned concepts NOT in the
     * allow-list are left untouched (excluded by user decision).
     */
    private static int addInSchemeForAllowedConcepts(Model model, String graphName, Set<String> allowList) {
        Property skosInScheme = model.createProperty(SKOS_NS + "inScheme");
        Resource pojemResource = model.createResource(POJEM_GENERIC);
        int count = 0;

        for (Resource concept : ownedConceptCandidates(model, pojemResource, graphName)) {
            if (concept.hasProperty(skosInScheme) || !allowList.contains(concept.getURI())) {
                continue;
            }
            concept.addProperty(skosInScheme, model.getResource(graphName));
            count++;
        }
        return count;
    }

    /**
     * Read/NKD-path inScheme derivation (no authoritative graphName). Adds
     * {@code inScheme} to every {@code skos:Concept} by stripping {@code /pojem/} from
     * its IRI; concepts that can't yield a derived scheme are left as-is.
     */
    private static int ensureConceptsHaveDerivedInScheme(Model model) {
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
            String scheme = extractOntologyIRIFromConcept(concept.getURI());
            if (scheme == null) {
                continue;
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
