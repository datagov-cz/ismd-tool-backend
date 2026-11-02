package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.vocabulary.SKOS;

import java.util.List;
import java.util.Set;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.POJEM;
import static com.dia.constants.VocabularyConstants.SKOS_NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Regression tests for the missing {@code skos:inScheme} write-path bug. The
 * resolver in {@link com.dia.ismdtoolbackend.repository.JenaTDB2Repository#fetchConceptResolutions}
 * only finds concepts that carry {@code skos:inScheme}, so the upload-side
 * normalizer must add it for every concept that's missing one — classes,
 * datatype properties, and object properties alike.
 */
class OFNTypeNormalizerTest {

    private static final String SKOS_INSCHEME = "http://www.w3.org/2004/02/skos/core#inScheme";
    private static final String ONTOLOGY = "https://example.org/slovnik/test-slovnik";
    private static final String CLASS_IRI = ONTOLOGY + "/pojem/test-trida";
    private static final String DATATYPE_PROP_IRI = ONTOLOGY + "/pojem/test-vlastnost";
    private static final String OBJECT_PROP_IRI = ONTOLOGY + "/pojem/test-vztah";

    @Test
    void normalize_addsInScheme_forOwlClassConcept() {
        Model model = ModelFactory.createDefaultModel();
        Resource cls = model.createResource(CLASS_IRI);
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalizeForNkd(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(cls.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("OWL Class concept should get skos:inScheme → enclosing ontology IRI")
                .isTrue();
    }

    @Test
    void normalize_addsInScheme_forDatatypeProperty() {
        Model model = ModelFactory.createDefaultModel();
        Resource prop = model.createResource(DATATYPE_PROP_IRI);
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalizeForNkd(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(prop.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("Datatype property concept should get skos:inScheme → enclosing ontology IRI")
                .isTrue();
    }

    @Test
    void normalize_addsInScheme_forObjectProperty() {
        Model model = ModelFactory.createDefaultModel();
        Resource prop = model.createResource(OBJECT_PROP_IRI);
        prop.addProperty(RDF.type, OWL2.ObjectProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalizeForNkd(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(prop.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("Object property concept should get skos:inScheme → enclosing ontology IRI")
                .isTrue();
    }

    @Test
    void normalize_doesNotOverwriteExistingInScheme_onProperty() {
        Model model = ModelFactory.createDefaultModel();
        String otherScheme = "https://example.org/slovnik/other-slovnik";
        Resource prop = model.createResource(DATATYPE_PROP_IRI);
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);
        prop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        Property inScheme = model.createProperty(SKOS_INSCHEME);
        prop.addProperty(inScheme, model.createResource(otherScheme));

        OFNTypeNormalizer.normalizeForNkd(model);

        assertThat(prop.hasProperty(inScheme, model.createResource(otherScheme)))
                .as("Existing skos:inScheme must be preserved")
                .isTrue();
        assertThat(prop.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("Normalizer must not add a competing skos:inScheme when one already exists")
                .isFalse();
    }

    @Test
    void normalize_ignoresPropertiesWithoutPojemSegment() {
        Model model = ModelFactory.createDefaultModel();
        Resource prop = model.createResource("https://example.org/something/not-a-concept");
        prop.addProperty(RDF.type, OWL2.DatatypeProperty);

        OFNTypeNormalizer.normalizeForNkd(model);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(prop.hasProperty(inScheme))
                .as("Resources outside the /pojem/ shape are not concepts and must be left alone")
                .isFalse();
    }

    // --- Graph-aware (upload-path) overload: normalize(model, graphName) ---

    @Test
    void normalizeWithGraphName_throws_whenGraphNameNull() {
        Model model = ModelFactory.createDefaultModel();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("Upload-path normalizer requires an authoritative graphName")
                .isThrownBy(() -> OFNTypeNormalizer.normalize(model, null));
    }

    @Test
    void normalizeWithGraphName_throws_whenGraphNameBlank() {
        Model model = ModelFactory.createDefaultModel();
        assertThatThrownBy(() -> OFNTypeNormalizer.normalize(model, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * End-state invariant: after the upload-path normalize, EVERY skos:Concept
     * under graphName carries skos:inScheme. This is the resolution invariant the
     * resolver depends on — the original stale orphans were concepts that slipped
     * through with no inScheme.
     */
    @Test
    void normalizeWithGraphName_everyOwnedConceptHasInScheme() {
        Model model = ModelFactory.createDefaultModel();

        Resource cls = model.createResource(CLASS_IRI);
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        Resource dprop = model.createResource(DATATYPE_PROP_IRI);
        dprop.addProperty(RDF.type, OWL2.DatatypeProperty);
        dprop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        Resource oprop = model.createResource(OBJECT_PROP_IRI);
        oprop.addProperty(RDF.type, OWL2.ObjectProperty);
        oprop.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalize(model, ONTOLOGY);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        ResIterator concepts = model.listResourcesWithProperty(RDF.type, SKOS.Concept);
        int checked = 0;
        while (concepts.hasNext()) {
            Resource c = concepts.next();
            if (!c.isURIResource() || !c.getURI().startsWith(ONTOLOGY)) {
                continue;
            }
            checked++;
            assertThat(c.hasProperty(inScheme))
                    .as("Every owned skos:Concept must carry skos:inScheme after normalize: " + c.getURI())
                    .isTrue();
            assertThat(c.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                    .as("inScheme must point to the authoritative graphName, not a /pojem/-derived value: " + c.getURI())
                    .isTrue();
        }
        assertThat(checked).as("All three owned concepts should have been checked").isEqualTo(3);
    }

    /**
     * The gap that produced the original stale orphans: a bare skos:Concept with no
     * owl:Class / ObjectProperty / DatatypeProperty type. The graph-aware normalizer
     * must still give it inScheme → graphName.
     */
    @Test
    void normalizeWithGraphName_addsInScheme_forBareSkosConcept() {
        Model model = ModelFactory.createDefaultModel();
        String bareIri = ONTOLOGY + "/pojem/bare-pojem";
        Resource concept = model.createResource(bareIri);
        concept.addProperty(RDF.type, SKOS.Concept);

        OFNTypeNormalizer.normalize(model, ONTOLOGY);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(concept.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("Bare skos:Concept under graphName must get inScheme → graphName")
                .isTrue();
    }

    /**
     * Alien concept: an embedded concept whose IRI is NOT under graphName (e.g. a
     * legislative reference) must NOT be claimed as owned — no inScheme → graphName.
     */
    @Test
    void normalizeWithGraphName_leavesAlienConceptUnclaimed() {
        Model model = ModelFactory.createDefaultModel();
        String alienIri = "https://example.org/legislativa/128-2000/pojem/obec";
        Resource alien = model.createResource(alienIri);
        alien.addProperty(RDF.type, SKOS.Concept);
        alien.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalize(model, ONTOLOGY);

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(alien.hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("Alien concept must NOT be claimed with inScheme → graphName")
                .isFalse();
    }

    // --- detectOwnedConceptsMissingInScheme (pure detection) ---

    @Test
    void detect_returnsOwnedConceptsMissingInScheme_andDoesNotMutate() {
        Model model = ModelFactory.createDefaultModel();
        String missing = ONTOLOGY + "/pojem/bez-scheme";
        Resource concept = model.createResource(missing);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        long sizeBefore = model.size();
        List<String> result = OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(model, ONTOLOGY);

        assertThat(result).as("Should report the one owned concept missing inScheme").isEqualTo(List.of(missing));
        assertThat(model.size()).as("detect must not mutate the model").isEqualTo(sizeBefore);
        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(concept.hasProperty(inScheme)).as("detect must not add inScheme").isFalse();
    }

    @Test
    void detect_ignoresConceptsThatAlreadyHaveInScheme() {
        Model model = ModelFactory.createDefaultModel();
        Resource concept = model.createResource(CLASS_IRI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        concept.addProperty(model.createProperty(SKOS_INSCHEME), model.createResource(ONTOLOGY));

        assertThat(OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(model, ONTOLOGY))
                .as("A concept that already has inScheme is not 'missing'")
                .isEmpty();
    }

    @Test
    void detect_ignoresAlienConcepts() {
        Model model = ModelFactory.createDefaultModel();
        Resource alien = model.createResource("https://example.org/128-2000/pojem/obec");
        alien.addProperty(RDF.type, SKOS.Concept);
        alien.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        assertThat(OFNTypeNormalizer.detectOwnedConceptsMissingInScheme(model, ONTOLOGY))
                .as("Alien concepts are not owned and must not appear in the missing list")
                .isEmpty();
    }

    // --- normalize(model, graphName, allowList) selective stamping ---

    @Test
    void normalizeWithAllowList_stampsOnlyListedConcepts() {
        Model model = ModelFactory.createDefaultModel();
        String keep = ONTOLOGY + "/pojem/keep";
        String drop = ONTOLOGY + "/pojem/drop";
        for (String iri : List.of(keep, drop)) {
            Resource c = model.createResource(iri);
            c.addProperty(RDF.type, SKOS.Concept);
            c.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        }

        OFNTypeNormalizer.normalize(model, ONTOLOGY, Set.of(keep));

        Property inScheme = model.createProperty(SKOS_INSCHEME);
        assertThat(model.getResource(keep).hasProperty(inScheme, model.createResource(ONTOLOGY)))
                .as("Allow-listed concept must be stamped")
                .isTrue();
        assertThat(model.getResource(drop).hasProperty(inScheme))
                .as("Concept not in the allow-list must be left without inScheme (excluded)")
                .isFalse();
    }

    @Test
    void normalizeWithEmptyAllowList_stampsNothing() {
        Model model = ModelFactory.createDefaultModel();
        Resource c = model.createResource(ONTOLOGY + "/pojem/x");
        c.addProperty(RDF.type, SKOS.Concept);
        c.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalize(model, ONTOLOGY, Set.of());

        assertThat(c.hasProperty(model.createProperty(SKOS_INSCHEME)))
                .as("Empty allow-list (EXCLUDE_ALL) must add no inScheme")
                .isFalse();
    }

    // --- Read-path split: local does NOT derive inScheme, NKD does ---

    @Test
    void normalizeForLocalDetail_doesNotDeriveInScheme_butStillInfersRoleTags() {
        Model model = ModelFactory.createDefaultModel();
        Resource cls = model.createResource(CLASS_IRI);
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalizeForLocalDetail(model);

        // Role tags still inferred so the concept survives TurtleFilterUtil...
        assertThat(cls.hasProperty(RDF.type, SKOS.Concept))
                .as("Local path must still infer skos:Concept role tag")
                .isTrue();
        // ...but inScheme is NOT derived: local data is authoritative post-upload-fix.
        assertThat(cls.hasProperty(model.createProperty(SKOS_INSCHEME)))
                .as("Local path must NOT derive skos:inScheme (redundant; trusts authoritative upload)")
                .isFalse();
    }

    @Test
    void normalizeForNkd_derivesInScheme_fromPojemPattern() {
        Model model = ModelFactory.createDefaultModel();
        Resource cls = model.createResource(CLASS_IRI);
        cls.addProperty(RDF.type, OWL2.Class);
        cls.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        OFNTypeNormalizer.normalizeForNkd(model);

        assertThat(cls.hasProperty(model.createProperty(SKOS_INSCHEME), model.createResource(ONTOLOGY)))
                .as("NKD path must derive skos:inScheme by stripping /pojem/ from the concept IRI")
                .isTrue();
    }

    // --- pruneUnreferencedExcludedConcepts: close the "excluded leaks into TDB2" gap ---

    private static Resource ownedConcept(Model model, String iri) {
        Resource c = model.createResource(iri);
        c.addProperty(RDF.type, SKOS.Concept);
        c.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));
        return c;
    }

    @Test
    void prune_throws_whenGraphNameNull() {
        Model model = ModelFactory.createDefaultModel();
        assertThatThrownBy(() -> OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prune_removesUnreferencedExcludedConcept() {
        Model model = ModelFactory.createDefaultModel();
        String excludedIri = ONTOLOGY + "/pojem/excluded";
        Resource excluded = ownedConcept(model, excludedIri);
        excluded.addProperty(model.createProperty(SKOS_NS + "prefLabel"), "Excluded");

        int pruned = OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, ONTOLOGY);

        assertThat(pruned).as("The single unreferenced excluded concept should be pruned").isEqualTo(1);
        assertThat(model.listStatements(model.createResource(excludedIri), null, (RDFNode) null).hasNext())
                .as("Excluded concept body must be removed from the model")
                .isFalse();
    }

    @Test
    void prune_keepsExcludedConcept_whenReferencedByOwnedConcept() {
        Model model = ModelFactory.createDefaultModel();
        // Owned (normalized) class points at an excluded class via rdfs:subClassOf.
        String ownedIri = ONTOLOGY + "/pojem/owned-trida";
        Resource owned = ownedConcept(model, ownedIri);
        owned.addProperty(model.createProperty(SKOS_NS + "inScheme"), model.createResource(ONTOLOGY));

        String excludedIri = ONTOLOGY + "/pojem/excluded-parent";
        Resource excluded = ownedConcept(model, excludedIri);
        excluded.addProperty(model.createProperty(SKOS_NS + "prefLabel"), "Parent");
        owned.addProperty(RDFS.subClassOf, excluded);

        int pruned = OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, ONTOLOGY);

        assertThat(pruned).as("A referenced excluded concept must NOT be pruned").isEqualTo(0);
        assertThat(model.listStatements(excluded, null, (RDFNode) null).hasNext())
                .as("Referenced excluded concept must keep its triples (inert context)")
                .isTrue();
        assertThat(owned.hasProperty(RDFS.subClassOf, excluded))
                .as("Owned concept's edge to the excluded concept must survive (no dangling reference)")
                .isTrue();
    }

    @Test
    void prune_leavesNormalizedConceptsUntouched() {
        Model model = ModelFactory.createDefaultModel();
        String keptIri = ONTOLOGY + "/pojem/kept";
        Resource kept = ownedConcept(model, keptIri);
        kept.addProperty(model.createProperty(SKOS_NS + "inScheme"), model.createResource(ONTOLOGY));

        int pruned = OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, ONTOLOGY);

        assertThat(pruned).as("Concepts carrying inScheme are owned, not excluded").isEqualTo(0);
        assertThat(model.listStatements(kept, null, (RDFNode) null).hasNext())
                .as("Normalized owned concept must be left intact")
                .isTrue();
    }

    @Test
    void prune_leavesAlienConceptUntouched() {
        // An alien (external-namespace) concept without inScheme is NOT an owned
        // candidate, so prune must ignore it entirely — even when unreferenced.
        Model model = ModelFactory.createDefaultModel();
        String alienIri = "https://example.org/jine/pojem/adresa";
        Resource alien = model.createResource(alienIri);
        alien.addProperty(RDF.type, SKOS.Concept);
        alien.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + POJEM));

        int pruned = OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, ONTOLOGY);

        assertThat(pruned).as("Alien concepts are not owned candidates and must be left alone").isEqualTo(0);
        assertThat(model.listStatements(alien, null, (RDFNode) null).hasNext())
                .as("Alien concept triples must remain")
                .isTrue();
    }

    @Test
    void prune_afterExcludeAllNormalize_endToEnd() {
        // Simulates the EXCLUDE_ALL path: normalize with an empty allow-list leaves
        // every owned concept without inScheme; prune then removes the unreferenced ones.
        Model model = ModelFactory.createDefaultModel();
        String aIri = ONTOLOGY + "/pojem/a";
        String bIri = ONTOLOGY + "/pojem/b-referenced";
        Resource a = ownedConcept(model, aIri);
        Resource b = ownedConcept(model, bIri);
        // a references b, so b must survive even though both are excluded.
        a.addProperty(RDFS.subClassOf, b);

        OFNTypeNormalizer.normalize(model, ONTOLOGY, Set.of()); // EXCLUDE_ALL
        int pruned = OFNTypeNormalizer.pruneUnreferencedExcludedConcepts(model, ONTOLOGY);

        assertThat(pruned).as("Only the unreferenced excluded concept (a) is pruned").isEqualTo(1);
        assertThat(model.listStatements(model.createResource(aIri), null, (RDFNode) null).hasNext())
                .as("Unreferenced excluded concept 'a' must be removed")
                .isFalse();
        assertThat(model.listStatements(b, null, (RDFNode) null).hasNext())
                .as("Referenced excluded concept 'b' must be kept")
                .isTrue();
    }
}
