package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.models.concept.ConceptPropertiesModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static com.dia.constants.VocabularyConstants.VLASTNOST;
import static com.dia.constants.VocabularyConstants.VZTAH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code range} + {@code rangeResolved} population in all three extractor sites:
 *
 * <ul>
 *   <li>{@code mapToConceptDetailModel} — parent property concept's own {@code obor-hodnot}.</li>
 *   <li>{@code extractConceptProperties} (ConceptData path) — per-property entries on a class.</li>
 *   <li>{@code extractConceptPropertiesFromModel} (OntModel path) — used when {@code conceptData}
 *       is null (e.g. single-concept detail).</li>
 * </ul>
 *
 * Fallback: {@code rangeResolved} returns the {@code Literal} DTO for null/empty/unknown
 * input — mirrors the write-path default in {@code ConceptCreator.addRangeProperty}.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorRangeResolutionTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab";
    private static final String CLASS_IRI = "https://example.org/vocab/pojem/osoba";
    private static final String PROP_STRING_IRI = "https://example.org/vocab/pojem/jméno";
    private static final String PROP_DATE_IRI = "https://example.org/vocab/pojem/datum-narození";
    private static final String PROP_NORANGE_IRI = "https://example.org/vocab/pojem/poznámka";
    private static final String PROP_UNKNOWN_RANGE_IRI = "https://example.org/vocab/pojem/cosi";
    private static final String OTHER_CLASS_IRI = "https://example.org/vocab/pojem/adresa";
    private static final String REL_IRI = "https://example.org/vocab/pojem/bydlí-na";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    // ── ConceptData path (extractOntologyDetail → extractConceptProperties + Site 1) ──

    @Test
    void conceptDataPath_perPropertyEntriesCarryRangeAndRangeResolved() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        List<ConceptPropertiesModel> properties = getClassConcept(detail).getConceptProperties();

        ConceptPropertiesModel stringProp = findProperty(properties, PROP_STRING_IRI);
        assertThat(stringProp.getRange()).isEqualTo("xsd:string");
        assertThat(stringProp.getRangeResolved())
                .isEqualTo(new DataTypeDto("string", "Řetězec"));

        ConceptPropertiesModel dateProp = findProperty(properties, PROP_DATE_IRI);
        assertThat(dateProp.getRange()).isEqualTo("xsd:date");
        assertThat(dateProp.getRangeResolved())
                .isEqualTo(new DataTypeDto("date", "Datum"));
    }

    @Test
    void conceptDataPath_noRange_fallsBackToLiteralDto() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        ConceptPropertiesModel noRange = findProperty(
                getClassConcept(detail).getConceptProperties(), PROP_NORANGE_IRI);

        assertThat(noRange.getRange()).isNull();
        assertThat(noRange.getRangeResolved())
                .isEqualTo(new DataTypeDto("Literal", "Text"));
    }

    @Test
    void conceptDataPath_unknownRange_fallsBackToLiteralDto() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        ConceptPropertiesModel unknown = findProperty(
                getClassConcept(detail).getConceptProperties(), PROP_UNKNOWN_RANGE_IRI);

        // Non-XSD ranges pass through as raw IRI; resolved view falls back to Literal.
        assertThat(unknown.getRange()).isEqualTo("https://example.org/types/not-a-real-type");
        assertThat(unknown.getRangeResolved())
                .isEqualTo(new DataTypeDto("Literal", "Text"));
    }

    @Test
    void conceptDataPath_propertyOwnDetail_rangeResolvedPopulated() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        OntologyDetailModel.ConceptDetailModel stringPropDetail =
                detail.getConcepts().stream()
                        .filter(c -> PROP_STRING_IRI.equals(c.getIri()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("property concept missing"));

        // Raw range stays exactly as ConceptProcessor wrote it.
        assertThat(stringPropDetail.getRange()).isEqualTo("xsd:string");
        assertThat(stringPropDetail.getRangeResolved())
                .isEqualTo(new DataTypeDto("string", "Řetězec"));
    }

    @Test
    void conceptDataPath_propertyOwnDetail_noRange_fallsBackToLiteralDto() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        OntologyDetailModel.ConceptDetailModel noRangeDetail =
                detail.getConcepts().stream()
                        .filter(c -> PROP_NORANGE_IRI.equals(c.getIri()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no-range property missing"));

        assertThat(noRangeDetail.getRange()).isNull();
        assertThat(noRangeDetail.getRangeResolved())
                .isEqualTo(new DataTypeDto("Literal", "Text"));
    }

    @Test
    void conceptDataPath_vztahDetail_keepsRawClassIriAndOmitsRangeResolved() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        OntologyDetailModel.ConceptDetailModel relDetail =
                detail.getConcepts().stream()
                        .filter(c -> REL_IRI.equals(c.getIri()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("relationship concept missing"));

        // Vztah's obor-hodnot is a class IRI, NOT a datatype — must pass through unchanged
        // and rangeResolved must stay null (codelist applies only to Vlastnost).
        assertThat(relDetail.getRange()).isEqualTo(OTHER_CLASS_IRI);
        assertThat(relDetail.getRangeResolved()).isNull();
    }

    @Test
    void conceptDataPath_classDetail_omitsRangeResolved() {
        Model model = buildModel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(
                model, OntologyDetailExtractor.iriResolver());

        // Třída has no obor-hodnot at all, but guard against future regressions
        // by asserting we never set rangeResolved on a non-Vlastnost.
        assertThat(getClassConcept(detail).getRangeResolved()).isNull();
    }

    // ── OntModel path (extractConceptDetail → extractConceptPropertiesFromModel) ──

    @Test
    void ontModelPath_perPropertyEntriesCarryRangeAndRangeResolved() {
        Model model = buildModel();

        OntologyDetailModel.ConceptDetailModel classDetail =
                extractor.extractConceptDetail(model, CLASS_IRI, OntologyDetailExtractor.iriResolver());

        List<ConceptPropertiesModel> properties = classDetail.getConceptProperties();

        ConceptPropertiesModel stringProp = findProperty(properties, PROP_STRING_IRI);
        assertThat(stringProp.getRange()).isEqualTo("xsd:string");
        assertThat(stringProp.getRangeResolved())
                .isEqualTo(new DataTypeDto("string", "Řetězec"));

        ConceptPropertiesModel dateProp = findProperty(properties, PROP_DATE_IRI);
        assertThat(dateProp.getRange()).isEqualTo("xsd:date");
        assertThat(dateProp.getRangeResolved())
                .isEqualTo(new DataTypeDto("date", "Datum"));
    }

    @Test
    void ontModelPath_noRange_fallsBackToLiteralDto() {
        Model model = buildModel();

        OntologyDetailModel.ConceptDetailModel classDetail =
                extractor.extractConceptDetail(model, CLASS_IRI, OntologyDetailExtractor.iriResolver());

        ConceptPropertiesModel noRange = findProperty(
                classDetail.getConceptProperties(), PROP_NORANGE_IRI);

        assertThat(noRange.getRange()).isNull();
        assertThat(noRange.getRangeResolved())
                .isEqualTo(new DataTypeDto("Literal", "Text"));
    }

    @Test
    void ontModelPath_nonXsdRange_passesRawIriThroughAndFallsBackToLiteralDto() {
        Model model = buildModel();

        OntologyDetailModel.ConceptDetailModel classDetail =
                extractor.extractConceptDetail(model, CLASS_IRI, OntologyDetailExtractor.iriResolver());

        ConceptPropertiesModel unknown = findProperty(
                classDetail.getConceptProperties(), PROP_UNKNOWN_RANGE_IRI);

        assertThat(unknown.getRange()).isEqualTo("https://example.org/types/not-a-real-type");
        assertThat(unknown.getRangeResolved())
                .isEqualTo(new DataTypeDto("Literal", "Text"));
    }

    // ── helpers ──

    private OntologyDetailModel.ConceptDetailModel getClassConcept(OntologyDetailModel detail) {
        return detail.getConcepts().stream()
                .filter(c -> CLASS_IRI.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("class concept not extracted"));
    }

    private ConceptPropertiesModel findProperty(List<ConceptPropertiesModel> properties, String iri) {
        return properties.stream()
                .filter(p -> iri.equals(p.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("property not extracted: " + iri));
    }

    /**
     * Minimal OFN-shaped model: one class with four datatype properties —
     * known XSD range, another known XSD range, no range, and a non-XSD range.
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

        Resource otherClass = model.createResource(OTHER_CLASS_IRI);
        otherClass.addProperty(RDF.type, tridaType);
        otherClass.addProperty(inScheme, ontology);
        otherClass.addProperty(SKOS.prefLabel, "Adresa", "cs");

        Resource relationship = model.createResource(REL_IRI);
        relationship.addProperty(RDF.type, vztahType);
        relationship.addProperty(inScheme, ontology);
        relationship.addProperty(SKOS.prefLabel, "bydlí na", "cs");
        relationship.addProperty(RDFS.domain, classConcept);
        relationship.addProperty(RDFS.range, otherClass);

        addProperty(model, vlastnostType, inScheme, ontology, classConcept,
                PROP_STRING_IRI, "jméno",
                ResourceFactory.createResource(XSD_NS + "string"));

        addProperty(model, vlastnostType, inScheme, ontology, classConcept,
                PROP_DATE_IRI, "datum narození",
                ResourceFactory.createResource(XSD_NS + "date"));

        addProperty(model, vlastnostType, inScheme, ontology, classConcept,
                PROP_NORANGE_IRI, "poznámka",
                null);

        addProperty(model, vlastnostType, inScheme, ontology, classConcept,
                PROP_UNKNOWN_RANGE_IRI, "cosi",
                ResourceFactory.createResource("https://example.org/types/not-a-real-type"));

        return model;
    }

    private void addProperty(Model model, Resource vlastnostType, Property inScheme,
                             Resource ontology, Resource domain,
                             String iri, String label, Resource range) {
        Resource property = model.createResource(iri);
        property.addProperty(RDF.type, vlastnostType);
        property.addProperty(inScheme, ontology);
        property.addProperty(SKOS.prefLabel, label, "cs");
        property.addProperty(RDFS.domain, domain);
        if (range != null) {
            property.addProperty(RDFS.range, range);
        }
    }
}
