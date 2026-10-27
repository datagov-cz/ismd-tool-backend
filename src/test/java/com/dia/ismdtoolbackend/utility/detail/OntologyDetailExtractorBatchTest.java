package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.entity.ConceptMetadataEntity;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dia.constants.VocabularyConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link OntologyDetailExtractor#extractConceptDetails} and the single-concept slug resolution. */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorBatchTest {

    private static final String ONTOLOGY = "https://example.org/vocab";
    private static final String PERSON = ONTOLOGY + "/pojem/osoba";
    private static final String ADDRESS = ONTOLOGY + "/pojem/adresa";
    private static final String AGE = ONTOLOGY + "/pojem/věk";
    private static final String LIVES_AT = ONTOLOGY + "/pojem/bydlí-na";
    private static final String MISSING = ONTOLOGY + "/pojem/neexistuje";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    private static Model model() {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix ofn:  <%s> .
                <%s> a <http://www.w3.org/2002/07/owl#Ontology> ; skos:prefLabel "Slovník"@cs .
                <%s> a ofn:třída ; skos:inScheme <%s> ; skos:prefLabel "Osoba"@cs .
                <%s> a ofn:třída ; skos:inScheme <%s> ; skos:prefLabel "Adresa"@cs .
                <%s> a ofn:vlastnost ; skos:inScheme <%s> ; skos:prefLabel "věk"@cs ;
                    rdfs:domain <%s> ; rdfs:range xsd:integer .
                <%s> a ofn:vztah ; skos:inScheme <%s> ; skos:prefLabel "bydlí na"@cs ;
                    rdfs:domain <%s> ; rdfs:range <%s> .
                """.formatted(OFN_NAMESPACE, ONTOLOGY, PERSON, ONTOLOGY, ADDRESS, ONTOLOGY, AGE, ONTOLOGY, PERSON,
                LIVES_AT, ONTOLOGY, PERSON, ADDRESS), Lang.TURTLE).parse(model);
        return model;
    }

    private static ConceptMetadataEntity slug(String iri, String slug) {
        ConceptMetadataEntity entity = new ConceptMetadataEntity();
        entity.setConceptIri(iri);
        entity.setSlug(slug);
        return entity;
    }

    @Test
    void extractsEachRequestedConceptWithItsMembersFromOneSlugQuery() {
        when(conceptMetadataRepository.findByConceptIriIn(
                argThat(iris -> iris.size() == 2 && iris.containsAll(List.of(AGE, LIVES_AT)))))
                .thenReturn(List.of(slug(AGE, "vocab-věk"), slug(LIVES_AT, "vocab-bydlí-na")));

        Map<String, OntologyDetailModel.ConceptDetailModel> details =
                extractor.extractConceptDetails(model(), List.of(PERSON, ADDRESS));

        assertThat(details).containsOnlyKeys(PERSON, ADDRESS);
        OntologyDetailModel.ConceptDetailModel person = details.get(PERSON);
        assertThat(person.getIri()).isEqualTo(PERSON);
        assertThat(person.getName()).isEqualTo(Map.of("cs", "Osoba"));
        assertThat(person.getConceptProperties()).extracting(ConceptPropertiesModel::getIri,
                ConceptPropertiesModel::getName, ConceptPropertiesModel::getRef, ConceptPropertiesModel::getRange)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(AGE, "věk", "vocab-věk", "xsd:integer"));
        assertThat(person.getConceptRelationships()).extracting(ConceptRelationshipsModel::getIri,
                        ConceptRelationshipsModel::getRef)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LIVES_AT, "vocab-bydlí-na"));
        assertThat(details.get(ADDRESS).getConceptProperties()).isEmpty();
        assertThat(details.get(ADDRESS).getConceptRelationships()).extracting(ConceptRelationshipsModel::getIri)
                .containsExactly(LIVES_AT);
        verify(conceptMetadataRepository, never()).findByConceptIri(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void conceptWithoutMembersNeedsNoSlugQuery() {
        OntologyDetailModel.ConceptDetailModel age = extractor.extractConceptDetail(model(), AGE);

        assertThat(age.getIri()).isEqualTo(AGE);
        assertThat(age.getRange()).isEqualTo("xsd:integer");
        assertThat(age.getConceptProperties()).isEmpty();
        assertThat(age.getConceptRelationships()).isEmpty();
        verify(conceptMetadataRepository, never()).findByConceptIriIn(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void conceptAbsentFromTheModelMapsToNullWithoutFailingTheOthers() {
        Map<String, OntologyDetailModel.ConceptDetailModel> details =
                extractor.extractConceptDetails(model(), List.of(MISSING, AGE));

        assertThat(details).containsOnlyKeys(MISSING, AGE);
        assertThat(details.get(MISSING)).isNull();
        assertThat(details.get(AGE).getIri()).isEqualTo(AGE);
    }

    @Test
    void emptyModelResolvesSlugsOneIriAtATime() {
        when(conceptMetadataRepository.findByConceptIri(PERSON)).thenReturn(Optional.of(slug(PERSON, "vocab-osoba")));

        var resolver = extractor.graphSlugResolver(ModelFactory.createDefaultModel());

        assertThat(resolver.apply(PERSON)).isEqualTo("vocab-osoba");
    }
}
