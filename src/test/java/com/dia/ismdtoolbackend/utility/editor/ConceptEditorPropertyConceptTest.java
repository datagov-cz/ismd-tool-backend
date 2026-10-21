package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.testsupport.fixtures.Rdf;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.fixtures.Rdf.iris;
import static org.assertj.core.api.Assertions.assertThat;

/** Edits of property concepts (VLASTNOST) through {@link ConceptEditor}. */
class ConceptEditorPropertyConceptTest extends ConceptEditorTestBase {

    private static final String CONCEPT = DEFAULT_NS + "pojem/vlastnost";
    private static final String CONTENT_TYPE_ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/typy-obsahu-údajů/položky/";

    private void seedProperty(String extraPredicates) {
        Rdf.add(model, "<" + CONCEPT + "> a ofn:vlastnost ; skos:prefLabel \"Vlastnost\"@cs " + extraPredicates + " .");
    }

    @Test
    void domainRangeSuperPropertyPpdfAndContentTypeAreReplaced() {
        seedProperty("""
                ; rdfs:domain <https://example.com/old-domain>
                ; rdfs:range xsd:integer
                ; rdfs:subPropertyOf <https://example.com/old-super>
                ; a104:%s "false"
                ; ofn:%s <%sold>
                """.formatted(JE_PPDF_LONG, TYP_OBSAHU, CONTENT_TYPE_ITEMS));
        propertyConceptEditModel.setDomain("https://example.com/new-domain");
        propertyConceptEditModel.setDataType("xsd:string");
        propertyConceptEditModel.setSuperProperty(List.of("https://example.com/new-super"));
        propertyConceptEditModel.setIsInPPDF(Boolean.TRUE);
        propertyConceptEditModel.setContentType("evidenční");

        ConceptEditor.EditResult result = conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);

        assertThat(result.iriChanged).isFalse();
        assertThat(model.isIsomorphicWith(Rdf.model("""
                <%s> a ofn:vlastnost ; skos:prefLabel "Vlastnost"@cs ;
                    rdfs:domain <https://example.com/new-domain> ;
                    rdfs:range xsd:string ;
                    rdfs:subPropertyOf <https://example.com/new-super> ;
                    a104:%s "true" ;
                    ofn:%s <%sevidenční> .
                """.formatted(CONCEPT, JE_PPDF_LONG, TYP_OBSAHU, CONTENT_TYPE_ITEMS)))).isTrue();
    }

    @Test
    void renameCarriesTheDomainToTheNewIri() {
        seedProperty("; rdfs:domain <https://example.com/domain>");
        propertyConceptEditModel.setNameModel(Texts.name("cs", "Nová vlastnost"));
        propertyConceptEditModel.setIdentifier("B3-ID");

        ConceptEditor.EditResult result = conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);

        assertThat(result.newConceptIRI).isEqualTo(DEFAULT_NS + "pojem/b3-id");
        assertThat(iris(model, result.newConceptIRI, RDFS.domain)).containsExactly("https://example.com/domain");
        assertThat(model.containsResource(model.getResource(CONCEPT))).isFalse();
    }

    @Test
    void domainGivenAsANameResolvesToTheConceptIriInTheNamespace() {
        seedProperty("");
        propertyConceptEditModel.setDomain("Osoba");

        conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);

        assertThat(iris(model, CONCEPT, RDFS.domain)).containsExactly(DEFAULT_NS + "pojem/osoba");
    }

    @ParameterizedTest(name = "data type \"{0}\" → range {1}")
    @CsvSource({
            "xsd:boolean, http://www.w3.org/2001/XMLSchema#boolean",
            "nesmysl, http://www.w3.org/2000/01/rdf-schema#Literal"
    })
    void dataTypeIsWrittenAsTheRange(String dataType, String expectedRange) {
        seedProperty("; rdfs:range xsd:string");
        propertyConceptEditModel.setDataType(dataType);

        conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);

        assertThat(iris(model, CONCEPT, RDFS.range)).containsExactly(expectedRange);
    }

    @Test
    void blankDataTypeClearsTheRange() {
        seedProperty("; rdfs:range xsd:string");
        propertyConceptEditModel.setDataType(" ");

        conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);

        assertThat(model.getResource(CONCEPT).hasProperty(RDFS.range)).isFalse();
    }

    @Test
    void unchangedRangeStagesNothing() {
        seedProperty("; rdfs:range xsd:string");
        propertyConceptEditModel.setDataType("xsd:string");

        ConceptEditor.EditResult result = conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);

        assertThat(result.changesCount).isZero();
    }
}
