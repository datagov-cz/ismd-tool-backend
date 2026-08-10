package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.CodeListDto;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.dia.constants.VocabularyConstants.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the číselník read path: a named {@code l111-2009:číselník} subject in the graph
 * reaches {@code ConceptDetailModel.codeList}, so an edit form can be populated from a GET.
 * Without this the value is written but never returned, and an edit round-trip drops it.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorCodeListTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab";
    private static final String CLASS_IRI = "https://example.org/vocab/pojem/typ-turistického-cíle";
    private static final String PLAIN_CLASS_IRI = "https://example.org/vocab/pojem/osoba";
    private static final String CODE_LIST_IRI =
            "https://data.mvcr.gov.cz/zdroj/číselníky/typy-turistických-cílů";
    private static final String NKOD_DATASET =
            "https://data.gov.cz/zdroj/datové-sady/17651921/ff931872553062c9890157ce8615af03";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    @Test
    void classWithCodeList_exposesIriAndDatasetOnDetail() {
        Model model = buildModel(true);

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        CodeListDto codeList = conceptByIri(detail, CLASS_IRI).getCodeList();

        assertThat(codeList).isNotNull();
        assertThat(codeList.getIri()).isEqualTo(CODE_LIST_IRI);
        assertThat(codeList.getDatovaSadaVNkod()).isEqualTo(NKOD_DATASET);
        assertThat(codeList.getTyp()).isEqualTo(CISELNIK_JSON_LD);
    }

    @Test
    void classWithoutCodeList_leavesFieldNull() {
        Model model = buildModel(true);

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        assertThat(conceptByIri(detail, PLAIN_CLASS_IRI).getCodeList()).isNull();
    }

    @Test
    void codeListWithoutCiselnikType_isIgnored() {
        Model model = buildModel(false);

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        assertThat(conceptByIri(detail, CLASS_IRI).getCodeList()).isNull();
    }

    private OntologyDetailModel.ConceptDetailModel conceptByIri(OntologyDetailModel detail, String iri) {
        return detail.getConcepts().stream()
                .filter(c -> iri.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("concept missing: " + iri));
    }

    /**
     * @param typed whether the číselník subject carries its {@code rdf:type}; an untyped one
     *              must be ignored by the reader.
     */
    private Model buildModel(boolean typed) {
        Model model = ModelFactory.createDefaultModel();

        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, OWL2.Ontology);
        ontology.addProperty(SKOS.prefLabel, model.createLiteral("Slovník", "cs"));

        Resource clazz = model.createResource(CLASS_IRI);
        clazz.addProperty(RDF.type, OWL2.Class);
        clazz.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        clazz.addProperty(SKOS.prefLabel, model.createLiteral("Typ turistického cíle", "cs"));
        clazz.addProperty(SKOS.inScheme, ontology);

        Resource plain = model.createResource(PLAIN_CLASS_IRI);
        plain.addProperty(RDF.type, OWL2.Class);
        plain.addProperty(RDF.type, model.createResource(OFN_NAMESPACE + TRIDA));
        plain.addProperty(SKOS.prefLabel, model.createLiteral("Osoba", "cs"));
        plain.addProperty(SKOS.inScheme, ontology);

        Resource codeList = model.createResource(CODE_LIST_IRI);
        if (typed) {
            codeList.addProperty(RDF.type, model.createResource(OFN_NAMESPACE_LEGAL + CISELNIK));
        }
        codeList.addProperty(
                model.createProperty(OFN_NAMESPACE_LEGAL + MA_V_NKOD_ZASTRESUJICI_DATOVOU_SADU),
                model.createResource(NKOD_DATASET));
        clazz.addProperty(
                model.createProperty(OFN_NAMESPACE + MA_INSTANCE_DEFINOVANE_CISELNIKEM),
                codeList);

        return model;
    }
}
