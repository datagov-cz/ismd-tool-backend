package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins that the OFN transforms leave the caller's model untouched.
 *
 * <p>{@code OFNTypeNormalizer} mutates the model it is handed, and callers pass models they do not
 * own: {@code fetchPublishedOntology} passes the {@code @Cacheable} raw NKD model, and
 * {@code getConceptDetail} extracts from the same {@code rawModel} it hands to the {@code @Cacheable}
 * {@code canonicalLocalConcept}. Mutating either corrupts a cache entry or makes the response depend
 * on cache state.
 *
 * <p>The fixture stores its label as {@code rdfs:label} with generic types — the shape the normalizer
 * acts on. An already-OFN-canonical fixture makes it a no-op and hides the regression.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorInputImmutabilityTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab-a";
    private static final String CONCEPT_IRI = ONTOLOGY_IRI + "/pojem/obec";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    /** A concept whose label is rdfs:label and whose typing is generic — normalizer bait. */
    private static Model buildNonCanonicalModel() {
        Model model = ModelFactory.createDefaultModel();
        Resource concept = model.createResource(CONCEPT_IRI);
        concept.addProperty(RDF.type, SKOS.Concept);
        concept.addProperty(RDF.type, OWL2.Class);
        concept.addProperty(RDFS.label, model.createLiteral("Obec", "cs"));
        concept.addProperty(SKOS.inScheme, model.createResource(ONTOLOGY_IRI));
        return model;
    }

    @Test
    void applyOFNTransformations_leavesTheCallersModelUnchanged() {
        Model rawModel = buildNonCanonicalModel();
        Model pristine = ModelFactory.createDefaultModel().add(rawModel);

        extractor.applyOFNTransformations(rawModel);

        assertThat(rawModel.isIsomorphicWith(pristine))
                .as("applyOFNTransformations must not mutate the model it is given")
                .isTrue();
    }

    @Test
    void applyOFNTransformationsForNkd_leavesTheCallersModelUnchanged() {
        Model rawModel = buildNonCanonicalModel();
        Model pristine = ModelFactory.createDefaultModel().add(rawModel);

        extractor.applyOFNTransformationsForNkd(rawModel);

        assertThat(rawModel.isIsomorphicWith(pristine))
                .as("applyOFNTransformationsForNkd must not mutate the shared cached NKD model")
                .isTrue();
    }

    /**
     * The transform still has to do its job — a guard that only checked "input unchanged"
     * would pass if the transform became a no-op.
     */
    @Test
    void applyOFNTransformations_stillNormalizesIntoTheReturnedModel() {
        Model rawModel = buildNonCanonicalModel();

        Model processed = extractor.applyOFNTransformations(rawModel);

        assertThat(processed.contains(processed.getResource(CONCEPT_IRI), SKOS.prefLabel))
                .as("the returned model carries the normalized skos:prefLabel")
                .isTrue();
        assertThat(rawModel.contains(rawModel.getResource(CONCEPT_IRI), RDFS.label))
                .as("the caller's model keeps its original rdfs:label")
                .isTrue();
    }

    /**
     * Regression for the concept-detail response depending on cache state: extracting after
     * a transform must match extracting without one, since {@code canonicalLocalConcept} is
     * {@code @Cacheable} and only runs the transform on a miss.
     */
    @Test
    void extractionAfterATransform_matchesExtractionWithoutOne() {
        Model afterTransform = buildNonCanonicalModel();
        extractor.applyOFNTransformations(afterTransform);
        OntologyDetailModel.ConceptDetailModel cold =
                extractor.extractConceptDetail(afterTransform, CONCEPT_IRI);

        Model untouched = buildNonCanonicalModel();
        OntologyDetailModel.ConceptDetailModel warm =
                extractor.extractConceptDetail(untouched, CONCEPT_IRI);

        assertThat(nameOf(cold))
                .as("concept detail must not differ between a cold and a warm projection cache")
                .isEqualTo(nameOf(warm));
    }

    private static Object nameOf(OntologyDetailModel.ConceptDetailModel detail) {
        return detail == null ? null : detail.getName();
    }
}