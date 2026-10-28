package com.dia.ismdtoolbackend.utility.detail;

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
 * Detail-read only, temporary: adds the role markers, {@code skos:Concept} and {@code skos:prefLabel}
 * the detail readers and {@code TurtleFilterUtil} recognise concepts by, to models that lack them (NKD
 * data, legacy local data). Remove once the readers take the role from the OWL kind (plan step 5).
 * Never used on a write path: upload stores data as uploaded.
 */
@Slf4j
final class DetailModelNormalizer {

    private static final String POJEM_GENERIC = OFN_NAMESPACE + POJEM;

    private DetailModelNormalizer() {
    }

    /**
     * Local-detail-path normalization. Infers the OFN role tags
     * ({@code skos:Concept}, {@code slovníky:třída/vztah/vlastnost}) and converts
     * labels so real concepts survive {@code TurtleFilterUtil}, but does NOT touch
     * {@code skos:inScheme}: local vocabularies are written via the authoritative
     * upload path, which guarantees every owned concept carries an explicit
     * {@code inScheme}. Deriving it here would be redundant and risks the
     * {@code /pojem/} string heuristic diverging from the real scheme.
     */
    public static int normalizeForLocalDetail(Model model) {
        int count = 0;
        count += ensureConceptsHaveSkosType(model);
        count += normalizeOwlClassConcepts(model);
        count += normalizePropertyConcepts(model);
        count += convertLabelsToSkosPrefLabel(model);
        return count;
    }

    /**
     * NKD-path normalization. Same role-tag/label inference as
     * {@link #normalizeForLocalDetail} PLUS {@code skos:inScheme} derivation: NKD data
     * is out of our control and often arrives without an explicit scheme, so we infer
     * it per-concept by stripping {@code /pojem/} from the concept IRI
     * ({@link #extractOntologyIRIFromConcept}) — a reasonable inference given OFN's IRI
     * structure. There is no single authoritative graph IRI here (the detail model can
     * mix vocabularies), hence the per-concept derivation rather than a fixed graphName.
     */
    public static int normalizeForNkd(Model model) {
        int count = normalizeForLocalDetail(model);
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
     * Infers OFN role tags ({@code skos:Concept}, {@code slovníky:pojem},
     * {@code slovníky:třída}) on {@code owl:Class} concepts. Role tags only — does NOT
     * touch {@code skos:inScheme} (that's the NKD-only derivation step, or the
     * authoritative upload step).
     */
    private static int normalizeOwlClassConcepts(Model model) {
        Resource slovnikyPojem = model.createResource(OFN_NAMESPACE + POJEM);
        Resource slovnikyTrida = model.createResource(OFN_NAMESPACE + TRIDA);
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
            if (modified) count++;
        }

        return count;
    }

    /**
     * Infers OFN role tags ({@code slovníky:vztah} on object properties,
     * {@code slovníky:vlastnost} on datatype properties) for {@code /pojem/} concepts.
     * Role tags only — does NOT touch {@code skos:inScheme}.
     */
    private static int normalizePropertyConcepts(Model model) {
        Resource slovnikyVztah = model.createResource(OFN_NAMESPACE + VZTAH);
        Resource slovnikyVlastnost = model.createResource(OFN_NAMESPACE + VLASTNOST);
        int count = 0;

        List<Resource> objectProperties = new ArrayList<>();
        ResIterator iter = model.listResourcesWithProperty(RDF.type, OWL2.ObjectProperty);
        while (iter.hasNext()) {
            objectProperties.add(iter.next());
        }
        for (Resource prop : objectProperties) {
            if (prop.isURIResource() && prop.getURI().contains("/pojem/")
                    && !prop.hasProperty(RDF.type, slovnikyVztah)) {
                prop.addProperty(RDF.type, slovnikyVztah);
                count++;
            }
        }

        List<Resource> datatypeProperties = new ArrayList<>();
        iter = model.listResourcesWithProperty(RDF.type, OWL2.DatatypeProperty);
        while (iter.hasNext()) {
            datatypeProperties.add(iter.next());
        }
        for (Resource prop : datatypeProperties) {
            if (prop.isURIResource() && prop.getURI().contains("/pojem/")
                    && !prop.hasProperty(RDF.type, OWL2.ObjectProperty)
                    && !prop.hasProperty(RDF.type, slovnikyVlastnost)) {
                prop.addProperty(RDF.type, slovnikyVlastnost);
                count++;
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
