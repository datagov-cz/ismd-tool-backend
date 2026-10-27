package com.dia.ismdtoolbackend.utility.creator;

import com.dia.ismdtoolbackend.models.concept.ConceptCreateModel;
import com.dia.ismdtoolbackend.models.concept.DigitalObjectModel;
import com.dia.ismdtoolbackend.testsupport.fixtures.ConceptModels;
import com.dia.ismdtoolbackend.testsupport.fixtures.Texts;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.dia.constants.VocabularyConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** The base classes and properties {@link ConceptSchemaSelector} declares in a concept's working model. */
class ConceptSchemaSelectorTest {

    private static final String PROVISION = "https://opendata.eselpoint.gov.cz/esel-esb/eli/cz/sb/2020/50";
    private static final Set<String> COMMON = Set.of(NAZEV, POPIS, DEFINICE);
    private static final Set<String> GOVERNANCE = Set.of(ZPUSOB_SDILENI, ZPUSOB_ZISKANI, TYP_OBSAHU);

    private final ConceptSchemaSelector selector = new ConceptSchemaSelector();

    @Nested
    class BaseClasses {

        static Stream<Arguments> cases() {
            return Stream.of(
                    arguments("plain class", ConceptModels.trida("T"), Set.of(POJEM, TRIDA)),
                    arguments("subject class", ConceptModels.trida("T", m -> m.setType("subjekt práva")),
                            Set.of(POJEM, TRIDA, TSP)),
                    arguments("public object class", ConceptModels.trida("T", m -> {
                        m.setType("objekt práva");
                        m.setIsPublic(true);
                    }), Set.of(POJEM, TRIDA, TOP, VEREJNY_UDAJ)),
                    arguments("non-public class of another type", ConceptModels.trida("T", m -> {
                        m.setType("třída");
                        m.setIsPublic(false);
                    }), Set.of(POJEM, TRIDA, NEVEREJNY_UDAJ)),
                    arguments("unclassified property", ConceptModels.vlastnost("V"), Set.of(POJEM, VLASTNOST)),
                    arguments("public property", ConceptModels.vlastnost("V", m -> m.setIsPublic(true)),
                            Set.of(POJEM, VLASTNOST, VEREJNY_UDAJ)),
                    arguments("public property with empty provisions", ConceptModels.vlastnost("V", m -> {
                        m.setIsPublic(true);
                        m.setPrivacyProvisions(List.of());
                    }), Set.of(POJEM, VLASTNOST, VEREJNY_UDAJ)),
                    arguments("public property with a provision", ConceptModels.vlastnost("V", m -> {
                        m.setIsPublic(true);
                        m.setPrivacyProvisions(List.of(PROVISION));
                    }), Set.of(POJEM, VLASTNOST, NEVEREJNY_UDAJ)),
                    arguments("non-public property", ConceptModels.vlastnost("V", m -> m.setIsPublic(false)),
                            Set.of(POJEM, VLASTNOST, NEVEREJNY_UDAJ)),
                    arguments("unclassified relationship", ConceptModels.vztah("R"), Set.of(POJEM, VZTAH)),
                    arguments("public relationship", ConceptModels.vztah("R", m -> m.setIsPublic(true)),
                            Set.of(POJEM, VZTAH, VEREJNY_UDAJ)),
                    arguments("public relationship with empty provisions", ConceptModels.vztah("R", m -> {
                        m.setIsPublic(true);
                        m.setPrivacyProvisions(List.of());
                    }), Set.of(POJEM, VZTAH, VEREJNY_UDAJ)),
                    arguments("relationship with a provision", ConceptModels.vztah("R", m -> {
                        m.setIsPublic(true);
                        m.setPrivacyProvisions(List.of(PROVISION));
                    }), Set.of(POJEM, VZTAH, NEVEREJNY_UDAJ)),
                    arguments("non-public relationship", ConceptModels.vztah("R", m -> m.setIsPublic(false)),
                            Set.of(POJEM, VZTAH, NEVEREJNY_UDAJ)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        void areSelectedFromTypeAndClassification(String description, ConceptCreateModel model, Set<String> expected) {
            assertThat(selector.determineRequiredBaseClasses(model)).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Nested
    class Properties {

        @Test
        void bareConceptsNeedOnlyTheCommonProperties() {
            for (ConceptCreateModel model : List.of(ConceptModels.trida("T"), ConceptModels.vlastnost("V"),
                    ConceptModels.vztah("R"))) {
                assertThat(selector.determineRequiredProperties(model)).containsExactlyInAnyOrderElementsOf(COMMON);
            }
        }

        @Test
        void altNamesAndSourcesAddTheirProperties() {
            ConceptCreateModel model = ConceptModels.trida("T", m -> {
                m.setAltNameModel(Texts.altName("cs", "A"));
                m.setRelatedLegalSource(List.of(PROVISION));
                m.setRelatedNonLegalSource(List.of(new DigitalObjectModel(null, null, "https://example.org")));
            });

            assertThat(selector.determineRequiredProperties(model)).containsExactlyInAnyOrder(
                    NAZEV, POPIS, DEFINICE, ALTERNATIVNI_NAZEV, DEFINUJICI_USTANOVENI, SOUVISEJICI_USTANOVENI,
                    DEFINUJICI_NELEGISLATIVNI_ZDROJ, SOUVISEJICI_NELEGISLATIVNI_ZDROJ,
                    "schema:url", "dcterms:title", "dcterms:description");
        }

        @Test
        void emptySourceListsAddNothing() {
            ConceptCreateModel model = ConceptModels.trida("T", m -> {
                m.setDefiningLegalSource(List.of());
                m.setRelatedLegalSource(List.of());
                m.setDefiningNonLegalSource(List.of());
                m.setRelatedNonLegalSource(List.of());
            });

            assertThat(selector.determineRequiredProperties(model)).containsExactlyInAnyOrderElementsOf(COMMON);
        }

        @Test
        void definingSourcesAloneAddTheSourceProperties() {
            ConceptCreateModel model = ConceptModels.vztah("R", m -> {
                m.setDefiningLegalSource(List.of(PROVISION));
                m.setDefiningNonLegalSource(List.of(new DigitalObjectModel(null, null, "https://example.org")));
            });

            assertThat(selector.determineRequiredProperties(model))
                    .contains(DEFINUJICI_USTANOVENI, DEFINUJICI_NELEGISLATIVNI_ZDROJ);
        }

        static Stream<Arguments> classGovernance() {
            return Stream.of(
                    arguments("agenda", ConceptModels.trida("T", m -> m.setAgendaCode("A1")), Set.of(AGENDA_LONG)),
                    arguments("AIS", ConceptModels.trida("T", m -> m.setAgendaSystemCode("1")), Set.of(UDAJE_AIS)),
                    arguments("blank agenda and AIS", ConceptModels.trida("T", m -> {
                        m.setAgendaCode(" ");
                        m.setAgendaSystemCode("");
                    }), Set.of()),
                    arguments("non-public", ConceptModels.trida("T", m -> m.setIsPublic(false)),
                            Set.of(USTANOVENI_NEVEREJNOST)),
                    arguments("public", ConceptModels.trida("T", m -> m.setIsPublic(true)), Set.of()),
                    arguments("sharing", ConceptModels.trida("T", m -> m.setSharingMethod(List.of("nesdílené"))),
                            GOVERNANCE),
                    arguments("empty sharing", ConceptModels.trida("T", m -> {
                        m.setSharingMethod(List.of());
                        m.setAcquisitionMethod("");
                        m.setContentType("");
                    }), Set.of()),
                    arguments("acquisition", ConceptModels.trida("T", m -> m.setAcquisitionMethod("vlastní")), GOVERNANCE),
                    arguments("content type", ConceptModels.trida("T", m -> m.setContentType("provozní")), GOVERNANCE));
        }

        @ParameterizedTest(name = "class with {0}")
        @MethodSource("classGovernance")
        void classGovernanceAddsItsProperties(String description, ConceptCreateModel model, Set<String> extra) {
            assertThat(selector.determineRequiredProperties(model))
                    .containsExactlyInAnyOrderElementsOf(union(COMMON, extra));
        }

        static Stream<Arguments> propertyAndRelationshipGovernance() {
            return Stream.of(
                    arguments("property in PPDF", ConceptModels.vlastnost("V", m -> m.setIsInPPDF(true)), Set.of(JE_PPDF_LONG)),
                    arguments("property outside PPDF", ConceptModels.vlastnost("V", m -> m.setIsInPPDF(false)), Set.of()),
                    arguments("property with agenda and AIS", ConceptModels.vlastnost("V", m -> {
                        m.setAgendaCode("A1");
                        m.setAgendaSystemCode("1");
                    }), Set.of(AGENDA_LONG, UDAJE_AIS)),
                    arguments("property with blank agenda and AIS", ConceptModels.vlastnost("V", m -> {
                        m.setAgendaCode(" ");
                        m.setAgendaSystemCode(" ");
                    }), Set.of()),
                    arguments("property with a provision", ConceptModels.vlastnost("V",
                            m -> m.setPrivacyProvisions(List.of(PROVISION))), Set.of(USTANOVENI_NEVEREJNOST)),
                    arguments("property with empty provisions", ConceptModels.vlastnost("V",
                            m -> m.setPrivacyProvisions(List.of())), Set.of()),
                    arguments("property with sharing", ConceptModels.vlastnost("V",
                            m -> m.setSharingMethod(List.of("nesdílené"))), GOVERNANCE),
                    arguments("property with blank governance", ConceptModels.vlastnost("V", m -> {
                        m.setSharingMethod(List.of());
                        m.setAcquisitionMethod(" ");
                        m.setContentType(" ");
                    }), Set.of()),
                    arguments("relationship with acquisition", ConceptModels.vztah("R",
                            m -> m.setAcquisitionMethod("vlastní")), GOVERNANCE),
                    arguments("relationship with content type and PPDF", ConceptModels.vztah("R", m -> {
                        m.setContentType("provozní");
                        m.setIsInPPDF(true);
                    }), union(GOVERNANCE, Set.of(JE_PPDF_LONG))),
                    arguments("relationship with agenda and a provision", ConceptModels.vztah("R", m -> {
                        m.setAgendaCode("A1");
                        m.setPrivacyProvisions(List.of(PROVISION));
                    }), Set.of(AGENDA_LONG, USTANOVENI_NEVEREJNOST)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("propertyAndRelationshipGovernance")
        void propertyAndRelationshipGovernanceAddsItsProperties(String description, ConceptCreateModel model,
                                                                Set<String> extra) {
            assertThat(selector.determineRequiredProperties(model))
                    .containsExactlyInAnyOrderElementsOf(union(COMMON, extra));
        }

        private static Set<String> union(Set<String> a, Set<String> b) {
            return Stream.concat(a.stream(), b.stream()).collect(java.util.stream.Collectors.toSet());
        }
    }
}