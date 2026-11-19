package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.OntologyDetailModel.ConceptDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.models.concept.ConceptRelationshipsModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NKD concepts typed only with an OWL kind (no {@code skos:Concept}, no {@code slovníky:pojem}) are read
 * on the NKD detail path, properties and relationships included.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OntologyDetailExtractor - NKD concepts typed only with an OWL kind")
class OntologyDetailExtractorNkdOwlOnlyTest {

    private static final String VOCABULARY = Ttl.VOCABULARY.substring(0, Ttl.VOCABULARY.length() - 1);

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    @Test
    @DisplayName("reads OWL-only Třída, Vztah and Vlastnost, and links them to the class")
    void owlOnlyConceptsRead() {
        Model nkd = Ttl.parse("""
                <%1$s> a owl:Ontology, skos:ConceptScheme ; skos:prefLabel "Slovník"@cs .
                pojem:osoba a owl:Class ; skos:prefLabel "Osoba"@cs ; skos:inScheme <%1$s> .
                pojem:zná a owl:ObjectProperty ; skos:prefLabel "Zná"@cs ;
                    rdfs:domain pojem:osoba ; rdfs:range pojem:osoba .
                pojem:jméno a owl:DatatypeProperty ; rdfs:label "Jméno"@cs ; rdfs:domain pojem:osoba .
                """.formatted(VOCABULARY));
        OntologyDetailExtractor extractor = new OntologyDetailExtractor(conceptMetadataRepository);

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                extractor.applyOFNTransformationsForNkd(nkd), OntologyDetailExtractor.iriResolver());

        List<ConceptDetailModel> concepts = detail.getConcepts();
        assertThat(concepts).extracting(ConceptDetailModel::getIri)
                .containsExactlyInAnyOrder(Ttl.POJEM + "osoba", Ttl.POJEM + "zná", Ttl.POJEM + "jméno");
        ConceptDetailModel osoba = concepts.stream()
                .filter(concept -> concept.getIri().equals(Ttl.POJEM + "osoba"))
                .findFirst().orElseThrow();
        assertThat(osoba.getConceptRelationships()).extracting(ConceptRelationshipsModel::getIri)
                .contains(Ttl.POJEM + "zná");
        assertThat(osoba.getConceptProperties()).extracting(ConceptPropertiesModel::getIri)
                .containsExactly(Ttl.POJEM + "jméno");
    }
}
