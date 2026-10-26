package com.dia.ismdtoolbackend.utility.editor;

import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import com.dia.ismdtoolbackend.testsupport.rdf.Ttl;
import org.apache.jena.rdf.model.Property;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static com.dia.constants.VocabularyConstants.*;
import static com.dia.ismdtoolbackend.testsupport.rdf.Ttl.iris;
import static org.assertj.core.api.Assertions.assertThat;

/** Governance fields (agenda, AIS, PPDF, code-list items) and non-legal sources, edited through {@link ConceptEditor}. */
class ConceptEditorGovernanceTest extends ConceptEditorTestBase {

    private static final String CONCEPT = DEFAULT_NS + "pojem/vlastnost";
    private static final String RENAMED = DEFAULT_NS + "pojem/nová-vlastnost";
    private static final String AGENDA_ITEMS = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/";
    private static final String AIS_ITEMS = "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/";
    private static final String ITEMS = "https://data.dia.gov.cz/zdroj/číselníky/";

    private static final Property AGENDA = Ttl.property(DEFAULT_NS + AGENDOVY_104 + AGENDA_LONG);
    private static final Property AIS = Ttl.property(DEFAULT_NS + AGENDOVY_104 + UDAJE_AIS);
    private static final Property PPDF = Ttl.property(DEFAULT_NS + AGENDOVY_104 + JE_PPDF_LONG);
    private static final Property SHARING = Ttl.property(OFN_NAMESPACE + ZPUSOB_SDILENI);
    private static final Property ACQUISITION = Ttl.property(OFN_NAMESPACE + ZPUSOB_ZISKANI);
    private static final Property CONTENT_TYPE = Ttl.property(OFN_NAMESPACE + TYP_OBSAHU);
    private static final Property DEFINING_NON_LEGAL = Ttl.property(DEFAULT_NS + DEFINUJICI_NELEGISLATIVNI_ZDROJ);

    private void seedProperty(String extraPredicates) {
        Ttl.add(model, "<" + CONCEPT + "> a ofn:vlastnost ; skos:prefLabel \"Vlastnost\"@cs "
                + extraPredicates + " .");
    }

    private ConceptEditor.EditResult edit() {
        return conceptEditor.editConcept(CONCEPT, propertyConceptEditModel, model, null);
    }

    private void renameTo(String csName) {
        propertyConceptEditModel.setNameModel(Texts.name("cs", csName));
    }

    @Nested
    class AgendaAndAis {

        @ParameterizedTest(name = "agenda \"{0}\"")
        @CsvSource({"123", "A123", "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/agenda/A123/"})
        void agendaIsWrittenAsTheRppAgendaIri(String agendaCode) {
            seedProperty("; a104:%s <%sA1>".formatted(AGENDA_LONG, AGENDA_ITEMS));
            propertyConceptEditModel.setAgendaCode(agendaCode);

            edit();

            assertThat(iris(model, CONCEPT, AGENDA)).containsExactly(AGENDA_ITEMS + "A123");
        }

        @ParameterizedTest(name = "AIS \"{0}\"")
        @CsvSource({"456", "https://rpp-opendata.egon.gov.cz/odrpp/zdroj/isvs/456"})
        void aisIsWrittenAsTheRppIsvsIri(String aisCode) {
            seedProperty("");
            propertyConceptEditModel.setAgendaSystemCode(aisCode);

            edit();

            assertThat(iris(model, CONCEPT, AIS)).containsExactly(AIS_ITEMS + "456");
        }

        @Test
        void blankAgendaAndAisClearThem() {
            seedProperty("; a104:%s <%sA1> ; a104:%s <%s1>".formatted(AGENDA_LONG, AGENDA_ITEMS, UDAJE_AIS, AIS_ITEMS));
            propertyConceptEditModel.setAgendaCode(" ");
            propertyConceptEditModel.setAgendaSystemCode("");

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(AGENDA)).isFalse();
            assertThat(model.getResource(CONCEPT).hasProperty(AIS)).isFalse();
        }

        @Test
        void blankAgendaAndAisClearThemAlsoWhenTheEditRenames() {
            seedProperty("; a104:%s <%sA1> ; a104:%s <%s1>".formatted(AGENDA_LONG, AGENDA_ITEMS, UDAJE_AIS, AIS_ITEMS));
            renameTo("Nová vlastnost");
            propertyConceptEditModel.setAgendaCode("");
            propertyConceptEditModel.setAgendaSystemCode("");

            ConceptEditor.EditResult result = edit();

            assertThat(result.newConceptIRI).isEqualTo(RENAMED);
            assertThat(model.getResource(RENAMED).hasProperty(AGENDA)).isFalse();
            assertThat(model.getResource(RENAMED).hasProperty(AIS)).isFalse();
        }
    }

    @Nested
    class PpdfAndCodeListItems {

        @Test
        void unchangedPpdfFlagStagesNothing() {
            seedProperty("; a104:%s \"true\"".formatted(JE_PPDF_LONG));
            propertyConceptEditModel.setIsInPPDF(Boolean.TRUE);

            assertThat(edit().changesCount).isZero();
        }

        @Test
        void blankAcquisitionAndContentTypeClearThem() {
            seedProperty("; ofn:%s <%szpůsoby-získání-údajů/položky/vlastní> ; ofn:%s <%stypy-obsahu-údajů/položky/provozní>"
                    .formatted(ZPUSOB_ZISKANI, ITEMS, TYP_OBSAHU, ITEMS));
            propertyConceptEditModel.setAcquisitionMethod("");
            propertyConceptEditModel.setContentType(" ");

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(ACQUISITION)).isFalse();
            assertThat(model.getResource(CONCEPT).hasProperty(CONTENT_TYPE)).isFalse();
        }

        @Test
        void blankOnAnUnsetCodeListItemStagesNothing() {
            seedProperty("");
            propertyConceptEditModel.setAcquisitionMethod("");
            propertyConceptEditModel.setSharingMethod(List.of());

            assertThat(edit().changesCount).isZero();
        }

        @Test
        void emptySharingListClearsTheSharingMethods() {
            seedProperty("; ofn:%s <%szpůsoby-sdílení-údajů/položky/nesdílené>".formatted(ZPUSOB_SDILENI, ITEMS));
            propertyConceptEditModel.setSharingMethod(List.of());

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(SHARING)).isFalse();
        }

        @Test
        void unchangedSharingMethodsStageNothing() {
            seedProperty("; ofn:%s <%szpůsoby-sdílení-údajů/položky/nesdílené>".formatted(ZPUSOB_SDILENI, ITEMS));
            propertyConceptEditModel.setSharingMethod(List.of("nesdílené"));

            assertThat(edit().changesCount).isZero();
        }
    }

    @Nested
    class NonLegalSources {

        @Test
        void emptyListRemovesTheSourceLinks() {
            seedProperty("; sl:%s [ a <%s> ; <%s> <https://example.com/doc> ]"
                    .formatted(DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL));
            propertyConceptEditModel.setDefiningNonLegalSource(List.of());

            edit();

            assertThat(model.getResource(CONCEPT).hasProperty(DEFINING_NON_LEGAL)).isFalse();
        }

        @Test
        void emptyListRemovesTheSourceLinksAlsoWhenTheEditRenames() {
            seedProperty("; sl:%s [ a <%s> ; <%s> <https://example.com/doc> ]"
                    .formatted(DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL));
            renameTo("Nová vlastnost");
            propertyConceptEditModel.setDefiningNonLegalSource(List.of());

            edit();

            assertThat(model.getResource(RENAMED).hasProperty(DEFINING_NON_LEGAL)).isFalse();
        }

        @Test
        void sourceWithoutNameOrDescriptionCarriesOnlyTypeAndUrl() {
            seedProperty("");
            propertyConceptEditModel.setDefiningNonLegalSource(
                    List.of(new DigitalObjectModel(" ", null, " https://example.com/doc ")));

            edit();

            assertThat(model.isIsomorphicWith(Ttl.parse("""
                    <%s> a ofn:vlastnost ; skos:prefLabel "Vlastnost"@cs ;
                        sl:%s [ a <%s> ; <%s> <https://example.com/doc> ] .
                    """.formatted(CONCEPT, DEFINUJICI_NELEGISLATIVNI_ZDROJ, DIGITALNI_OBJEKT, SCHEMA_URL))))
                    .isTrue();
        }
    }
}
