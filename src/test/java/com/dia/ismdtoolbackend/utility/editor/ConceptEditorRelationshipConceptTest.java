package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.rdf.Ttl.iris;
import static org.assertj.core.api.Assertions.assertThat;

/** Edits of relationship concepts (VZTAH) through {@link ConceptEditor}. */
class ConceptEditorRelationshipConceptTest extends ConceptEditorTestBase {

    private static final String CONCEPT = DEFAULT_NS + "pojem/vztah";
    private static final String ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/";

    private void seedRelationship(String extraPredicates) {
        Ttl.add(model, "<" + CONCEPT + "> a ofn:vztah ; skos:prefLabel \"Vztah\"@cs " + extraPredicates + " .");
    }

    @Test
    void domainRangeSuperRelationPpdfAndGovernanceAreReplaced() {
        seedRelationship("""
                ; rdfs:domain <https://example.com/old-domain>
                ; rdfs:range <https://example.com/old-range>
                ; rdfs:subPropertyOf <https://example.com/old-super>
                ; a104:%s "false"
                ; ofn:%s <%styp-obsahu-údajů/položky/old>
                ; ofn:%s <%szpůsoby-sdílení-údajů/položky/old>
                ; ofn:%s <%szpůsoby-získání-údajů/položky/old>
                """.formatted(JE_PPDF_LONG, TYP_OBSAHU, ITEMS, ZPUSOB_SDILENI, ITEMS, ZPUSOB_ZISKANI, ITEMS));
        relationshipConceptEditModel.setDomain("https://example.com/new-domain");
        relationshipConceptEditModel.setRange("https://example.com/new-range");
        relationshipConceptEditModel.setSuperRelation(List.of("https://example.com/new-super"));
        relationshipConceptEditModel.setIsInPPDF(Boolean.TRUE);
        relationshipConceptEditModel.setContentType("identifikační");
        relationshipConceptEditModel.setAcquisitionMethod("jiných agend");
        relationshipConceptEditModel.setSharingMethod(List.of("poskytované na žádost"));

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(CONCEPT, relationshipConceptEditModel, model, null);

        assertThat(result.iriChanged).isFalse();
        assertThat(model.isIsomorphicWith(Ttl.parse("""
                <%s> a ofn:vztah ; skos:prefLabel "Vztah"@cs ;
                    rdfs:domain <https://example.com/new-domain> ;
                    rdfs:range <https://example.com/new-range> ;
                    rdfs:subPropertyOf <https://example.com/new-super> ;
                    a104:%s "true" ;
                    ofn:%s <%stypy-obsahu-údajů/položky/identifikační> ;
                    ofn:%s <%szpůsoby-sdílení-údajů/položky/poskytované-na-žádost> ;
                    ofn:%s <%szpůsoby-získání-údajů/položky/jiných-agend> .
                """.formatted(CONCEPT, JE_PPDF_LONG, TYP_OBSAHU, ITEMS, ZPUSOB_SDILENI, ITEMS,
                ZPUSOB_ZISKANI, ITEMS)))).isTrue();
    }

    @Test
    void renameCarriesDomainAndRangeToTheNewIri() {
        seedRelationship("; rdfs:domain <https://example.com/domain> ; rdfs:range <https://example.com/range>");
        relationshipConceptEditModel.setNameModel(Texts.name("cs", "Nový vztah"));

        ConceptEditor.EditResult result =
                conceptEditor.editConcept(CONCEPT, relationshipConceptEditModel, model, null);

        assertThat(result.newConceptIRI).isEqualTo(DEFAULT_NS + "pojem/nový-vztah");
        assertThat(iris(model, result.newConceptIRI, RDFS.domain)).containsExactly("https://example.com/domain");
        assertThat(iris(model, result.newConceptIRI, RDFS.range)).containsExactly("https://example.com/range");
        assertThat(model.containsResource(model.getResource(CONCEPT))).isFalse();
    }

    @Test
    void blankDomainAndRangeClearThem() {
        seedRelationship("; rdfs:domain <https://example.com/domain> ; rdfs:range <https://example.com/range>");
        relationshipConceptEditModel.setDomain("");
        relationshipConceptEditModel.setRange("  ");

        conceptEditor.editConcept(CONCEPT, relationshipConceptEditModel, model, null);

        assertThat(model.getResource(CONCEPT).hasProperty(RDFS.domain)).isFalse();
        assertThat(model.getResource(CONCEPT).hasProperty(RDFS.range)).isFalse();
    }

    @Test
    void rangeGivenAsANameResolvesToTheConceptIriInTheNamespace() {
        seedRelationship("");
        relationshipConceptEditModel.setRange("Adresa");

        conceptEditor.editConcept(CONCEPT, relationshipConceptEditModel, model, null);

        assertThat(iris(model, CONCEPT, RDFS.range)).containsExactly(DEFAULT_NS + "pojem/adresa");
    }

    @Test
    void superRelationGivenAsANameResolvesToTheConceptIri() {
        seedRelationship("; rdfs:subPropertyOf <https://example.com/old-super>");
        relationshipConceptEditModel.setSuperRelation(List.of("Nadřazený vztah", " "));

        conceptEditor.editConcept(CONCEPT, relationshipConceptEditModel, model, null);

        assertThat(iris(model, CONCEPT, RDFS.subPropertyOf)).containsExactly(DEFAULT_NS + "pojem/nadřazený-vztah");
    }
}
