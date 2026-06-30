package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
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
 * Regression for the cross-graph property/relationship read asymmetry: a member
 * (property or relationship) whose {@code rdfs:domain} points at a class but which
 * lives in a <em>different</em> vocabulary graph than the class was dropped from the
 * class-detail response, even though it stayed visible from the member's own detail.
 *
 * <p>The class-detail read ({@code extractConceptDetail}) traverses a flat model.
 * The production fix merges the cross-graph members into that model before extraction
 * (see {@code JenaTDB2Repository#fetchExternalDomainMembers} +
 * {@code ConceptServiceImpl#getConceptDetail}). Here we simulate the post-merge model:
 * the member carries only the triples the cross-graph CONSTRUCT returns
 * ({@code rdf:type}, {@code rdfs:domain}, {@code skos:prefLabel}, {@code rdfs:range}) —
 * notably NO {@code skos:inScheme} into the class's ontology — and assert the class
 * still surfaces it.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorCrossGraphTest {

    private static final String CLASS_IRI = "https://example.org/vocab-a/pojem/osoba";
    private static final String EXTERNAL_PROPERTY_IRI = "https://example.org/vocab-b/pojem/věk";
    private static final String EXTERNAL_RELATIONSHIP_IRI = "https://example.org/vocab-b/pojem/bydlí-v";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    private static final String RANGE_RELATIONSHIP_IRI = "https://example.org/vocab-b/pojem/má-bydliště";

    @Test
    void classDetail_includesCrossGraphPropertyAndRelationship() {
        Model model = buildPostMergeModel();

        OntologyDetailModel.ConceptDetailModel classDetail =
                extractor.extractConceptDetail(model, CLASS_IRI, iri -> null);

        assertThat(classDetail).isNotNull();
        assertThat(classDetail.getConceptProperties())
                .as("cross-graph property must surface on the class detail")
                .anySatisfy(p -> assertThat(p.getIri()).isEqualTo(EXTERNAL_PROPERTY_IRI));
        assertThat(classDetail.getConceptRelationships())
                .as("cross-graph relationship must surface on the class detail")
                .anySatisfy(r -> assertThat(r.getIri()).isEqualTo(EXTERNAL_RELATIONSHIP_IRI));
    }

    /**
     * A class is the {@code rdfs:range} (not the domain) of a relationship — that relationship
     * must still surface on the class detail. Regression for the bug where a class only ever
     * listed relationships where it was the domain, so a range-target class showed nothing.
     */
    @Test
    void classDetail_includesRelationshipWhereClassIsRange() {
        Model model = buildPostMergeModel();

        // A relationship whose RANGE is the class (domain points elsewhere).
        Resource vztahType = model.createResource(OFN_NAMESPACE + VZTAH);
        Resource otherClass = model.createResource("https://example.org/vocab-b/pojem/adresa");
        Resource rangeRel = model.createResource(RANGE_RELATIONSHIP_IRI);
        rangeRel.addProperty(RDF.type, vztahType);
        rangeRel.addProperty(SKOS.prefLabel, "má bydliště", "cs");
        rangeRel.addProperty(RDFS.domain, otherClass);
        rangeRel.addProperty(RDFS.range, model.createResource(CLASS_IRI));

        OntologyDetailModel.ConceptDetailModel classDetail =
                extractor.extractConceptDetail(model, CLASS_IRI, iri -> null);

        assertThat(classDetail).isNotNull();
        assertThat(classDetail.getConceptRelationships())
                .as("a relationship whose range is the class must surface on the class detail")
                .anySatisfy(r -> assertThat(r.getIri()).isEqualTo(RANGE_RELATIONSHIP_IRI));
    }

    /**
     * A relationship that points BOTH its domain and range at the same class is listed once
     * (deduped on IRI), not twice.
     */
    @Test
    void classDetail_dedupesRelationshipWithClassAsBothDomainAndRange() {
        Model model = buildPostMergeModel();

        Resource vztahType = model.createResource(OFN_NAMESPACE + VZTAH);
        Resource reflexive = model.createResource("https://example.org/vocab-b/pojem/zná");
        reflexive.addProperty(RDF.type, vztahType);
        reflexive.addProperty(SKOS.prefLabel, "zná", "cs");
        reflexive.addProperty(RDFS.domain, model.createResource(CLASS_IRI));
        reflexive.addProperty(RDFS.range, model.createResource(CLASS_IRI));

        OntologyDetailModel.ConceptDetailModel classDetail =
                extractor.extractConceptDetail(model, CLASS_IRI, iri -> null);

        assertThat(classDetail).isNotNull();
        assertThat(classDetail.getConceptRelationships())
                .filteredOn(r -> "https://example.org/vocab-b/pojem/zná".equals(r.getIri()))
                .as("a domain==range relationship must appear exactly once")
                .hasSize(1);
    }

    /**
     * Class in vocab A; property + relationship that point at it via rdfs:domain but
     * belong to vocab B and were fetched cross-graph — so they have type/domain/label
     * but no inScheme into vocab A's ontology.
     */
    private Model buildPostMergeModel() {
        Model model = ModelFactory.createDefaultModel();

        Resource tridaType = model.createResource(OFN_NAMESPACE + TRIDA);
        Resource vlastnostType = model.createResource(OFN_NAMESPACE + VLASTNOST);
        Resource vztahType = model.createResource(OFN_NAMESPACE + VZTAH);

        Resource classConcept = model.createResource(CLASS_IRI);
        classConcept.addProperty(RDF.type, tridaType);
        classConcept.addProperty(SKOS.prefLabel, "Osoba", "cs");

        Resource property = model.createResource(EXTERNAL_PROPERTY_IRI);
        property.addProperty(RDF.type, vlastnostType);
        property.addProperty(SKOS.prefLabel, "věk", "cs");
        property.addProperty(RDFS.domain, classConcept);
        property.addProperty(RDFS.range, model.createResource("http://www.w3.org/2001/XMLSchema#integer"));

        Resource relationship = model.createResource(EXTERNAL_RELATIONSHIP_IRI);
        relationship.addProperty(RDF.type, vztahType);
        relationship.addProperty(SKOS.prefLabel, "bydlí v", "cs");
        relationship.addProperty(RDFS.domain, classConcept);

        return model;
    }
}