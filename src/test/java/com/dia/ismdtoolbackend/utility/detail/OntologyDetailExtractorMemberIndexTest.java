package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code MemberIndex} invariants on the whole-ontology detail path. The index replaced
 * two O(N^2) full rescans with a single-pass build, and the behaviours it had to preserve were
 * verified only by a JSON body diff at the time — no test covered them.
 *
 * <p>These run through {@link OntologyDetailExtractor#extractOntologyDetail(Model, java.util.function.Function)},
 * the whole-ontology entry point, which is the branch that uses the index (the single-concept
 * branch walks the model directly instead).
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorMemberIndexTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab-a";
    private static final String CLASS_IRI = ONTOLOGY_IRI + "/pojem/osoba";
    private static final String OTHER_CLASS_IRI = ONTOLOGY_IRI + "/pojem/adresa";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    /** A vztah with domain == range is listed once, not twice. */
    @Test
    void selfReferencingRelationshipListedOnce() {
        Model model = ontologyWithClasses();
        Resource rel = member(model, ONTOLOGY_IRI + "/pojem/zná", VZTAH, "zná");
        rel.addProperty(RDFS.domain, model.createResource(CLASS_IRI));
        rel.addProperty(RDFS.range, model.createResource(CLASS_IRI));

        OntologyDetailModel.ConceptDetailModel klass = classDetail(model);

        assertThat(klass.getConceptRelationships())
                .filteredOn(r -> (ONTOLOGY_IRI + "/pojem/zná").equals(r.getIri()))
                .as("a domain==range vztah must appear exactly once")
                .hasSize(1);
    }

    /** A vztah attaches to BOTH endpoints — the domain class and the range class. */
    @Test
    void relationshipAttachesToDomainAndRange() {
        Model model = ontologyWithClasses();
        Resource rel = member(model, ONTOLOGY_IRI + "/pojem/bydlí-v", VZTAH, "bydlí v");
        rel.addProperty(RDFS.domain, model.createResource(CLASS_IRI));
        rel.addProperty(RDFS.range, model.createResource(OTHER_CLASS_IRI));

        OntologyDetailModel detail = extract(model);

        assertThat(concept(detail, CLASS_IRI).getConceptRelationships())
                .as("domain endpoint lists the vztah")
                .anySatisfy(r -> assertThat(r.getIri()).isEqualTo(ONTOLOGY_IRI + "/pojem/bydlí-v"));
        assertThat(concept(detail, OTHER_CLASS_IRI).getConceptRelationships())
                .as("range endpoint lists the vztah too")
                .anySatisfy(r -> assertThat(r.getIri()).isEqualTo(ONTOLOGY_IRI + "/pojem/bydlí-v"));
    }

    /**
     * A vlastnost attaches by domain only. Its {@code obor-hodnot} is a datatype, not a class,
     * so it must never surface as a member of whatever its range points at.
     */
    @Test
    void propertyAttachesByDomainOnly() {
        Model model = ontologyWithClasses();
        Resource prop = member(model, ONTOLOGY_IRI + "/pojem/věk", VLASTNOST, "věk");
        prop.addProperty(RDFS.domain, model.createResource(CLASS_IRI));
        prop.addProperty(RDFS.range, model.createResource(OTHER_CLASS_IRI));

        OntologyDetailModel detail = extract(model);

        assertThat(concept(detail, CLASS_IRI).getConceptProperties())
                .anySatisfy(p -> assertThat(p.getIri()).isEqualTo(ONTOLOGY_IRI + "/pojem/věk"));
        assertThat(concept(detail, OTHER_CLASS_IRI).getConceptProperties())
                .as("a vlastnost must not attach to its range")
                .noneSatisfy(p -> assertThat(p.getIri()).isEqualTo(ONTOLOGY_IRI + "/pojem/věk"));
    }

    /** Properties and relationships land in their own buckets, never crossed. */
    @Test
    void propertiesAndRelationshipsDoNotCrossBuckets() {
        Model model = ontologyWithClasses();
        member(model, ONTOLOGY_IRI + "/pojem/věk", VLASTNOST, "věk")
                .addProperty(RDFS.domain, model.createResource(CLASS_IRI));
        member(model, ONTOLOGY_IRI + "/pojem/bydlí-v", VZTAH, "bydlí v")
                .addProperty(RDFS.domain, model.createResource(CLASS_IRI));

        OntologyDetailModel.ConceptDetailModel klass = classDetail(model);

        assertThat(klass.getConceptProperties()).extracting("iri")
                .containsExactly(ONTOLOGY_IRI + "/pojem/věk");
        assertThat(klass.getConceptRelationships()).extracting("iri")
                .containsExactly(ONTOLOGY_IRI + "/pojem/bydlí-v");
    }

    /** A member with no domain at all attaches to nothing rather than blowing up the build. */
    @Test
    void memberWithoutDomainIsSkipped() {
        Model model = ontologyWithClasses();
        member(model, ONTOLOGY_IRI + "/pojem/bez-oboru", VLASTNOST, "bez oboru");

        OntologyDetailModel.ConceptDetailModel klass = classDetail(model);

        assertThat(klass.getConceptProperties()).isEmpty();
        assertThat(klass.getConceptRelationships()).isEmpty();
    }

    // --- helpers ---

    private OntologyDetailModel extract(Model model) {
        return extractor.extractOntologyDetail(model, iri -> null);
    }

    private OntologyDetailModel.ConceptDetailModel classDetail(Model model) {
        return concept(extract(model), CLASS_IRI);
    }

    private static OntologyDetailModel.ConceptDetailModel concept(OntologyDetailModel detail, String iri) {
        return detail.getConcepts().stream()
                .filter(c -> iri.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("concept not in detail: " + iri));
    }

    private static Model ontologyWithClasses() {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + "slovník"));

        for (String iri : new String[]{CLASS_IRI, OTHER_CLASS_IRI}) {
            Resource klass = model.createResource(iri);
            klass.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
            klass.addProperty(SKOS.prefLabel, iri.substring(iri.lastIndexOf('/') + 1), "cs");
            klass.addProperty(SKOS.inScheme, ontology);
        }
        return model;
    }

    private static Resource member(Model model, String iri, String ofnType, String label) {
        Resource r = model.createResource(iri);
        r.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + ofnType));
        r.addProperty(SKOS.prefLabel, label, "cs");
        r.addProperty(SKOS.inScheme, model.createResource(ONTOLOGY_IRI));
        return r;
    }
}
