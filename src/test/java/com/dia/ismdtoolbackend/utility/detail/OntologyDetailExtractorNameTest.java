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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the ontology-level {@code název} losing every language variant but {@code cs}.
 *
 * <p>The detail extractor used to collapse the vocabulary name to a single {@code String}
 * ({@code ModelAnalyzer.extractModelName} returns the first literal) and re-wrap it under
 * {@code cs} only, so a multilingual name created/edited via the API came back single-language.
 * Description never had this bug because it was read multilingually straight off the resource;
 * the name now goes through the same {@code extractMultilingualValue} path.
 */
@ExtendWith(MockitoExtension.class)
class OntologyDetailExtractorNameTest {

    private static final String ONTOLOGY_IRI = "https://example.org/vocab";

    @Mock
    private ConceptMetadataRepository conceptMetadataRepository;

    private OntologyDetailExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OntologyDetailExtractor(conceptMetadataRepository);
    }

    @Test
    void extractOntologyDetail_multilingualPrefLabel_surfacesAllLanguages() {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, model.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        ontology.addProperty(SKOS.prefLabel, "Testovací slovník", "cs");
        ontology.addProperty(SKOS.prefLabel, "Test vocabulary", "en");
        ontology.addProperty(SKOS.prefLabel, "Testwörterbuch", "de");

        OntologyDetailModel detail = extractor.extractOntologyDetail(model, OntologyDetailExtractor.iriResolver());

        assertThat(detail.getName())
                .containsEntry("cs", "Testovací slovník")
                .containsEntry("en", "Test vocabulary")
                .containsEntry("de", "Testwörterbuch")
                .hasSize(3);
    }

    @Test
    void extractOntologyDetail_rdfsLabelPreferredOverPrefLabelForSameLang() {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, model.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        // rdfs:label is read first, so it wins for cs; skos:prefLabel still contributes en.
        ontology.addProperty(RDFS.label, "Název z rdfs:label", "cs");
        ontology.addProperty(SKOS.prefLabel, "Název z prefLabel", "cs");
        ontology.addProperty(SKOS.prefLabel, "Name from prefLabel", "en");

        OntologyDetailModel detail = extractor.extractOntologyDetail(model, OntologyDetailExtractor.iriResolver());

        assertThat(detail.getName())
                .containsEntry("cs", "Název z rdfs:label")
                .containsEntry("en", "Name from prefLabel")
                .hasSize(2);
    }

    @Test
    void extractOntologyDetail_nkdShapeConceptScheme_surfacesAllNameLanguages() {
        // Mirrors the NKD detail path: a ConceptScheme ontology (skos:prefLabel,
        // no rdfs:label) with an in-scheme concept, run through the NKD OFN
        // transform + extract via the iriResolver. Pins that the multilingual
        // ontology name survives end-to-end on the NKD path, not just locally.
        Model raw = ModelFactory.createDefaultModel();
        Resource ontology = raw.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, raw.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        ontology.addProperty(SKOS.prefLabel, "Slovník", "cs");
        ontology.addProperty(SKOS.prefLabel, "Vocabulary", "en");

        Resource concept = raw.createResource(ONTOLOGY_IRI + "/pojem/věc");
        concept.addProperty(RDF.type, raw.createResource("http://www.w3.org/2004/02/skos/core#Concept"));
        concept.addProperty(SKOS.inScheme, ontology);
        concept.addProperty(SKOS.prefLabel, "Věc", "cs");

        Model processed = extractor.applyOFNTransformationsForNkd(raw);
        OntologyDetailModel detail = extractor.extractOntologyDetail(processed, OntologyDetailExtractor.iriResolver());

        assertThat(detail.getName())
                .containsEntry("cs", "Slovník")
                .containsEntry("en", "Vocabulary")
                .hasSize(2);
    }

    @Test
    void extractOntologyDetail_untaggedLabel_keysUnderDefaultLang() {
        Model model = ModelFactory.createDefaultModel();
        Resource ontology = model.createResource(ONTOLOGY_IRI);
        ontology.addProperty(RDF.type, model.createResource("http://www.w3.org/2002/07/owl#Ontology"));
        ontology.addProperty(SKOS.prefLabel, "Slovník bez jazyka");

        OntologyDetailModel detail = extractor.extractOntologyDetail(model, OntologyDetailExtractor.iriResolver());

        assertThat(detail.getName()).containsEntry("cs", "Slovník bez jazyka");
    }
}