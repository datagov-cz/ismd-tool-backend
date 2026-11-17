package com.dia.ismdtoolbackend.utility.detail;

import lombok.extern.slf4j.Slf4j;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static com.dia.ismdtoolbackend.constants.VocabularyConstants.*;

/**
 * Read-side tolerance for data ISMD does not own: NKD responses and upload input. Adds the
 * {@code skos:Concept}, {@code slovníky:pojem} and {@code skos:prefLabel} that {@code TurtleFilterUtil} and
 * the detail mappers recognise concepts by, where that data lacks them (NKD often has no
 * {@code skos:Concept}). Stored ISMD data never goes through here: the write gate keeps it OFN-shaped.
 * It changes only the in-memory copy being read; nothing is written.
 */
@Slf4j
final class OutsideDataNormalizer {

    private static final String POJEM_GENERIC = OFN_NAMESPACE + POJEM;

    private OutsideDataNormalizer() {
    }

    /**
     * Upload input. Infers {@code skos:Concept} and {@code slovníky:pojem} and converts labels so the
     * concepts survive {@code TurtleFilterUtil}, but does NOT touch {@code skos:inScheme}: the upload decides
     * scheme membership itself ({@code InSchemeStamping}).
     */
    public static int normalizeUploadInput(Model model) {
        int count = 0;
        count += ensureConceptsHaveSkosType(model);
        count += normalizeOwlKindConcepts(model);
        count += convertLabelsToSkosPrefLabel(model);
        return count;
    }

    /**
     * NKD responses. Same inference as {@link #normalizeUploadInput} PLUS {@code skos:inScheme} derivation: NKD data
     * is out of our control and often arrives without an explicit scheme, so we infer
     * it per-concept by stripping {@code /pojem/} from the concept IRI
     * ({@link #extractOntologyIRIFromConcept}) — a reasonable inference given OFN's IRI
     * structure. There is no single authoritative graph IRI here (the detail model can
     * mix vocabularies), hence the per-concept derivation rather than a fixed graphName.
     */
    public static int normalizeNkd(Model model) {
        int count = normalizeUploadInput(model);
        count += ensureConceptsHaveDerivedInScheme(model);
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

    /**
     * Infers {@code skos:Concept} and {@code slovníky:pojem} on {@code /pojem/} concepts of every OWL kind
     * ({@code owl:Class}, {@code owl:DatatypeProperty}, {@code owl:ObjectProperty}); without them
     * {@code TurtleFilterUtil} drops a resource typed only with an OWL kind. Does NOT touch
     * {@code skos:inScheme} (that's the NKD-only derivation step, or the authoritative upload step).
     */
    private static int normalizeOwlKindConcepts(Model model) {
        Resource slovnikyPojem = model.createResource(OFN_NAMESPACE + POJEM);
        int count = 0;

        Set<Resource> conceptsToNormalize = new LinkedHashSet<>();
        for (Resource owlKind : List.of(OWL2.Class, OWL2.DatatypeProperty, OWL2.ObjectProperty)) {
            ResIterator iter = model.listResourcesWithProperty(RDF.type, owlKind);
            while (iter.hasNext()) {
                Resource r = iter.next();
                if (r.isURIResource() && isConceptResource(r.getURI())) {
                    conceptsToNormalize.add(r);
                }
            }
        }

        for (Resource concept : conceptsToNormalize) {
            boolean modified = false;
            if (!concept.hasProperty(RDF.type, SKOS.Concept)) {
                concept.addProperty(RDF.type, SKOS.Concept);
                modified = true;
            }
            if (!concept.hasProperty(RDF.type, slovnikyPojem)) {
                concept.addProperty(RDF.type, slovnikyPojem);
                modified = true;
            }
            if (modified) count++;
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
