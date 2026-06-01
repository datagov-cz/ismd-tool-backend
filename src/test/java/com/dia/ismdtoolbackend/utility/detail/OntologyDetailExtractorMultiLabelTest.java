package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.SKOS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static com.dia.constants.VocabularyConstants.TRIDA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression for the {@code ClassCastException} that blanked the NKD dictionary detail page
 * (observed live on {@code a124---datový-slovník-iskn}, concept {@code .../111/2009/pojem/fyzická-osoba},
 * which carries two {@code cs} {@code skos:prefLabel}s).
 *
 * <p>{@code ConceptProcessor.addValueToLanguageMap} promotes a duplicated language key to a
 * {@code List}; the old raw {@code (Map<String, String>)} cast in the extractor let that List slip
 * past type erasure, and Jackson threw {@code ClassCastException} while serializing {@code název.cs}.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorMultiLabelTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab";
    private static final String CLASS_IRI = "https://example.org/vocab/pojem/fyzická-osoba";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    @Test
    void extractOntologyDetail_conceptWithDuplicateCsPrefLabel_keepsFirstAsString() {
        Model model = buildModelWithDuplicateCsLabel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(model, OntologyDetailExtractor.iriResolver());

        OntologyDetailModel.ConceptDetailModel concept = detail.getConcepts().stream()
                .filter(c -> CLASS_IRI.equals(c.getIri()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("concept not extracted"));

        // Exactly one cs label is kept, as a plain String (not a List), so the Map<String,String> contract holds.
        // Which of the two wins depends on Jena statement iteration order, so assert the contract, not the value.
        assertThat(concept.getName().get("cs"))
                .isInstanceOf(String.class)
                .isIn("Fyzická osoba", "Fyzická osoba v registru obyvatel");
    }

    @Test
    void extractOntologyDetail_conceptWithDuplicateCsPrefLabel_serializesWithoutClassCast() throws Exception {
        Model model = buildModelWithDuplicateCsLabel();

        OntologyDetailModel detail = extractor.extractOntologyDetail(model, OntologyDetailExtractor.iriResolver());

        // This is the exact production failure: Jackson serializing název.cs == List threw ClassCastException.
        ObjectMapper mapper = new ObjectMapper();
        assertThatCode(() -> mapper.writeValueAsString(detail)).doesNotThrowAnyException();
        assertThat(mapper.writeValueAsString(detail)).contains("Fyzická osoba");
    }

    private Model buildModelWithDuplicateCsLabel() {
        Model model = ModelFactory.createDefaultModel();

        Resource owlOntology = model.createResource("http://www.w3.org/2002/07/owl#Ontology");
        Resource tridaType = model.createResource(OFN_NAMESPACE + TRIDA);
        Property inScheme = model.createProperty("http://www.w3.org/2004/02/skos/core#inScheme");

        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, owlOntology);
        ontology.addProperty(SKOS.prefLabel, "Testovací slovník", "cs");

        Resource classConcept = model.createResource(CLASS_IRI);
        classConcept.addProperty(RDF.type, tridaType);
        classConcept.addProperty(inScheme, ontology);
        // Two cs prefLabels — the exact upstream shape that triggered the bug.
        classConcept.addProperty(SKOS.prefLabel, "Fyzická osoba", "cs");
        classConcept.addProperty(SKOS.prefLabel, "Fyzická osoba v registru obyvatel", "cs");

        return model;
    }
}