package com.dia.ismdtoolbackend.utility.detail;

import com.dia.ismdtoolbackend.models.OntologyDetailModel;
import com.dia.ismdtoolbackend.repository.ConceptMetadataRepository;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;

import static com.dia.constants.VocabularyConstants.OFN_NAMESPACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** A concept's {@code dcterms:identifier}, as uploaded files and NKD data carry it, reaches the detail. */
class OntologyDetailExtractorIdentifierTest {

    private static final String ONTOLOGY = "https://example.org/vocab";
    private static final String PERSON = ONTOLOGY + "/pojem/osoba";

    private final OntologyDetailExtractor extractor =
            new OntologyDetailExtractor(mock(ConceptMetadataRepository.class));

    private static Model withIdentifier(String identifierTurtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
                @prefix dct:  <http://purl.org/dc/terms/> .
                @prefix ofn:  <%s> .
                <%s> a <http://www.w3.org/2002/07/owl#Ontology> ; skos:prefLabel "Slovník"@cs .
                <%s> a ofn:třída ; skos:inScheme <%s> ; skos:prefLabel "Osoba"@cs ; dct:identifier %s .
                """.formatted(OFN_NAMESPACE, ONTOLOGY, PERSON, ONTOLOGY, identifierTurtle), Lang.TURTLE).parse(model);
        return model;
    }

    @Test
    void literalIdentifierIsReadAsText() {
        OntologyDetailModel.ConceptDetailModel person = extractor.extractConceptDetail(withIdentifier("\"OS-17\""), PERSON);

        assertThat(person.getIdentifier()).isEqualTo("OS-17");
    }

    @Test
    void iriIdentifierIsReadAsItsIri() {
        OntologyDetailModel.ConceptDetailModel person =
                extractor.extractConceptDetail(withIdentifier("<https://example.org/id/17>"), PERSON);

        assertThat(person.getIdentifier()).isEqualTo("https://example.org/id/17");
    }
}
