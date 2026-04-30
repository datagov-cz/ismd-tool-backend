package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
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

import java.util.List;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that the {@code ref} field on property/relationship entries is populated via the
 * resolver passed to the extractor — slug for local flow (DB-backed), IRI for NKD flow.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorRefTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab";
    private static final String CLASS_IRI = "https://example.org/vocab/pojem/osoba";
    private static final String PROPERTY_IRI = "https://example.org/vocab/pojem/věk";
    private static final String RELATIONSHIP_IRI = "https://example.org/vocab/pojem/bydlí-v";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    @Test
    void extractOntologyDetail_withIriResolver_populatesRefWithIri() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(model, OntologyDetailExtractor.iriResolver());

        OntologyDetailModel.ConceptDetailModel classConcept = getClassConcept(detail);

        List<ConceptPropertiesModel> properties = classConcept.getConceptProperties();
        assertThat(properties).anySatisfy(p -> assertThat(p.getRef()).isEqualTo(PROPERTY_IRI));

        List<ConceptRelationshipsModel> relationships = classConcept.getConceptRelationships();
        assertThat(relationships).anySatisfy(r -> assertThat(r.getRef()).isEqualTo(RELATIONSHIP_IRI));
    }

    @Test
    void extractOntologyDetail_withDbSlugResolver_populatesRefWithSlug() {
        ConceptMetadataEntity propertyEntity = new ConceptMetadataEntity();
        propertyEntity.setSlug("vocab-věk");
        ConceptMetadataEntity relationshipEntity = new ConceptMetadataEntity();
        relationshipEntity.setSlug("vocab-bydlí-v");

        when(conceptMetadataRepository.findByConceptIri(PROPERTY_IRI)).thenReturn(Optional.of(propertyEntity));
        when(conceptMetadataRepository.findByConceptIri(RELATIONSHIP_IRI)).thenReturn(Optional.of(relationshipEntity));

        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(model);

        OntologyDetailModel.ConceptDetailModel classConcept = getClassConcept(detail);

        assertThat(classConcept.getConceptProperties())
                .anySatisfy(p -> assertThat(p.getRef()).isEqualTo("vocab-věk"));
        assertThat(classConcept.getConceptRelationships())
                .anySatisfy(r -> assertThat(r.getRef()).isEqualTo("vocab-bydlí-v"));
    }

    @Test
    void extractOntologyDetail_dbSlugResolver_missingMetadata_leavesRefNull() {
        when(conceptMetadataRepository.findByConceptIri(anyString())).thenReturn(Optional.empty());

        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(model);

        OntologyDetailModel.ConceptDetailModel classConcept = getClassConcept(detail);
        assertThat(classConcept.getConceptProperties()).allSatisfy(p -> assertThat(p.getRef()).isNull());
        assertThat(classConcept.getConceptRelationships()).allSatisfy(r -> assertThat(r.getRef()).isNull());
    }

    private OntologyDetailModel.ConceptDetailModel getClassConcept(OntologyDetailModel detail) {
        return detail.getConcepts().stream()
                .filter(c -> CLASS_IRI.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("class concept not extracted"));
    }

    /**
     * Minimal OFN-shaped model: ontology + class + property + relationship,
     * all linked via skos:inScheme, property and relationship domain = class.
     */
    private Model buildModel() {
        Model model = ModelFactory.createDefaultModel();

        Resource owlOntology = model.createResource("http://www.w3.org/2002/07/owl#Ontology");
        Resource tridaType = model.createResource(OFN_NAMESPACE + TRIDA);
        Resource vlastnostType = model.createResource(OFN_NAMESPACE + VLASTNOST);
        Resource vztahType = model.createResource(OFN_NAMESPACE + VZTAH);
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");

        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, owlOntology);
        ontology.addProperty(SKOS.prefLabel, "Testovací slovník", "cs");

        Resource classConcept = model.createResource(CLASS_IRI);
        classConcept.addProperty(RDF.type, tridaType);
        classConcept.addProperty(inScheme, ontology);
        classConcept.addProperty(SKOS.prefLabel, "Osoba", "cs");

        Resource property = model.createResource(PROPERTY_IRI);
        property.addProperty(RDF.type, vlastnostType);
        property.addProperty(inScheme, ontology);
        property.addProperty(SKOS.prefLabel, "věk", "cs");
        property.addProperty(RDFS.domain, classConcept);

        Resource relationship = model.createResource(RELATIONSHIP_IRI);
        relationship.addProperty(RDF.type, vztahType);
        relationship.addProperty(inScheme, ontology);
        relationship.addProperty(SKOS.prefLabel, "bydlí v", "cs");
        relationship.addProperty(RDFS.domain, classConcept);

        return model;
    }
}
